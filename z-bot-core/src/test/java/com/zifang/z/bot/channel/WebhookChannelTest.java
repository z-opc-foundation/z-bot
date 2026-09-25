package com.zifang.z.bot.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link WebhookChannel} 真实 HTTP 端到端：POST /webhook/in → bus → agent → 出站队列轮询。
 *
 * <p>LLM 用脚本替身（echo 工具返 "pong"）；沙箱 / 会话都在 {@link TemporaryFolder}，
 * 不碰 {@code ~/.zbot}。每个测试方法结束后 stop channel。</p>
 */
public class WebhookChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private ChannelBus bus;
    private WebhookChannel channel;
    private BotAgent agent;
    private int port;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        File sandboxDir = tmp.newFolder("sandbox");
        File sessionDir = tmp.newFolder("sessions");
        agent = BotAgent.builder(null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .build();
        bus = new ChannelBus(agent);
        channel = new WebhookChannel(bus, 0, "webhook");
        bus.register(channel);
        bus.start();
        channel.start();
        port = channel.getPort();
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.stop();
        }
        if (bus != null) {
            bus.shutdown();
        }
    }

    @Test
    public void inboundPostDeliversMessageAndReplyShowsInOutboundQueue() throws Exception {
        // 让 LLM 调 echo 工具，返 "pong" —— 这样就有真实工具调用 + 最终回复
        llm.script(toolReply(call("c1", "echo", "{\"message\":\"hi\"}")));
        llm.script(textReply("pong"));

        post("/webhook/in",
                "{\"conversationId\":\"conv-x\",\"senderId\":\"alice\",\"text\":\"say hi\"}");
        // 等 bus 处理完 + 出站入队
        Thread.sleep(500);
        OutboundMessage out = pollOutbound();
        assertEquals("conv-x", out.replyTo);
        assertTrue("期望含 pong，实际: " + out.text, out.text.contains("pong"));
    }

    @Test
    public void inboundPostRejects400WhenMissingConversationId() throws Exception {
        HttpURLConnection con = postRaw("/webhook/in", "{\"text\":\"hi\"}");
        assertEquals(400, con.getResponseCode());
    }

    @Test
    public void outboundPollKeepsLongPollingUntilTextAppears() throws Exception {
        // 先发入站
        llm.script(textReply("after-poll"));
        post("/webhook/in", "{\"conversationId\":\"c\",\"text\":\"trigger\"}");
        // 等 agent 处理完
        Thread.sleep(300);
        // 这次轮询应该立即拿到结果
        long start = System.currentTimeMillis();
        OutboundMessage out = pollOutbound();
        assertTrue("应在 5s 内拿到回复", System.currentTimeMillis() - start < 5000);
        assertEquals("c", out.replyTo);
    }

    @Test
    public void multipleConversationsAreRoutedSeparately() throws Exception {
        llm.script(textReply("reply-A"));
        post("/webhook/in", "{\"conversationId\":\"a\",\"text\":\"hello\"}");
        Thread.sleep(200);
        llm.script(textReply("reply-B"));
        post("/webhook/in", "{\"conversationId\":\"b\",\"text\":\"hello\"}");
        Thread.sleep(200);

        // 出站队列里有两条
        OutboundMessage o1 = pollOutbound();
        OutboundMessage o2 = pollOutbound();
        assertEquals("a", o1.replyTo);
        assertEquals("b", o2.replyTo);
        // bus 的 history 应该都记下来
        assertEquals(2, bus.history().size());
    }

    @Test
    public void sendMethodPushesToOutboundQueueDirectly() throws Exception {
        channel.send(OutboundMessage.text("direct", "manual-reply"));
        OutboundMessage out = pollOutbound();
        assertEquals("direct", out.replyTo);
        assertEquals("manual-reply", out.text);
    }

    // ===== helpers =====

    private void post(String path, String body) throws IOException {
        HttpURLConnection con = postRaw(path, body);
        int rc = con.getResponseCode();
        assertEquals("POST " + path + " expected 200", 200, rc);
    }

    private HttpURLConnection postRaw(String path, String body) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return con;
    }

    private OutboundMessage pollOutbound() throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + "/webhook/out");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(2000);
        con.setReadTimeout(25000);
        int rc = con.getResponseCode();
        assertEquals(200, rc);
        try (InputStream is = con.getInputStream()) {
            byte[] body = readAll(is);
            ObjectMapper json = new ObjectMapper();
            java.util.Map<?, ?> map = json.readValue(body, java.util.Map.class);
            String text = (String) map.get("text");
            Object conv = map.get("conversationId");
            if (text == null || text.isEmpty()) {
                // 长轮询到空响应时也返 200，重试一次
                return pollOutbound();
            }
            return OutboundMessage.text(conv == null ? null : conv.toString(), text);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static ChatCompletionsResponse textReply(String content) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content,
                        Collections.<ToolCall>emptyList(), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, "",
                        Arrays.asList(calls), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    /** 脚本化 LLM 替身。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

        ScriptedProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}