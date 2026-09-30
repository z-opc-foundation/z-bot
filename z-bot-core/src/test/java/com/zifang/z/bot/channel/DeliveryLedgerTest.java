package com.zifang.z.bot.channel;

import com.zifang.z.bot.store.SqliteTx;
import com.zifang.z.bot.store.StateStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DeliveryLedger}：三态推进、崩溃语义（{@code attempting} 带「可能重复」前缀重投、
 * {@code pending} 直投）、认领原子性、毒行上限与保留策略。
 *
 * <p>「归属进程已死」这件事在单测里造不出来（不能让测试真去杀一个进程），所以
 * {@link DeliveryLedger.ProcessLiveness} 是注入点；真 pid + 真 kill -9 的现场在
 * {@code _doc/005_testing/acceptance/p16/p16_e2e.py}。</p>
 */
public class DeliveryLedgerTest {

    private static final SqliteTx.Config CFG = StateStore.Options.defaults();
    private static final Set<String> ALL = platformSet("webhook", "feishu");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File db;
    private DeliveryLedger ledger;

    private static Set<String> platformSet(String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    /** 一律判死的存活探针（等价于「归属进程已经不在了」）。 */
    private static final DeliveryLedger.ProcessLiveness ALWAYS_DEAD =
            new DeliveryLedger.ProcessLiveness() {
                @Override public boolean alive(long pid, Long startedAt) {
                    return false;
                }
            };

    private static DeliveryLedger ledgerWith(File whichDb, DeliveryLedger.ProcessLiveness liveness) {
        return new DeliveryLedger(whichDb, CFG, liveness);
    }

    @Before
    public void setUp() throws Exception {
        db = new File(tmp.newFolder("profile"), "state.db");
        ledger = new DeliveryLedger(db, CFG);
    }

    // ===== 1. 三态推进 =====

    @Test
    public void obligationMovesPendingAttemptingDelivered() {
        ledger.recordObligation("o1", "c1", "webhook", "c1", "回复正文");
        assertEquals(Integer.valueOf(1), ledger.countsByState().get(DeliveryLedger.PENDING));
        assertTrue(ledger.markAttempting("o1"));
        assertEquals(Integer.valueOf(1), ledger.countsByState().get(DeliveryLedger.ATTEMPTING));
        assertEquals("attempting 阶段内容还没结清", 1, ledger.idsInState(DeliveryLedger.ATTEMPTING).size());
        assertTrue(ledger.markDelivered("o1"));
        assertEquals(Collections.emptyList(), ledger.idsInState(DeliveryLedger.ATTEMPTING));
        assertEquals(Arrays.asList("o1"), ledger.idsInState(DeliveryLedger.DELIVERED));
        assertEquals(1, ledger.totalRows());
        assertEquals(1L, ledger.recordsInThisProcess());
    }

    @Test
    public void markingAnUnknownObligationIsFalseNotAnError() {
        assertFalse(ledger.markDelivered("nope"));
        assertFalse(ledger.markAttempting("nope"));
        assertFalse(ledger.markFailed("nope", "x"));
    }

    @Test
    public void ledgerCreatesItsOwnTableOnAFreshProfile() throws Exception {
        File fresh = new File(tmp.newFolder("p2"), "state.db");
        assertFalse("新 profile 不该预置库文件", fresh.isFile());
        DeliveryLedger l2 = new DeliveryLedger(fresh, CFG);
        l2.recordObligation("x", "c", "webhook", "c", "hi");
        assertEquals(1, l2.totalRows());
    }

    // ===== 2. 崩溃语义 =====

    @Test
    public void crashMidAttemptIsRedeliveredWithDuplicateWarning() {
        ledger.recordObligation("oA", "c1", "webhook", "c1", "断点前的回复");
        ledger.markAttempting("oA");

        List<DeliveryLedger.Claimed> claimed =
                ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null);
        assertEquals(1, claimed.size());
        DeliveryLedger.Claimed c = claimed.get(0);
        assertEquals("oA", c.obligationId);
        assertEquals(DeliveryLedger.ATTEMPTING, c.stateBeforeClaim);
        assertTrue("死在 await 里 ⇒ 平台可能已经有了 ⇒ 必须带标记", c.needsMarker());
        assertTrue(c.contentForDelivery().startsWith(DeliveryLedger.RECOVERED_MARKER));
        assertTrue(c.contentForDelivery().contains("断点前的回复"));
        assertEquals("重投预算被这一次认领烧掉 1", 1, c.attemptsAfterClaim);
    }

    @Test
    public void pendingObligationIsRedeliveredWithoutMarker() {
        ledger.recordObligation("oP", "c1", "webhook", "c1", "还没发出去的回复");
        List<DeliveryLedger.Claimed> claimed =
                ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null);
        assertEquals(1, claimed.size());
        assertFalse("发送根本没开始 ⇒ 直接重投，无重复风险", claimed.get(0).needsMarker());
        assertEquals("还没发出去的回复", claimed.get(0).contentForDelivery());
        assertEquals(DeliveryLedger.PENDING, claimed.get(0).stateBeforeClaim);
    }

    @Test
    public void failedObligationIsRedeliveredWithMarker() {
        ledger.recordObligation("oF", "c1", "webhook", "c1", "被拒过一次");
        ledger.markFailed("oF", "平台 500");
        List<DeliveryLedger.Claimed> claimed =
                ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null);
        assertEquals(1, claimed.size());
        assertTrue("重启是天然的重试边界，但歧义要说在前头", claimed.get(0).needsMarker());
        assertEquals(DeliveryLedger.FAILED, claimed.get(0).stateBeforeClaim);
    }

    @Test
    public void markerIsNotStackedOnReDelivery() {
        String once = DeliveryLedger.RECOVERED_MARKER + "正文";
        ledger.recordObligation("oM", "c1", "webhook", "c1", once);
        ledger.markAttempting("oM");
        DeliveryLedger.Claimed c = ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null).get(0);
        assertEquals("已有标记不再叠第二层", once, c.contentForDelivery());
    }

    @Test
    public void deliveredRowIsNeverClaimedAgain() {
        ledger.recordObligation("oD", "c1", "webhook", "c1", "已经发出去了");
        ledger.markAttempting("oD");
        ledger.markDelivered("oD");
        assertEquals(0, ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null).size());
    }

    @Test
    public void rowsOwnedByALiveProcessAreLeftAlone() throws Exception {
        ledger.recordObligation("oL", "c1", "webhook", "c1", "别的网关正在发");
        ledger.markAttempting("oL");
        // 探针一口咬定归属进程还活着（本进程就是 owner，真实现场也是这样）
        DeliveryLedger.ProcessLiveness alwaysAlive = new DeliveryLedger.ProcessLiveness() {
            @Override public boolean alive(long pid, Long startedAt) {
                return true;
            }
        };
        assertEquals("活着的 owner 的行不许抢", 0,
                ledgerWith(db, alwaysAlive).sweepRecoverable(ALL, null).size());
        assertEquals(1, ledger.idsInState(DeliveryLedger.ATTEMPTING).size());
    }

    @Test
    public void secondSweeperBacksOffOnceALiveOwnerHoldsTheRow() throws Exception {
        ledger.recordObligation("oT", "c1", "webhook", "c1", "只能被认领一次");
        ledger.markAttempting("oT");
        assertEquals(1, ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null).size());
        // 认领把 owner 换成了本进程（pid + 启动时刻）⇒ 第二次清扫用真判据必须认为它还活着。
        // 「认领语句」本身只保证同进程内不被重复 UPDATE，跨进程同时 sweep 的那一档在 E2E 里量。
        assertEquals("同一条义务不能被认领两次", 0,
                new DeliveryLedger(db, CFG).sweepRecoverable(ALL, null).size());
        assertEquals(1, ledger.idsInState(DeliveryLedger.ATTEMPTING).size());
        assertEquals("认领一次只烧一次预算", 1, attemptsOf("oT"));
    }

    // ===== 3. 毒行上限与过期 =====

    @Test
    public void exhaustedAttemptBudgetTurnsAbandonedInsteadOfSpinning() throws Exception {
        ledger.recordObligation("oCap", "c1", "webhook", "c1", "已经试过三轮");
        forceRow("oCap", DeliveryLedger.ATTEMPTING, DeliveryLedger.MAX_ATTEMPTS,
                System.currentTimeMillis());
        List<DeliveryLedger.Claimed> claimed =
                ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null);
        assertEquals("预算到顶就不该再交出去", 0, claimed.size());
        assertEquals(Arrays.asList("oCap"), ledger.idsInState(DeliveryLedger.ABANDONED));
        assertEquals(0, ledger.idsInState(DeliveryLedger.ATTEMPTING).size());
    }

    @Test
    public void staleObligationIsAbandonedNotReplayed() throws Exception {
        ledger.recordObligation("oStale", "c1", "webhook", "c1", "一天以前欠的");
        forceRow("oStale", DeliveryLedger.PENDING, 0,
                System.currentTimeMillis() - DeliveryLedger.STALE_AFTER_MILLIS - 10_000L);
        assertEquals(0, ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null).size());
        assertEquals(Arrays.asList("oStale"), ledger.idsInState(DeliveryLedger.ABANDONED));
    }

    @Test
    public void sweepOnlyClaimsRowsThisBootCanActuallyDeliver() throws Exception {
        ledger.recordObligation("oFeishu", "c1", "feishu", "c1", "发到没起来的平台");
        ledger.recordObligation("oHook", "c2", "webhook", "c2", "发到在线的平台");
        List<DeliveryLedger.Claimed> claimed = ledgerWith(db, ALWAYS_DEAD)
                .sweepRecoverable(platformSet("webhook"), null);
        assertEquals(1, claimed.size());
        assertEquals("oHook", claimed.get(0).obligationId);
        assertEquals("被认领的那条预算 +1", 1, attemptsOf("oHook"));
        assertEquals("缺平台的行原地不动、预算不烧", 0, attemptsOf("oFeishu"));
    }

    @Test
    public void sweepSurvivesMissingTableAndBadInput() {
        assertEquals(0, ledger.sweepRecoverable(null, null).size());
        ledger.recordObligation("oNull", "c1", "webhook", "c1", "正文");
        // deliverablePlatforms = null ⇒ 不设限（与 hermes 同义），仍应正常认领
        assertEquals(1, ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(null, null).size());
    }

    // ===== 4. 稳定 id =====

    @Test
    public void obligationIdIsStablePerTurnAndSensitiveToContent() {
        String a = DeliveryLedger.computeObligationId("c1", "msg-1", "回复");
        assertEquals("同轮同内容重复记账必须幂等", a,
                DeliveryLedger.computeObligationId("c1", "msg-1", "回复"));
        assertFalse(a.equals(DeliveryLedger.computeObligationId("c1", "msg-2", "回复")));
        assertFalse(a.equals(DeliveryLedger.computeObligationId("c1", "msg-1", "别的回复")));
        assertFalse(a.equals(DeliveryLedger.computeObligationId("c2", "msg-1", "回复")));
        assertEquals(24, a.length());
    }

    @Test
    public void reRecordingTheSameObligationResetsItsAttemptBudget() {
        ledger.recordObligation("oR", "c1", "webhook", "c1", "同一轮重放同一条");
        ledger.markAttempting("oR");
        DeliveryLedger.Claimed c = ledgerWith(db, ALWAYS_DEAD).sweepRecoverable(ALL, null).get(0);
        assertEquals(1, c.attemptsAfterClaim);
        // 同一条义务又被记了一次（平台重投 webhook）⇒ 预算归零，但仍只有一行
        ledger.recordObligation("oR", "c1", "webhook", "c1", "同一轮重放同一条");
        assertEquals(1, ledger.totalRows());
        assertEquals(1, ledger.idsInState(DeliveryLedger.PENDING).size());
    }

    // ===== 5. 保留策略 =====

    @Test
    public void pruneDropsOldTerminalRowsAndCapsTotal() throws Exception {
        long now = System.currentTimeMillis();
        ledger.recordObligation("gone", "c1", "webhook", "c1", "很久以前的已完成");
        ledger.markDelivered("gone");
        forceRow("gone", DeliveryLedger.DELIVERED, 1, now, now - DeliveryLedger.RETENTION_MILLIS - 1000L);
        ledger.prune(Long.valueOf(now));
        assertEquals(0, ledger.idsInState(DeliveryLedger.DELIVERED).size());
        // 新鲜行不动
        ledger.recordObligation("keep", "c1", "webhook", "c1", "刚记的");
        ledger.prune(Long.valueOf(now));
        assertEquals(Arrays.asList("keep"), ledger.idsInState(DeliveryLedger.PENDING));
    }

    // ===== 6. 进程存活判据本体 =====

    @Test
    public void osLivenessKnowsItselfAndRejectsBogusPids() {
        DeliveryLedger.OsProcessLiveness probe = new DeliveryLedger.OsProcessLiveness();
        long pid = DeliveryLedger.ownPid();
        assertTrue("本进程必须判活", probe.alive(pid, DeliveryLedger.ownStartedAt()));
        assertTrue("pid 对得上但启动时刻没记 ⇒ 也判活（保守）", probe.alive(pid, null));
        assertFalse("pid<=0 一律死", probe.alive(0L, null));
        assertFalse("pid<=0 一律死", probe.alive(-1L, Long.valueOf(1L)));
        assertFalse("查无此 pid ⇒ 死", probe.alive(4_000_000_001L, Long.valueOf(1L)));
        assertTrue("epoch 毫秒量级远大于 /proc tick 数",
                DeliveryLedger.OsProcessLiveness.TICK_VS_MILLIS > 1000L);
    }

    @Test
    public void pidReuseIsDetectedByStartStampMismatch() throws Exception {
        // 「pid 存在但启动时刻对不上」在本机造不出第二个现场 ⇒ 拿一个真·别的进程当宿主：
        // 子进程自己把 pid 打出来（不用反射 JDK 内部类），先验真活，再验「同 pid 不同启动时刻 = 死」
        Process p = new ProcessBuilder("/bin/sh", "-c", "echo $$; sleep 4").start();
        DeliveryLedger.OsProcessLiveness probe = new DeliveryLedger.OsProcessLiveness();
        try {
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getInputStream(), "UTF-8")).readLine();
            assertNotNull("子进程没报出自己的 pid", line);
            long childPid = Long.parseLong(line.trim());
            assertTrue("测到的 pid 不能是自己", childPid != DeliveryLedger.ownPid());
            Long stamp = DeliveryLedger.OsProcessLiveness.startStampOf(childPid);
            assertNotNull("本机读不出该 pid 的启动时刻 ⇒ 判据退化成只看 pid 在不在", stamp);
            assertTrue("同 pid + 同启动时刻 ⇒ 活", probe.alive(childPid, stamp));
            assertFalse("同 pid、启动时刻差 1 小时 ⇒ pid 已被复用，判原主已死",
                    probe.alive(childPid, Long.valueOf(stamp.longValue() - 3_600_000L)));
        } finally {
            p.destroy();
        }
    }

    @Test
    public void deadProcessIsNeverSeenAsAlive() throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", "echo $$").start();
        String line = new java.io.BufferedReader(new java.io.InputStreamReader(
                p.getInputStream(), "UTF-8")).readLine();
        p.waitFor();
        long deadPid = Long.parseLong(line.trim());
        assertFalse("已经退出的进程必须判死（否则它的义务永远没人认领）",
                new DeliveryLedger.OsProcessLiveness().alive(deadPid,
                        DeliveryLedger.OsProcessLiveness.startStampOf(deadPid)));
    }

    @Test
    public void lstartParsingIsLenient() {
        assertNotNull(DeliveryLedger.OsProcessLiveness.parseLstart("Fri Sep 25 21:05:15 2026"));
        assertNull(DeliveryLedger.OsProcessLiveness.parseLstart("不是时间"));
        assertNull(DeliveryLedger.OsProcessLiveness.parseLstart("   "));
        assertNull(DeliveryLedger.OsProcessLiveness.parseLstart(null));
    }

    // ===== 7. 观察面 =====

    @Test
    public void debugRowsSummarisesForStatusAndLogs() {
        ledger.recordObligation("oDbg", "c1", "webhook", "c1", "正文");
        ledger.markAttempting("oDbg");
        String rows = ledger.debugRows(5);
        assertTrue(rows, rows.contains("oDbg"));
        assertTrue(rows, rows.contains(DeliveryLedger.ATTEMPTING));
        assertEquals("正文", ledger.contentOf("oDbg"));
        assertNull(ledger.contentOf("不存在"));
        Map<String, Integer> counts = ledger.countsByState();
        for (String state : new String[] {DeliveryLedger.PENDING, DeliveryLedger.ATTEMPTING,
                DeliveryLedger.DELIVERED, DeliveryLedger.FAILED, DeliveryLedger.ABANDONED}) {
            assertNotNull("五档恒有键: " + state, counts.get(state));
        }
        assertNotNull(ledger.counters());
    }

    // ===== helpers =====

    private int attemptsOf(final String oid) throws Exception {
        Integer n = SqliteTx.read(db, CFG, new SqliteTx.Counters(), new SqliteTx.Body<Integer>() {
            @Override public Integer run(Connection c) throws SQLException {
                PreparedStatement ps = c.prepareStatement(
                        "SELECT attempts FROM " + DeliveryLedger.TABLE + " WHERE obligation_id=?");
                try {
                    ps.setString(1, oid);
                    java.sql.ResultSet rs = ps.executeQuery();
                    return rs.next() ? Integer.valueOf(rs.getInt(1)) : Integer.valueOf(-1);
                } finally {
                    ps.close();
                }
            }
        });
        return n == null ? -1 : n.intValue();
    }

    /** 直接改行，用来造「预算烧光 / 行太老」这种跑不出来的现场。 */
    private void forceRow(final String oid, final String state, final int attempts, final long createdAt)
            throws Exception {
        forceRow(oid, state, attempts, createdAt, createdAt);
    }

    private void forceRow(final String oid, final String state, final int attempts,
            final long createdAt, final long updatedAt) throws Exception {
        SqliteTx.write(db, CFG, new SqliteTx.Counters(), new SqliteTx.Body<Object>() {
            @Override public Object run(Connection c) throws SQLException {
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + DeliveryLedger.TABLE + " SET state=?, attempts=?, created_at=?,"
                                + " updated_at=? WHERE obligation_id=?");
                try {
                    ps.setString(1, state);
                    ps.setInt(2, attempts);
                    ps.setLong(3, createdAt);
                    ps.setLong(4, updatedAt);
                    ps.setString(5, oid);
                    ps.executeUpdate();
                } finally {
                    ps.close();
                }
                return null;
            }
        });
    }
}
