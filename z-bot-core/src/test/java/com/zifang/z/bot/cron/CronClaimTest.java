package com.zifang.z.bot.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P17 的三条新守卫：先落账再跑（{@link CronScheduler#claimDispatch}，红线 8）、
 * 心跳保鲜（{@link CronScheduler#heartbeatRunClaim}）、jobs.json 双层锁 + 重入计数。
 *
 * <p><b>时间一律走注入的假时钟</b>：认领过期是 {@code 0 <= age < ttl} 的时间语义，
 * 拿真墙钟测它就得 sleep 到 ttl 量级 —— 本仓 {@code PairingServiceTest} 正是靠 200ms TTL
 * 撞真实墙钟红过的。这里 ttl=1000ms 由测试一格一格拨出来，全程不睡。</p>
 *
 * <p>唯一碰真线程的是 {@link #executeBeatsHeartbeatWhileRunnerIsStillGoing}：它只问
 * "跑着的时候确实有线程在续期"，用<b>有界条件等待</b>（最多 3s，一到就返回）而非固定 sleep。</p>
 */
public class CronClaimTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TTL = 1000L;
    /** 假时钟起点：写死的时刻，与被测进程的启动时刻无关。 */
    private static final Instant T0 = Instant.parse("2026-09-25T12:00:00Z");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cronDir;
    /** 两个实例共用一个时钟源，否则"谁的认领更新"没有共同参照。 */
    private AtomicReference<Instant> fakeNow;

    @Before
    public void setUp() throws Exception {
        cronDir = tmp.newFolder("cron");
        fakeNow = new AtomicReference<Instant>(T0);
    }

    /** 投递口塞一个吞掉的，免得单测往标准输出刷任务结果。 */
    private static final CronSink SINK = new CronSink();

    private static final class CronSink implements CronDelivery {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String deliver(CronJob job, String text) {
            calls.incrementAndGet();
            return null;
        }
    }

    private CronScheduler scheduler(CronScheduler.TaskRunner runner) {
        return new CronScheduler(cronDir, 60, runner)
                .clock(fakeNow::get)
                .runClaimTtlMillis(TTL)
                .heartbeatMillis(10L)
                .setDelivery(SINK);
    }

    private CronScheduler quietScheduler() {
        return scheduler(prompt -> "ok");
    }

    private void advance(long millis) {
        fakeNow.set(fakeNow.get().plusMillis(millis));
    }

    /** 直接读盘上的 jobs.json —— 证明"账"真落了盘，而不是只改了内存里那一份。 */
    private List<CronJob> readDisk() throws Exception {
        File f = new File(cronDir, "jobs.json");
        if (!f.isFile()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.asList(JSON.readValue(f, CronJob[].class));
    }

    private static CronJob byId(List<CronJob> jobs, String id) {
        for (CronJob j : jobs) {
            if (j.id.equals(id)) {
                return j;
            }
        }
        return null;
    }

    // ===== 先落账再跑（红线 8） =====

    @Test
    public void claimIsOnDiskBeforeTheRunnerRuns() throws Exception {
        final AtomicReference<String> seenAtRunTime = new AtomicReference<String>("(没跑)");
        CronScheduler s = scheduler(prompt -> {
            try {
                List<CronJob> disk = readDisk();
                CronJob j = byId(disk, disk.isEmpty() ? "" : disk.get(0).id);
                seenAtRunTime.set(j == null ? "盘上没有这条"
                        : j.runClaim == null ? "认领没落盘" : "claim=" + j.runClaim.by);
            } catch (Exception e) {
                seenAtRunTime.set("读盘失败 " + e);
            }
            return "done";
        });
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        assertEquals("跑副作用之前，盘上就得写着这条归谁在跑", "claim=" + s.ownerId(), seenAtRunTime.get());
    }

    @Test
    public void oneShotQuotaIsOnDiskBeforeTheRunnerRuns() throws Exception {
        final AtomicInteger dispatchesSeenAtRunTime = new AtomicInteger(-1);
        CronScheduler s = scheduler(prompt -> {
            try {
                List<CronJob> disk = readDisk();
                CronJob j = byId(disk, disk.isEmpty() ? "" : disk.get(0).id);
                dispatchesSeenAtRunTime.set(j == null ? -2 : j.dispatches);
            } catch (Exception e) {
                dispatchesSeenAtRunTime.set(-3);
            }
            return "done";
        });
        CronJob j = s.add("once", "p", "once 2020-01-01T00:00:00Z");
        s.execute(j);
        assertEquals("一次性任务的额度必须在跑之前就记上，掉电也不会复活成没跑过",
                1, dispatchesSeenAtRunTime.get());
    }

    @Test
    public void oneShotIsNotRerunAfterARestart() {
        final AtomicInteger runs = new AtomicInteger();
        CronScheduler a = scheduler(prompt -> {
            runs.incrementAndGet();
            return "done";
        });
        CronJob j = a.add("once", "p", "once 2020-01-01T00:00:00Z");
        a.execute(j);
        assertEquals(1, runs.get());

        // 重启后的进程：账还在（这里靠"已被摘除"这条路径），同样的 due 判定不得再跑一回
        CronScheduler b = scheduler(prompt -> {
            runs.incrementAndGet();
            return "done-again";
        });
        assertEquals(0, b.list().size());
        b.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(3600));
        assertEquals("一次性任务被重跑了一次", 1, runs.get());
    }

    @Test
    public void tickDoesNotRerunAOneShotThatAnotherProcessAlreadyFinished() {
        // 双实例同刻竞争的进程内版本（E2E 里用两个真 JVM 再跑一遍）：
        // b 启动时把这条一次性任务搬进了自己的内存，a 先跑完并把它从盘上摘掉；
        // b 的下一拍如果看内存里那份旧单子，就会拿着"库里已经没有账"的 job 去认领，
        // 撞上 claimDispatch 的"无账可落，按原样放行"分支 —— 于是又跑一遍。
        final AtomicInteger runs = new AtomicInteger();
        CronScheduler.TaskRunner countAndGo = prompt -> {
            runs.incrementAndGet();
            return "x";
        };
        CronScheduler a = scheduler(countAndGo);
        a.add("once", "p", "once 2020-01-01T00:00:00Z");
        CronScheduler b = scheduler(countAndGo);
        assertEquals("b 启动时确实把这条搬进了自己的内存", 1, b.list().size());

        java.time.ZonedDateTime longAgo = java.time.ZonedDateTime.now().minusSeconds(3600);
        a.tickWithOffset(longAgo);
        assertEquals(1, runs.get());
        assertEquals("跑完就该从盘上摘掉", 0, a.list().size());

        b.tickWithOffset(longAgo);
        assertEquals("隔壁已经跑完并摘除的一次性任务，被 b 用过期内存副本又跑了一遍",
                1, runs.get());
    }

    @Test
    public void tickStillSeesJobsThatAreReallyOnDisk() {
        // 上一条的反向保险：改成"读盘"之后，正常到点的任务必须照跑（别把调度器读哑）。
        final AtomicInteger runs = new AtomicInteger();
        CronScheduler a = scheduler(prompt -> {
            runs.incrementAndGet();
            return "x";
        });
        a.add("t", "p", "every 5s");
        CronScheduler b = scheduler(countingRunner(runs));
        b.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(3600));
        assertEquals(1, runs.get());
        assertEquals("ok", b.list().get(0).lastResult);
    }

    private CronScheduler.TaskRunner countingRunner(final AtomicInteger runs) {
        return prompt -> {
            runs.incrementAndGet();
            return "ok";
        };
    }

    @Test
    public void claimOfFreshForeignOwnerBlocksDispatch() {
        CronScheduler a = quietScheduler();
        CronScheduler b = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertFalse(a.ownerId().equals(b.ownerId()));
        assertTrue(a.claimDispatch(j.id));
        assertFalse("别人正拿着还新鲜的认领，第二家不能也跑", b.claimDispatch(j.id));
    }

    @Test
    public void claimOfExpiredForeignOwnerIsTakenOver() {
        CronScheduler a = quietScheduler();
        CronScheduler b = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertTrue(a.claimDispatch(j.id));
        advance(TTL + 1);
        assertTrue("认领过期 = 那个进程死了；接管不了就等于永久卡死", b.claimDispatch(j.id));
        assertTrue(byId(b.list(), j.id).runClaim.heldBy(b.ownerId()));
    }

    @Test
    public void futureStampedClaimIsNotTreatedAsLive() {
        // 对端时钟超前（跨重启的时钟漂移）时，负年龄若算"新鲜"就会把任务永久楔死
        CronRunClaim fromTheFuture = new CronRunClaim("pid@1/abc", T0.plusSeconds(60));
        assertFalse(fromTheFuture.isLive(T0, TTL * 1000));
        assertTrue("同一张认领按它自己的时刻看是活的（排除'一律判死'的假通过）",
                fromTheFuture.isLive(T0.plusSeconds(60), TTL));

        CronScheduler a = quietScheduler();
        CronScheduler b = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertTrue(a.claimDispatch(j.id));
        advance(TTL + 500);
        assertTrue(b.claimDispatch(j.id));
    }

    @Test
    public void corruptClaimTimestampIsTreatedAsDead() {
        CronRunClaim broken = new CronRunClaim("pid@1/abc", null);
        assertFalse(broken.isLive(T0, TTL));
        broken.at = "not-a-time";
        assertFalse(broken.isLive(T0, TTL));
        broken.at = T0.toString();
        assertTrue(broken.isLive(T0.plusMillis(TTL - 1), TTL));
        assertFalse("边界：age == ttl 已经不算新鲜", broken.isLive(T0.plusMillis(TTL), TTL));
    }

    @Test
    public void claimOfUnknownJobProceedsWithoutLedger() {
        CronScheduler s = quietScheduler();
        assertTrue("库里没这条：无账可落，按原样放行（不猜、也不静默吞）",
                s.claimDispatch("cron_notthere"));
    }

    @Test
    public void claimAndDispatchQuotaSurviveARestart() throws Exception {
        CronScheduler a = quietScheduler();
        CronJob j = a.add("once", "p", "once 2020-01-01T00:00:00Z");
        assertTrue(a.claimDispatch(j.id));
        CronJob onDisk = byId(readDisk(), j.id);
        assertNotNull(onDisk);
        assertEquals(1, onDisk.dispatches);
        assertNotNull(onDisk.runClaim);
        CronScheduler restarted = quietScheduler();
        assertFalse("新实例只看盘就该知道这一枪有人开过了", restarted.claimDispatch(j.id));
    }

    @Test
    public void oneShotQuotaRemovesTheStaleJobInsteadOfRerunningIt() throws Exception {
        CronScheduler a = quietScheduler();
        CronJob j = a.add("once", "p", "once 2020-01-01T00:00:00Z");
        assertTrue(a.claimDispatch(j.id));
        advance(TTL + 1);   // 跑到一半进程死了：认领没清、也没收尾
        CronScheduler restarted = quietScheduler();
        assertFalse("额度用完就该判 false", restarted.claimDispatch(j.id));
        assertEquals("陈旧的一次性任务要摘掉，而不是每个 tick 都重新 due",
                0, restarted.list().size());
        assertNull(byId(readDisk(), j.id));
    }

    @Test
    public void oneShotDispatchLimitIsOne() {
        assertEquals(1, CronJob.ONESHOT_DISPATCH_LIMIT);
    }

    // ===== 心跳保鲜 =====

    @Test
    public void heartbeatKeepsTheClaimLivePastItsTtl() {
        CronScheduler a = quietScheduler();
        CronScheduler b = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertTrue(a.claimDispatch(j.id));

        advance(900);
        assertTrue(a.heartbeatRunClaim(j.id, a.ownerId()));

        advance(600);   // 距上次心跳只 600ms
        assertFalse("心跳续过期的认领仍然有效，第二家必须让路", b.claimDispatch(j.id));

        advance(TTL + 1);  // 心跳停了 = 那个进程真死了
        assertTrue("心跳断了就该能被接管", b.claimDispatch(j.id));
    }

    @Test
    public void heartbeatAfterATakeoverIsRejected() {
        CronScheduler a = quietScheduler();
        CronScheduler b = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertTrue(a.claimDispatch(j.id));
        advance(TTL + 1);
        assertTrue(b.claimDispatch(j.id));           // b 接管了这条认领
        String ownersStamp = b.list().get(0).runClaim.at;
        advance(10);
        assertFalse("睡了一觉的旧跑者不能给已被接管的认领续命",
                a.heartbeatRunClaim(j.id, a.ownerId()));
        assertFalse("陌生人更不行", b.heartbeatRunClaim(j.id, "whoever"));
        assertEquals("a 的失败续期不得改动现任主人的时间戳",
                ownersStamp, b.list().get(0).runClaim.at);
    }

    @Test
    public void heartbeatOfUnclaimedJobIsRejected() {
        CronScheduler a = quietScheduler();
        CronJob j = a.add("t", "p", "every 5s");
        assertFalse("没认领过就没有可续的东西", a.heartbeatRunClaim(j.id, a.ownerId()));
        assertNull(a.list().get(0).runClaim);
    }

    @Test
    public void executeBeatsHeartbeatWhileRunnerIsStillGoing() throws Exception {
        SINK.calls.set(0);
        final AtomicReference<String> claimAtRunStart = new AtomicReference<String>();
        final AtomicBoolean sawRenewal = new AtomicBoolean();
        final CronScheduler[] holder = new CronScheduler[1];
        // 这条不注入假时钟：心跳线程用的是真实节拍，判据是"跑着的时候 at 被改过"
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> {
            CronScheduler self = holder[0];
            claimAtRunStart.set(self.list().get(0).runClaim.at);
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline && !sawRenewal.get()) {
                if (!claimAtRunStart.get().equals(self.list().get(0).runClaim.at)) {
                    sawRenewal.set(true);
                } else {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return "ok";
        }).heartbeatMillis(10L).setDelivery(SINK);
        holder[0] = s;
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        assertTrue("长跑的 runner 没人续期，认领会在 ttl 之后被别人抢走", sawRenewal.get());
    }

    // ===== 收尾与投递结论 =====

    @Test
    public void finishRunClearsTheClaimAndMarksDeliveryOk() {
        CronScheduler s = quietScheduler();
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        CronJob after = s.list().get(0);
        assertNull("跑完必须清账，否则下次 tick 会被自己的残留认领挡住", after.runClaim);
        assertEquals("ok", after.lastDelivery);
        assertEquals("ok", after.lastResult);
        assertEquals("非一次性任务不占投递额度", 0, after.dispatches);
    }

    @Test
    public void deliveryFailureIsRecordedButTheResultSurvives() {
        CronScheduler s = quietScheduler();
        s.setDelivery(new CronDelivery() {
            @Override
            public String deliver(CronJob job, String text) {
                return "channel down";
            }
        });
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        CronJob after = s.list().get(0);
        assertEquals("channel down", after.lastDelivery);
        assertEquals("投递失败不能把执行结果一起吞掉", "ok", after.lastResult);
    }

    @Test
    public void deliveryThrowingIsCaughtIntoTheLedger() {
        CronScheduler s = quietScheduler();
        s.setDelivery(new CronDelivery() {
            @Override
            public String deliver(CronJob job, String text) {
                throw new RuntimeException("send blew up");
            }
        });
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        assertTrue(s.list().get(0).lastDelivery.startsWith("delivery threw:"));
    }

    @Test
    public void emptyResultIsNotDeliveredAtAll() {
        CronScheduler s = scheduler(prompt -> "   ");
        int before = SINK.calls.get();
        CronJob j = s.add("t", "p", "every 5s");
        s.execute(j);
        assertEquals("空输出没有可投的东西", before, SINK.calls.get());
        assertEquals("空输出不能记成 ok —— 那是一条都没发出去",
                "skipped: 没有可投递的输出", s.list().get(0).lastDelivery);
    }

    // ===== jobs.json 双层锁 + 重入计数 =====

    @Test
    public void nestedStoreCallsTakeTheFlockOnce() {
        final CronScheduler s = quietScheduler();
        final int flockBefore = s.flockAcquisitions();
        final AtomicInteger depthAtNest = new AtomicInteger(-1);
        final int[] flockAtNest = new int[]{-1};
        String out = s.withStore(new CronScheduler.LockedCall<String>() {
            @Override
            public String call(final CronScheduler.StoreTxn tx) {
                assertEquals(1, s.jobsLockDepth());
                s.withStore(new CronScheduler.LockedCall<String>() {
                    @Override
                    public String call(CronScheduler.StoreTxn inner) {
                        depthAtNest.set(s.jobsLockDepth());
                        flockAtNest[0] = s.flockAcquisitions() - flockBefore;
                        return "inner";
                    }
                });
                assertEquals("嵌套出来深度要还回去", 1, s.jobsLockDepth());
                return "outer";
            }
        });
        assertEquals("outer", out);
        assertEquals(2, depthAtNest.get());
        assertEquals("嵌套时绝不能二次 tryLock（同 JVM 里那会直接抛 OverlappingFileLockException）",
                1, flockAtNest[0]);
        assertEquals(0, s.jobsLockDepth());
        assertEquals("整段（外层 + 嵌套）只真抢了一次 flock", 1, s.flockAcquisitions() - flockBefore);
        assertEquals(0, s.jobsLockDegradedCount());
    }

    @Test
    public void depthReturnsToZeroWhenTheBodyThrows() {
        final CronScheduler s = quietScheduler();
        final int flockBefore = s.flockAcquisitions();
        try {
            s.withJobsLock(new CronScheduler.VoidCall() {
                @Override
                public void run() {
                    throw new IllegalStateException("boom");
                }
            });
        } catch (IllegalStateException expected) {
            // 就是要它原样抛：锁必须在 finally 里还回去
        }
        assertEquals(0, s.jobsLockDepth());
        final AtomicBoolean gotIn = new AtomicBoolean();
        s.withJobsLock(new CronScheduler.VoidCall() {
            @Override
            public void run() {
                gotIn.set(s.jobsLockDepth() == 1);
            }
        });
        assertTrue("上一次异常把锁扣死了", gotIn.get());
        assertEquals(2, s.flockAcquisitions() - flockBefore);
    }

    @Test
    public void twoInstancesOnOneDirNeverDegradeTheirLock() throws Exception {
        // 同进程两个实例打同一个 cron 目录（gateway 自动装配那个 + 手工建那个）。
        // Java 的 FileLock 是<b>整进程</b>互斥的，所以进程内那把监视器必须按目录共享；
        // 挂在实例上时第二个不是"等锁"，而是当场抛 OverlappingFileLockException 并降级成只锁自己。
        final CronScheduler a = quietScheduler();
        final CronScheduler b = quietScheduler();
        final CountDownLatch aInside = new CountDownLatch(1);
        final CountDownLatch bAtTheDoor = new CountDownLatch(1);
        final AtomicBoolean aSawBWaiting = new AtomicBoolean();
        final AtomicBoolean aStillInside = new AtomicBoolean();
        final AtomicBoolean overlapped = new AtomicBoolean();

        Thread ta = new Thread(new Runnable() {
            @Override
            public void run() {
                a.withJobsLock(new CronScheduler.VoidCall() {
                    @Override
                    public void run() {
                        aStillInside.set(true);
                        aInside.countDown();
                        try {
                            bAtTheDoor.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        aSawBWaiting.set(bAtTheDoor.getCount() == 0);
                        try {
                            // 给 b 一点时间走到门口：监视器要是没共享，它就在这 50ms 里闯进来了
                            Thread.sleep(50L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        aStillInside.set(false);   // 出关键区前最后一步，之后锁才还回去
                    }
                });
            }
        }, "cron-lock-a");
        ta.start();
        assertTrue("a 进不去关键区", aInside.await(3, TimeUnit.SECONDS));

        // b 另起线程，在 a 攥着锁的时候来敲门：它必须<b>排队</b>，不是降级、更不是并行进来
        final CountDownLatch bDone = new CountDownLatch(1);
        Thread tb = new Thread(new Runnable() {
            @Override
            public void run() {
                bAtTheDoor.countDown();
                b.withJobsLock(new CronScheduler.VoidCall() {
                    @Override
                    public void run() {
                        overlapped.set(aStillInside.get());
                    }
                });
                bDone.countDown();
            }
        }, "cron-lock-b");
        tb.start();
        assertTrue("b 被永久挡在门外了", bDone.await(5, TimeUnit.SECONDS));
        ta.join(5000L);

        assertTrue("a 没等到 b 来敲门 ⇒ 这个用例根本没测到并发", aSawBWaiting.get());
        assertFalse("两个实例的关键区重叠了：监视器没按 cron 目录共享", overlapped.get());
        assertEquals(0, a.jobsLockDegradedCount());
        assertEquals(0, b.jobsLockDegradedCount());
        int taken = (a.flockAcquisitions() - 1) + (b.flockAcquisitions() - 1);   // 各减去构造器 load() 那次
        assertEquals("两边各真抢了一次 flock", 2, taken);
    }

    @Test
    public void everyDispatchGoesThroughBothLockLayers() {
        CronScheduler s = quietScheduler();
        CronJob j = s.add("t", "p", "every 5s");
        int before = s.flockAcquisitions();
        s.execute(j);
        assertTrue("execute 至少要走两次 jobs.json 关键区（认领落账 + 收尾回写）",
                s.flockAcquisitions() - before >= 2);
        assertTrue("per-job run.lock 要留下文件", new File(cronDir, j.id + ".run.lock").exists());
        assertTrue("跨进程 .jobs.lock 要留下文件", new File(cronDir, CronScheduler.JOBS_LOCK_FILE).exists());
        assertEquals(0, s.jobsLockDegradedCount());
    }
}
