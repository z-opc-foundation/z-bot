package com.zifang.z.bot.channel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Supervisor} 的两件事：
 * <ol>
 *   <li><b>监督重启</b> —— 干净退出不重启、崩了按退避重启、连崩到顶放弃、跑满健康窗口的崩溃算新账；</li>
 *   <li><b>跨进程熔断</b> —— 60 秒内第 3 次「带着待续跑的活启动」就跳过自动续跑；
 *       以及<b>陈旧实例锁自愈</b>（归属进程真死了才抢，活着就拒绝双开）。</li>
 * </ol>
 *
 * <p>退避单位在测试里调成 1ms（构造参数就是为此而开），否则这条测试要跑几分钟。</p>
 */
public class SupervisorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(5);
        }
    }

    private Supervisor fast(String folder) throws IOException {
        return new Supervisor(tmp.newFolder(folder), 1L, Supervisor.SUPERVISED_HEALTHY_MILLIS);
    }

    // ===== 1. 重启策略 =====

    @Test
    public void cleanExitIsNeverRestarted() throws Exception {
        Supervisor sup = fast("s1");
        final AtomicInteger runs = new AtomicInteger();
        Supervisor.Handle h = sup.spawn("watcher", new Supervisor.Task() {
            @Override public void run() {
                runs.incrementAndGet();          // 正常返回 = 有意关停 / 自我停用
            }
        });
        sleep(250);
        assertEquals("干净退出后重启就是 busy-spin", 1, runs.get());
        assertEquals(1, h.runCount());
        assertEquals(0, h.restartCount());
        assertFalse(h.isAbandoned());
        sup.shutdown();
    }

    @Test
    public void crashIsRestartedUntilItStopsCrashing() throws Exception {
        Supervisor sup = fast("s2");
        final AtomicInteger runs = new AtomicInteger();
        Supervisor.Handle h = sup.spawn("flaky", new Supervisor.Task() {
            @Override public void run() {
                if (runs.incrementAndGet() < 3) {
                    throw new IllegalStateException("第 " + runs.get() + " 次崩");
                }
            }
        });
        waitUntil(() -> runs.get() >= 3, 5000L);
        assertEquals("崩两次就该被拉起来第三次", 3, runs.get());
        assertEquals(2, h.restartCount());
        assertFalse(h.isAbandoned());
        assertNotNull(h.lastError());
        sleep(120);
        assertEquals("第三次干净退出 ⇒ 不再重启", 3, runs.get());
        sup.shutdown();
    }

    @Test
    public void restartOnCrashFalseLeavesTheTaskDead() throws Exception {
        Supervisor sup = fast("s3");
        final AtomicInteger runs = new AtomicInteger();
        Supervisor.Handle h = sup.spawn("once", new Supervisor.Task() {
            @Override public void run() {
                runs.incrementAndGet();
                throw new IllegalStateException("崩了但没人管");
            }
        }, false);
        sleep(250);
        assertEquals(1, runs.get());
        assertEquals(0, h.restartCount());
        assertFalse("不重启不等于放弃：这是调用方的选择", h.isAbandoned());
        sup.shutdown();
    }

    @Test
    public void rapidCrashesTripTheRestartCeiling() throws Exception {
        Supervisor sup = fast("s4");
        final AtomicInteger runs = new AtomicInteger();
        Supervisor.Handle h = sup.spawn("poison", new Supervisor.Task() {
            @Override public void run() {
                runs.incrementAndGet();
                throw new IllegalStateException("起来就死");
            }
        });
        waitUntil(h::isAbandoned, 5000L);
        assertTrue("必须认输", h.isAbandoned());
        assertEquals("1 次首跑 + " + Supervisor.MAX_SUPERVISED_RESTARTS + " 次重启",
                Supervisor.MAX_SUPERVISED_RESTARTS + 1, runs.get());
        assertEquals(Supervisor.MAX_SUPERVISED_RESTARTS, h.restartCount());
        sleep(200);
        assertEquals("放弃之后不许再偷偷拉起来", Supervisor.MAX_SUPERVISED_RESTARTS + 1, runs.get());
        sup.shutdown();
    }

    @Test
    public void crashesAfterTheHealthyWindowStartAFreshStreak() throws Exception {
        // 健康窗口压到 5ms：每轮跑 12ms 再崩 ⇒ 每次都算「健康过」⇒ 永不放弃（否则跑了几天的
        // 守护进程偶发崩几次就会被永久放弃，她在 NS 里踩过）
        Supervisor sup = new Supervisor(tmp.newFolder("s5"), 1L, 5L);
        final AtomicInteger runs = new AtomicInteger();
        Supervisor.Handle h = sup.spawn("long-lived", new Supervisor.Task() {
            @Override public void run() {
                runs.incrementAndGet();
                sleep(12);
                throw new IllegalStateException("跑满窗口后才崩");
            }
        });
        waitUntil(() -> runs.get() > Supervisor.MAX_SUPERVISED_RESTARTS + 2, 8000L);
        assertFalse("跑满健康窗口的崩溃不该攒成放弃", h.isAbandoned());
        assertTrue(h.restartCount() > Supervisor.MAX_SUPERVISED_RESTARTS);
        int seen = runs.get();
        sup.shutdown();
        sleep(120);
        assertEquals("shutdown 之后不再续命", seen, runs.get());
    }

    @Test
    public void handlesAreListedForStatus() throws Exception {
        Supervisor sup = fast("s6");
        sup.spawn("a", new Supervisor.Task() {
            @Override public void run() {
                sleep(30);
            }
        });
        sup.spawn("b", new Supervisor.Task() {
            @Override public void run() {
                sleep(30);
            }
        });
        sleep(10);
        List<Supervisor.Handle> hs = sup.handles();
        assertEquals(2, hs.size());
        assertEquals("a", hs.get(0).name());
        assertEquals("b", hs.get(1).name());
        assertTrue(sup.isRunning());
        sup.shutdown();
        assertFalse(sup.isRunning());
    }

    @Test
    public void backoffGrowsExponentiallyAndCaps() {
        Supervisor sup;
        try {
            sup = new Supervisor(tmp.newFolder("s7"), 1000L, Supervisor.SUPERVISED_HEALTHY_MILLIS);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1000L, sup.backoffMillis(0));
        assertEquals(2000L, sup.backoffMillis(1));
        assertEquals(4000L, sup.backoffMillis(2));
        assertEquals(60_000L, sup.backoffMillis(6));
        assertEquals("封顶之后不许再涨", 60_000L, sup.backoffMillis(99));
        assertEquals("负数当 0 处理", 1000L, sup.backoffMillis(-3));
    }

    // ===== 2. 跨进程熔断 =====

    @Test
    public void thirdInterruptedBootInSixtySecondsTripsTheBreaker() throws Exception {
        File configDir = tmp.newFolder("b1");
        long t0 = System.currentTimeMillis();
        // 三次启动 = 三个新进程 ⇒ 每次新建一个 Supervisor，读同一份账
        assertFalse("第 1 次带活启动不熔断",
                new Supervisor(configDir).checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0)));
        assertFalse("第 2 次还不熔断",
                new Supervisor(configDir).checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 1000L)));
        Supervisor third = new Supervisor(configDir);
        assertTrue("第 3 次必须跳过自动续跑",
                third.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 2000L)));
        assertTrue("熔断之后继续熔断",
                new Supervisor(configDir).checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 3000L)));
        assertEquals("窗口内的带活启动都记得", 4,
                third.recentInterruptedBoots(60, Long.valueOf(t0 + 3000L)).size());
        assertTrue(third.restartLoopStateFile().isFile());
    }

    @Test
    public void bootsOutsideTheWindowAreForgotten() throws Exception {
        File configDir = tmp.newFolder("b2");
        long t0 = System.currentTimeMillis();
        final Supervisor sup = new Supervisor(configDir);
        for (int i = 0; i < 2; i++) {
            assertFalse(sup.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + i * 1000L)));
        }
        // 窗口已经滑过去 ⇒ 账作废，第 3 次又回到「不熔断」
        assertFalse("60 秒之外不算数",
                sup.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 120_000L)));
        assertEquals("窗口内只剩这一次", 1, sup.recentInterruptedBoots(60, Long.valueOf(t0 + 120_000L)).size());
    }

    @Test
    public void cleanShutdownClearsTheBreakerDebt() throws Exception {
        File configDir = tmp.newFolder("b3");
        Supervisor sup = new Supervisor(configDir);
        long t0 = System.currentTimeMillis();
        sup.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0));
        sup.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 1L));
        sup.clearInterruptedBoots();
        assertEquals(0, sup.recentInterruptedBoots(60, Long.valueOf(t0 + 2L)).size());
        assertFalse("清账之后重新开始数",
                new Supervisor(configDir).checkAndRecordInterruptedBoot(3, 60, Long.valueOf(t0 + 2L)));
        Supervisor noProfile = new Supervisor((File) null);
        assertNull("无 profile 没有账本文件", noProfile.restartLoopStateFile());
        noProfile.clearInterruptedBoots(); // 不许炸
    }

    @Test
    public void corruptBreakerLedgerFailsOpen() throws Exception {
        File configDir = tmp.newFolder("b4");
        File gateway = new File(configDir, "gateway");
        assertTrue(gateway.mkdirs());
        Files.write(new File(gateway, "restart_loop.json").toPath(),
                "{\"boots\": [not, a, number]}".getBytes(StandardCharsets.UTF_8));
        assertFalse("坏掉的断路器绝不能楔死一个健康的网关",
                new Supervisor(configDir).checkAndRecordInterruptedBoot(3, 60, Long.valueOf(1L)));
    }

    @Test
    public void breakerWorksWithoutAProfile() {
        Supervisor sup = new Supervisor(null);
        assertNull(sup.restartLoopStateFile());
        assertFalse(sup.checkAndRecordInterruptedBoot(3, 60, Long.valueOf(1L)));
        sup.clearInterruptedBoots();
    }

    // ===== 3. 陈旧实例锁自愈 =====

    /** 起一个真进程、报出自己的 pid、然后退出 ⇒ 得到一个「确已死掉」的 pid。 */
    private static long spawnThenExit() throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", "echo $$").start();
        String line = new java.io.BufferedReader(new java.io.InputStreamReader(
                p.getInputStream(), "UTF-8")).readLine();
        p.waitFor();
        return Long.parseLong(line.trim());
    }

    /**
     * 起一个还活着的别的进程，并把<b>它自己的 pid</b>写进临时文件（不反射 JDK 内部类，
     * 也不靠 stdout 与 pid 的先后关系）。
     */
    private static final class AliveChild {
        final Process process;
        final long pid;

        AliveChild(Process process, long pid) {
            this.process = process;
            this.pid = pid;
        }

        void destroy() {
            process.destroy();
        }
    }

    private AliveChild spawnAlive(File pidFile, long seconds) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c",
                "echo $$ > '" + pidFile.getAbsolutePath() + "'; sleep " + seconds).start();
        long deadline = System.currentTimeMillis() + 5000L;
        while ((!pidFile.isFile() || pidFile.length() == 0L) && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertTrue("子进程没把 pid 写出来: " + pidFile, pidFile.isFile() && pidFile.length() > 0L);
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile.toPath()),
                StandardCharsets.UTF_8).trim());
        return new AliveChild(p, pid);
    }

    @Test
    public void staleLockIsTakenOverAndLiveLockRefusesADoubleBoot() throws Exception {
        File configDir = tmp.newFolder("L1");
        Supervisor sup = new Supervisor(configDir);
        // 先起一个真进程拿到它的 pid，等它退出 ⇒ 陈旧锁
        long deadPid = spawnThenExit();
        File lock = sup.instanceLockFile();
        assertTrue("测试自建锁文件要先造目录（生产路径由 tryAcquireInstanceLock 建）",
                lock.getParentFile().isDirectory() || lock.getParentFile().mkdirs());
        Files.write(lock.toPath(),
                ("{\"pid\":" + deadPid + ",\"started_at\":1}").getBytes(StandardCharsets.UTF_8));
        Supervisor.InstanceLock taken = sup.tryAcquireInstanceLock();
        assertNotNull("归属进程已死的锁必须自愈接管", taken);
        assertTrue(taken.isPersisted());
        assertEquals(DeliveryLedger.ownPid(), taken.pid());
        assertTrue("锁内容已换成本实例", new String(Files.readAllBytes(lock.toPath()), "UTF-8")
                .contains("\"pid\":" + DeliveryLedger.ownPid()));

        // 现在锁属于本进程（真活着）⇒ 第二个实例（判据用真 OS）必须拒绝再起一个
        Supervisor.InstanceLock second = new Supervisor(configDir).tryAcquireInstanceLock();
        assertNotNull("自己持有的锁不算被别人占（同 pid 视为自己）", second);
        // 换一个「别的活进程」写进锁里 ⇒ 必须拒绝
        AliveChild other = spawnAlive(new File(configDir, "other.pid"), 4);
        try {
            long otherPid = other.pid;
            Files.write(lock.toPath(), ("{\"pid\":" + otherPid + ",\"started_at\":"
                    + DeliveryLedger.OsProcessLiveness.startStampOf(otherPid) + "}")
                    .getBytes(StandardCharsets.UTF_8));
            assertNull("锁被活着的实例持有 ⇒ 不能双开", new Supervisor(configDir).tryAcquireInstanceLock());
            // pid 在、启动时刻对不上 = pid 已被复用 ⇒ 判陈旧，抢
            Files.write(lock.toPath(), ("{\"pid\":" + otherPid + ",\"started_at\":1}")
                    .getBytes(StandardCharsets.UTF_8));
            assertNotNull("pid 复用必须被识破并自愈", new Supervisor(configDir).tryAcquireInstanceLock());
        } finally {
            other.destroy();
        }
    }

    @Test
    public void releaseRemovesOnlyOurOwnLock() throws Exception {
        File configDir = tmp.newFolder("L2");
        Supervisor sup = new Supervisor(configDir);
        Supervisor.InstanceLock held = sup.tryAcquireInstanceLock();
        assertNotNull(held);
        assertTrue(held.isPersisted());
        // 别人把锁改写了（新实例已经接手）⇒ 我们收尾时绝不能误删它的新锁
        File lock = sup.instanceLockFile();
        Files.write(lock.toPath(), "{\"pid\":999999,\"started_at\":1}".getBytes(StandardCharsets.UTF_8));
        assertFalse("不是自己的锁不许删", held.release());
        assertTrue(lock.isFile());
        Files.write(lock.toPath(), ("{\"pid\":" + DeliveryLedger.ownPid() + ",\"started_at\":1}")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue("内容是自己的 ⇒ 删掉", held.release());
        assertFalse(lock.exists());
        assertFalse("重复 release 是空操作", held.release());
    }

    @Test
    public void unreadableOrMissingLockFileIsNotABarrier() throws Exception {
        File configDir = tmp.newFolder("L3");
        Supervisor sup = new Supervisor(configDir);
        assertFalse("还没人起过网关 ⇒ 无历史锁", sup.instanceLockFile().isFile());
        Supervisor.InstanceLock held = sup.tryAcquireInstanceLock();
        assertNotNull(held);
        // 内容坏掉（读不出 pid）⇒ 按陈旧处理，别把网关楔死
        File lock = sup.instanceLockFile();
        Files.write(lock.toPath(), "garbage-not-json".getBytes(StandardCharsets.UTF_8));
        assertNotNull("读不出的锁文件按陈旧处理", new Supervisor(configDir).tryAcquireInstanceLock());
        assertTrue(lock.isFile());
        held.release();
    }

    @Test
    public void lockIsNotPersistedWithoutAProfile() {
        Supervisor sup = new Supervisor((File) null);
        Supervisor.InstanceLock held = sup.tryAcquireInstanceLock();
        assertNotNull("无 profile 也要给一个内存档的锁，别让单进程用法被挡住", held);
        assertFalse(held.isPersisted());
        assertNull(sup.instanceLockFile());
        assertFalse("没有文件可删", held.release());
        assertTrue(held.diagnostic().contains("no-profile"));
    }

    // ===== helpers =====

}
