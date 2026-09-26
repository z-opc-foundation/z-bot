package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DingTalkChannel} 的<b>真出站</b>单测：对 127.0.0.1 的假群机器人端点断言
 * 发出去的字节（URL、加签参数、body 形状），并演 errcode 分类与凭据不泄漏。
 * 本期没有真钉钉凭据（EVIDENCE §0-8），所以这里证的是协议，不是"钉钉收到了"。
 */
public class DingTalkOutboundTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCESS_SENTINEL = "0123456789abcdef0123456789abcdef-leakcanary";
    private static final String SECRET_SENTINEL = "SECleakcanary0123456789abcdefghij";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeImEndpoint fake;
    private ChannelBus bus;

    @Before
    public void setUp() throws Exception {
        fake = new FakeImEndpoint();
        bus = new ChannelBus(stubAgent());
        bus.start();
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

    private DingTalkChannel signed() {
        return new DingTalkChannel(bus, 0, webhookUrl(), SECRET_SENTINEL, null);
    }

    private String webhookUrl() {
        return fake.base() + "/robot/send?access_token=" + ACCESS_SENTINEL;
    }

    // ===== 协议形状 =====

    @Test
    public void sendPostsTextBodyToSignedWebhookUrl() throws Exception {
        signed().send(OutboundMessage.text("chat_1", "真出站测试"));
        List<FakeImEndpoint.Recorded> posts = fake.requestsTo("/robot/send");
        assertEquals("钉钉出站就是 POST 群机器人 webhook", 1, posts.size());
        FakeImEndpoint.Recorded r = posts.get(0);
        assertEquals("POST", r.method);
        assertEquals("/robot/send", r.path);
        assertEquals(ACCESS_SENTINEL, r.query.get("access_token"));
        assertTrue("加签必须带 timestamp：" + r.query, r.query.containsKey("timestamp"));
        assertTrue("加签必须带 sign：" + r.query, r.query.containsKey("sign"));

        String expected = sign(r.query.get("timestamp"), SECRET_SENTINEL);
        String sent = URLDecoder.decode(r.query.get("sign"), "UTF-8");
        assertEquals("sign 就是 base64(HMAC-SHA256(secret, ts + \"\\n\" + secret))", expected, sent);

        Map<String, Object> body = JSON.readValue(r.body, new TypeReference<Map<String, Object>>() {
        });
        assertEquals("text", body.get("msgtype"));
        assertEquals("真出站测试", asMap(body.get("text")).get("content"));
    }

    /**
     * P0-2 的"过线字节"判据：不看 Java 对象，看假端点<b>服务端视角</b>还原出来的请求原文 ——
     * 请求行、Host、Content-Type、Content-Length、未解码的 timestamp/sign、body。
     * 只有这些都上了线，"真 HTTP 调用"才成立（不是进程内对象传参）。
     */
    @Test
    public void rawWireIsARealSignedPostAndReplyIsJson() throws Exception {
        DingTalkChannel ch = signed();
        ch.start();
        try {
            ch.send(OutboundMessage.text("chat_raw", "过线字节"));
            String raw = lastPost().rawDump();
            assertTrue("请求行: " + raw, raw.startsWith(
                    "POST /robot/send?access_token=" + ACCESS_SENTINEL + "&timestamp="));
            assertTrue("签名必须未解码地上线: " + raw, raw.contains("&sign="));
            assertTrue("Host 指向假端点: " + raw, raw.contains("host: 127.0.0.1:" + fake.port()));
            assertTrue("Content-Type: " + raw, raw.contains("content-type: application/json"));
            assertTrue("Content-Length: " + raw, raw.contains("content-length:"));
            assertTrue("body 原样过线: " + raw, raw.contains("\"msgtype\":\"text\""));
            assertTrue("消息正文原样过线: " + raw, raw.contains("过线字节"));
            assertEquals("假端点回的是 200 + JSON", 200, ch.lastHttpStatus());
            assertEquals("JSON 里 errcode=0 才算送出去", 0, ch.lastErrcode());
        } finally {
            ch.stop();
        }
    }

    @Test
    public void errorMessageAddsAtUsersAndStillSends() throws Exception {
        signed().send(OutboundMessage.error("chat_9", "Error: 炸了"));
        Map<String, Object> body = JSON.readValue(lastPost().body,
                new TypeReference<Map<String, Object>>() {
                });
        assertEquals("text", body.get("msgtype"));
        assertTrue("错误回执要 @ 到会坏的那会话：" + body, body.containsKey("at"));
        assertEquals(Collections.singletonList("chat_9"), (List<?>) asMap(body.get("at")).get("atUserIds"));
    }

    @Test
    public void unsignedRobotSendsWithoutTimestampAndSign() throws Exception {
        new DingTalkChannel(bus, 0, webhookUrl(), null, null)
                .send(OutboundMessage.text("chat_2", "hi"));
        FakeImEndpoint.Recorded r = lastPost();
        assertEquals(ACCESS_SENTINEL, r.query.get("access_token"));
        assertFalse("没配 secret 就不该自己造 timestamp/sign", r.query.containsKey("sign"));
        assertFalse(r.query.containsKey("timestamp"));
    }

    @Test
    public void realDomainUrlIsKeptVerbatimButIsNeverDialed() {
        // 真域名的正确性只以"构造出来的 URL 字符串"断言（本期无凭据，绝不外发）
        DingTalkChannel ch = new DingTalkChannel(bus, 0,
                "https://oapi.dingtalk.com/robot/send?access_token=zzz", "sec", null);
        String url = ch.signedWebhookUrl();
        assertTrue(url, url.startsWith("https://oapi.dingtalk.com/robot/send?access_token=zzz&"));
        assertTrue(url, url.contains("&timestamp=") && url.contains("&sign="));
    }

    // ===== 显式降级 =====

    @Test
    public void missingWebhookUrlThrowsNamingKeyAndSendsNothing() throws Exception {
        DingTalkChannel ch = new DingTalkChannel(bus, 0, null, "sec", null);
        try {
            ch.send(OutboundMessage.text("chat_3", "hi"));
            fail("缺 webhook-url 必须抛，不许静默当成已送达");
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("webhook-url"), e.missingKeys());
            assertTrue(e.getMessage(), e.getMessage().contains("webhook-url"));
        }
        assertEquals(0, fake.requests().size());
        // 阳性对照：同一支假端点配上 webhook-url 就真收到（否则"零请求"是空跑）
        signed().send(OutboundMessage.text("chat_3", "hi"));
        assertEquals(1, fake.requests().size());
    }

    @Test
    public void blankWebhookUrlIsTreatedAsMissingNotAsEmptyTarget() throws Exception {
        try {
            new DingTalkChannel(bus, 0, "   ", null, null).send(OutboundMessage.text("c", "hi"));
            fail();
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("webhook-url"), e.missingKeys());
        }
    }

    // ===== 错误分类 =====

    @Test
    public void errcodeNonZeroIsFailureAndIsClassifiedForDeadTargets() throws Exception {
        fake.setRobotResponse("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}");
        DingTalkChannel ch = signed();
        try {
            ch.send(OutboundMessage.text("chat_4", "hi"));
            fail("errcode!=0 就是没送出去");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("310000"));
            assertEquals(DeadTargets.FORBIDDEN, DeadTargets.classifySendError(e, null));
        }
        assertEquals(310000, lastErrcodeOf(ch));
    }

    @Test
    public void tokenNotExistIsChatLevelNotFound() throws Exception {
        fake.setRobotResponse("{\"errcode\":130101,\"errmsg\":\"token is not exist\"}");
        try {
            signed().send(OutboundMessage.text("chat_5", "hi"));
            fail();
        } catch (IOException e) {
            String kind = DeadTargets.classifySendError(e, null);
            assertEquals(DeadTargets.NOT_FOUND, kind);
            assertTrue(DeadTargets.isDeadErrorKind(kind));
        }
    }

    @Test
    public void subChatLevelMissIsNotPromotedToChatDeath() throws Exception {
        fake.setRobotResponse("{\"errcode\":1,\"errmsg\":\"message to reply not found\"}");
        try {
            signed().send(OutboundMessage.text("chat_6", "hi"));
            fail();
        } catch (IOException e) {
            String kind = DeadTargets.classifySendError(e, null);
            assertEquals(DeadTargets.THREAD_NOT_FOUND, kind);
            assertFalse(DeadTargets.isDeadErrorKind(kind));
        }
    }

    @Test
    public void httpLevelFailureIsReportedWithStatus() throws Exception {
        fake.queueResponse(500, "{\"errcode\":500,\"errmsg\":\"system error\"}");
        try {
            signed().send(OutboundMessage.text("chat_7", "hi"));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("500"));
            assertTrue(e.getMessage(), e.getMessage().contains("system error"));
        }
    }

    // ===== 凭据不许出现在文案里 =====

    @Test
    public void accessTokenNeverAppearsInExceptionEvenWhenPlatformEchoesIt() throws Exception {
        // 阳性对照：假端点收到的 query 里就在（哨兵真上了线）
        fake.setRobotResponse("{\"errcode\":400,\"errmsg\":\"bad request, url="
                + webhookUrl() + "&sign=xyz\"}");
        try {
            signed().send(OutboundMessage.text("chat_8", "hi"));
            fail();
        } catch (IOException e) {
            assertFalse("异常文案不许带 access_token 原文: " + e.getMessage(),
                    e.getMessage().contains(ACCESS_SENTINEL));
            assertFalse("也不许带 sign 原文: " + e.getMessage(),
                    e.getMessage().matches("(?s).*&sign=[A-Za-z0-9%+/=]{8,}.*"));
        }
        assertTrue("阳性对照：access_token 确实上了线",
                lastPost().query.get("access_token").equals(ACCESS_SENTINEL));
    }

    @Test
    public void scrubUrlCoversTokenAndSignShapes() {
        String s = DingTalkChannel.scrubUrl("http://127.0.0.1:1/robot/send?access_token=AAA&x=1&sign=BBB%3D");
        assertEquals("http://127.0.0.1:1/robot/send?access_token=***&x=1&sign=***", s);
    }

    @Test
    public void transportFailureTextIsScrubbedToo() throws Exception {
        // 连不上的端点：okhttp 的 ConnectException 里本来就没有 query，但文案这一层仍要过 scrub
        DingTalkChannel ch = new DingTalkChannel(bus, 0,
                "http://127.0.0.1:1/robot/send?access_token=" + ACCESS_SENTINEL, SECRET_SENTINEL, null);
        try {
            ch.send(OutboundMessage.text("chat_9", "hi"));
            fail("连不上必须抛");
        } catch (IOException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(ACCESS_SENTINEL));
            assertFalse(e.getMessage(), e.getMessage().contains(SECRET_SENTINEL));
            assertTrue("要留下可诊断的形状: " + e.getMessage(), e.getMessage().contains("传输失败"));
        }
    }

    // ===== delivered 标志（负向判据 + 阳性对照） =====

    @Test
    public void deliveredFlagIsOnlyWrittenOnRealSuccess() throws Exception {
        DingTalkChannel ok = new DingTalkChannel(bus, 0, webhookUrl(), SECRET_SENTINEL, null);
        ok.start();
        try {
            ok.send(OutboundMessage.text("chat_a", "delivered-case"));
            Map<String, Object> payload = pollOutbound(ok, 1500L);
            assertEquals(Boolean.TRUE, payload.get("delivered"));
            assertEquals("delivered-case", payload.get("text"));

            fake.setRobotResponse("{\"errcode\":1,\"errmsg\":\"send failed\"}");
            try {
                ok.send(OutboundMessage.text("chat_b", "failed-case"));
                fail();
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("send failed"));
            }
            if (pollOutboundQuietly(ok) != null) {
                fail("失败的那条不许进 outbound 队列");
            }
        } finally {
            ok.stop();
        }
    }

    // ===== helpers =====

    private FakeImEndpoint.Recorded lastPost() {
        List<FakeImEndpoint.Recorded> posts = fake.requestsTo("/robot/send");
        assertFalse("假端点没收到 /robot/send", posts.isEmpty());
        return posts.get(posts.size() - 1);
    }

    private int lastErrcodeOf(DingTalkChannel ch) {
        return ch.lastErrcode();
    }

    private Map<String, Object> pollOutbound(DingTalkChannel ch, long readTimeoutMs) throws Exception {
        java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + ch.getPort() + "/dingtalk/out").openConnection();
        con.setConnectTimeout(1500);
        con.setReadTimeout((int) readTimeoutMs);
        try (java.io.InputStream is = con.getInputStream()) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return JSON.readValue(new String(baos.toByteArray(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {
                    });
        }
    }

    private Map<String, Object> pollOutboundQuietly(DingTalkChannel ch) throws Exception {
        try {
            Map<String, Object> m = pollOutbound(ch, 500L);
            return "".equals(m.get("text")) ? null : m;
        } catch (java.net.SocketTimeoutException expected) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static String sign(String timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(raw);
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
