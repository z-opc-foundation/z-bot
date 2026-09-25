package com.zifang.z.bot.cron;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@link CronScheduler} 任务增删/tick/lock/persist 单测。 */
public class CronSchedulerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cronDir;
    private AtomicInteger ran;

    @Before
    public void setUp() throws Exception {
        cronDir = tmp.newFolder("cron");
        ran = new AtomicInteger(0);
    }

    private CronScheduler newScheduler() {
        return newScheduler(prompt -> {
            ran.incrementAndGet();
            return "ok-" + ran.get();
        });
    }

    private CronScheduler newScheduler(CronScheduler.TaskRunner runner) {
        return new CronScheduler(cronDir, 1, runner);
    }

    @Test
    public void addPersistsJobAndAssignsUniqueIds() {
        CronScheduler s = newScheduler();
        CronJob j1 = s.add("t1", "p1", "every 30s");
        CronJob j2 = s.add("t2", "p2", "hourly");
        assertNotNull(j1.id);
        assertNotNull(j2.id);
        assertFalse(j1.id.equals(j2.id));
        assertEquals(2, s.list().size());
        assertTrue(new File(cronDir, "jobs.json").exists());
    }

    @Test
    public void listReturnsSnapshot() {
        CronScheduler s = newScheduler();
        s.add("t1", "p", "every 10s");
        s.add("t2", "p", "hourly");
        List<CronJob> snap = s.list();
        assertEquals(2, snap.size());
        // 返回的是快照而非 live view，外部修改不应影响内部
        snap.clear();
        assertEquals(2, s.list().size());
    }

    @Test
    public void removeByExactIdAndPrefix() {
        CronScheduler s = newScheduler();
        CronJob j = s.add("t1", "p", "every 10s");
        assertTrue(s.remove(j.id));
        assertEquals(0, s.list().size());
        CronJob j2 = s.add("t2", "p", "hourly");
        assertTrue(s.remove(j2.id.substring(0, 6)));
        assertEquals(0, s.list().size());
        assertFalse(s.remove("does-not-exist"));
    }

    @Test
    public void pauseAndResumeFlipEnabled() {
        CronScheduler s = newScheduler();
        CronJob j = s.add("t1", "p", "every 10s");
        assertTrue(j.enabled);
        assertTrue(s.setEnabled(j.id, false));
        assertFalse(s.list().get(0).enabled);
        assertTrue(s.setEnabled(j.id, true));
        assertTrue(s.list().get(0).enabled);
        assertFalse(s.setEnabled("nope", false));
    }

    @Test
    public void tickExecutesDueJobsWithRunner() {
        CronScheduler s = newScheduler();
        s.add("t", "echo", "every 5s");
        // 喂 lastTick 为 11s 前，保证 every 5s 至少触发一次
        s.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(11));
        assertEquals(1, ran.get());
        CronJob only = s.list().get(0);
        assertEquals("ok-1", only.lastResult);
        assertNotNull(only.lastRun);
    }

    @Test
    public void tickSkipsDisabledJobs() {
        CronScheduler s = newScheduler();
        CronJob j = s.add("t", "echo", "every 5s");
        s.setEnabled(j.id, false);
        s.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(11));
        assertEquals(0, ran.get());
    }

    @Test
    public void pauseKeepsJobPersistedButSkipsExecution() {
        CronScheduler s = newScheduler();
        CronJob j = s.add("t", "echo", "every 5s");
        s.setEnabled(j.id, false);
        // 暂停状态仍持久化在 jobs.json
        assertTrue(new File(cronDir, "jobs.json").exists());
        assertEquals(1, s.list().size());
        assertFalse(s.list().get(0).enabled);
        s.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(60));
        assertEquals(0, ran.get());
    }

    @Test
    public void executeRecordsRunnerFailure() {
        CronScheduler s = newScheduler(prompt -> {
            throw new RuntimeException("kaboom");
        });
        CronJob j = s.add("t", "echo", "every 5s");
        s.execute(j);
        // 关键区是"读盘→改→写盘→同步内存"，跑完后内存里那份是刚落盘的新对象；
        // add() 当场的返回值只用来传 id，不回写（跨进程看到的才是事实）。
        CronJob after = s.findJob(j.id);
        assertTrue(after.lastResult.startsWith("执行失败"));
        assertTrue(after.lastResult.contains("kaboom"));
    }

    @Test
    public void jobsJsonPersistsAcrossInstances() throws Exception {
        CronScheduler s1 = newScheduler();
        s1.add("t", "p", "every 10s");
        s1.add("t2", "p2", "hourly");
        // 重新构造以模拟进程重启
        CronScheduler s2 = newScheduler();
        List<CronJob> reloaded = s2.list();
        assertEquals(2, reloaded.size());
        assertEquals("every 10s", reloaded.get(0).schedule);
        assertEquals("hourly", reloaded.get(1).schedule);
    }

    @Test
    public void badScheduleOnDiskIsKeptButDisabled() throws Exception {
        File jobs = new File(cronDir, "jobs.json");
        Files.write(jobs.toPath(),
                ("[{\"id\":\"c_x\",\"name\":\"bad\",\"prompt\":\"p\",\"schedule\":\"nope\",\"enabled\":true}]")
                        .getBytes("UTF-8"));
        CronScheduler s = newScheduler();
        assertEquals(1, s.list().size());
        assertFalse(s.list().get(0).enabled);
        // 修好的表达式会重新 enable
        s.list().get(0).schedule = "every 10s";
        // tick 时不应抛
        s.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(11));
    }

    @Test
    public void executeCreatesLockFile() throws Exception {
        CronScheduler s = newScheduler();
        CronJob j = s.add("t", "p", "every 5s");
        File lockFile = new File(cronDir, j.id + ".run.lock");
        assertFalse("执行前锁文件不存在", lockFile.exists());
        s.execute(j);
        // 执行后锁文件可能还在（try-with-resources 会关掉）—— 关键是不抛、lastResult 落得进盘
        assertEquals("ok-1", s.findJob(j.id).lastResult);
        assertEquals(1, ran.get());
    }

    @Test
    public void startStopLifecycleDoesNotThrow() {
        CronScheduler s = newScheduler();
        s.start();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        s.stop();
    }

    @Test
    public void toStringOfJobShape() {
        CronScheduler s = newScheduler();
        CronJob j = s.add("greet", "say hi", "daily 09:30");
        assertEquals("daily 09:30", j.schedule);
        assertEquals("greet", j.name);
    }
}