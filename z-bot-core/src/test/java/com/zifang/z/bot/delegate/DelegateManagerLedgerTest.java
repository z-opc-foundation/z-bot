package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P27 靶子一 + 靶子二在 {@link DelegateManager} 上的合体取证：
 * <b>委托现场建账先于执行</b>（进程死了还在盘上）、<b>DONE 不等于送达</b>、
 * <b>投递上限只在有人 claim 时增长</b>。
 *
 * <p>所有等待都是<b>有界</b>的（{@code await(5, SECONDS)} / 有截止时间的轮询）——
 * 委托这一路天生等子进程，无界等待在共享变异锁下就是全编队的事故（P14a 死法）。</p>
 */
public class DelegateManagerLedgerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;
    private File sessionDir;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
    }

    // ===== 同步委托：现场建账先于执行 =====

    @Test
    public void syncDelegationWritesASceneThatIsReadableWithoutTheCaller() throws Exception {
        Scripted llm = new Scripted(textReply("child-answer-77"));
        BotAgent agent = agent(llm);
        DelegationManagerHandle h = handle(agent);

        ToolResult result = agent.getDelegation().delegateTool()
                .execute(Collections.<String, Object>singletonMap("task", "找出答案并把 api-key=sk-abcdefghijklmnop 带出去"));
        assertFalse("同步委托不该出错: " + result.getContent(), result.isError());
        assertTrue(result.getContent(), result.getContent().contains("child-answer-77"));

        File root = h.ledger.root();
        assertNotNull("config==null 时台账要从 childSessionDir 推出根目录", root);
        List<DelegationLedger.Entry> all = h.ledger.list();
        assertEquals("同步委托必须留一条现场: " + dirNames(root), 1, all.size());
        DelegationLedger.Entry e = all.get(0);
        assertEquals(DelegateState.DONE, e.state);
        assertTrue("结果要能在盘上读回: " + e.reply, e.reply.contains("child-answer-77"));
        assertEquals("子代理收工 ≠ 有人接住", DeliveryState.PENDING, e.delivery);
        assertEquals(0, e.deliveryAttempts);
        assertTrue(e.id, e.id.startsWith("dlg"));
        assertTrue(e.childSession, new File(e.childSession).getName().startsWith("d0-"));
        String events = String.join("\n", h.ledger.tail(e.id, 50));
        assertTrue(events, events.contains("delegate.dispatch"));
        assertTrue(events, events.contains("delegate.task_spawned"));
        assertTrue(events, events.contains("delegate.task_completed"));
        assertFalse("密钥不许原文进台账:\n" + events, events.contains("abcdefghijklmnop"));
        // 换一个实例读 = "委托方进程死了之后还能从盘上判词"
        DelegationLedger reopened = new DelegationLedger(root);
        assertNotNull(reopened.load(e.id));
        assertEquals(DelegateState.DONE, reopened.load(e.id).state);
    }

    // ===== 异步委托：DONE 不是送达，拉一次才算 =====

    @Test
    public void asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery() throws Exception {
        Gate gate = new Gate();
        Scripted llm = new Scripted(gate);
        BotAgent agent = agent(llm);
        DelegationManagerHandle h = handle(agent);

        String submitted = agent.submitBackground("gate task");
        String id = idOf(submitted);
        assertTrue(submitted, submitted.contains("已提交异步委托"));

        // 子代理还被闸门挡住：此刻现场必须已经在盘上，且是非终态
        assertTrue("建账没先于执行落盘", awaitFile(new File(h.ledger.root(), id + "/state.json"), 5000L));
        DelegationLedger.Entry mid = new DelegationLedger(h.ledger.root()).load(id);
        assertNotNull(mid);
        assertTrue("中途状态: " + mid.state, mid.state == DelegateState.QUEUED || mid.state == DelegateState.RUNNING);
        assertEquals(DeliveryState.PENDING, mid.delivery);
        assertEquals(0, mid.deliveryAttempts);
        assertEquals("还没收工，谈不上待投递", 0, h.delivery.undeliveredTerminalResults().size());

        gate.open();
        String result = awaitPull(agent, id, 5000L);
        assertTrue(result, result.contains("gate-reply"));
        // 拉取之前：盘上是 DONE + PENDING —— 这就是修之前被记成 ok 的那一格
        DelegationLedger.Entry after = new DelegationLedger(h.ledger.root()).load(id);
        assertEquals(DelegateState.DONE, after.state);
        assertEquals("拉取之后才允许 DELIVERED", DeliveryState.DELIVERED, after.delivery);
        assertEquals(1, after.deliveryAttempts);
        assertTrue(result, result.contains("DELIVERED"));
        String agents = agent.describeAgents();
        assertTrue(agents, agents.contains("投递=DELIVERED(1/8)"));
        assertEquals(0, h.delivery.undeliveredTerminalResults().size());

        // 第二次拉取：不再烧尝试数，判词仍是 DELIVERED
        String again = agent.backgroundResult(id);
        assertTrue(again, again.contains("gate-reply"));
        assertEquals(1, new DelegationLedger(h.ledger.root()).load(id).deliveryAttempts);
        assertTrue(again, again.contains("DELIVERED(1/8)"));
    }

    /** 闸门挡住收工 ⇒ 台账停在非终态；放闸后由"重启读回"的实例判它。 */
    @Test
    public void unfinishedAsyncSceneIsAdoptableAsOrphan() throws Exception {
        Gate gate = new Gate();
        Scripted llm = new Scripted(gate);
        BotAgent agent = agent(llm);
        DelegationManagerHandle h = handle(agent);
        String id = idOf(agent.submitBackground("orphan task"));
        assertTrue(awaitFile(new File(h.ledger.root(), id + "/state.json"), 5000L));
        gate.open();
        awaitPull(agent, id, 5000L);

        // 造一条"死了的"现场：非终态 + 心跳超期
        DelegationLedger.Entry ghost = h.ledger.create("dlg-ghost", "跑到一半进程没了", 0, "", null);
        h.ledger.advance(ghost, DelegateEvent.TASK_SPAWNED, "");
        ghost.updatedAt = System.currentTimeMillis() - DelegationLedger.ORPHAN_STALE_MILLIS - 5000L;
        h.ledger.save(ghost);

        assertEquals("只有超期的非终态被认领", 1,
                h.ledger.adoptOrphans(DelegationLedger.ORPHAN_STALE_MILLIS, System.currentTimeMillis()));
        assertEquals(DelegateState.UNKNOWN, h.ledger.load("dlg-ghost").state);
        assertEquals(DelegateState.DONE, h.ledger.load(id).state);
        String sweep = agent.getDelegation().sweepLiveLedger();
        assertTrue(sweep, sweep.startsWith("adopted=0 pruned=0"));
    }

    @Test
    public void stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins() throws Exception {
        Gate gate = new Gate();
        Scripted llm = new Scripted(gate);
        BotAgent agent = agent(llm);
        DelegationManagerHandle h = handle(agent);
        final AtomicReference<Object> out = new AtomicReference<Object>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    out.set(agent.getDelegation().delegateTool()
                            .execute(Collections.<String, Object>singletonMap("task", "long running")));
                } catch (RuntimeException e) {
                    out.set("threw:" + e.getClass().getSimpleName());
                }
            }
        }, "p27-stop-worker");
        worker.setDaemon(true);
        worker.start();
        assertTrue("子代理没能进入在飞集合", awaitInFlight(agent, 1, 5000L));
        assertTrue("现场要先落盘", awaitEvents(h.ledger, 1, 5000L));
        assertEquals(1, agent.getDelegation().inFlightCount());

        int stopped = agent.getDelegation().stopChildren();
        gate.open();
        worker.join(10_000L);
        assertFalse("委托线程没收口，测试会拖死全编队的锁", worker.isAlive());
        assertEquals(1, stopped);
        assertEquals(0, agent.getDelegation().inFlightCount());

        List<DelegationLedger.Entry> all = h.ledger.list();
        assertEquals(1, all.size());
        DelegationLedger.Entry e = all.get(0);
        assertEquals("被叫停的现场要留在盘上: " + e.state, DelegateState.STOPPED, e.state);
        String events = String.join("\n", h.ledger.tail(e.id, 50));
        assertTrue(events, events.contains("delegate.task_stopped"));
        int terminals = 0;
        for (String line : events.split("\n")) {
            if (line.contains("|delegate.task_stopped|") || line.contains("|delegate.task_completed|")
                    || line.contains("|delegate.task_failed|")) {
                terminals++;
            }
        }
        assertEquals("第二次判决不许改写终态（也不许再落一条终态事件）:\n" + events, 1, terminals);
    }

    // ===== 预算旁路取证（§0 靶子四：:164 的 if (p != null) 分支） =====

    @Test
    public void unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached() throws Exception {
        Scripted llm = new Scripted(textReply("no-parent-reply"));
        File children = new File(tmp.getRoot(), "children");
        DelegateManager bare = new DelegateManager(null, llm,
                new Sandbox(sandboxDir.getAbsolutePath()), children, 0, 2);
        assertNotNull("childSessionDir 有父目录时台账要能推出来", bare.liveLedger().root());
        String submitted = bare.submitAsync("no attach at all");
        String id = idOf(submitted);
        assertTrue("未 attach 时投递仍要收口", awaitLedgerState(bare.liveLedger(), id, 5000L));
        String events = String.join("\n", bare.liveLedger().tail(id, 50));
        assertTrue("父引用未回填这条旁路必须留在盘上:\n" + events, events.contains("delegate.budget_unclamped"));
        assertEquals(DelegateState.DONE, bare.liveLedger().load(id).state);
        assertEquals(DeliveryState.PENDING, bare.liveLedger().load(id).delivery);
    }

    // ===== 台账定址（不改 BotAgent 也能落地的前提） =====

    @Test
    public void ledgerRootDerivationNeedsNoConfigChange() throws Exception {
        File cfgDir = tmp.newFolder("cfg");
        assertEquals(new File(cfgDir, "delegate/live"),
                DelegateManager.ledgerRootFor(BotConfig.load(cfgDir), null));
        assertEquals(new File(tmp.getRoot(), "delegate/live"),
                DelegateManager.ledgerRootFor(null, new File(tmp.getRoot(), "delegate/children")));
        assertNullRoot(DelegateManager.ledgerRootFor(null, new File("bare-relative")));
        assertNullRoot(DelegateManager.ledgerRootFor(null, null));
    }

    // ===== 并发闸：QUEUED 也要算进在飞（修前只算 RUNNING） =====

    @Test
    public void concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth() throws Exception {
        Gate gate = new Gate();
        Scripted llm = new Scripted(gate);
        BotAgent agent = agent(llm);   // config==null ⇒ width 3
        List<String> accepted = new ArrayList<String>();
        String rejected = null;
        for (int i = 0; i < 5; i++) {
            String r = agent.submitBackground("burst-" + i);
            if (r.contains("已提交异步委托")) {
                accepted.add(idOf(r));
            } else {
                rejected = r;
                break;
            }
        }
        assertEquals("前 3 条都该收下", 3, accepted.size());
        assertNotNull("第 4 条必须被并发闸挡下（QUEUED 也算在飞）：" + rejected, rejected);
        assertTrue(rejected, rejected.contains("并发已满"));
        assertTrue(rejected, rejected.contains("3/3"));
        gate.open();
        for (String id : accepted) {
            assertTrue("闸门放开后收不了口", awaitLedgerState(handle(agent).ledger, id, 8000L));
        }
    }

    @Test
    public void depthExceededWritesNoSceneAtAll() throws Exception {
        Scripted llm = new Scripted(textReply("unused"));
        File children = new File(tmp.getRoot(), "depth-children");
        DelegateManager full = new DelegateManager(null, llm,
                new Sandbox(sandboxDir.getAbsolutePath()), children, 1, 1);   // depth 已用尽
        assertEquals(0, full.liveLedger().list().size());
        String async = full.submitAsync("深度已经用尽");
        assertTrue(async, async.contains("已达到委托深度上限"));
        ToolResult sync = full.delegateTool()
                .execute(Collections.<String, Object>singletonMap("task", "同样不许建账"));
        assertTrue(sync.getContent(), sync.isError());
        assertTrue(sync.getContent(), sync.getContent().contains("已达到委托深度上限"));
        assertEquals("深度上限之外一条现场都不许建（拒了还留现场 = 假台账）",
                0, full.liveLedger().list().size());
        assertEquals(0, full.liveLedger().saveCount());
    }

    @Test
    public void startupSweepIsSilentWhenTheLedgerCannotBeAddressed() {
        DelegateManager bare = new DelegateManager(null, new Scripted(),
                new Sandbox(sandboxDir.getAbsolutePath()), new File("p27-relative-children"), 0, 2);
        assertFalse("推不出根目录时台账必须是 disabled 态", bare.liveLedger().enabled());
        assertEquals("定不了址就不许有判词，也不许抛", "", bare.sweepAtStartup());
        assertFalse("不许在工作目录里留下委托文件", new File("p27-relative-children").exists());
    }

    @Test
    public void startupSweepAdoptsStaleSceneAndReportsFromDisk() throws Exception {
        Scripted llm = new Scripted();
        BotAgent agent = agent(llm);
        DelegationManagerHandle h = handle(agent);
        DelegationLedger.Entry ghost = h.ledger.create("dlg-startup-ghost", "上次进程没跑完", 0, "", null);
        h.ledger.advance(ghost, DelegateEvent.TASK_SPAWNED, "");
        ghost.updatedAt = System.currentTimeMillis() - DelegationLedger.ORPHAN_STALE_MILLIS - 1000L;
        h.ledger.save(ghost);
        assertEquals("adopted=1 pruned=0", agent.getDelegation().sweepAtStartup());
        assertEquals(DelegateState.UNKNOWN, new DelegationLedger(h.ledger.root()).load("dlg-startup-ghost").state);
    }

    // ===== helpers =====

    private static final class DelegationManagerHandle {
        final DelegationLedger ledger;
        final DelegationDelivery delivery;

        DelegationManagerHandle(DelegateManager m) {
            this.ledger = m.liveLedger();
            this.delivery = m.delivery();
        }
    }

    private DelegationManagerHandle handle(BotAgent agent) {
        assertNotNull("delegate 面没接上", agent.getDelegation());
        return new DelegationManagerHandle(agent.getDelegation());
    }

    private BotAgent agent(LlmProvider llm) {
        return agent(null, llm);
    }

    private BotAgent agent(BotConfig config, LlmProvider llm) {
        return BotAgent.builder(config)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .delegateDepth(0)
                .model("p27-model")
                .build();
    }

    private BotConfig configWith(String... extraLines) throws Exception {
        File dir = tmp.newFolder("cfg2");
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(dir, "config.properties")), "UTF-8");
        try {
            w.write("llm.provider=glm\nglm.type=openai\nglm.api.key=stub-key-not-real\n");
            w.write("glm.base.url=http://127.0.0.1:1/v1\nglm.model=glm-4\n");
            for (String line : extraLines) {
                w.write(line + "\n");
            }
        } finally {
            w.close();
        }
        return BotConfig.load(dir);
    }

    private static String idOf(String submitted) {
        return submitted.replaceFirst(".*(bg[0-9]+-[0-9]+|dlg[0-9]+-[0-9]+).*", "$1");
    }

    private static void assertNullRoot(File f) {
        assertTrue("推不出目录时必须返回 null（台账自动降级）", f == null);
    }

    /** 有界轮询：文件出现。 */
    private static boolean awaitFile(File f, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (f.isFile()) {
                return true;
            }
            sleep(25L);
        }
        return f.isFile();
    }

    /** 有界轮询：在飞子代理条数达到期望值。 */
    private static boolean awaitInFlight(BotAgent agent, int expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (agent.getDelegation().inFlightCount() == expected) {
                return true;
            }
            sleep(25L);
        }
        return agent.getDelegation().inFlightCount() == expected;
    }

    /** 有界轮询：台账里出现 N 条以上现场。 */
    private static boolean awaitEvents(DelegationLedger ledger, int minEntries, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ledger.list().size() >= minEntries) {
                return true;
            }
            sleep(25L);
        }
        return ledger.list().size() >= minEntries;
    }

    /** 有界轮询：该 id 的现场进入终态。 */
    private static boolean awaitLedgerState(DelegationLedger ledger, String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            DelegationLedger.Entry e = ledger.load(id);
            if (e != null && e.state.terminal()) {
                return true;
            }
            sleep(25L);
        }
        return false;
    }

    /** 有界轮询：拉取结果不再写着"还在"。 */
    private static String awaitPull(BotAgent agent, String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String r = "";
        while (System.currentTimeMillis() < deadline) {
            r = agent.backgroundResult(id);
            if (!r.contains("还在")) {
                return r;
            }
            sleep(50L);
        }
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> dirNames(File root) {
        List<String> out = new ArrayList<String>();
        File[] kids = root == null ? null : root.listFiles();
        if (kids != null) {
            for (File k : kids) {
                out.add(k.getName());
            }
        }
        return out;
    }

    // ===== 子进程/Llm 替身：全部有硬超时 =====

    /** 一次委托的闸门：provider 在子代理这边等闸门放开（有 10s 硬超时，绝不无界）。 */
    private static final class Gate {
        private final CountDownLatch latch = new CountDownLatch(1);

        void open() {
            latch.countDown();
        }

        boolean await() {
            try {
                return latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static ChatCompletionsResponse textReply(String content) {
        return new ChatCompletionsResponse("p27", "p27-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(
                        0, content, Collections.<ToolCall>emptyList(), "stop")),
                new TokenUsage(3L, 4L, 7L), "stop", null);
    }

    private static final class Scripted implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        private final List<Gate> gates = new ArrayList<Gate>();

        Scripted(Object... items) {
            for (Object o : items) {
                if (o instanceof ChatCompletionsResponse) {
                    scripted.add((ChatCompletionsResponse) o);
                } else if (o instanceof Gate) {
                    gates.add((Gate) o);
                }
            }
        }

        @Override
        public String name() {
            return "p27-scripted";
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
            if (!gates.isEmpty()) {
                Gate g = gates.remove(0);
                g.await();
                return textReply("gate-reply");
            }
            return scripted.isEmpty() ? textReply("exhausted") : scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
