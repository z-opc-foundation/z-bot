package com.zifang.z.bot.delegate;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P27 靶子一：投递有上限、且上限<b>可取证</b>。
 *
 * <p>锚点是 hermes {@code tools/async_delegation.py:84} 的 {@code _MAX_DELIVERY_ATTEMPTS = 8}。
 * 三件必须成立的事：① 一次 claim 烧一次尝试数；② 没 claim 过不许 ack（P16 那个
 * "投给拉模式控制台却被记成 ok" 的同型口子）；③ 烧到顶收敛成终态 {@code DROPPED}，
 * 重启恢复只捞 {@code PENDING}，所以没人接的完成事件不会永远重放。</p>
 */
public class DelegationDeliveryTest {

    private File root;
    private DelegationLedger ledger;
    private DelegationDelivery delivery;

    @Before
    public void setUp() {
        root = new File(tmpRoot(), "live");
        ledger = new DelegationLedger(root);
        delivery = new DelegationDelivery(ledger);
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File tmpRoot() {
        return new File(folder.getRoot(), "delegation-delivery");
    }

    // ===== 锚点 =====

    @Test
    public void attemptCapIsEightLikeTheHermesAnchor() {
        assertEquals(8, DelegationDelivery.MAX_DELIVERY_ATTEMPTS);
        assertEquals(300_000L, DelegationDelivery.CLAIM_LEASE_MILLIS);
    }

    @Test
    public void constructorRefusesANullLedger() {
        try {
            new DelegationDelivery(null);
            throw new AssertionError("null 台账必须拒收");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ===== claim 烧尝试数 =====

    @Test
    public void eachClaimBurnsExactlyOneAttempt() {
        readyResult("bg-1");
        assertEquals("没 claim 之前尝试数是 0，判词是 PENDING(0/8)", "PENDING(0/8)", delivery.describe("bg-1"));
        for (int i = 1; i <= 5; i++) {
            String token = delivery.claim("bg-1", "cli");
            assertNotNull("第 " + i + " 次 claim 失败", token);
            assertEquals(i, delivery.attempts("bg-1"));
            assertEquals(DeliveryState.CLAIMED, delivery.stateOf("bg-1"));
            assertTrue("release 必须动账: " + i, delivery.release("bg-1", token));
            assertEquals(DeliveryState.PENDING, delivery.stateOf("bg-1"));
            assertEquals("release 不该退尝试数", i, delivery.attempts("bg-1"));
        }
        assertEquals("PENDING(5/8)", delivery.describe("bg-1"));
    }

    @Test
    public void missingRowCannotBeClaimed() {
        assertNull(delivery.claim("nope", "cli"));
        assertEquals(-1, delivery.attempts("nope"));
        assertNull(delivery.stateOf("nope"));
        assertEquals("MISSING(-/1)", delivery.describe("nope"));
    }

    // ===== 没 claim 就想记成功 ⇒ 大声失败 =====

    @Test
    public void ackWithoutClaimFailsLoudly() {
        readyResult("bg-2");
        try {
            delivery.complete("bg-2", "随便编的凭证");
            throw new AssertionError("PENDING 行上的 ack 必须抛，不许静默返回 false");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("PENDING"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("bg-2"));
        }
        assertEquals("拒了 ack 之后账上仍是 PENDING", DeliveryState.PENDING, delivery.stateOf("bg-2"));
        assertEquals(0, delivery.attempts("bg-2"));
    }

    @Test
    public void staleOrForeignTokenCannotAck() {
        readyResult("bg-3");
        String token = delivery.claim("bg-3", "cli");
        assertFalse("凭证不对不能 ack", delivery.complete("bg-3", token + "-but-wrong"));
        assertEquals(DeliveryState.CLAIMED, delivery.stateOf("bg-3"));
        assertTrue("凭证对得上才 ack", delivery.complete("bg-3", token));
        assertEquals(DeliveryState.DELIVERED, delivery.stateOf("bg-3"));
    }

    // ===== 上限：烧完收敛成 DROPPED =====

    @Test
    public void exhaustedAttemptsConvergeToTerminalDropped() {
        readyResult("bg-4");
        for (int i = 1; i <= DelegationDelivery.MAX_DELIVERY_ATTEMPTS; i++) {
            String token = delivery.claim("bg-4", "pull-console");
            assertNotNull("第 " + i + " 次 claim 应当还能领", token);
            assertTrue(delivery.release("bg-4", token));
            if (i < DelegationDelivery.MAX_DELIVERY_ATTEMPTS) {
                assertEquals("未到上限前必须回 PENDING 等下一个消费者",
                        DeliveryState.PENDING, delivery.stateOf("bg-4"));
            }
        }
        assertEquals("DROPPED(8/8)", delivery.describe("bg-4"));
        assertEquals(DeliveryState.DROPPED, delivery.stateOf("bg-4"));
        assertNull("到顶之后 claim 必须直接领不到", delivery.claim("bg-4", "pull-console"));
        assertFalse("到顶之后 drop 是多余的", delivery.drop("bg-4", "再来一次"));
        assertEquals(DelegationDelivery.MAX_DELIVERY_ATTEMPTS, delivery.attempts("bg-4"));
        List<String> tail = ledger.tail("bg-4", 100);
        int droppedLines = 0;
        for (String line : tail) {
            if (line.contains("|delegate.result_dropped|")) {
                droppedLines++;
            }
        }
        assertEquals("终态判词只许落一次", 1, droppedLines);
    }

    /** 上限的可取证性：换一个新台账实例（= 进程重启）从盘上读，计数与终态都在。 */
    @Test
    public void theCapEvidenceSurvivesARestart() throws Exception {
        readyResult("bg-5");
        for (int i = 0; i < DelegationDelivery.MAX_DELIVERY_ATTEMPTS; i++) {
            String token = delivery.claim("bg-5", "cli");
            assertNotNull(token);
            delivery.release("bg-5", token);
        }
        DelegationLedger reopened = new DelegationLedger(root);
        DelegationDelivery fresh = new DelegationDelivery(reopened);
        DelegationLedger.Entry e = reopened.load("bg-5");
        assertNotNull(e);
        assertEquals(DelegationDelivery.MAX_DELIVERY_ATTEMPTS, e.deliveryAttempts);
        assertEquals(DeliveryState.DROPPED, fresh.stateOf("bg-5"));
        assertEquals("重启后 attempt 计数不许从 0 重来", 8, fresh.attempts("bg-5"));
        String json = new String(readBytes(new File(root, "bg-5/state.json")), "UTF-8");
        assertTrue("state.json 里必须写着 attempts=8:\n" + json,
                json.contains("\"delivery_attempts\" : 8") || json.contains("\"delivery_attempts\":8"));
        assertTrue(json, json.contains("\"delivery_state\" : \"DROPPED\"")
                || json.contains("\"delivery_state\":\"DROPPED\""));
    }

    @Test
    public void droppedRowsAreNotOfferedForRestoreButPendingRowsAre() {
        readyResult("bg-pending");
        assertEquals(1, delivery.undeliveredTerminalResults().size());
        assertEquals("bg-pending", delivery.undeliveredTerminalResults().get(0).id);

        readyResult("bg-dropped");
        assertTrue(delivery.drop("bg-dropped", "对端会话已被用户显式结束"));
        readyResult("bg-delivered");
        String token = delivery.claim("bg-delivered", "cli");
        assertTrue(delivery.complete("bg-delivered", token));

        List<DelegationLedger.Entry> restore = delivery.undeliveredTerminalResults();
        assertEquals("只有 PENDING 参与恢复（dropped/delivered 都不许重放）: " + ids(restore),
                1, restore.size());
        assertEquals("bg-pending", restore.get(0).id);
    }

    // ===== 租约 =====

    @Test
    public void claimLeaseBlocksOtherConsumersUntilItExpires() {
        readyResult("bg-6");
        long now = 1_700_000_000_000L;
        String mine = delivery.claim("bg-6", "cli", now);
        assertNotNull(mine);
        assertNull("租约内别人抢不走", delivery.claim("bg-6", "gateway", now + 1000L));
        assertEquals("抢不走就不该烧尝试数", 1, delivery.attempts("bg-6"));
        String stolen = delivery.claim("bg-6", "gateway", now + DelegationDelivery.CLAIM_LEASE_MILLIS + 1L);
        assertNotNull("租约过期后允许偷走", stolen);
        assertEquals(2, delivery.attempts("bg-6"));
        assertFalse("旧凭证被偷走之后不能再 ack", delivery.complete("bg-6", mine));
        assertTrue(delivery.complete("bg-6", stolen));
    }

    // ===== 终态之后再动账 =====

    @Test
    public void deliveredIsTerminal() {
        readyResult("bg-7");
        String token = delivery.claim("bg-7", "cli");
        assertTrue(delivery.complete("bg-7", token));
        assertEquals("DELIVERED(1/8)", delivery.describe("bg-7"));
        assertNull(delivery.claim("bg-7", "cli"));
        assertFalse(delivery.drop("bg-7", "x"));
        assertFalse(delivery.release("bg-7", token));
        assertEquals(1, delivery.attempts("bg-7"));
    }

    @Test
    public void explicitDropFromPendingIsHonouredOnce() {
        readyResult("bg-8");
        assertTrue(delivery.drop("bg-8", "no consumer anymore"));
        assertEquals(DeliveryState.DROPPED, delivery.stateOf("bg-8"));
        assertFalse(delivery.drop("bg-8", "重复"));
        assertEquals("显式丢弃不烧尝试数", 0, delivery.attempts("bg-8"));
    }

    @Test
    public void summaryCountsEveryDeliveryState() {
        readyResult("s-pending");
        readyResult("s-delivered");
        String t = delivery.claim("s-delivered", "cli");
        delivery.complete("s-delivered", t);
        readyResult("s-dropped");
        delivery.drop("s-dropped", "x");
        readyResult("s-claimed");
        delivery.claim("s-claimed", "cli");
        String s = delivery.summary();
        assertTrue(s, s.contains("delegations=4"));
        assertTrue(s, s.contains("pending=1"));
        assertTrue(s, s.contains("delivered=1"));
        assertTrue(s, s.contains("dropped=1"));
        assertTrue(s, s.contains("claimed=1"));
        assertTrue(s, s.contains("maxAttempts=1/8"));
    }

    @Test
    public void summaryOnEmptyLedgerIsHonest() {
        assertEquals("delegations=0 pending=0 claimed=0 delivered=0 dropped=0 maxAttempts=0/8",
                delivery.summary());
        assertEquals(0, delivery.undeliveredTerminalResults().size());
    }

    // ===== helpers =====

    /** 造一条"子代理已收工、结果在盘上等人来拿"的现场。 */
    private void readyResult(String id) {
        DelegationLedger.Entry e = ledger.create(id, "task " + id, 0, "lbl", null);
        ledger.advance(e, DelegateEvent.TASK_SPAWNED, "起跑");
        e.reply = "reply " + id;
        ledger.advance(e, DelegateEvent.TASK_COMPLETED, "收工");
        assertEquals("收工不等于送达", DeliveryState.PENDING, e.delivery);
    }

    private static List<String> ids(List<DelegationLedger.Entry> es) {
        List<String> out = new java.util.ArrayList<String>();
        for (DelegationLedger.Entry e : es) {
            out.add(e.id + "/" + e.delivery);
        }
        return out;
    }

    private static byte[] readBytes(File f) {
        try {
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
