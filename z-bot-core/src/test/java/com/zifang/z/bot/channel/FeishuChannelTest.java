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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FeishuChannel} URL 校验 + 入站分发 + 签名校验真 HTTP 单测。
 * stub 的发送仍只走 outbound 队列，不发真飞书。
 */
public class FeishuChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** fixture 与签名 helper 共用一份 key —— 各写一遍迟早会漂。 */
    private static final String VERIFY_TOKEN = "verify-token-123";
    private static final String ENCRYPT_KEY = "encrypt-key-456";
    private static final String TS = "1700000000";

    private FeishuChannel channel;
    private ChannelBus bus;
    private int port;

    @Before
    public void setUp() throws Exception {
        bus = new ChannelBus(stubAgent());
        bus.start();
        channel = new FeishuChannel(bus, 0, "app-id", "app-secret",
                VERIFY_TOKEN, ENCRYPT_KEY, null);
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
        String[] resp = postEvent(body, TS, "n-1", Sign.CORRECT);
        assertEquals("带正确签名的入站事件必须过签名门（否则后面那串断言全是空跑）: " + resp[1], "200", resp[0]);
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
        // 必须带着正确签名进来，否则 401 是签名门给的，token 门根本没被走到（会假绿）。
        String body = "{\"token\":\"wrong-token\",\"event\":{\"chat_id\":\"x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-2", Sign.CORRECT);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自 token 门而不是签名门: " + resp[1], resp[1].contains("token mismatch"));
        // 等一小段时间确保没有异步投递
        Thread.sleep(150);
        assertTrue("未授权时不应投递", bus.history().isEmpty());
    }

    @Test
    public void tamperedSignatureIsRejectedWith401AndNeverReachesBus() throws Exception {
        // 猎物：token 是对的、签名是拿别人的 key 算的 ⇒ 只能被签名门挡下。
        // 这条是 LEDGER D10/D11 的杀手：把 verifySignature 改成恒真、或把 handleEvent 里的验签摘掉，都会让它变绿。
        String body = "{\"token\":\"verify-token-123\",\"event\":{\"chat_id\":\"oc_x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-3", Sign.FOREIGN_KEY);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自签名门: " + resp[1], resp[1].contains("signature mismatch"));
        Thread.sleep(150);
        assertTrue("错签名的内容不得进 bus", bus.history().isEmpty());
    }

    @Test
    public void missingSignatureHeadersAreRejectedWhenEncryptKeyConfigured() throws Exception {
        // fail-closed：配了 encrypt-key 却一个签名头都不带，也算拒（不许靠"不带头"绕过去）。
        String body = "{\"token\":\"verify-token-123\",\"event\":{\"chat_id\":\"oc_x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-4", Sign.NONE);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自签名门: " + resp[1], resp[1].contains("signature mismatch"));
        Thread.sleep(150);
        assertTrue("缺签名的内容不得进 bus", bus.history().isEmpty());
    }

    @Test
    public void unsignedEventIsStillAcceptedWhenEncryptKeyAbsent() throws Exception {
        // 反向对照：门是**配了 encrypt-key 才关**的。这条防的是"把守卫改成恒验"这种过头修法
        // （那样上面两条负例照样绿，而真实未加密接入方会被全拒）。
        FeishuChannel plain = new FeishuChannel(bus, 0, "app-id", "app-secret",
                VERIFY_TOKEN, null, null);
        try {
            plain.start();
            String body = "{\"token\":\"verify-token-123\",\"event\":"
                    + "{\"sender_id\":\"ou_9\",\"chat_id\":\"oc_plain\",\"text\":\"未加密接入\"}}";
            int rc = post(plain.getPort(), body, Sign.NONE);
            assertEquals("未配 encrypt-key 时未签名入站应被接受", 200, rc);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            assertEquals("未加密接入", bus.history().get(0).text);
        } finally {
            plain.stop();
        }
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
    public void signatureHelperRejectsWrongDigest() {
        // 兑现侧那一层（上面两条 HTTP 负例钉"接线"，这条钉"算法"）：
        // 错签名、内容被改一个字节、缺 signature，都必须返回 false —— 恒真的实现当场变红。
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", "my-secret", null);
        String body = "{\"event\":{}}";
        assertFalse("错签名必须拒", ch.verifySignature("1700000000", "n-1", body, "deadbeef"));
        assertFalse("换了 key 算出来的签名必须拒",
                ch.verifySignature("1700000000", "n-1", body,
                        sha1Hex("1700000000" + "n-1" + "another-key" + body)));
        assertFalse("内容改一个字节，原签名必须失效",
                ch.verifySignature("1700000000", "n-1", body + " ",
                        sha1Hex("1700000000" + "n-1" + "my-secret" + body)));
        assertFalse("缺 signature 必须拒", ch.verifySignature("1700000000", "n-1", body, null));
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

    /** 签名怎么带：{@link #NONE} 一个头都不发，{@link #FOREIGN_KEY} 发拿别人 key 算的签名。 */
    private enum Sign { NONE, CORRECT, FOREIGN_KEY }

    private String[] postEvent(String body, String ts, String nonce, Sign sign) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/feishu/event");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (sign != Sign.NONE) {
            String key = sign == Sign.CORRECT ? ENCRYPT_KEY : "someone-elses-key";
            con.setRequestProperty("X-Lark-Request-Timestamp", ts);
            con.setRequestProperty("X-Lark-Request-Nonce", nonce);
            con.setRequestProperty("X-Lark-Signature", sha1Hex(ts + nonce + key + body));
        }
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return new String[] {String.valueOf(code), is == null ? "" : readAll(is)};
    }

    private int post(int targetPort, String body, Sign sign) throws IOException {
        URL url = new URL("http://127.0.0.1:" + targetPort + "/feishu/event");
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