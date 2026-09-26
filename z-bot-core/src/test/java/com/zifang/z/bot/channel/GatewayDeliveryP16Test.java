package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P16 接线端到端（进程内）：{@link Gateway} + {@link ChannelBus} 真跑一轮对话，
 * 三件套（{@link TurnLease} / {@link DeliveryLedger} / {@link DeadTargets}）与
 * {@link Supervisor} 的自愈面必须在同一条链路上被观测到。
 *
 * <p>时序类断言都在<b>副作用发生的那一刻</b>取样（{@link ProbedChannel#probe} 在
 * {@code channel.send()} 里面读台账），而不是事后看总数 —— 事后看总数区分不了
 * 「先落账再发」与「先发再落账」。</p>
 *
 * <p>反向类断言（「不该再发」「不该判死」）都在同一用例里先钉一次「它真的会发」，
 * 免得短路/判死逻辑被删掉后测试仍然空跑通过。</p>
 *
 * <p>红线 1：所有数据目录都来自 {@code BotConfig.load(tempProfileDir)}，
 * 由 {@link #artifactsStayInsideProfile()} 逐条核对落点。</p>
 */
public class GatewayDeliveryP16Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** send() 里面可执行的探针。 */
    private interface SendProbe {
        void during(OutboundMessage m) throws Exception;
    }

    private static final class ProbedChannel implements Channel {
        final String name;
        volatile boolean started;
        final AtomicInteger sendCalls = new AtomicInteger();
        final List<OutboundMessage> sent = Collections.synchronizedList(new ArrayList<OutboundMessage>());
        volatile SendProbe probe;
        volatile RuntimeException throwOnSend;

        ProbedChannel(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public void start() { started = true; }
        @Override public void stop() { started = false; }
        @Override public void awaitTermination() {}
        @Override public boolean isRunning() { return started; }

        @Override public void send(OutboundMessage message) throws Exception {
            sendCalls.incrementAndGet();
            SendProbe p = probe;
            if (p != null) {
                p.during(message);
            }
            RuntimeException boom = throwOnSend;
            if (boom != null) {
                throw boom;
            }
            sent.add(message);
        }
    }

    /** send 里抛一个带平台原文的异常（走真分类器，不额外造包装类）。 */
    private static final class PlatformError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PlatformError(String platformError) {
            super(platformError);
        }
    }

    private File configDir;
    private File sessionDir;
    private File sandboxDir;
    private BotAgent prototype;
    private Gateway gw;
    private ProbedChannel chan;
    private final AtomicInteger turnMillis = new AtomicInteger(0);

    /** stub LLM：回显最后一条用户消息；{@link #turnMillis} 控制这一轮占住租约多久。 */
    private BotAgent newPrototype() {
        LlmProvider stub = new LlmProvider() {
            @Override public String name() { return "stub"; }
            @Override public List<Model> listModels() { return Collections.emptyList(); }
            @Override public boolean supportsModel(String m) { return true; }
            @Override public ChatCompletionsResponse chat(ChatCompletionsRequest r) {
                int wait = turnMillis.get();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                String last = r.getMessages().get(r.getMessages().size() - 1).getContent();
                return new ChatCompletionsResponse("id", "stub",
                        Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                                "echo: " + last, Collections.<ToolCall>emptyList(), "stop")),
                        new TokenUsage(5L, 3L, 8L), "stop", null);
            }
            @Override public void streamChat(ChatCompletionsRequest r,
                                             java.util.function.Consumer<ChatCompletionsResponse> on,
                                             java.util.function.Consumer<Throwable> err) {
                on.accept(chat(r));
            }
        };
        return BotAgent.builder(BotConfig.load(configDir))
                .provider(stub)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("stub")
                .withoutCenter()
                .build();
    }

    @Before
    public void setUp() throws Exception {
        configDir = tmp.newFolder("profile");
        sessionDir = tmp.newFolder("sessions");
        sandboxDir = tmp.newFolder("sandbox");
        prototype = newPrototype();
        gw = new Gateway(prototype);
        chan = new ProbedChannel("chanA");
        gw.register(chan);
        gw.start();
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.stop();
        }
        if (prototype != null) {
            prototype.shutdown();
        }
    }

    // ===== 1. 红线 8：先落账，再产生副作用 =====

    @Test
    public void obligationIsOnTheLedgerBeforeTheSendSideEffectStarts() throws Exception {
        final List<Map<String, Integer>> atSend = new ArrayList<Map<String, Integer>>();
        chan.probe = new SendProbe() {
            @Override public void during(OutboundMessage m) {
                atSend.add(gw.bus().deliveryLedger().countsByState());
            }
        };
        gw.bus().deliver(new ChannelMessage("chanA", "c-1", "alice", "hello"));
        waitFor(() -> !atSend.isEmpty(), 5000);
        assertEquals(1, atSend.size());
        Map<String, Integer> counts = atSend.get(0);
        assertEquals("send 里面必须已经看得到这条义务（红线 8）", 1, sum(counts));
        assertEquals("而且状态必须是 attempting（markAttempting 紧贴 send）",
                Integer.valueOf(1), counts.get(DeliveryLedger.ATTEMPTING));
        assertEquals(Integer.valueOf(0), counts.get(DeliveryLedger.PENDING));
        assertEquals("发出去之前绝不能已经记成 delivered",
                Integer.valueOf(0), counts.get(DeliveryLedger.DELIVERED));
        waitFor(() -> Integer.valueOf(1).equals(
                gw.bus().deliveryLedger().countsByState().get(DeliveryLedger.DELIVERED)), 5000);
    }

    @Test
    public void deliveredObligationConvergesAndASecondSweepDoesNotResend() throws Exception {
        gw.bus().deliver(new ChannelMessage("chanA", "c-1", "alice", "hello"));
        waitFor(() -> chan.sent.size() >= 1, 5000);
        assertEquals(1, chan.sent.size());
        waitFor(() -> Integer.valueOf(1).equals(
                gw.bus().deliveryLedger().countsByState().get(DeliveryLedger.DELIVERED)), 5000);
        assertEquals("结清的行不该被再认领", 0, gw.recoverPendingDeliveries());
        assertEquals(1, chan.sent.size());
        Map<String, Integer> counts = gw.bus().deliveryLedger().countsByState();
        assertEquals(Integer.valueOf(0), counts.get(DeliveryLedger.PENDING));
        assertEquals(Integer.valueOf(0), counts.get(DeliveryLedger.ATTEMPTING));
        assertEquals(Integer.valueOf(1), counts.get(DeliveryLedger.DELIVERED));
    }

    // ===== 2. 断点重投：歧义窗口带标记，没开始的干净重投 =====

    @Test
    public void attemptingRowOwnedByADeadProcessIsRedeliveredWithDuplicateWarning() throws Exception {
        String oid = seedObligation("chanA", "c-9", "chat-crash", "半条没发完的回复",
                DeliveryLedger.ATTEMPTING, deadChildPid());
        assertEquals(DeliveryLedger.ATTEMPTING, row(oid).get("state"));

        assertEquals(1, gw.recoverPendingDeliveries());
        assertEquals(1, chan.sent.size());
        assertEquals(DeliveryLedger.RECOVERED_MARKER + "半条没发完的回复", chan.sent.get(0).text);
        assertEquals("c-9", chan.sent.get(0).replyTo);
        assertEquals(DeliveryLedger.DELIVERED, row(oid).get("state"));
    }

    @Test
    public void pendingRowOwnedByADeadProcessIsRedeliveredWithoutWarning() throws Exception {
        String oid = seedObligation("chanA", "c-8", "chat-pending", "压根没开始发的回复",
                DeliveryLedger.PENDING, deadChildPid());
        assertEquals(1, gw.recoverPendingDeliveries());
        assertEquals(1, chan.sent.size());
        assertEquals("pending = 发送根本没开始 ⇒ 不该吓用户说是重复",
                "压根没开始发的回复", chan.sent.get(0).text);
        assertFalse(chan.sent.get(0).text.contains(DeliveryLedger.RECOVERED_MARKER));
        assertEquals(DeliveryLedger.DELIVERED, row(oid).get("state"));
    }

    @Test
    public void markerIsNotStackedWhenARowIsClaimedTwice() throws Exception {
        long deadPid = deadChildPid();
        String oid = seedObligation("chanA", "c-7", "chat-twice", "又要重投",
                DeliveryLedger.FAILED, deadPid);
        chan.throwOnSend = new PlatformError("平台 500 抖动");
        // 返回值是「真发出去的条数」，认领成功但发送失败 ⇒ 0
        assertEquals(0, gw.recoverPendingDeliveries());
        assertEquals("失败的那次确实撞过 send", 1, chan.sendCalls.get());
        assertEquals(0, chan.sent.size());
        assertEquals(DeliveryLedger.FAILED, row(oid).get("state"));
        assertTrue("重投失败也要留下原因: " + row(oid).get("last_error"),
                String.valueOf(row(oid).get("last_error")).contains("recovered-redelivery"));

        chan.throwOnSend = null;
        reown(oid, deadPid);
        assertEquals(1, gw.recoverPendingDeliveries());
        String sent = chan.sent.get(0).text;
        int first = sent.indexOf(DeliveryLedger.RECOVERED_MARKER);
        assertTrue("第二次重投仍属歧义窗口 ⇒ 必须带标记", first >= 0);
        assertEquals("标记不许叠两层", -1,
                sent.indexOf(DeliveryLedger.RECOVERED_MARKER, first + 1));
        assertEquals(DeliveryLedger.RECOVERED_MARKER + "又要重投", sent);
    }

    // ===== 3. 死目标：短路 + 自愈 =====

    @Test
    public void chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends() throws Exception {
        chan.throwOnSend = new PlatformError("Bad Request: chat not found");
        gw.bus().deliver(new ChannelMessage("chanA", "c-dead", "alice", "第一条"));
        // 注意：history 是在 dispatch <b>之前</b>记的，所以只能等 send/台账本身
        waitFor(() -> chan.sendCalls.get() >= 1, 5000);
        waitForState(DeliveryLedger.FAILED, 1);
        assertEquals("第一次是真去发的（否则下面的「没再发」就是空断言）", 1, chan.sendCalls.get());
        assertTrue("会话级 not found ⇒ 整个目标判死",
                gw.bus().deadTargets().isDead("chanA", "c-dead"));

        // 把 send 治好：判死生效的话第二条根本走不到 send
        chan.throwOnSend = null;
        gw.bus().deliver(new ChannelMessage("chanA", "c-dead", "alice", "第二条"));
        waitForState(DeliveryLedger.FAILED, 2);
        assertEquals("判死之后不许继续撞墙", 1, chan.sendCalls.get());
        assertEquals(0, chan.sent.size());
        Map<String, Integer> counts = gw.bus().deliveryLedger().countsByState();
        assertEquals("两条义务都留在台账里（failed），不能静默丢弃",
                Integer.valueOf(2), counts.get(DeliveryLedger.FAILED));
        assertTrue("短路也要在台账里写明原因: " + latestFailedError(),
                String.valueOf(latestFailedError()).contains("dead-target"));
    }

    @Test
    public void subChatLevelNotFoundNeverRegistersTheWholeChat() throws Exception {
        chan.throwOnSend = new PlatformError("Bad Request: message to edit not found");
        gw.bus().deliver(new ChannelMessage("chanA", "c-thread", "alice", "改消息"));
        waitFor(() -> chan.sendCalls.get() >= 1, 5000);
        waitForState(DeliveryLedger.FAILED, 1);
        assertEquals(1, chan.sendCalls.get());
        assertFalse("话题级失败不代表整会话死了",
                gw.bus().deadTargets().isDead("chanA", "c-thread"));
        chan.throwOnSend = null;
        gw.bus().deliver(new ChannelMessage("chanA", "c-thread", "alice", "下一条照常发"));
        waitFor(() -> chan.sent.size() >= 1, 5000);
        assertEquals("没判死 ⇒ 第二条必须真发得出去", 2, chan.sendCalls.get());
    }

    @Test
    public void bootRedeliveryIsTheSelfHealPathForADeadTarget() throws Exception {
        assertTrue(gw.bus().deadTargets().markDead("chanA", "c-heal", "上一轮判的死刑"));
        assertTrue(gw.bus().deadTargets().isDead("chanA", "c-heal"));
        String oid = seedObligation("chanA", "c-heal", "chat-heal", "死目标也能被重投救回来",
                DeliveryLedger.PENDING, deadChildPid());
        assertEquals(1, gw.recoverPendingDeliveries());
        assertEquals(1, chan.sent.size());
        assertFalse("发出去过一次 ⇒ 标记自愈清除", gw.bus().deadTargets().isDead("chanA", "c-heal"));
        assertEquals(DeliveryLedger.DELIVERED, row(oid).get("state"));
    }

    @Test
    public void unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered() throws Exception {
        // 来源通道不在册：义务仍要落账，但绝不能谎报成 delivered
        gw.bus().deliver(new ChannelMessage("ghost", "c-ghost", "alice", "没人收的回复"));
        waitForState(DeliveryLedger.FAILED, 1);
        Map<String, Integer> counts = gw.bus().deliveryLedger().countsByState();
        assertEquals(Integer.valueOf(1), counts.get(DeliveryLedger.FAILED));
        assertEquals("谎报结清 = 台账与真实世界分叉",
                Integer.valueOf(0), counts.get(DeliveryLedger.DELIVERED));
        assertTrue("原因要写明是 no-channel: " + latestFailedError(),
                String.valueOf(latestFailedError()).contains("no-channel"));
        assertEquals(0, chan.sent.size());
    }

    // ===== 4. 熔断器：反复被弄死的网关不能进入紧密重启动循环 =====

    @Test
    public void thirdInterruptedBootWithinTheWindowSkipsAutoContinuation() throws Exception {
        String oid = seedObligation("chanA", "c-brk", "chat-brk", "被熔断搁置的义务",
                DeliveryLedger.PENDING, deadChildPid());
        // 前两次按「上一次崩了」记账（真时间戳，窗口才认）
        assertFalse(gw.supervisor().checkAndRecordInterruptedBoot());
        assertFalse(gw.supervisor().checkAndRecordInterruptedBoot());
        assertEquals(2, gw.supervisor().recentInterruptedBoots(
                Supervisor.DEFAULT_WINDOW_SECONDS, null).size());
        assertEquals("第三次带活启动 ⇒ 跳过自动续跑", -1, gw.recoverPendingDeliveries());
        assertEquals("被熔断 ⇒ 一条都不发", 0, chan.sent.size());
        assertEquals("义务必须原样留在台账里等下次", DeliveryLedger.PENDING, row(oid).get("state"));
    }

    @Test
    public void cleanBootWithNothingToResumeChargesNothing() throws Exception {
        assertEquals("setUp 那次启动无待续跑 ⇒ 不该向熔断器记账",
                0, gw.recoverPendingDeliveries());
        assertTrue(gw.supervisor().recentInterruptedBoots(
                Supervisor.DEFAULT_WINDOW_SECONDS, null).isEmpty());
    }

    // ===== 5. turn lease：路由键多、session_id 一 =====

    @Test
    public void graftedChatsShareOneSessionAndSerializeWithoutWedging() throws Exception {
        turnMillis.set(60);
        ProbedChannel chanB = new ProbedChannel("chanB");
        gw.register(chanB);
        assertTrue(gw.bus().graftConversation("chat-B", "chat-A"));
        String sidA = gw.bus().sessionIdOf("chat-A");
        assertNotNull(sidA);
        assertEquals("两个路由键必须解析到同一个 session_id（这才是多对一）",
                sidA, gw.bus().sessionIdOf("chat-B"));

        for (int i = 0; i < 4; i++) {
            gw.bus().deliver(new ChannelMessage("chanA", "chat-A", "alice", "A" + i));
            gw.bus().deliver(new ChannelMessage("chanB", "chat-B", "bob", "B" + i));
        }
        waitFor(() -> chan.sent.size() + chanB.sent.size() >= 8, 20000);
        assertEquals(4, chan.sent.size());
        assertEquals(4, chanB.sent.size());
        for (OutboundMessage m : chan.sent) {
            assertFalse("串行化失败的症状就是 Error: BotAgent 正在运行中 —— 实测 " + m.text,
                    m.text.startsWith("Error:"));
            assertTrue("回显对不上说明两条会话串味了: " + m.text, m.text.startsWith("echo: A"));
        }
        for (OutboundMessage m : chanB.sent) {
            assertTrue("回显对不上说明两条会话串味了: " + m.text, m.text.startsWith("echo: B"));
        }
        assertEquals("一个 session_id 只该有一把租约", 1, gw.bus().turnLeases().size());
        assertEquals("正常网关不该出现 fail-open 放行", 0L, gw.bus().leaseTimeoutCount());

        List<String> roles = transcriptRoles();
        System.out.println("[p16-wiring] 共享 transcript 角色序列 = " + roles);
        System.out.println("[p16-wiring] session_id=" + sidA
                + " chat-A 回复=" + describe(chan.sent) + " | chat-B 回复=" + describe(chanB.sent));
        assertEquals(8, countRole(roles, "user"));
        assertEquals(8, countRole(roles, "assistant"));
        assertAlternating(roles);
    }

    /**
     * 反向对照：同一个 agent 不加租约裸并发必须真能楔住。
     * 没有这条，上面那条「没有 Error」有可能只是没把猎物引进来。
     */
    @Test
    public void rawConcurrentChatOnOneAgentReallyDoesWedge() throws Exception {
        turnMillis.set(250);
        final BotAgent shared = gw.bus().agentForRoute("chat-A");
        assertNotNull(shared);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger refused = new AtomicInteger();
        final List<String> replies = Collections.synchronizedList(new ArrayList<String>());
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try {
                        go.await();
                        replies.add(shared.chat("裸并发 " + idx, null));
                    } catch (Exception e) {
                        if (String.valueOf(e.getMessage()).contains("正在运行中")) {
                            refused.incrementAndGet();
                        }
                        replies.add("Error: " + e.getMessage());
                    }
                }
            }, "p16-raw-" + i);
            ts.add(t);
            t.start();
        }
        ready.await();
        go.countDown();
        for (Thread t : ts) {
            t.join(15000);
        }
        System.out.println("[p16-wiring] 裸并发对照组 replies=" + replies + " refused=" + refused.get());
        assertTrue("并发裸调同一个 agent 必须真能撞出「BotAgent 正在运行中」，"
                + "否则本类的串行化断言是空断言（实测 replies=" + replies + "）",
                refused.get() > 0);
    }

    @Test
    public void ungraftedChatsKeepTheirOwnSessions() throws Exception {
        gw.bus().deliver(new ChannelMessage("chanA", "chat-X", "alice", "x"));
        gw.bus().deliver(new ChannelMessage("chanA", "chat-Y", "bob", "y"));
        waitFor(() -> gw.bus().history().size() >= 2, 5000);
        assertNotNull(gw.bus().sessionIdOf("chat-X"));
        assertNotNull(gw.bus().sessionIdOf("chat-Y"));
        assertFalse("没并入就该各走各的（防止把所有会话挤成一条）",
                gw.bus().sessionIdOf("chat-X").equals(gw.bus().sessionIdOf("chat-Y")));
        assertSame(gw.bus().agentForRoute("chat-X"), gw.bus().agentForRoute("chat-X"));
        assertEquals(2, gw.bus().turnLeases().size());
    }

    // ===== 6. 实例锁 + 观测面 + 红线 1 =====

    @Test
    public void liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote() throws Exception {
        assertNotNull(gw.instanceLock());
        assertTrue(gw.instanceLock().isPersisted());
        File lock = gw.supervisor().instanceLockFile();
        assertTrue(lock.isFile());
        String body = new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"pid\":" + DeliveryLedger.ownPid()));

        // 换一个真活着的别的进程进锁 ⇒ 第二个实例必须拒绝双开
        File pidFile = new File(tmp.getRoot(), "live.pid");
        Process other = new ProcessBuilder("/bin/sh", "-c",
                "echo $$ > '" + pidFile.getAbsolutePath() + "'; sleep 6").start();
        try {
            long deadline = System.currentTimeMillis() + 5000L;
            while ((!pidFile.isFile() || pidFile.length() == 0L) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            long otherPid = Long.parseLong(new String(Files.readAllBytes(pidFile.toPath()),
                    StandardCharsets.UTF_8).trim());
            Long otherStart = DeliveryLedger.OsProcessLiveness.startStampOf(otherPid);
            assertTrue("对照组：这个 pid 必须被判活，否则下面那条拒绝是空断言",
                    new DeliveryLedger.OsProcessLiveness().alive(otherPid, otherStart));
            Files.write(lock.toPath(), ("{\"pid\":" + otherPid + ",\"started_at\":" + otherStart + "}")
                    .getBytes(StandardCharsets.UTF_8));
            assertNull("锁被活着的实例持有 ⇒ 不能双开",
                    new Supervisor(configDir).tryAcquireInstanceLock());
            gw.stop();
            assertTrue("锁已经不是本实例的 ⇒ stop 不许误删别人的锁", lock.isFile());
            assertTrue(new String(Files.readAllBytes(lock.toPath()), StandardCharsets.UTF_8)
                    .contains("\"pid\":" + otherPid));
            gw = null;
        } finally {
            other.destroy();
        }
    }

    @Test
    public void cleanStopRemovesOurOwnInstanceLock() throws Exception {
        File lock = gw.supervisor().instanceLockFile();
        assertTrue(lock.isFile());
        gw.stop();
        assertFalse("干净收尾要把锁摘掉，否则下次启动白白撞一次自愈", lock.isFile());
        gw = null;
    }

    @Test
    public void statusExposesTheSelfHealingReadings() throws Exception {
        gw.bus().deliver(new ChannelMessage("chanA", "c-1", "alice", "hello"));
        waitFor(() -> chan.sent.size() >= 1, 5000);
        Map<String, Object> st = gw.status();
        assertNotNull("status 必须报台账各态计数", st.get("deliveryObligations"));
        assertNotNull(st.get("deliveryRows"));
        assertNotNull(st.get("deadTargets"));
        assertNotNull(st.get("turnLeases"));
        assertNotNull(st.get("leaseFailOpen"));
        assertNotNull(st.get("instanceLock"));
        assertTrue(st.get("instanceLock").toString().contains("pid="));
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) st.get("deliveryObligations");
        assertEquals(Integer.valueOf(1), counts.get(DeliveryLedger.DELIVERED));
        assertEquals(Integer.valueOf(1), st.get("deliveryRows"));
        assertEquals(Integer.valueOf(0), st.get("deadTargets"));
        assertEquals(Long.valueOf(0L), st.get("leaseFailOpen"));
    }

    @Test
    public void artifactsStayInsideProfile() throws Exception {
        gw.bus().deadTargets().markDead("chanA", "c-x", "占位");
        gw.bus().deliver(new ChannelMessage("chanA", "c-1", "alice", "hello"));
        waitFor(() -> chan.sent.size() >= 1, 5000);
        String root = configDir.getCanonicalPath();
        assertTrue(gw.stateDbFile().getCanonicalPath() + " 必须在 profile 里",
                gw.stateDbFile().getCanonicalPath().startsWith(root));
        assertTrue(gw.bus().deadTargets().path().getCanonicalPath().startsWith(root));
        assertTrue(gw.supervisor().instanceLockFile().getCanonicalPath().startsWith(root));
        assertTrue(gw.supervisor().restartLoopStateFile().getCanonicalPath().startsWith(root));
        assertEquals("gateway/dead_targets.json", relativeTo(root, gw.bus().deadTargets().path()));
        assertEquals("gateway/gateway.lock", relativeTo(root, gw.supervisor().instanceLockFile()));
        assertEquals("gateway/restart_loop.json",
                relativeTo(root, gw.supervisor().restartLoopStateFile()));
        assertEquals("state.db", relativeTo(root, gw.stateDbFile()));
        assertFalse("台账不得指回真实 ~/.zbot",
                new File(System.getProperty("user.home"), ".zbot/state.db")
                        .getCanonicalFile().equals(gw.stateDbFile().getCanonicalFile()));
    }

    // ===== helpers =====

    private static String relativeTo(String root, File f) throws IOException {
        String p = f.getCanonicalPath();
        return p.startsWith(root) ? p.substring(root.length() + 1).replace('\\', '/') : "<outside>";
    }

    private static String describe(List<OutboundMessage> ms) {
        StringBuilder sb = new StringBuilder();
        for (OutboundMessage m : ms) {
            sb.append(m.replyTo).append('=').append(m.text).append(' ');
        }
        return sb.toString().trim();
    }

    private static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (Integer v : counts.values()) {
            total += v == null ? 0 : v.intValue();
        }
        return total;
    }

    private List<String> transcriptRoles() throws IOException {
        // forkFor 把会话目录建在 prototype 的 sessionDir 的<b>同级</b>（getSessionDir().getParentFile()）
        File convDir = new File(sessionDir.getParentFile(), "chat-A");
        File[] files = convDir.listFiles();
        assertNotNull("共享 transcript 目录不存在: " + convDir, files);
        List<String> roles = new ArrayList<String>();
        Pattern p = Pattern.compile("\"role\"\\s*:\\s*\"(user|assistant)\"");
        for (File f : files) {
            if (f == null || !f.getName().endsWith(".json") || f.getName().startsWith("_")) {
                continue;
            }
            Matcher m = p.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            while (m.find()) {
                roles.add(m.group(1));
            }
        }
        return roles;
    }

    private static int countRole(List<String> roles, String want) {
        int n = 0;
        for (String r : roles) {
            if (want.equals(r)) {
                n++;
            }
        }
        return n;
    }

    private static void assertAlternating(List<String> roles) {
        List<String> ua = new ArrayList<String>();
        for (String r : roles) {
            if ("user".equals(r) || "assistant".equals(r)) {
                ua.add(r);
            }
        }
        for (int i = 1; i < ua.size(); i++) {
            assertFalse("第 " + i + " 处出现同类相邻（user;user 楔死的签名）: " + ua,
                    ua.get(i).equals(ua.get(i - 1)));
        }
    }

    /** 起一个真进程、等它退出 ⇒ 拿到一个 OS 确认真的死掉的 pid（不用反射）。 */
    private long deadChildPid() throws Exception {
        final File pidFile = new File(tmp.getRoot(), "dead-" + System.nanoTime() + ".pid");
        Process p = new ProcessBuilder("/bin/sh", "-c",
                "echo $$ > '" + pidFile.getAbsolutePath() + "'; exit 0").start();
        assertEquals(0, p.waitFor());
        long deadline = System.currentTimeMillis() + 5000L;
        while ((!pidFile.isFile() || pidFile.length() == 0L) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile.toPath()),
                StandardCharsets.UTF_8).trim());
        assertFalse("子进程没退干净，pid " + pid + " 仍被判活",
                new DeliveryLedger.OsProcessLiveness().alive(pid, null));
        return pid;
    }

    /** 用<b>生产代码</b>写一行义务，只把 owner 改成死进程（模拟「上一个网关崩在这儿」）。 */
    private String seedObligation(String platform, String chatId, String sessionKey, String content,
                                  String initialState, long ownerPid) throws Exception {
        DeliveryLedger led = gw.bus().deliveryLedger();
        String oid = DeliveryLedger.computeObligationId(sessionKey, platform + ":" + chatId + ":seed", content);
        led.recordObligation(oid, sessionKey, platform, chatId, content);
        if (DeliveryLedger.ATTEMPTING.equals(initialState)) {
            assertTrue(led.markAttempting(oid));
        } else if (DeliveryLedger.FAILED.equals(initialState)) {
            assertTrue(led.markFailed(oid, "上一轮的错"));
        } else if (!DeliveryLedger.PENDING.equals(initialState)) {
            throw new IllegalArgumentException(initialState);
        }
        reown(oid, ownerPid);
        return oid;
    }

    private void reown(String oid, long ownerPid) throws Exception {
        Connection c = open();
        try {
            PreparedStatement ps = c.prepareStatement("UPDATE " + DeliveryLedger.TABLE
                    + " SET owner_pid=?, owner_started_at=? WHERE obligation_id=?");
            try {
                ps.setLong(1, ownerPid);
                ps.setLong(2, 1L);
                ps.setString(3, oid);
                assertEquals(1, ps.executeUpdate());
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    private Map<String, Object> row(String oid) throws Exception {
        Connection c = open();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT state, attempts, last_error, owner_pid FROM "
                    + DeliveryLedger.TABLE + " WHERE obligation_id=?");
            try {
                ps.setString(1, oid);
                ResultSet rs = ps.executeQuery();
                assertTrue("行不见了: " + oid, rs.next());
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("state", rs.getString(1));
                out.put("attempts", Integer.valueOf(rs.getInt(2)));
                out.put("last_error", rs.getString(3));
                out.put("owner_pid", Long.valueOf(rs.getLong(4)));
                return out;
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    private String latestFailedError() throws Exception {
        Connection c = open();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT last_error FROM " + DeliveryLedger.TABLE
                    + " WHERE state=? ORDER BY updated_at DESC, rowid DESC LIMIT 1");
            try {
                ps.setString(1, DeliveryLedger.FAILED);
                ResultSet rs = ps.executeQuery();
                return rs.next() ? rs.getString(1) : null;
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + gw.stateDbFile().getAbsolutePath());
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /** 等台账里某个状态攒到 n 条（bus 是异步的，且 history 先于 dispatch 落）。 */
    private void waitForState(final String state, final int n) throws InterruptedException {
        waitFor(() -> {
            Integer v = gw.bus().deliveryLedger().countsByState().get(state);
            return v != null && v.intValue() >= n;
        }, 5000);
    }
}
