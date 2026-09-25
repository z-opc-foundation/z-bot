package com.zifang.z.bot.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务调度器（对齐 hermes cron）：daemon 线程按 tickSeconds 节拍扫任务，
 * 到点的交给 {@link TaskRunner}（默认由 Builder 装配成"spawn 一个隔离子 agent 跑 prompt"）。
 *
 * <p>防多进程重复执行：每个 job 触发前尝试锁 {@code <jobId>.run.lock}，
 * 拿不到说明另一个 z-bot 进程正在跑同一条任务，本次跳过。
 * 任务定义落在 jobs.json，保存走同样的锁目录（tmp+ATOMIC_MOVE）。</p>
 */
public final class CronScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CronScheduler.class);

    /** 到点任务的执行器：把 prompt 交给一个 agent，返回最终回复。 */
    public interface TaskRunner {
        String run(String prompt);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final File dir;
    private final File jobsFile;
    private final long tickSeconds;
    private final TaskRunner runner;
    private final List<CronJob> jobs = new CopyOnWriteArrayList<CronJob>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ZonedDateTime lastTick;
    private Thread worker;

    public CronScheduler(File dir, long tickSeconds, TaskRunner runner) {
        this.dir = dir;
        this.jobsFile = new File(dir, "jobs.json");
        this.tickSeconds = Math.max(1, tickSeconds);
        this.runner = runner;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cron 目录不可创建: " + dir);
        }
        load();
    }

    // ===== 任务管理 =====

    public synchronized CronJob add(String name, String prompt, String schedule) {
        CronSchedule parsed = CronSchedule.parse(schedule);
        CronJob job = new CronJob("cron_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                name, prompt, schedule.trim());
        job.parsed = parsed;
        jobs.add(job);
        save();
        return job;
    }

    public synchronized boolean remove(String idOrPrefix) {
        boolean removed = jobs.removeIf(j -> j.id.equals(idOrPrefix) || j.id.startsWith(idOrPrefix));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean setEnabled(String idOrPrefix, boolean enabled) {
        for (CronJob j : jobs) {
            if (j.id.equals(idOrPrefix) || j.id.startsWith(idOrPrefix)) {
                j.enabled = enabled;
                save();
                return true;
            }
        }
        return false;
    }

    public List<CronJob> list() {
        return new ArrayList<CronJob>(jobs);
    }

    // ===== 调度 =====

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        lastTick = ZonedDateTime.now();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (started.get()) {
                    try {
                        Thread.sleep(tickSeconds * 1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        tick();
                    } catch (Exception e) {
                        LOG.warn("[cron] tick 失败: {}", e.getMessage());
                    }
                }
            }
        }, "zbot-cron");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        started.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 一个节拍：扫到点任务并执行（也供测试直接调用）。 */
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime last = lastTick;
        lastTick = now;
        for (CronJob j : jobs) {
            if (!j.enabled || !j.schedule().due(last, now)) {
                continue;
            }
            execute(j);
        }
    }

    /** 测试钩子：以指定 lastTick 触发一次 tick，用于验证 due 边界。 */
    public void tickWithOffset(ZonedDateTime last) {
        ZonedDateTime now = ZonedDateTime.now();
        lastTick = now;
        for (CronJob j : jobs) {
            if (!j.enabled || !j.schedule().due(last, now)) {
                continue;
            }
            execute(j);
        }
    }

    /** 执行一条任务：run.lock 防多进程抢跑，结果记回 job 并持久化。 */
    void execute(CronJob job) {
        File lock = new File(dir, job.id + ".run.lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw");
             FileChannel ch = raf.getChannel();
             FileLock held = ch.tryLock()) {
            if (held == null) {
                LOG.info("[cron] {} 正在被其他进程执行，跳过", job.id);
                return;
            }
            String reply;
            try {
                reply = runner.run(job.prompt);
            } catch (Exception e) {
                reply = "执行失败: " + e.getMessage();
            }
            job.markRun(reply);
            save();
        } catch (IOException e) {
            LOG.warn("[cron] {} 执行失败: {}", job.id, e.getMessage());
        }
    }

    // ===== 持久化 =====

    private void load() {
        if (!jobsFile.isFile()) {
            return;
        }
        try {
            CronJob[] arr = JSON.readValue(jobsFile, CronJob[].class);
            jobs.clear();
            for (CronJob j : arr) {
                try {
                    j.schedule();
                } catch (IllegalArgumentException e) {
                    j.enabled = false; // 坏表达式不再触发但保留记录
                }
                jobs.add(j);
            }
        } catch (IOException e) {
            LOG.warn("[cron] 读取 jobs.json 失败: {}", e.getMessage());
        }
    }

    synchronized void save() {
        try {
            File tmp = new File(dir, "jobs.json.tmp");
            Files.write(tmp.toPath(), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(jobs).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), jobsFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("[cron] 保存 jobs.json 失败: {}", e.getMessage());
        }
    }
}
