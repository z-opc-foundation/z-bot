package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.store.SqliteTx;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.util.json.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ZMsgChannel} 的单测：z-msg 直发端点的协议字节、台账里的 msgId、失败与幂等语义。
 *
 * <p>对端是本机 {@code 127.0.0.1} 上的假 z-msg（{@link FakeZMsg}），只认
 * {@code POST /api/msg/send}，回的是 z-msg 真回执形状
 * （{@code {"data":{"msgId":…},"success":true,"code":200}}）。
 * 线上真 z-msg 那条最后一跳（真邮件送达）不在本测试范围内 ——
 * 它需要活的 z-msg + 邮件通道，属卡内的「待人工授权/未验证」项。</p>
 */
public class ZMsgChannelTest {

    private static final SqliteTx.Config CFG = StateStore.Options.defaults();
    private static final String TOKEN_SENTINEL = "zmsg-token-leakcanary-0123456789";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeZMsg fake;
    private ChannelBus bus;
    private DeliveryLedger ledger;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        fake = new FakeZMsg();
        agent = stubAgent();
        bus = new ChannelBus(agent);
        bus.start();
        ledger = new DeliveryLedger(new File(tmp.newFolder("profile"), "state.db"), CFG);
        bus.setDeliveryLedger(ledger);
    }

    @After
    public void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
        if (fake != null) {
            fake.close();
        }
        if (agent != null) {
            agent.shutdown();
        }
    }

    private ZMsgChannel channel() {
        return new ZMsgChannel(bus, fake.base(), null, null, null, null);
    }

    // ===== 协议形状 =====

    @Test
    public void sendPostsToZMsgSendEndpointWithTheExpectedBody() throws Exception {
        channel().send(OutboundMessage.text("me@example.com", "把这段总结发给 me@example.com"));

        assertEquals("应当只发一条", 1, fake.requests().size());
        FakeZMsg.Request r = fake.requests().get(0);
        assertEquals("POST", r.method);
        assertEquals("/api/msg/send", r.path);
        assertTrue("Content-Type 必须是 JSON: " + r.header("content-type"),
                String.valueOf(r.header("content-type")).startsWith("application/json"));

        Map<String, Object> body = plainMap(r.body);
        assertEquals("z-msg 侧介质缺省是邮件", "EMAIL", body.get("channel"));
        assertEquals("收件人取投递目标", "me@example.com", body.get("receiver"));
        assertEquals("正文原样透传", "把这段总结发给 me@example.com", body.get("content"));
        assertEquals("归档分类缺省 DIRECT_SEND", "DIRECT_SEND", body.get("bizType"));
        assertTrue("幂等键必须非空（它是两本账的 join key）",
                String.valueOf(body.get("idempotencyKey")).length() > 0);
    }

    @Test
    public void configuredSubjectAndMsgChannelArePassedThrough() throws Exception {
        new ZMsgChannel(bus, fake.base(), null, "SMS", "Z_BOT_OUTBOUND", "【z-bot】总结")
                .send(OutboundMessage.text("+8613800000000", "正文"));
        Map<String, Object> body = plainMap(fake.requests().get(0).body);
        assertEquals("SMS", body.get("channel"));
        assertEquals("Z_BOT_OUTBOUND", body.get("bizType"));
        assertEquals("【z-bot】总结", body.get("subject"));
    }

    @Test
    public void baseUrlIsUsedVerbatimAndPathIsAppended() {
        ZMsgChannel ch = new ZMsgChannel(bus, "http://127.0.0.1:9/zmsg/", null, null, null, null);
        assertEquals("http://127.0.0.1:9/zmsg/api/msg/send", ch.sendUrl());
    }

    @Test
    public void authorizationHeaderIsSentOnlyWhenTokenConfigured() throws Exception {
        new ZMsgChannel(bus, fake.base(), null, null, null, null)
                .send(OutboundMessage.text("me@example.com", "无 token"));
        assertEquals("没配 token 就不该有 Authorization 头", null,
                fake.requests().get(0).header("authorization"));

        new ZMsgChannel(bus, fake.base(), TOKEN_SENTINEL, null, null, null)
                .send(OutboundMessage.text("me@example.com", "有 token"));
        assertEquals("Bearer " + TOKEN_SENTINEL, fake.requests().get(1).header("authorization"));
    }

    // ===== 缺配置不许静默 =====

    @Test
    public void missingBaseUrlThrowsNamingTheKeyAndSendsNothing() throws Exception {
        ZMsgChannel ch = new ZMsgChannel(bus, null, null, null, null, null);
        try {
            ch.send(OutboundMessage.text("me@example.com", "hi"));
            fail("缺 base-url 必须抛，不许静默当成已送达");
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("base-url"), e.missingKeys());
            assertTrue(e.getMessage(), e.getMessage().contains("base-url"));
        }
        assertEquals("一条都不许发出去", 0, fake.requests().size());
    }

    @Test
    public void blankReceiverFailsBeforeAnythingIsSent() throws Exception {
        try {
            channel().send(OutboundMessage.text("   ", "hi"));
            fail("没有收件人必须抛");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("收件人"));
        }
        assertEquals(0, fake.requests().size());
    }

    // ===== 台账：msgId =====

    @Test
    public void ledgerRecordsTheMsgIdZMsgReturned() throws Exception {
        fake.setMsgId("msgid-abc-123");
        channel().send(OutboundMessage.text("me@example.com", "总结正文"));

        String oid = ZMsgChannel.obligationId("me@example.com", "总结正文");
        assertEquals("这一条应当已结清", Collections.singletonList(oid),
                ledger.idsInState(DeliveryLedger.DELIVERED));
        assertEquals("台账里记的 msgId 必须与 z-msg 回执一致", "msgid-abc-123",
                ledger.externalMsgIdOf(oid));
    }

    @Test
    public void idempotencyKeySentToZMsgIsTheLedgerObligationId() throws Exception {
        channel().send(OutboundMessage.text("me@example.com", "总结正文"));
        Map<String, Object> body = plainMap(fake.requests().get(0).body);
        assertEquals("z-msg 的幂等键 = 台账义务 id（两本账的 join key）",
                ZMsgChannel.obligationId("me@example.com", "总结正文"), body.get("idempotencyKey"));
    }

    @Test
    public void obligationIdIsStableAcrossTheRecoveredMarker() {
        String plain = ZMsgChannel.obligationId("me@example.com", "总结正文");
        String marked = ZMsgChannel.obligationId("me@example.com",
                DeliveryLedger.RECOVERED_MARKER + "总结正文");
        assertEquals("重投标记不是正文的一部分 ⇒ 义务 id 必须一样（否则重投会记成新的一条）",
                plain, marked);
    }

    @Test
    public void sameReceiverAndContentShareOneLedgerRow() throws Exception {
        channel().send(OutboundMessage.text("me@example.com", "同一份总结"));
        channel().send(OutboundMessage.text("me@example.com", "同一份总结"));
        assertEquals("同一封邮件只占一行台账", 1, ledger.totalRows());
        assertEquals(2, fake.requests().size());
    }

    // ===== 失败语义 =====

    @Test
    public void serverErrorIsRecordedAsFailedAndCarriesTheStatus() throws Exception {
        fake.setStatus(500);
        fake.setBody("{\"success\":false,\"code\":500,\"message\":\"发送失败[PROVIDER_EXCEPTION]：厂商侧炸了\"}");
        try {
            channel().send(OutboundMessage.text("me@example.com", "总结正文"));
            fail("5xx 必须抛");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("500"));
            assertTrue(e.getMessage(), e.getMessage().contains("厂商侧炸了"));
        }
        String oid = ZMsgChannel.obligationId("me@example.com", "总结正文");
        assertEquals("失败要落 failed 态（它是天然的 Retry 边界）",
                Collections.singletonList(oid), ledger.idsInState(DeliveryLedger.FAILED));
        assertEquals("失败行不许有 msgId（没送出去）", "", ledger.externalMsgIdOf(oid));
    }

    @Test
    public void clientErrorIsRecordedAsFailedToo() throws Exception {
        fake.setStatus(400);
        fake.setBody("{\"success\":false,\"code\":400,\"message\":\"处理失败|未知通道：EMAIL\"}");
        try {
            channel().send(OutboundMessage.text("me@example.com", "总结正文"));
            fail("4xx 必须抛");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("400"));
        }
        String oid = ZMsgChannel.obligationId("me@example.com", "总结正文");
        assertEquals(Collections.singletonList(oid), ledger.idsInState(DeliveryLedger.FAILED));
    }

    @Test
    public void successWithoutMsgIdIsNotCountedAsDelivered() throws Exception {
        fake.setBody("{\"data\":null,\"success\":true,\"code\":200}");
        try {
            channel().send(OutboundMessage.text("me@example.com", "总结正文"));
            fail("拿不到回执 msgId 不许记 delivered");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("msgId"));
        }
        String oid = ZMsgChannel.obligationId("me@example.com", "总结正文");
        assertEquals(Collections.singletonList(oid), ledger.idsInState(DeliveryLedger.FAILED));
    }

    @Test
    public void duplicate409IsTreatedAsAlreadySubmitted() throws Exception {
        fake.setMsgId("msgid-first");
        channel().send(OutboundMessage.text("me@example.com", "总结正文"));

        fake.setStatus(409);
        fake.setBody("{\"success\":false,\"code\":409,\"message\":\"发送失败[DUPLICATED]：幂等命中\"}");
        channel().send(OutboundMessage.text("me@example.com", "总结正文"));

        String oid = ZMsgChannel.obligationId("me@example.com", "总结正文");
        assertEquals("幂等命中不算失败", Collections.singletonList(oid),
                ledger.idsInState(DeliveryLedger.DELIVERED));
        assertEquals("首次那条的 msgId 留着", "msgid-first", ledger.externalMsgIdOf(oid));
    }

    @Test
    public void transportFailureIsReportedWithoutTheToken() throws Exception {
        ZMsgChannel ch = new ZMsgChannel(bus, "http://127.0.0.1:1", TOKEN_SENTINEL, null, null, null);
        try {
            ch.send(OutboundMessage.text("me@example.com", "hi"));
            fail("连不上必须抛");
        } catch (IOException e) {
            assertFalse("异常文案不许带 token 原文: " + e.getMessage(),
                    e.getMessage().contains(TOKEN_SENTINEL));
            assertTrue("要留下可诊断的形状: " + e.getMessage(), e.getMessage().contains("传输失败"));
        }
    }

    // ===== 装配 =====

    @Test
    public void manifestShapeIsWiredToTheConstructor() throws Exception {
        java.io.File configDir = tmp.newFolder("zbot-home");
        java.io.File manifest = new java.io.File(configDir, ChannelRegistry.MANIFEST_FILE_NAME);
        java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifest), StandardCharsets.UTF_8);
        try {
            w.write("channel.zmsg.enabled=true\n"
                    + "channel.zmsg.config.base-url=" + fake.base() + "\n"
                    + "channel.zmsg.config.token=" + TOKEN_SENTINEL + "\n"
                    + "channel.zmsg.config.msg-channel=EMAIL\n"
                    + "channel.zmsg.config.biz-type=Z_BOT_OUTBOUND\n");
        } finally {
            w.close();
        }
        ChannelRegistry registry = ChannelRegistry.load(configDir);
        ZMsgChannel ch = (ZMsgChannel) registry.create("zmsg",
                new ChannelRegistry.Context(agent, bus, configDir));
        assertEquals("zmsg", ch.name());
        assertEquals(fake.base() + "/api/msg/send", ch.sendUrl());
        ch.send(OutboundMessage.text("me@example.com", "接线测试"));
        assertEquals("Bearer " + TOKEN_SENTINEL, fake.requests().get(0).header("authorization"));
        Map<String, Object> body = plainMap(fake.requests().get(0).body);
        assertEquals("Z_BOT_OUTBOUND", body.get("bizType"));
    }

    @Test
    public void missingBaseUrlInManifestIsRejectedByTheRegistry() throws Exception {
        java.io.File configDir = tmp.newFolder("zbot-home2");
        java.io.File manifest = new java.io.File(configDir, ChannelRegistry.MANIFEST_FILE_NAME);
        java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifest), StandardCharsets.UTF_8);
        try {
            w.write("channel.zmsg.enabled=true\n");
        } finally {
            w.close();
        }
        ChannelRegistry registry = ChannelRegistry.load(configDir);
        try {
            registry.create("zmsg", new ChannelRegistry.Context(agent, bus, configDir));
            fail("缺 base-url 必须在装配期就被点名");
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("base-url"), e.missingKeys());
        }
    }

    // ===== helpers =====

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plainMap(String json) {
        Object node = JsonUtil.toPlainValue(JsonUtil.parse(json));
        return node instanceof Map ? (Map<String, Object>) node : null;
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
                        return new ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                                        "echo", Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new TokenUsage(1L, 1L, 2L), "stop", null);
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

    /** 假 z-msg：只回 z-msg 的直发端点形状（{@code data.msgId}）。 */
    private static final class FakeZMsg implements AutoCloseable {

        static final class Request {
            final String method;
            final String path;
            final Map<String, String> headers;
            final String body;

            Request(String method, String path, Map<String, String> headers, String body) {
                this.method = method;
                this.path = path;
                this.headers = headers;
                this.body = body;
            }

            String header(String lowerName) {
                return headers.get(lowerName);
            }
        }

        private final HttpServer server;
        private final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
        private volatile int status = 200;
        private volatile String msgId = "msgid-fake-0001";
        private volatile String body;

        FakeZMsg() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String base() {
            return "http://127.0.0.1:" + port();
        }

        List<Request> requests() {
            synchronized (requests) {
                return new ArrayList<Request>(requests);
            }
        }

        void setStatus(int s) {
            this.status = s;
        }

        void setMsgId(String id) {
            this.msgId = id;
        }

        void setBody(String b) {
            this.body = b;
        }

        private void handle(HttpExchange ex) throws IOException {
            Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
            for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
                headers.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
                        e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
            String in = readAll(ex.getRequestBody());
            requests.add(new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, in));
            String out = body != null ? body
                    : ("{\"data\":{\"msgId\":\"" + msgId + "\",\"channel\":\"EMAIL\","
                    + "\"provider\":\"harness\",\"bizType\":\"DIRECT_SEND\"},"
                    + "\"success\":true,\"code\":200,\"message\":null}");
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
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
    }
}
