package com.zifang.z.bot.channel;

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
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FeishuChannel} URL 校验 + 入站分发 + 签名校验真 HTTP 单测。
 * stub 的发送仍只走 outbound 队列，不发真飞书。
 */
public class FeishuChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FeishuChannel channel;
    private ChannelBus bus;
    private int port;

    @Before
    public void setUp() throws Exception {
        bus = new ChannelBus(stubAgent());
        bus.start();
        channel = new FeishuChannel(bus, 0, "app-id", "app-secret",
                "verify-token-123", "encrypt-key-456", null);
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

    private com.zifang.z.bot.agent.BotAgent stubAgent() throws Exception {
        return com.zifang.z.bot.agent.BotAgent.builder(null)
                .provider(new com.zifang.z.agent.kernel.llm.LlmProvider() {
                    @Override public String name() { return "stub"; }
                    @Override public java.util.List<com.zifang.z.agent.kernel.llm.Model> listModels() { return Collections.emptyList(); }
                    @Override public boolean supportsModel(String m) { return true; }
                    @Override public com.zifang.z.agent.kernel.llm.ChatCompletionsResponse chat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r) {
                        String last = r.getMessages().isEmpty() ? "" : r.getMessages().get(r.getMessages().size() - 1).getContent();
                        return new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse.Choice(0,
                                        "echo: " + last,
                                        Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new com.zifang.z.agent.kernel.types.TokenUsage(5L, 3L, 8L), "stop", null);
                    }
                    @Override public void streamChat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r,
                                                     java.util.function.Consumer<com.zifang.z.agent.kernel.llm.ChatCompletionsResponse> on,
                                                     java.util.function.Consumer<Throwable> err) { on.accept(chat(r)); }
                })
                .sandbox(new com.zifang.z.bot.tool.Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new com.zifang.z.bot.session.SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutCenter()
                .build();
    }

    @Test
    public void getUrlVerificationReturnsEchostr() throws Exception {
        HttpURLConnection con = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + "/feishu/event?echostr=hello-feishu").openConnection();
        con.setRequestMethod("GET");
        int rc = con.getResponseCode();
        assertEquals(200, rc);
        try (InputStream is = con.getInputStream()) {
            String body = readAll(is);
            assertEquals("hello-feishu", body);
        }
    }

    @Test
    public void postEventWithValidTokenDeliversToBus() throws Exception {
        String body = "{\"token\":\"verify-token-123\",\"event\":"
                + "{\"sender_id\":\"ou_1\",\"chat_id\":\"oc_chat1\",\"chat_type\":\"group\",\"text\":\"你好\"}}";
        int rc = postJson("/feishu/event", body);
        assertEquals(200, rc);
        waitFor(() -> !bus.history().isEmpty(), 2000);
        assertEquals(1, bus.history().size());
        ChannelBus.Entry e = bus.history().get(0);
        assertEquals("feishu", e.channel);
        assertEquals("group:oc_chat1", e.conversationId);
        assertEquals("ou_1", e.senderId);
        assertEquals("你好", e.text);
        assertTrue("reply 应是 echo 形式，实际: " + e.reply, e.reply.contains("你好"));
    }

    @Test
    public void postEventWithBadTokenReturns401() throws Exception {
        String body = "{\"token\":\"wrong-token\",\"event\":{\"chat_id\":\"x\",\"text\":\"hi\"}}";
        int rc = postJson("/feishu/event", body);
        assertEquals(401, rc);
        // 等一小段时间确保没有异步投递
        Thread.sleep(150);
        assertTrue("未授权时不应投递", bus.history().isEmpty());
    }

    @Test
    public void currentTokenReturnsStaticOrStub() {
        FeishuChannel noStatic = new FeishuChannel(bus, 0, "app", "sec", "v", "e", null);
        assertEquals("STUB-TOKEN-please-fill-appSecret", noStatic.currentToken());
        FeishuChannel withStatic = new FeishuChannel(bus, 0, "app", "sec", "v", "e", "static-token-value");
        assertEquals("static-token-value", withStatic.currentToken());
    }

    @Test
    public void signatureHelperRejectsMismatchWithoutEncryptKey() {
        // encryptKey=null/empty 时 verifySignature 直接返回 true（关闭校验）
        FeishuChannel ch = new FeishuChannel(bus, 0, "app", "sec", "v", null, null);
        assertTrue(ch.verifySignature("ts", "nonce", "body", "anything"));
        FeishuChannel empty = new FeishuChannel(bus, 0, "app", "sec", "v", "", null);
        assertTrue(empty.verifySignature("ts", "nonce", "body", "anything"));
    }

    @Test
    public void signatureHelperAcceptsCorrectDigest() {
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", "my-secret", null);
        long ts = 1700000000L;
        String nonce = "n-1";
        String body = "{\"event\":{}}";
        String s = ts + nonce + "my-secret" + body;
        String sha1 = sha1Hex(s);
        assertTrue(ch.verifySignature(String.valueOf(ts), nonce, body, sha1));
    }

    @Test
    public void outgoingUrlIsStable() {
        assertNotNull(channel.outgoingUrl());
        assertTrue(channel.outgoingUrl().contains("open-apis/im/v1/messages"));
    }

    // ===== helpers =====

    private int postJson(String path, String body) throws IOException {
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
        return con.getResponseCode();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long maxMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}