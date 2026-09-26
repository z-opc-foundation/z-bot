package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link FeishuChannel} 的<b>真出站</b>单测：全部对着 127.0.0.1 的假端点
 * （{@link FakeImEndpoint}）断言<b>发出去的字节</b>——URL、query、header、body ——
 * 不是对着日志。本期没有真飞书凭据（EVIDENCE §0-8），所以这里证明的是协议形状与错误分类，
 * 不是"飞书收到了"。
 */
public class FeishuOutboundTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeImEndpoint fake;
    private ChannelBus bus;
    private int port;

    @Before
    public void setUp() throws Exception {
        fake = new FakeImEndpoint();
        bus = new ChannelBus(stubAgent());
        bus.start();
        port = 0;
    }

    @After
    public void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
        if (fake != null) {
            fake.close();
        }
    }

    private FeishuChannel channel(String appId, String appSecret, String staticToken) {
        return new FeishuChannel(bus, 0, appId, appSecret, "verify-token", "encrypt-key",
                staticToken, null, fake.base(), null);
    }

    // ===== token 换取 =====

    @Test
    public void tokenExchangeIsRealPostWithAppIdAndSecret() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_recv_1", "hi"));

        List<FakeImEndpoint.Recorded> tokens = fake.requestsTo("/auth/v3/tenant_access_token/internal");
        assertEquals("token 端点应当被真打了 1 次", 1, tokens.size());
        FakeImEndpoint.Recorded t = tokens.get(0);
        assertEquals("POST", t.method);
        Map<String, Object> body = parse(t.body);
        assertEquals("cli_stub_app", body.get("app_id"));
        assertEquals("stub-secret-not-real", body.get("app_secret"));
        assertEquals("application/json; charset=utf-8", t.header("content-type"));
        assertEquals("token 换取消耗在 /auth/ 上", 1, fake.tokenFetches());
    }

    @Test
    public void staticTokenPathSkipsTokenEndpoint() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", "stub-static-token");
        ch.send(OutboundMessage.text("ou_recv_1", "hi"));
        assertEquals("给了 static-token 就不该再换 token", 0, fake.tokenFetches());
        FakeImEndpoint.Recorded msg = lastMessagePost();
        assertEquals("Bearer stub-static-token", msg.header("authorization"));
    }

    @Test
    public void tokenIsCachedAcrossSends() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_a", "one"));
        ch.send(OutboundMessage.text("ou_b", "two"));
        assertEquals("expire=3600 ⇒ 第二次发送复用缓存", 1, fake.tokenFetches());
        assertEquals(2, fake.messagePosts());
    }

    @Test
    public void shortExpiryForcesRefetchBecauseOfRefreshMargin() throws Exception {
        fake.setTokenResponse("{\"code\":0,\"tenant_access_token\":\"t-short\",\"expire\":1}");
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_a", "one"));
        ch.send(OutboundMessage.text("ou_b", "two"));
        assertEquals("只剩 1s 的 token 落在 60s 提前续期窗里 ⇒ 每次都重取", 2, fake.tokenFetches());
    }

    @Test
    public void authRejectionInvalidatesCacheRefetchesAndRetriesOnce() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_a", "first"));         // 1 次 token + 1 次 message
        assertEquals(1, fake.tokenFetches());
        fake.queueResponse(401, "{\"code\":99991663,\"msg\":\"tenant access token invalid\"}");
        ch.send(OutboundMessage.text("ou_b", "second"));        // message 吃 401 → 重取 token → 重发成功
        assertEquals("判废后应当重取了一次 token", 2, fake.tokenFetches());
        assertEquals("第一次 401 + 重发成功那一次", 3, fake.messagePosts());
        assertEquals(0, ch.lastPlatformCode());
    }

    // ===== 发送协议形状 =====

    @Test
    public void messagePostCarriesBearerTokenAndFeishuBodyShape() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_recv_42", "你好，世界"));

        FakeImEndpoint.Recorded m = lastMessagePost();
        assertEquals("POST", m.method);
        assertEquals("/im/v1/messages", m.path);
        assertEquals("open_id", m.query.get("receive_id_type"));
        assertEquals("Bearer t-fake-0001", m.header("authorization"));
        Map<String, Object> body = parse(m.body);
        assertEquals("ou_recv_42", body.get("receive_id"));
        assertEquals("text", body.get("msg_type"));
        assertTrue("飞书协议要求 content 是字符串化的 JSON，不是对象",
                body.get("content") instanceof String);
        Map<String, Object> content = parse((String) body.get("content"));
        assertEquals("你好，世界", content.get("text"));
    }

    @Test
    public void receiveIdTypeIsHonouredFromConfig() throws Exception {
        FeishuChannel ch = new FeishuChannel(bus, 0, "cli_stub_app", "stub-secret-not-real",
                "v", "e", null, null, fake.base(), "chat_id");
        ch.send(OutboundMessage.text("oc_chat", "hi"));
        assertEquals("chat_id", lastMessagePost().query.get("receive_id_type"));
        assertTrue(ch.outgoingUrl().endsWith("?receive_id_type=chat_id"));
    }

    @Test
    public void errorMessageCarriesUuidAndStillSends() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.error("ou_recv_9", "Error: 模型没答上来"));
        Map<String, Object> body = parse(lastMessagePost().body);
        assertNotNull("错误回执带一个幂等 uuid", body.get("uuid"));
        assertTrue(String.valueOf(body.get("uuid")).startsWith("zbot-err-"));
    }

    @Test
    public void outgoingUrlDefaultsToRealFeishuDomainButNeverSendsThere() {
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", "e", null);
        assertEquals("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id",
                ch.outgoingUrl());
        assertEquals("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                ch.tokenUrl());
    }

    // ===== 显式降级：缺凭据不静默 =====

    @Test
    public void missingCredentialsThrowsNamingKeysAndSendsNothing() throws Exception {
        FeishuChannel ch = channel(null, null, null);
        try {
            ch.send(OutboundMessage.text("ou_recv_1", "hi"));
            fail("缺凭据时必须抛，不许看起来发成功了");
        } catch (ChannelConfigException e) {
            assertEquals(java.util.Arrays.asList("app-id", "app-secret"), e.missingKeys());
            assertTrue(e.getMessage(), e.getMessage().contains("app-id"));
            assertTrue(e.getMessage(), e.getMessage().contains("static-token"));
        }
        assertEquals("一个字节都不该往外发", 0, fake.requests().size());
        // 阳性对照：同一个假端点配上凭据就真收到东西（证明"零请求"不是因为量具坏了）
        channel("cli_stub_app", "stub-secret-not-real", null)
                .send(OutboundMessage.text("ou_recv_1", "hi"));
        assertTrue("阳性对照：有凭据时确实发出去了", fake.requests().size() > 0);
    }

    @Test
    public void halfConfiguredCredentialsNameOnlyTheMissingKey() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", null, null);
        try {
            ch.send(OutboundMessage.text("ou_1", "hi"));
            fail();
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("app-secret"), e.missingKeys());
        }
        assertTrue(ch.missingCredentialKeys().contains("app-secret"));
    }

    // ===== 错误分类（喂给 P16 的死目标登记表） =====

    @Test
    public void chatLevelNotFoundIsClassifiedAsDeadTarget() throws Exception {
        fake.setMessageResponse("{\"code\":230002,\"msg\":\"chat not found\"}");
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        try {
            ch.send(OutboundMessage.text("oc_gone", "hi"));
            fail("平台 code!=0 不能算发送成功");
        } catch (IOException e) {
            assertTrue("异常原文带着平台 msg：" + e.getMessage(),
                    e.getMessage().contains("chat not found"));
            assertEquals(DeadTargets.NOT_FOUND, DeadTargets.classifySendError(e, null));
            assertTrue(DeadTargets.isDeadErrorKind(DeadTargets.classifySendError(e, null)));
        }
        assertEquals("失败不留 delivered 记录", 230002, ch.lastPlatformCode());
    }

    @Test
    public void blockedBotIsClassifiedAsForbidden() throws Exception {
        fake.setMessageResponse("{\"code\":230018,\"msg\":\"Bot was blocked by the user\"}");
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        try {
            ch.send(OutboundMessage.text("ou_block", "hi"));
            fail();
        } catch (IOException e) {
            assertEquals(DeadTargets.FORBIDDEN, DeadTargets.classifySendError(e, null));
        }
    }

    @Test
    public void threadLevelNotFoundIsNotAChatLevelDeath() throws Exception {
        fake.setMessageResponse("{\"code\":230004,\"msg\":\"thread not found\"}");
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        try {
            ch.send(OutboundMessage.text("ou_thread", "hi"));
            fail();
        } catch (IOException e) {
            String kind = DeadTargets.classifySendError(e, null);
            assertEquals("话题级 not_found 不能升级成整会话不可达",
                    DeadTargets.THREAD_NOT_FOUND, kind);
            assertFalse(DeadTargets.isDeadErrorKind(kind));
        }
    }

    @Test
    public void httpLevelFailureIsReportedWithStatusAndNotDelivered() throws Exception {
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_1", "warm-up"));     // 先把 token 换到手
        assertEquals(1, fake.tokenFetches());
        fake.queueResponse(503, "upstream unavailable");      // 下一次请求 = message 发送
        try {
            ch.send(OutboundMessage.text("ou_2", "hi"));
            fail("HTTP 5xx 就是没送出去");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("503"));
            assertEquals(503L, ch.lastHttpStatus());
        }
        assertEquals("503 不是判废信号，不该重取 token", 1, fake.tokenFetches());
        assertEquals(2, fake.messagePosts());
    }

    // ===== 凭据不许出现在文案里 =====

    @Test
    public void leakedSecretFromTokenEndpointIsScrubbedOutOfExceptionText() throws Exception {
        // 阳性对照：哨兵真的上了线 —— 假端点收到的请求体里就在
        String sentinel = "SENTINEL-SECRET-abcdefghijklmnop";
        fake.queueResponse(500, "{\"code\":99991672,\"msg\":\"invalid app_secret "
                + sentinel + "\"}");
        FeishuChannel ch = channel("cli_stub_app", sentinel, null);
        try {
            ch.send(OutboundMessage.text("ou_1", "hi"));
            fail("token 换取失败要冒出来");
        } catch (IOException e) {
            assertFalse("异常文案里不许带 appSecret 原文: " + e.getMessage(),
                    e.getMessage().contains(sentinel));
            assertTrue("要留出可诊断的形状: " + e.getMessage(), e.getMessage().contains("99991672"));
        }
        assertTrue("阳性对照：哨兵确实发出去了（否则上面的\"没泄漏\"是空跑）",
                fake.requestsTo("/auth/").get(0).body.contains(sentinel));
    }

    @Test
    public void revokedTokenEchoedBackByPlatformIsScrubbed() throws Exception {
        String tokenSentinel = "t-fakesecret-0000000011111111";
        fake.setTokenResponse("{\"code\":0,\"tenant_access_token\":\"" + tokenSentinel + "\",\"expire\":3600}");
        FeishuChannel ch = channel("cli_stub_app", "stub-secret-not-real", null);
        ch.send(OutboundMessage.text("ou_1", "hi"));
        // 平台把刚用过的 bearer 回显在 msg 里（describeFailure 会把 msg 原样带进异常文案）
        fake.setMessageResponse("{\"code\":230099,\"msg\":\"send failed, used token "
                + tokenSentinel + "\"}");
        try {
            ch.send(OutboundMessage.text("ou_2", "hi"));
            fail();
        } catch (IOException e) {
            assertFalse("平台把 bearer token 回显进 msg 时也要洗掉: " + e.getMessage(),
                    e.getMessage().contains(tokenSentinel));
            assertTrue("阳性对照：token 真被用过（否则\"没泄漏\"是空跑）",
                    lastMessagePost().header("authorization").contains(tokenSentinel));
            assertEquals(tokenSentinel, ch.currentToken());
        }
    }

    // ===== 出站队列的 delivered 标志（杠② 的负向判据） =====

    @Test
    public void successfulSendQueuesDeliveredPayloadAndFailureDoesNot() throws Exception {
        FeishuChannel ok = channel("cli_stub_app", "stub-secret-not-real", null);
        ok.start();
        try {
            ok.send(OutboundMessage.text("ou_1", "delivered-case"));
            Map<String, Object> payload = pollOutbound(ok, 1500L);
            assertEquals(Boolean.TRUE, payload.get("delivered"));
            assertEquals("delivered-case", payload.get("text"));

            fake.setMessageResponse("{\"code\":230002,\"msg\":\"chat not found\"}");
            try {
                ok.send(OutboundMessage.text("ou_2", "failed-case"));
                fail("平台报 chat not found 就是没送出去");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("chat not found"));
            }
            assertNullish(pollOutboundQuietly(ok), "失败的那条不许进 outbound 队列");
        } finally {
            ok.stop();
        }
    }

    // ===== helpers =====

    private FakeImEndpoint.Recorded lastMessagePost() {
        List<FakeImEndpoint.Recorded> posts = fake.requestsTo("/im/v1/messages");
        assertFalse("假端点没收到任何 /im/v1/messages 请求", posts.isEmpty());
        return posts.get(posts.size() - 1);
    }

    /** GET /feishu/out：服务端最多阻塞 20s，所以读超时就是"队里没有东西"的证据。 */
    private Map<String, Object> pollOutbound(FeishuChannel ch, long readTimeoutMs) throws Exception {
        java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + ch.getPort() + "/feishu/out").openConnection();
        con.setConnectTimeout(1500);
        con.setReadTimeout((int) readTimeoutMs);
        try (java.io.InputStream is = con.getInputStream()) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return parse(new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 队里没东西时 /feishu/out 会一直阻塞到读超时 ⇒ 返回 {@code null}。 */
    private Map<String, Object> pollOutboundQuietly(FeishuChannel ch) throws Exception {
        try {
            Map<String, Object> m = pollOutbound(ch, 500L);
            return "".equals(m.get("text")) ? null : m;
        } catch (java.net.SocketTimeoutException expected) {
            return null;
        }
    }

    private void assertNullish(Map<String, Object> v, String msg) {
        if (v != null && !v.isEmpty()) {
            fail(msg + " —— 实际读到 " + v);
        }
    }

    private static Map<String, Object> parse(String body) throws IOException {
        return JSON.readValue(body, new TypeReference<Map<String, Object>>() {
        });
    }

    private BotAgent stubAgent() throws Exception {
        return BotAgent.builder(null)
                .provider(new LlmProvider() {
                    @Override
                    public String name() {
                        return "stub";
                    }

                    @Override
                    public List<Model> listModels() {
                        return Collections.emptyList();
                    }

                    @Override
                    public boolean supportsModel(String m) {
                        return true;
                    }

                    @Override
                    public ChatCompletionsResponse chat(ChatCompletionsRequest r) {
                        String last = r.getMessages().isEmpty() ? ""
                                : r.getMessages().get(r.getMessages().size() - 1).getContent();
                        return new ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                                        "echo: " + last,
                                        Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new TokenUsage(5L, 3L, 8L), "stop", null);
                    }

                    @Override
                    public void streamChat(ChatCompletionsRequest r,
                                           java.util.function.Consumer<ChatCompletionsResponse> on,
                                           java.util.function.Consumer<Throwable> err) {
                        on.accept(chat(r));
                    }
                })
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutCenter()
                .build();
    }
}
