package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link HttpChannel} 单测：真起一个 JDK HttpServer（端口 0 由系统分配），
 * 用 {@link HttpURLConnection} 打真实请求，校验状态、SSE 帧与控制台资源。
 *
 * <p>LLM 仍是脚本替身，沙箱/会话落在 {@link TemporaryFolder}，不碰 {@code ~/.zbot}。</p>
 */
public class HttpChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private BotAgent agent;
    private HttpChannel channel;
    private String base;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        Toolkit toolkit = new Toolkit();
        toolkit.register(Toolkit.of("echo", "回显文本", schema("message"), args ->
                result("echoed:" + str(args, "message"))));
        toolkit.register(Toolkit.of("risky", "高危操作", schema("cmd"), args ->
                Confirmations.alreadyConfirmed(args)
                        ? result("risk-accepted") : Confirmations.needsConfirmation("高危操作")));
        agent = BotAgent.builder(null)
                .provider(llm)
                .providerCode("scripted")
                .toolkit(toolkit)
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        channel = new HttpChannel(agent, 0);
        channel.start();
        base = "http://127.0.0.1:" + channel.getPort();
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.stop();
        }
    }

    // ===== 控制面 =====

    @Test
    public void statusDescribesRunningAgent() throws Exception {
        Response r = call("GET", "/bot/status", null);

        assertEquals(200, r.code);
        assertTrue(r.body, r.body.contains("model=test-model"));
        assertTrue(r.body, r.body.contains("tools=2"));
        assertTrue(r.body, r.body.contains("provider=scripted"));
        assertTrue(r.body, r.body.contains("instance=local"));
    }

    @Test
    public void toolsEndpointReflectsToolkit() throws Exception {
        Response r = call("GET", "/bot/tools", null);

        assertEquals(200, r.code);
        assertTrue(r.body, r.body.contains("\"name\":\"echo\""));
        assertTrue(r.body, r.body.contains("\"name\":\"risky\""));
        assertTrue(r.body, r.body.contains("\"description\":\"回显文本\""));
    }

    @Test
    public void clearEndpointEmptiesMemory() throws Exception {
        llm.script(textReply("pong"));
        call("POST", "/bot/chat", "{\"message\":\"ping\"}");
        assertFalse(currentMessages().equals("[]"));

        assertEquals(200, call("GET", "/bot/clear", null).code);
        assertEquals("[]", currentMessages());
    }

    @Test
    public void unknownPathReturnsJsonError() throws Exception {
        Response r = call("GET", "/nope", null);

        assertEquals(404, r.code);
        assertTrue(r.body, r.body.contains("\"ok\":false"));
        assertTrue(r.body, r.body.contains("not found"));
    }

    // ===== 同步对话 =====

    @Test
    public void chatEndpointReturnsAgentReply() throws Exception {
        llm.script(textReply("你好"));

        Response r = call("POST", "/bot/chat", "{\"message\":\"hi\"}");

        assertEquals(200, r.code);
        assertEquals("你好", r.body);
        assertEquals(1, llm.callCount);
    }

    @Test
    public void chatEndpointAcceptsRawTextBody() throws Exception {
        llm.script(textReply("收到"));

        Response r = call("POST", "/bot/chat", "hello there");

        assertEquals("收到", r.body);
        assertTrue(lastUserMessage().contains("hello there"));
    }

    @Test
    public void chatEndpointRejectsEmptyMessage() throws Exception {
        Response r = call("POST", "/bot/chat", "{}");

        assertEquals(400, r.code);
        assertTrue(r.body, r.body.contains("消息不能为空"));
        assertEquals(0, llm.callCount);
    }

    @Test
    public void chatEndpointRejectsConcurrentRequests() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        llm.script(textReply("slow-answer")).blockingOn(release);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Response> first = caller.submit(() -> call("POST", "/bot/chat", "{\"message\":\"slow\"}"));
            awaitUntilRunning();

            Response second = call("POST", "/bot/chat", "{\"message\":\"another\"}");
            assertEquals(409, second.code);
            assertTrue(second.body, second.body.contains("还在处理中"));

            release.countDown();
            Response handled = first.get(10, TimeUnit.SECONDS);
            assertEquals(200, handled.code);
            assertEquals("slow-answer", handled.body);
            assertEquals("上一条仍占着 agent，第二条不该打到 LLM", 1, llm.callCount);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    private void awaitUntilRunning() throws InterruptedException {
        for (int i = 0; i < 200 && !agent.isRunning(); i++) {
            Thread.sleep(10);
        }
        assertTrue("第一条请求应进入运行态", agent.isRunning());
    }

    @Test
    public void confirmEndpointExecutesPendingTool() throws Exception {
        llm.script(toolReply(toolCall("c1", "risky", "{\"cmd\":\"rm\"}")));

        Response waiting = call("POST", "/bot/chat", "{\"message\":\"do it\"}");
        assertEquals(BotAgent.WAIT_CONFIRM_PREFIX + "risky|{\"cmd\":\"rm\"}|高危操作", waiting.body);

        Response r = call("POST", "/bot/confirm",
                "{\"toolName\":\"risky\",\"argsJson\":\"{\\\"cmd\\\":\\\"rm\\\"}\"}");
        assertEquals(200, r.code);
        assertEquals("risk-accepted", r.body);

        // 确认后补一条 tool 结果，且 id 与待确认那次调用对齐（不再合成 assistant）
        String messages = currentMessages();
        assertTrue(messages, messages.contains("c1"));
        assertTrue(messages, messages.contains("risk-accepted"));
    }

    @Test
    public void confirmEndpointRequiresToolName() throws Exception {
        Response r = call("POST", "/bot/confirm", "{}");

        assertEquals(400, r.code);
        assertTrue(r.body, r.body.contains("toolName"));
    }

    // ===== SSE =====

    @Test
    public void streamEmitsRealReactEvents() throws Exception {
        llm.script(toolReply(toolCall("c1", "echo", "{\"message\":\"ping\"}")));
        llm.script(textReply("第一行\n第二行"));

        Response r = call("POST", "/bot/chat/stream", "{\"message\":\"use echo\"}");

        assertEquals(200, r.code);
        assertTrue(r.contentType, r.contentType.contains("text/event-stream"));
        assertTrue(r.body, r.body.contains("event: step\ndata: Step 1\n\n"));
        assertTrue(r.body, r.body.contains("event: tool_call\ndata: [tool] echo args={\"message\":\"ping\"}\n\n"));
        assertTrue(r.body, r.body.contains("event: tool_result\ndata: [echo] OK: echoed:ping\n\n"));
        // data: 行里不能有裸换行，多行答案要转义后再发
        assertTrue(r.body, r.body.contains("event: final\ndata: 第一行\\n第二行\n\n"));
        assertTrue(r.body, r.body.contains("event: done\ndata: [DONE] steps=2 replyLen=7\n\n"));
        assertNoUnescapedNewlineInData(r.body);
    }

    @Test
    public void streamEmitsConfirmFrameForPendingDangerousTool() throws Exception {
        llm.script(toolReply(toolCall("c1", "risky", "{\"cmd\":\"rm -rf\"}")));

        Response r = call("POST", "/bot/chat/stream", "{\"message\":\"danger\"}");

        assertTrue(r.body, r.body.contains("event: tool_result\ndata: [risky] ERROR: 高危操作\n\n"));
        assertTrue(r.body, r.body.contains("event: confirm\ndata: [confirm] tool=risky args={\"cmd\":\"rm -rf\"}"
                + " reason=高危操作\n\n"));
        assertFalse(r.body, r.body.contains("event: final"));
    }

    @Test
    public void streamReportsEmptyMessageWithoutCallingLlm() throws Exception {
        Response r = call("POST", "/bot/chat/stream", "{}");

        assertEquals(200, r.code);
        assertTrue(r.body, r.body.contains("event: error\ndata: 消息不能为空\n\n"));
        assertEquals(0, llm.callCount);
    }

    // ===== 会话 =====

    @Test
    public void sessionEndpointsCreateListAndDelete() throws Exception {
        assertEquals("[]", call("GET", "/api/sessions", null).body);

        Response created = call("POST", "/api/sessions", null);
        assertEquals(200, created.code);
        String id = field(created.body, "id");
        assertNotNull(id);
        assertTrue(created.body, created.body.contains("\"ok\":true"));

        llm.script(textReply("记一下"));
        call("POST", "/bot/chat", "{\"message\":\"hello\"}");

        assertTrue(call("GET", "/api/sessions", null).body.contains(id));

        Response messages = call("GET", "/api/session/messages?id=" + id, null);
        assertTrue(messages.body, messages.body.contains("\"role\":\"user\""));
        assertTrue(messages.body, messages.body.contains("\"role\":\"assistant\""));
        assertTrue(messages.body, messages.body.contains("\"contentType\":\"text\""));
        assertTrue(messages.body, messages.body.contains("\"isFinal\":true"));

        assertEquals(200, call("POST", "/api/session/delete", "{\"id\":\"" + id + "\"}").code);
        assertFalse(call("GET", "/api/sessions", null).body.contains(id));
    }

    @Test
    public void sessionSwitchRejectsMissingId() throws Exception {
        Response r = call("POST", "/api/session/switch", "{}");

        assertEquals(400, r.code);
        assertTrue(r.body, r.body.contains("id 不能为空"));
    }

    @Test
    public void toolCallMessagesAreExposedAsToolContentType() throws Exception {
        llm.script(toolReply(toolCall("c1", "echo", "{\"message\":\"ping\"}")));
        llm.script(textReply("done"));
        call("POST", "/bot/chat", "{\"message\":\"go\"}");

        String messages = currentMessages();
        assertTrue(messages, messages.contains("\"contentType\":\"tool_call\""));
        assertTrue(messages, messages.contains("\"toolName\":\"echo\""));
        assertTrue(messages, messages.contains("\"contentType\":\"tool_result\""));
    }

    // ===== skill / center =====

    @Test
    public void skillEndpointsReportLocalOnlyState() throws Exception {
        Response list = call("GET", "/api/skill/list", null);
        assertEquals(200, list.code);
        assertTrue(list.body, list.body.contains("\"count\":0"));
        assertTrue(list.body, list.body.contains("\"skillCodes\":[]"));

        Response sync = call("POST", "/api/skill/sync", null);
        assertTrue(sync.body, sync.body.contains("\"ok\":true"));
        assertTrue(sync.body, sync.body.contains("\"installed\":0"));
    }

    @Test
    public void registerEndpointReportsDisabledCenter() throws Exception {
        Response r = call("POST", "/api/agent/register", "{}");

        assertEquals(200, r.code);
        assertTrue(r.body, r.body.contains("\"ok\":false"));
        assertTrue(r.body, r.body.contains("center client not enabled"));
    }

    // ===== 控制台 =====

    @Test
    public void consoleHtmlIsServed() throws Exception {
        Response r = call("GET", "/", null);

        assertEquals(200, r.code);
        assertTrue(r.contentType, r.contentType.contains("text/html"));
        assertTrue(r.body, r.body.contains("z-bot 控制台"));
        assertTrue("控制台必须渲染 final 事件", r.body.contains("fullReply += text"));
        assertTrue("控制台必须展示确认横条", r.body.contains("$confirmBar.classList.add('show')"));
        assertEquals(r.body, 200, call("GET", "/index.html", null).code);
        assertEquals(r.body, 200, call("GET", "/console", null).code);
    }

    // ===== helpers =====

    private String currentMessages() throws Exception {
        return call("GET", "/api/session/messages", null).body;
    }

    private String lastUserMessage() throws Exception {
        String body = currentMessages();
        return body.substring(body.lastIndexOf("\"role\":\"user\""));
    }

    /** 每个 {@code data:} 行都必须单行（SSE 约束）。 */
    private static void assertNoUnescapedNewlineInData(String sse) {
        String[] lines = sse.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("data: ")) {
                String data = lines[i].substring(6);
                assertFalse("data 行含裸换行: " + data, data.contains("\r"));
                if (i + 1 < lines.length) {
                    String next = lines[i + 1];
                    assertTrue("data 后应紧跟空行: [" + next + "]", next.isEmpty()
                            || next.startsWith("event: ") || next.startsWith("data: "));
                }
            }
        }
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int from = json.indexOf(needle);
        if (from < 0) {
            return null;
        }
        int start = from + needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private Response call(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(20000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.code = conn.getResponseCode();
        r.contentType = conn.getContentType();
        InputStream is = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        r.body = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
        conn.disconnect();
        return r;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return baos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static Map<String, Object> schema(String... propertyNames) {
        Map<String, Object> properties = new java.util.LinkedHashMap<String, Object>();
        for (String name : propertyNames) {
            properties.put(name, Collections.<String, Object>singletonMap("type", "string"));
        }
        Map<String, Object> schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static ToolResult result(String content) {
        return new ToolResult(null, null, content, false, Collections.<String, Object>emptyMap());
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }

    private static ChatCompletionsResponse textReply(String content) {
        return response(content, Collections.<ToolCall>emptyList());
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return response("", Arrays.asList(calls));
    }

    private static ChatCompletionsResponse response(String content, List<ToolCall> toolCalls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, toolCalls, "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall toolCall(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    private static final class Response {
        int code;
        String contentType;
        String body;
    }

    /** 脚本化 LLM 替身：按序吐出预设响应。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        private int callCount;
        /** 非空时 chat() 阻塞到闩归零——用来制造"上一条还在跑"的并发窗口。 */
        private volatile CountDownLatch gate;

        ScriptedProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

        ScriptedProvider blockingOn(CountDownLatch gate) {
            this.gate = gate;
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
            callCount++;
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    if (!g.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试闩未释放");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
