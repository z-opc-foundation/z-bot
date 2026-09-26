package com.zifang.z.bot.delegate;

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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P27b 收口棒 —— <b>证伪</b>委托面，不是给它补绿灯。
 *
 * <p>三问（工单 §3.2），每问一条具名测试，每条负向断言都在<b>同一次运行</b>里配一个阳性对照：</p>
 * <ol>
 *   <li>{@link #q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth()}
 *       —— 上限 8 到底挡什么：挡的是<b>单条委托的投递尝试数</b>，既不是队列长度也不是在飞子代理数；
 *       并且它在<b>生产投递入口</b>（{@code /background result} 的 claim+当场 ack）上永远走不到 8。</li>
 *   <li>{@link #q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry()}
 *       —— 两轴状态机：投递轴对生命周期轴<b>零约束</b>，未收工的现场也能被记成 DELIVERED；
 *       唯一的"没跑完不许投"是 {@code DelegateManager.asyncResult} 里的<b>内存</b> status，
 *       不在迁移表里。</li>
 *   <li>{@link #q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt()}
 *       {@link #q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt()}
 *       {@link #q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath()}
 *       —— 进程中途被杀：台账<b>会</b>留幻影在飞条目，{@code load()/list()} 一层对账都没有；
 *       判孤儿的 {@code adoptOrphans} 存在但必须有人手动调，而 {@code src/main} 里没有调用者
 *       （grep 原样读数见 EVIDENCE §9）。</li>
 * </ol>
 *
 * <p>本文件<b>不</b>断言"这些洞该怎么修"，只把洞钉在测试面上：实现哪天补了门，这些用例会以
 * "预期外的红"出现，由下一棒改判 —— 台账 {@code unexpected} 那一列就是交接面。</p>
 */
public class P27FalsificationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;
    private File sessionDir;
    private File liveRoot;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        // config==null 时 BotAgent 用 <sandbox 的父目录>/delegate-children 当子会话根
        // （BotAgent.java:1852-1853），DelegateManager 再取它的父目录 + /live 当台账根
        // （DelegateManager.ledgerRootFor）⇒ 本测试的台账根就是这个位置。
        liveRoot = new File(sandboxDir.getParentFile(), "live");
    }

    // ================= Q1：上限 8 挡什么 =================

    @Test
    public void q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth() {
        DelegationLedger ledger = new DelegationLedger(liveRoot);
        DelegationDelivery dd = new DelegationDelivery(ledger);
        done(ledger, "q1-a");
        done(ledger, "q1-b");

        // 阳性对照：上限真的会走动 —— 同一条烧满 8 次之后第 9 次领不到，收敛成 DROPPED(8/8)
        for (int i = 1; i <= 8; i++) {
            String token = dd.claim("q1-a", "pull-console");
            assertNotNull("第 " + i + " 次 claim 本该领得到: " + dd.describe("q1-a"), token);
            assertTrue("第 " + i + " 次 release 本该动账", dd.release("q1-a", token));
        }
        assertEquals(8, dd.attempts("q1-a"));
        assertEquals(DeliveryState.DROPPED, dd.stateOf("q1-a"));
        assertNull("烧完之后 claim 必须直接领不到", dd.claim("q1-a", "pull-console"));

        // 证伪"8 是队列长度/全局在飞数"这一读法：另一条现场一格都没被烧 ⇒ 这本账是<b>每条一个</b>
        assertEquals("上限是 per-delegation 计数，不是全局队列长度: " + dd.describe("q1-b"),
                0, dd.attempts("q1-b"));
        assertEquals(DeliveryState.PENDING, dd.stateOf("q1-b"));

        // 生产投递入口（/background result）永远走不到 8：它 claim 完就当场 ack
        BotAgent agent = agent(new Fixed(textReply("q1-reply-body")));
        String id = idOf(agent.submitBackground("q1 生产路径的投递尝试数"));
        assertTrue("盘上现场没进终态（有界 8s）", until(new File(liveRoot, id + "/state.json"), 8000L)
                && untilTerminal(ledger, id, 8000L));
        for (int i = 0; i < 6; i++) {
            String pull = agent.backgroundResult(id);
            assertTrue("第 " + i + " 次拉取该拿到回复正文: " + pull, pull.contains("q1-reply-body"));
        }
        assertEquals("生产路径每次拉取都自带 ack ⇒ 尝试数最多 1，8 这根门在生产里无人可撞",
                1, dd.attempts(id));
        assertEquals(DeliveryState.DELIVERED, dd.stateOf(id));
        assertTrue("汇总行的分母才是那根上限: " + dd.describe(id), dd.describe(id).endsWith("/8)"));

        // 记账 P27b-D2：现场不存在时判词把分母写死成 1，和同一本的 8 打架（操作员会读成"只许领一次"）
        assertEquals("MISSING(-/1)", dd.describe("q1-no-such-scene"));
        // 记账 P27b-D3：DelegationLedger.load 的注释写的是"盘上没有 / 解析失败 ⇒ null"，
        //   但 id 形状不合 checkedId 时它<b>抛</b> IllegalArgumentException（用户输入的 id 直达这里）。
        assertNull("形状合规但不存在的 id ⇒ 正常返 null（阳性对照）", ledger.load("q1-no-such-scene"));
        String thrown = "none";
        try {
            ledger.load("q1-没有这条现场");
        } catch (IllegalArgumentException expected) {
            thrown = expected.getClass().getSimpleName();
        }
        assertEquals("读侧对畸形 id 的契约是抛而不是 null", "IllegalArgumentException", thrown);
    }

    // ================= Q2：两轴的交叉组合 =================

    @Test
    public void q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry() {
        DelegationLedger ledger = new DelegationLedger(liveRoot);
        DelegationDelivery dd = new DelegationDelivery(ledger);

        // 证伪：投递轴只看自己的 terminal()（DelegationDelivery.claim:71），对生命周期轴零查询
        //       ⇒ (QUEUED, CLAIMED) / (QUEUED, DELIVERED) 这两格在实现里真能落地
        ledger.create("q2-unfinished", "子代理还在跑，回复正文还不存在", 0, "", null);
        assertEquals(DelegateState.QUEUED, ledger.load("q2-unfinished").state);
        String token = dd.claim("q2-unfinished", "cli");
        assertNotNull("实现允许对一条没收工的委托领投递凭证", token);
        assertTrue(dd.complete("q2-unfinished", token));
        DelegationLedger.Entry disk = ledger.load("q2-unfinished");
        assertEquals("生命周期轴还停在 QUEUED", DelegateState.QUEUED, disk.state);
        assertEquals("投递轴却已经写成交付终态", DeliveryState.DELIVERED, disk.delivery);
        assertEquals("恢复面只认终态现场 ⇒ 这条被记成交付的现场永远不会被提议重放",
                0, dd.undeliveredTerminalResults().size());

        // 阳性对照 A：表里唯一被承认的"跑完但没人接"组合 (DONE, PENDING) 确实进得了恢复面
        done(ledger, "q2-done");
        List<String> offered = new ArrayList<String>();
        for (DelegationLedger.Entry e : dd.undeliveredTerminalResults()) {
            offered.add(e.id);
        }
        assertEquals("只有 (终态, PENDING) 才该被提议重放: " + offered,
                Collections.singletonList("q2-done"), offered);

        // 阳性对照 B：生产入口确实不许"没跑完就投"，但那道门是 DelegateManager.asyncResult 里的
        //       <b>内存</b> status（:419），不在迁移表里 —— 它随进程一起消失（见 q3b）。
        //       子代理被闸门挡住（闸门自带 10s 硬上限，绝不无界），此刻 d.status 还是 QUEUED/RUNNING。
        Gate gate = new Gate();
        BotAgent agent = agent(new Fixed(gatedReply(), gate));
        String id = idOf(agent.submitBackground("q2 还没收工就想去拉结果"));
        assertTrue(until(new File(liveRoot, id + "/state.json"), 8000L));
        String early;
        try {
            early = agent.backgroundResult(id);
            assertTrue("生产入口的判词: " + early, early.contains("还在"));
            assertEquals("看进度不该烧投递配额（门在内存那一层）", 0, dd.attempts(id));
            assertEquals(DeliveryState.PENDING, dd.stateOf(id));
        } finally {
            gate.open();
        }
        assertTrue("闸门放开后子代理该收工（有界 10s）", untilTerminal(new DelegationLedger(liveRoot), id, 12000L));
        assertTrue(agent.backgroundResult(id).contains("q2-child-reply"));
    }

    // ================= Q3：进程中途被杀的台账语义 =================

    @Test
    public void q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt() {
        DelegationLedger writer = new DelegationLedger(liveRoot);
        DelegationLedger.Entry ghost = writer.create("q3-ghost", "委托方进程被 kill -9", 0, "", null);
        writer.advance(ghost, DelegateEvent.TASK_SPAWNED, "起跑");   // ← 进程在此刻死掉

        // 另一个进程（新实例、同根目录）读回来：幻影条目原样在盘上，非终态
        DelegationLedger reader = new DelegationLedger(liveRoot);
        DelegationLedger.Entry seen = reader.load("q3-ghost");
        assertNotNull(seen);
        assertEquals(DelegateState.RUNNING, seen.state);
        assertFalse("读回侧没有任何一层把它判死", seen.state.terminal());

        // 证伪"读回时有对账那一层"：连续三次只读 API（summary/list/load）之后盘上仍是 RUNNING
        DelegationDelivery dd = new DelegationDelivery(reader);
        assertTrue("幻影还被算进投递汇总: " + dd.summary(), dd.summary().contains("delegations=1"));
        assertEquals(1, reader.list().size());
        assertEquals(DelegateState.RUNNING, reader.load("q3-ghost").state);
        // 而且回收碰不到它（回收只认终态）⇒ 没人手动判孤儿的话，这个目录无限累积
        assertEquals(0, reader.pruneStale(DelegationLedger.LIVE_RETENTION_MILLIS,
                System.currentTimeMillis()));
        assertTrue(new File(liveRoot, "q3-ghost/state.json").isFile());

        // 阳性对照 A：心跳没超期的现场不许被误判（真在飞的那些）
        assertEquals("超期窗口内一条都不该动", 0, reader.adoptOrphans(
                DelegationLedger.ORPHAN_STALE_MILLIS, System.currentTimeMillis()));
        // 阳性对照 B：对账层<b>存在</b>，但只能手动调；调完才谈得上回收
        assertEquals(1, reader.adoptOrphans(0L, System.currentTimeMillis()));
        assertEquals(DelegateState.UNKNOWN, reader.load("q3-ghost").state);
        assertEquals(1, reader.pruneStale(0L, System.currentTimeMillis()));
        assertFalse(new File(liveRoot, "q3-ghost").exists());
    }

    @Test
    public void q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt() {
        BotAgent first = agent(new Fixed(textReply("q3b-reply")));
        String id = idOf(first.submitBackground("q3b 重启之后取不回的那条"));
        assertTrue(untilTerminal(new DelegationLedger(liveRoot), id, 8000L));

        // 换一个 BotAgent（同一台账根）= 进程重启：内存异步台账是空的，盘上现场还在
        BotAgent restarted = agent(new Fixed(textReply("不该被调到")));
        DelegationLedger disk = restarted.getDelegation().liveLedger();
        assertTrue("盘上必须还留着那条现场", disk.exists(id));
        assertTrue("/agents 对操作员说没有委托: " + restarted.describeAgents(),
                restarted.describeAgents().contains("暂无异步委托"));
        assertTrue("/background result 也找不到: " + restarted.backgroundResult(id),
                restarted.backgroundResult(id).startsWith("未知委托 id"));

        // 阳性对照：读侧 API 本身是好的（同根新实例读得到 DONE/PENDING，恢复面也真把它列出来）
        DelegationLedger.Entry e = new DelegationLedger(liveRoot).load(id);
        assertNotNull(e);
        assertEquals(DelegateState.DONE, e.state);
        assertEquals(DeliveryState.PENDING, e.delivery);
        List<String> offered = new ArrayList<String>();
        for (DelegationLedger.Entry x : restarted.getDelegation().delivery().undeliveredTerminalResults()) {
            offered.add(x.id);
        }
        assertEquals("盘上这条 DONE+PENDING 正是该回投的那一条: " + offered,
                Collections.singletonList(id), offered);
        // 而 sweepAtStartup() 这个"该被接线调"的入口，自己也能把幻影判死（前提是有地方调它）
        assertTrue(restarted.getDelegation().sweepAtStartup(),
                restarted.getDelegation().sweepAtStartup().startsWith("adopted=0 pruned=0"));
    }

    @Test
    public void q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath() throws Exception {
        DelegationLedger ledger = new DelegationLedger(liveRoot);
        // 阳性对照：完好现场读得回、也在回收的射程内
        done(ledger, "q3-good");
        assertNotNull(ledger.load("q3-good"));

        // hermes 的线名是小写 dropped（async_delegation.py 的 SQL 面值）；这里一旦按她的面值写进来，
        // DeliveryState.valueOf 抛 ⇒ load() catch(Exception) 静默返 null ⇒ 这条现场从所有读路径消失
        writeRawState(ledger, "q3-hermes-face",
                "{\"id\":\"q3-hermes-face\",\"state\":\"DONE\",\"delivery_state\":\"dropped\","
                        + "\"delivery_attempts\":8,\"dispatched_at\":1,\"updated_at\":1,\"finished_at\":1}");
        assertNull("盘上明明有文件，load() 却返 null", ledger.load("q3-hermes-face"));
        assertTrue(new File(liveRoot, "q3-hermes-face/state.json").isFile());
        // 时间戳全缺的终态现场（字段丢失/别的写入方留下的）
        writeRawState(ledger, "q3-no-timestamps",
                "{\"id\":\"q3-no-timestamps\",\"state\":\"FAILED\",\"delivery_state\":\"PENDING\"}");
        assertNotNull("字段缺省只影响年龄，不影响可读性", ledger.load("q3-no-timestamps"));

        List<String> listed = new ArrayList<String>();
        for (DelegationLedger.Entry x : ledger.list()) {
            listed.add(x.id);
        }
        assertTrue(listed.contains("q3-good"));
        assertTrue(listed.contains("q3-no-timestamps"));
        assertFalse("解析不了的现场不进 list() ⇒ 也就不进 pruneStale()", listed.contains("q3-hermes-face"));

        // 窗口取 0 ⇒ 看得见的那两条终态现场当场被删；看不见的那条永久留在盘上（目录只涨不落）
        int removed = ledger.pruneStale(0L, System.currentTimeMillis());
        assertEquals("list() 里两条终态都该被回收: " + removed, 2, removed);
        assertFalse(new File(liveRoot, "q3-good").exists());
        assertFalse(new File(liveRoot, "q3-no-timestamps").exists());
        assertTrue("不可解析的现场是永久泄漏：adoptOrphans 与 pruneStale 都碰不到它",
                new File(liveRoot, "q3-hermes-face").exists());
    }

    // ================= helpers（全部有界；本文件一个 await()/waitFor() 都不用） =================

    private DelegationLedger.Entry done(DelegationLedger ledger, String id) {
        DelegationLedger.Entry e = ledger.create(id, "已经收工、没人接", 0, "", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "起跑");
        e.reply = id + "-reply";
        ledger.advance(e, DelegateEvent.TASK_COMPLETED, "收工");
        return e;
    }

    private static void writeRawState(DelegationLedger ledger, String id, String json) throws Exception {
        File dir = ledger.dirOf(id);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "state.json")), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
    }

    private BotAgent agent(LlmProvider llm) {
        BotConfig config = null;   // 台账根从 sandbox 父目录推（见 setUp 注释）
        return BotAgent.builder(config)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .delegateDepth(0)
                .withoutCenter()
                .model("p27f-model")
                .build();
    }

    private static String idOf(String submitted) {
        return submitted.replaceFirst(".*(bg[0-9]+-[0-9]+|dlg[0-9]+-[0-9]+).*", "$1");
    }

    private static boolean until(File f, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (f.isFile()) {
                return true;
            }
            nap(25L);
        }
        return f.isFile();
    }

    private static boolean untilTerminal(DelegationLedger ledger, String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DelegationLedger.Entry e = ledger.load(id);
            if (e != null && e.state.terminal()) {
                return true;
            }
            nap(25L);
        }
        return false;
    }

    private static void nap(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 子代理那一侧的回覆：一次一个，取完就回默认值（不阻塞、不留无界等待）。 */
    private static ChatCompletionsResponse textReply(String content) {
        return new ChatCompletionsResponse("p27f", "p27f-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(
                        0, content, Collections.<ToolCall>emptyList(), "stop")),
                new TokenUsage(2L, 3L, 5L), "stop", null);
    }

    private static ChatCompletionsResponse gatedReply() {
        return textReply("q2-child-reply");
    }

    /** 挡在子代理那一侧的闸门：10s 硬上限（无界等待会在共享变异锁下酿成全编队事故）。 */
    private static final class Gate {
        private final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);

        boolean blocked() {
            try {
                return !latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void open() {
            latch.countDown();
        }
    }

    private static final class Fixed implements LlmProvider {
        private final ChatCompletionsResponse reply;
        private final Gate gate;

        Fixed(ChatCompletionsResponse reply) {
            this(reply, null);
        }

        Fixed(ChatCompletionsResponse reply, Gate gate) {
            this.reply = reply;
            this.gate = gate;
        }

        @Override
        public String name() {
            return "p27f-fixed";
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
            if (gate != null) {
                gate.blocked();
            }
            return reply;
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(reply);
        }
    }
}
