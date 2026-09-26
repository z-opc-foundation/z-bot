package com.zifang.z.bot.context;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.store.StateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P14 引擎侧：阈值新口径（窗口−输出额度）× pct、pct 配置位真的能挪动触发点、
 * 防抖计数、失败冷却入库（跨「进程重启」仍生效）、DB 抢锁（两个引擎实例 = 两个进程）、
 * 血统分叉 {@code base #N} 与检索回溯去重。
 *
 * <p>内存锁那一档（{@link CompressorEngineTest#compressLockLetsOnlyOneThreadThrough}）保留，
 * 但它只证明「同进程内不多烧一次摘要调用」；跨进程的正确性由本文件里
 * {@code compressionLocksTableLetsOnlyOneProcessThrough} 用真表钉。</p>
 */
public class CompressionEngineP14Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ContextEngine.Summarizer LONG_SUMMARY = msgs -> "摘要：保留了全部路径与决策。";
    private static final ContextEngine.Summarizer TINY_SUMMARY = msgs -> "短";
    private static final ContextEngine.Summarizer EMPTY_SUMMARY = msgs -> "   ";
    /** 「压了等于没压」：摘要长度 ≈ 中段原文长度 ⇒ 省不下任何 token，必须被防抖算一次无效。 */
    private static final ContextEngine.Summarizer AS_LONG_AS_INPUT = msgs -> {
        StringBuilder sb = new StringBuilder();
        for (Msg m : msgs) {
            sb.append(m.getContent());
        }
        return sb.toString();
    };

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    private static List<Msg> history(int n, int charsPerMsg) {
        List<Msg> out = new ArrayList<Msg>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < charsPerMsg; i++) {
            sb.append('x');
        }
        for (int i = 1; i <= n; i++) {
            out.add(user("m" + i + "=" + sb));
        }
        return out;
    }

    private static CompressorEngine engine(long window, int maxOutput, double pct) {
        return new CompressorEngine(window, maxOutput, pct, 4,
                CompressorEngine.DEFAULT_COOLDOWN_MILLIS, 2, 60_000L);
    }

    private StateStore store(String name) throws Exception {
        File dir = tmp.newFolder(name);
        return new StateStore(new File(dir, "state.db"));
    }

    private BotConfig configWith(String... lines) throws Exception {
        File dir = tmp.newFolder("cfg" + System.nanoTime());
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        Writer w = new OutputStreamWriter(Files.newOutputStream(new File(dir, "config.properties").toPath()),
                StandardCharsets.UTF_8);
        w.write(sb.toString());
        w.close();
        return BotConfig.load(dir);
    }

    // ===== 1. 阈值语义 =====

    @Test
    public void thresholdIsRatioOfWindowMinusMaxOutputTokens() {
        CompressorEngine e = engine(1000L, 200, 0.50D);
        assertEquals(800L, e.availableWindow());
        assertEquals(400L, e.compressionLimit());
        e.update(399, 0);
        assertFalse("399 < 400 不该触发", e.shouldCompress());
        e.update(400, 0);
        assertTrue("400 ≥ 400 必须触发", e.shouldCompress());
    }

    /** 摘掉 {@code - maxOutputTokens} 这一步会不会改变结论？会 —— 这一支就是杠② M1 的预期红。 */
    @Test
    public void ignoringMaxOutputTokensWouldTriggerTooLate() {
        CompressorEngine e = engine(1000L, 200, 0.50D);
        e.update(450, 0);
        assertTrue("450 已经在「窗口−输出额度」的 400 之上，必须触发（旧口径 500 会漏）", e.shouldCompress());
        assertEquals(500L, (long) (e.getMaxTokens() * 0.5D));
        assertFalse("按旧的「窗口×pct」口径 450 还不够：证明两口径不等价",
                450L >= (long) (e.getMaxTokens() * e.getPct()));
    }

    @Test
    public void pctComesFromConfigAndMovesTheTriggerPoint() throws Exception {
        BotConfig lo = configWith("agent.context.window=10000",
                "agent.context.max.output.tokens=2000", "agent.context.compress.pct=0.50");
        BotConfig hi = configWith("agent.context.window=10000",
                "agent.context.max.output.tokens=2000", "agent.context.compress.pct=0.90");
        assertEquals(0.50D, lo.getContextCompressPct(), 1e-9);
        assertEquals(0.90D, hi.getContextCompressPct(), 1e-9);
        CompressorEngine a = new CompressorEngine(lo.getContextWindow(), lo.getContextMaxOutputTokens(),
                lo.getContextCompressPct(), lo.getContextCompressKeepRecent(),
                lo.getContextCompressCooldownMillis(), lo.getContextCompressMaxIneffective(),
                lo.getContextCompressLockTtlMillis());
        CompressorEngine b = new CompressorEngine(hi.getContextWindow(), hi.getContextMaxOutputTokens(),
                hi.getContextCompressPct(), hi.getContextCompressKeepRecent(),
                hi.getContextCompressCooldownMillis(), hi.getContextCompressMaxIneffective(),
                hi.getContextCompressLockTtlMillis());
        assertEquals(4000L, a.compressionLimit());
        assertEquals(7200L, b.compressionLimit());
        // 同一条单调上涨的 token 流：pct 决定第几步触发（触发轮次对比数据的单元测试版）
        long[] stream = {2000, 3000, 4000, 5000, 6000, 7200, 8000};
        assertEquals(2, firstTriggerStep(a, stream));
        assertEquals(5, firstTriggerStep(b, stream));
    }

    private static int firstTriggerStep(CompressorEngine e, long[] stream) {
        for (int i = 0; i < stream.length; i++) {
            e.update((int) stream[i], 0);
            if (e.shouldCompress()) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void compressDisabledByConfig() throws Exception {
        BotConfig off = configWith("agent.context.compress=false");
        assertFalse(off.isContextCompressEnabled());
        BotConfig on = configWith();
        assertTrue(on.isContextCompressEnabled());
        assertTrue(on.contextCompressConfigLines().size() >= 8);
    }

    // ===== 2. 位点判定 =====

    @Test
    public void coarseEstimateSiteTriggersBeforeAnyRealUsage() {
        CompressorEngine e = engine(1000L, 0, 0.50D);
        assertFalse(e.shouldCompress());
        assertTrue("位点 2 的粗估必须自己就能判定，不能等真实 usage",
                e.evaluateBeforeApiCall(1200L));
        assertEquals(600L, e.observationAtSite(CompressorEngine.SITE_BEFORE_API_CALL));
        assertEquals(1, e.siteHits(CompressorEngine.SITE_BEFORE_API_CALL));
        assertEquals("轮首位点一次都没调过", 0, e.siteHits(CompressorEngine.SITE_TURN_START));
        assertEquals(0, e.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH));
    }

    @Test
    public void realUsageClearsStaleCoarseEstimate() {
        CompressorEngine e = engine(1000L, 0, 0.50D);
        e.evaluateBeforeApiCall(4000L);
        assertTrue(e.shouldCompress());
        e.update(100, 0);
        assertFalse("真实 usage 回来后要以真实口径为准，粗估不得赖着不走", e.shouldCompress());
    }

    @Test
    public void toolBatchSiteFoldsRealUsageWithToolOutput() {
        CompressorEngine e = engine(20_000L, 0, 0.50D);
        e.update(9000, 5);
        assertFalse(e.evaluateAtTurnStart());
        assertTrue("工具批灌进 2000 tokens 之后必须越线",
                e.evaluateAfterToolBatch(11_000, 5));
        assertEquals(1, e.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH));
    }

    // ===== 3. 防抖与冷却 =====

    @Test
    public void ineffectiveCompressionsStopTheEngine() throws Exception {
        StateStore store = store("ineff");
        CompressionLedger ledger = new CompressionLedger(store);
        CompressorEngine e = engine(1000L, 0, 0.50D).withGate(ledger, () -> "sess_ineff");
        List<Msg> big = history(30, 40);
        e.update(900, 0);
        assertSame("一次都没省下 ⇒ 不把这份「白压」换进记忆", big, e.compress(big, AS_LONG_AS_INPUT));
        assertEquals(1, e.getCompressCount());
        assertEquals(1, e.getIneffectiveStreak());

        e.update(900, 0);
        assertSame(big, e.compress(big, AS_LONG_AS_INPUT));
        assertEquals(2, e.getIneffectiveStreak());

        e.update(900, 0);
        assertFalse("连续两次无效 ⇒ 防抖生效，不再判「该压」", e.shouldCompress());
        assertEquals(CompressorEngine.SKIP_DEBOUNCE, e.getLastSkipReason());
        assertSame(big, e.compress(big, LONG_SUMMARY));
        assertEquals("第三次已经被防抖挡住，计数不该再涨", 2, e.getCompressCount());
        assertEquals(2, e.getIneffectiveStreak());
        assertEquals("防抖计数必须已经入库", 2,
                new CompressionLedger(store).loadIneffectiveStreak("sess_ineff"));
        // 手动 /compress 不受防抖管（用户就是要压）
        List<Msg> manual = e.forceCompress(big, LONG_SUMMARY);
        assertTrue("forceCompress 必须绕过防抖", manual != big);
    }

    @Test
    public void ineffectiveStreakSurvivesProcessRestart() throws Exception {
        StateStore store = store("streak");
        List<Msg> big = history(30, 40);
        CompressorEngine e = engine(1000L, 0, 0.50D).withGate(new CompressionLedger(store), () -> "sess_streak");
        e.update(900, 0);
        e.compress(big, AS_LONG_AS_INPUT);
        e.update(900, 0);
        e.compress(big, AS_LONG_AS_INPUT);
        // 「重启」：新引擎实例 + 新 ledger 实例，只共享那个库
        CompressorEngine rebooted = engine(1000L, 0, 0.50D)
                .withGate(new CompressionLedger(store), () -> "sess_streak");
        assertEquals(2, rebooted.getIneffectiveStreak());
        rebooted.update(900, 0);
        assertFalse("重启后防抖仍然生效", rebooted.shouldCompress());
        assertSame(big, rebooted.compress(big, LONG_SUMMARY));
    }

    @Test
    public void summarizerFailureCooldownIsPersistedAndHonoured() throws Exception {
        StateStore store = store("cool");
        CompressorEngine e = engine(1000L, 0, 0.50D).withGate(new CompressionLedger(store), () -> "sess_cool");
        ContextEngine.Summarizer boom = msgs -> {
            throw new IllegalStateException("aux 模型挂了");
        };
        e.update(900, 0);
        List<Msg> h = history(30, 40);
        assertSame("摘要器抛异常 ⇒ 历史原样", h, e.compress(h, boom));
        assertEquals(1, e.getFailureCount());
        assertTrue("失败之后必须进冷却", e.coolingDown());
        long stored = new CompressionLedger(store).loadCooldownUntilMillis("sess_cool");
        assertTrue("冷却截止时刻必须落在 state_meta 里，实际=" + stored, stored > System.currentTimeMillis());

        // 重启：新实例挂同一个库，仍然在冷却
        CompressorEngine rebooted = engine(1000L, 0, 0.50D)
                .withGate(new CompressionLedger(store), () -> "sess_cool");
        rebooted.update(900, 0);
        assertFalse("冷却期内不该再判成该压", rebooted.shouldCompress());
        assertEquals(CompressorEngine.SKIP_COOLDOWN, rebooted.getLastSkipReason());
        assertSame(h, rebooted.compress(h, LONG_SUMMARY));

        // 冷却到点：换一个已过期的截止时刻（不真等 120s）
        store.setMeta(CompressionLedger.META_COOLDOWN_PREFIX + "sess_cool",
                Long.toString(System.currentTimeMillis() - 1000L));
        CompressorEngine afterCooldown = engine(1000L, 0, 0.50D)
                .withGate(new CompressionLedger(store), () -> "sess_cool");
        afterCooldown.update(900, 0);
        assertTrue("冷却过期后必须重新判「该压」", afterCooldown.shouldCompress());
        List<Msg> out = afterCooldown.compress(history(30, 40), LONG_SUMMARY);
        assertTrue("冷却过期后必须真压", out != null && !out.isEmpty()
                && out.get(0).getContent().contains("压缩摘要"));
        assertEquals("压成功就作废冷却", 0L,
                new CompressionLedger(store).loadCooldownUntilMillis("sess_cool"));
    }

    @Test
    public void emptySummaryAlsoCountsAsFailure() throws Exception {
        StateStore store = store("empty");
        CompressorEngine e = engine(1000L, 0, 0.50D).withGate(new CompressionLedger(store), () -> "sess_empty");
        e.update(900, 0);
        List<Msg> h = history(30, 40);
        assertSame(h, e.compress(h, EMPTY_SUMMARY));
        assertEquals(CompressorEngine.SKIP_EMPTY_SUMMARY, e.getLastSkipReason());
        assertTrue(e.coolingDown());
    }

    // ===== 4. DB 抢锁 =====

    /** 两个引擎实例 + 两个 ledger = 两个进程，共用同一个 state.db：只有一个能压。 */
    @Test
    public void compressionLocksTableLetsOnlyOneProcessThrough() throws Exception {
        final StateStore store = store("lock");
        final CompressionLedger a = new CompressionLedger(store);
        final CompressionLedger b = new CompressionLedger(store);
        CompressorEngine ea = engine(1000L, 0, 0.50D).withGate(a, () -> "sess_lock");
        CompressorEngine eb = engine(1000L, 0, 0.50D).withGate(b, () -> "sess_lock");
        ea.update(900, 0);
        eb.update(900, 0);
        final List<Msg> h = history(30, 40);
        final AtomicInteger summarizerRuns = new AtomicInteger();
        ContextEngine.Summarizer counting = msgs -> {
            summarizerRuns.incrementAndGet();
            return LONG_SUMMARY.summarize(msgs);
        };
        // A 先把锁抢下来并「正在压」（TTL 没到）
        assertTrue(a.tryAcquire("sess_lock", null, 60_000L));
        assertNotNull(a.lockHolder("sess_lock"));
        assertSame("B 必须因为 DB 锁拿不到而原样返回", h, eb.compress(h, counting));
        assertEquals(CompressorEngine.SKIP_LOCK, eb.getLastSkipReason());
        assertEquals("被锁挡住的一方连摘要都不能调", 0, summarizerRuns.get());
        a.release("sess_lock", null);
        assertNull(a.lockHolder("sess_lock"));
        List<Msg> outB = eb.compress(h, counting);
        assertTrue("锁放掉之后 B 就能压了", outB != h);
        assertEquals(1, summarizerRuns.get());
    }

    /** 锁键是血统根：分叉出来的子会话不能各拿一把锁。 */
    @Test
    public void lockIsHeldForWholeLineage() throws Exception {
        StateStore store = store("lockline");
        store.upsertSession("root_1", "新会话", null, null, 0, 0, 0);
        store.upsertSession("child_1", "base #1", null, null, 0, 0, 0);
        assertNotNull(store.forkSession("root_1", "child_1", "base #1", null, "compressed"));
        CompressionLedger l = new CompressionLedger(store);
        assertTrue(l.tryAcquire("child_1", null, 60_000L));
        assertEquals("root_1", l.lineageRoot("child_1"));
        assertFalse("子会话持有的锁必须挡住父会话上的另一个申请者",
                new CompressionLedger(store).tryAcquire("root_1", null, 60_000L));
        l.release("child_1", null);
        assertTrue(new CompressionLedger(store).tryAcquire("root_1", null, 60_000L));
    }

    // ===== 5. 血统与派号 =====

    @Test
    public void compressionForkWritesParentAndBaseOrdinal() throws Exception {
        StateStore store = store("lineage");
        CompressionLedger l = new CompressionLedger(store);
        store.upsertSession("s0", "最初会话", null, null, 3, 100, 2);
        store.upsertSession("s1", "新会话", null, null, 0, 0, 0);
        assertEquals("base #1", l.forkForCompression("s0", "s1"));
        assertEquals("s0", store.parentOf("s1"));
        assertTrue("父会话要标成已结束", store.sessionEnded("s0"));
        // 标题不能顺手把 token 账清零（upsertSession 是整行覆盖语义）
        StateStore.SessionRow row = find(store, "s1");
        assertNotNull(row);
        assertEquals("base #1", row.title);
        store.upsertSession("s2", "新会话", null, null, 0, 777, 9);
        assertEquals("base #2", l.forkForCompression("s1", "s2"));
        assertEquals(777L, find(store, "s2").tokens);
        assertEquals(2, l.lineageDepth("s2"));
        assertEquals("s0", l.lineageRoot("s2"));
    }

    @Test
    public void lineageSearchWalksBackToOriginalAndDedupes() throws Exception {
        StateStore store = store("search");
        store.upsertSession("p", "父", null, null, 0, 0, 0);
        store.upsertSession("c", "base #1", null, null, 0, 0, 0);
        List<Msg> parentMsgs = new ArrayList<Msg>();
        parentMsgs.add(user("这里写着 P14needle 的原文"));
        store.saveMessages("p", parentMsgs);
        List<Msg> childMsgs = new ArrayList<Msg>();
        childMsgs.add(user("[context summary] 压缩摘要里也带着 P14needle"));
        store.saveMessages("c", childMsgs);
        assertNotNull(store.forkSession("p", "c", "base #1", null, "compressed"));
        CompressionLedger l = new CompressionLedger(store);
        List<StateStore.SearchHit> raw = store.search("P14needle", 8);
        assertEquals("原始检索应该两条都命中", 2, raw.size());
        List<CompressionLedger.SearchHit> out = l.searchAlongLineage("P14needle", 8);
        assertEquals("回溯到原文那一代之后只剩一条", 1, out.size());
        assertEquals("p", out.get(0).sessionId);
    }

    private static StateStore.SessionRow find(StateStore store, String id) {
        for (StateStore.SessionRow r : store.listSessions(true)) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }
}
