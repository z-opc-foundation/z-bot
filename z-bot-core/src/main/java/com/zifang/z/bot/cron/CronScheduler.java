package com.zifang.z.bot.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时任务调度器（对齐 hermes cron）：daemon 线程按 tickSeconds 节拍扫任务，
 * 到点的交给 {@link TaskRunner}（默认由 Builder 装配成"spawn 一个隔离子 agent 跑 prompt"），
 * 跑完的结果<b>真投递</b>到 {@link CronDelivery}（缺省 {@link LocalCronDelivery} = 打印）。
 *
 * <p><b>三层并发防护</b>（她的形状是 {@code threading.RLock} + {@code flock .jobs.lock} 两层，
 * 见 {@code cron/jobs.py:256 _jobs_lock}；我们在外面再套一层 per-job 的 {@code <jobId>.run.lock}）：</p>
 * <ol>
 *   <li>{@code <jobId>.run.lock} 的 {@link FileLock}：进程活着时同一条任务不并发；</li>
 *   <li>{@link #withJobsLock} —— jobs.json 的读-改-写关键区：进程内 {@link java.util.concurrent.locks.ReentrantLock}
 *       （= 她的 {@code _jobs_file_lock}）+ 跨进程 {@code .jobs.lock} flock
 *       （= 她的 {@code fcntl.flock}），<b>带线程局部重入计数</b>（= 她的
 *       {@code _jobs_lock_state.depth}）：嵌套调用复用已持有的锁，绝不重复 tryLock
 *       （同一 JVM 里对同一文件区域二次 tryLock 会抛 {@link OverlappingFileLockException}）；
 *       抢 flock 有上限等待（{@value #JOBS_LOCK_TIMEOUT_MILLIS} ms），超时降级为"只有进程内锁"
 *       并大声记日志 —— 她的 #60703 就是被一个卡死的兄弟进程冻住了整个 ticker；</li>
 *   <li>{@link CronRunClaim} 持久认领：先落账再产生副作用（红线 8）。</li>
 * </ol>
 *
 * <p>认领为什么必要：FileLock 只表达"有进程正拿着它"，进程一死锁就自动没了，
 * 于是"上次那回到底跑没跑完"无从判断。{@link #claimDispatch} 在跑之前把
 * {@link CronJob#dispatches} 与 {@link CronJob#runClaim} 写进 jobs.json，
 * 之后由 {@link #heartbeatRunClaim} 定时续期 —— 认领过期就严格等于"认领它的进程死了"
 * （{@link CronRunClaim#isLive}）。一次性任务因此是 at-most-once：
 * 掉电那一枪不会复活成"还没跑过"（她的 {@code jobs.py:1610 claim_dispatch} 同理，
 * 她的 #38758 就是这条之前的一-shot 无限重发）。</p>
 *
 * <p>所有状态文件（jobs.json / {@code .jobs.lock} / {@code <jobId>.run.lock}）都落在构造器
 * 给的 {@code dir} 里 —— 那个目录由 {@code BotAgent.Builder} 从 {@code config.getConfigDir()}
 * 派生，跟着 {@code --config-dir} 走（红线 1）。本类不出现 {@code ~/.zbot} 字面量。</p>
 */
public final class CronScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CronScheduler.class);

    /** 到点任务的执行器：把 prompt 交给一个 agent，返回最终回复。 */
    public interface TaskRunner {
        String run(String prompt);
    }

    /** jobs.json 读-改-写关键区里跑的东西（拿到的是刚从盘上读回来的任务表）。 */
    interface LockedCall<T> {
        T call(StoreTxn tx);
    }

    /** 只锁不碰库的段落（测锁本身用：重入计数、跨线程互斥）。 */
    interface VoidCall {
        void run();
    }

    /** {@link LockedCall} 的手：看/改 store，并声明"我改了，要落盘"。 */
    public final class StoreTxn {
        private final List<CronJob> store;
        private boolean changed;

        private StoreTxn(List<CronJob> store) {
            this.store = store;
        }

        /** 盘上当前的任务表（可增删改；只有 {@link #markChanged()} 过才会写回）。 */
        public List<CronJob> jobs() {
            return store;
        }

        /** 声明本次关键区改了状态，收尾要写 jobs.json。 */
        public void markChanged() {
            this.changed = true;
        }

        boolean changed() {
            return changed;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 认领新鲜期上限（毫秒）。对齐 hermes {@code jobs.py:183 ONESHOT_RUN_CLAIM_TTL_SECONDS=1800}：
     * 健康的跑法会在结束时 {@link #finishRun} 清掉认领，TTL 只用来回收"跑到一半进程就没了"的残骸。
     */
    public static final long DEFAULT_RUN_CLAIM_TTL_MILLIS = 1800_000L;

    /** 心跳保鲜节拍：远小于 TTL，让"认领过期"这件事只可能是进程死了。 */
    public static final long DEFAULT_HEARTBEAT_MILLIS = 20_000L;

    /** 等跨进程 flock 的上限（对齐 hermes {@code jobs.py:100 _JOBS_LOCK_TIMEOUT_SECONDS=30.0}）。 */
    static final long JOBS_LOCK_TIMEOUT_MILLIS = 30_000L;
    private static final long JOBS_LOCK_POLL_MILLIS = 50L;

    /** 跨进程 jobs.json 锁的文件名（对齐她的 {@code .jobs.lock}）。 */
    static final String JOBS_LOCK_FILE = ".jobs.lock";

    private final File dir;
    private final File jobsFile;
    private final File jobsLockFile;
    private final long tickSeconds;
    private final TaskRunner runner;
    private final List<CronJob> jobs = new CopyOnWriteArrayList<CronJob>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    // ===== jobs.json 关键区：进程内监视器 + 跨进程 flock + 线程局部重入计数 =====
    //
    // 监视器按<b>锁文件的绝对路径</b>放在 JVM 级的表里，而不是挂在实例上 —— 对齐 hermes 的
    // {@code threading.RLock}（那是<b>进程</b>粒度的，不是对象粒度的）。要是每个实例各拿一把
    // 监视器，同一个 JVM 里两个指同一 cron 目录的实例就会同时对同一个 {@code .jobs.lock}
    // tryLock，而 Java 的 FileLock 是<b>整进程</b>互斥的（{@code FileChannel.tryLock} 的
    // javadoc：同一 Java 虚拟机里重复锁定直接抛 OverlappingFileLockException，哪怕不同通道），
    // 于是第二把锁不是"等跨进程的那位"，而是当场炸成降级 —— 全量跑里真出现过这一行。
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>
            JOBS_MONITORS = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>();

    /** 本实例进关键区要先拿的进程内监视器（同目录的实例共用一把）。 */
    private final java.util.concurrent.locks.ReentrantLock jobsMonitor;
    private final ThreadLocal<Integer> jobsDepth = new ThreadLocal<Integer>();
    private FileChannel jobsLockChannel;
    private FileLock jobsLockHandle;
    private final java.util.concurrent.atomic.AtomicInteger flockAcquisitions =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 降级次数：这次进关键区时没能拿到跨进程 flock，只用上了进程内锁。 */
    private final java.util.concurrent.atomic.AtomicInteger jobsLockDegraded =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile CronDelivery delivery = new LocalCronDelivery();
    private volatile long runClaimTtlMillis = DEFAULT_RUN_CLAIM_TTL_MILLIS;
    private volatile long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;
    private final String ownerId = computeOwnerId();

    private static final java.util.function.Supplier<Instant> INSTANT_NOW =
            new java.util.function.Supplier<Instant>() {
                @Override
                public Instant get() {
                    return Instant.now();
                }
            };
    /** 认领/心跳判定用的时刻源，见 {@link #clock}。 */
    private volatile java.util.function.Supplier<Instant> clock = INSTANT_NOW;

    private volatile ZonedDateTime lastTick;
    private Thread worker;

    public CronScheduler(File dir, long tickSeconds, TaskRunner runner) {
        this.dir = dir;
        this.jobsFile = new File(dir, "jobs.json");
        this.jobsLockFile = new File(dir, JOBS_LOCK_FILE);
        this.jobsMonitor = monitorFor(jobsLockFile.getAbsolutePath());
        this.tickSeconds = Math.max(1, tickSeconds);
        this.runner = runner;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cron 目录不可创建: " + dir);
        }
        load();
    }

    /** 同一把锁文件 ↔ 同一个进程内监视器（表里只留一份，同目录的实例共用）。 */
    private static java.util.concurrent.locks.ReentrantLock monitorFor(String key) {
        java.util.concurrent.locks.ReentrantLock existing = JOBS_MONITORS.get(key);
        if (existing != null) {
            return existing;
        }
        java.util.concurrent.locks.ReentrantLock fresh =
                new java.util.concurrent.locks.ReentrantLock(true);
        java.util.concurrent.locks.ReentrantLock raced = JOBS_MONITORS.putIfAbsent(key, fresh);
        return raced == null ? fresh : raced;
    }

    // ===== 任务管理 =====

    public synchronized CronJob add(String name, String prompt, String schedule) {
        return add(name, prompt, schedule, "local");
    }

    /**
     * 建一条任务。{@code deliver} 是投递路由（见 {@link ChannelCronDelivery} 的语法）；
     * 语法错直接抛 {@link IllegalArgumentException}（她的路由是"到点再解析"，
     * 但写错的 {@code feishu:} 那种硬错没理由等到凌晨三点才告诉你）。
     */
    public synchronized CronJob add(String name, String prompt, String schedule, String deliver) {
        CronSchedule parsed = CronSchedule.parse(schedule);
        String route = deliver == null || deliver.trim().isEmpty() ? "local" : deliver.trim();
        String routeErr = ChannelCronDelivery.validateRoute(route);
        if (routeErr != null) {
            throw new IllegalArgumentException(routeErr);
        }
        CronJob job = new CronJob("cron_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                name, prompt, schedule.trim());
        job.parsed = parsed;
        job.deliver = route;
        addPersisted(job);
        return job;
    }

    private void addPersisted(final CronJob job) {
        withStore(new LockedCall<Void>() {
            @Override
            public Void call(StoreTxn tx) {
                tx.jobs().add(job);
                tx.markChanged();
                return null;
            }
        });
    }

    public synchronized boolean remove(final String idOrPrefix) {
        Boolean removed = withStore(new LockedCall<Boolean>() {
            @Override
            public Boolean call(StoreTxn tx) {
                CronJob j = findIn(tx.jobs(), idOrPrefix);
                if (j == null) {
                    return Boolean.FALSE;
                }
                tx.jobs().remove(j);
                tx.markChanged();
                return Boolean.TRUE;
            }
        });
        return removed.booleanValue();
    }

    public synchronized boolean setEnabled(final String idOrPrefix, final boolean enabled) {
        Boolean hit = withStore(new LockedCall<Boolean>() {
            @Override
            public Boolean call(StoreTxn tx) {
                CronJob j = findIn(tx.jobs(), idOrPrefix);
                if (j == null) {
                    return Boolean.FALSE;
                }
                j.enabled = enabled;
                tx.markChanged();
                return Boolean.TRUE;
            }
        });
        return hit.booleanValue();
    }

    public List<CronJob> list() {
        return new ArrayList<CronJob>(jobs);
    }

    /** 按 id / id 前缀找当前内存里的任务；没有返回 null。 */
    public CronJob findJob(String idOrPrefix) {
        return findIn(jobs, idOrPrefix);
    }

    // ===== 投递口装配 =====

    /** 换投递口（传 null = 回到 local 打印）。 */
    public CronScheduler setDelivery(CronDelivery delivery) {
        this.delivery = delivery == null ? new LocalCronDelivery() : delivery;
        return this;
    }

    public CronDelivery delivery() {
        return delivery;
    }

    /**
     * 把投递口换成"按通道名投"（{@link ChannelCronDelivery}），local 档与 origin 降级档仍走打印。
     * 通道表由装配方提供 —— 本类不 import {@code ChannelBus}/{@code Gateway}（P16 边界）。
     */
    public ChannelCronDelivery deliverViaChannels(ChannelCronDelivery.Channels channels) {
        ChannelCronDelivery d = new ChannelCronDelivery(channels, new LocalCronDelivery(), true);
        this.delivery = d;
        return d;
    }

    /** 认领新鲜期上限（毫秒），默认 {@value #DEFAULT_RUN_CLAIM_TTL_MILLIS}。 */
    public CronScheduler runClaimTtlMillis(long millis) {
        this.runClaimTtlMillis = Math.max(1L, millis);
        return this;
    }

    public long runClaimTtlMillis() {
        return runClaimTtlMillis;
    }

    /**
     * 换掉"现在几点"（缺省 {@link Instant#now()}）。
     *
     * <p>存在的理由只有一个但很硬：认领过期是一条<b>时间</b>语义（"age &lt; ttl 才算活着"），
     * 用真墙钟测它就得 sleep 到 ttl 量级 —— 本仓 {@code PairingServiceTest} 就是靠 200ms TTL
     * 撞真实墙钟翻过车的。给了这个口，{@code CronClaimTest} 能把时钟一格一格拨，
     * 而产品路径一行都不变（装配方从不注入）。</p>
     */
    public CronScheduler clock(java.util.function.Supplier<Instant> clock) {
        this.clock = clock == null ? INSTANT_NOW : clock;
        return this;
    }

    /** 当前用于认领/心跳判定的时刻（注入的假时钟或 {@link Instant#now()}）。 */
    public Instant now() {
        return clock.get();
    }

    /** 心跳保鲜节拍（毫秒），默认 {@value #DEFAULT_HEARTBEAT_MILLIS}。 */
    public CronScheduler heartbeatMillis(long millis) {
        this.heartbeatMillis = Math.max(1L, millis);
        return this;
    }

    public long heartbeatMillis() {
        return heartbeatMillis;
    }

    /** 本进程的所有者标识（pid + JVM 启动时刻 + 随机后缀），写进 {@link CronRunClaim#by}。 */
    public String ownerId() {
        return ownerId;
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

    // ===== 先落账再跑（红线 8） =====

    /**
     * 跑之前先认领这次投递。<b>必须在任何副作用之前调用。</b>
     *
     * @return true = 这一枪归你打，可以去跑；false = 一次性任务额度已用完（顺手摘掉陈旧任务），
     *         或别的进程正拿着还新鲜的认领
     */
    public boolean claimDispatch(final String jobId) {
        final Instant now = clock.get();
        Boolean proceed = withStore(new LockedCall<Boolean>() {
            @Override
            public Boolean call(StoreTxn tx) {
                CronJob job = findIn(tx.jobs(), jobId);
                if (job == null) {
                    // 她的同款分支（jobs.py:1665）：手里这张 job 单子已经不在库里了，
                    // 没有可落账的地方 —— 照旧放行（不猜、也不静默吞）。
                    LOG.debug("[cron] {} 不在库里，无账可落，按原样放行", jobId);
                    return Boolean.TRUE;
                }
                if (job.isOneShot() && job.dispatches >= CronJob.ONESHOT_DISPATCH_LIMIT) {
                    tx.jobs().remove(job);
                    tx.markChanged();
                    LOG.info("[cron] {} 一次性投递额度已用完（{}/{}）—— 摘除陈旧任务，不重跑",
                            jobId, Integer.valueOf(job.dispatches),
                            Integer.valueOf(CronJob.ONESHOT_DISPATCH_LIMIT));
                    return Boolean.FALSE;
                }
                long ttl = runClaimTtlMillis;
                if (job.runClaim != null && job.runClaim.isLive(now, ttl) && !job.runClaim.heldBy(ownerId)) {
                    LOG.info("[cron] {} 已被 {} 认领且仍新鲜（age={}ms < ttl={}ms）—— 本次跳过",
                            new Object[]{jobId, job.runClaim.by,
                                    Long.valueOf(now.toEpochMilli() - Instant.parse(job.runClaim.at).toEpochMilli()),
                                    Long.valueOf(ttl)});
                    return Boolean.FALSE;
                }
                if (job.runClaim != null && job.runClaim.isLive(now, ttl)) {
                    LOG.info("[cron] {} 是本店自己的过期/残留认领，原地接管续用", jobId);
                }
                job.runClaim = new CronRunClaim(ownerId, now);
                if (job.isOneShot()) {
                    job.dispatches = job.dispatches + 1;
                }
                tx.markChanged();
                LOG.info("[cron] {} 已落账认领（owner={}，dispatches={}）→ 这才开始跑副作用",
                        jobId, ownerId, Integer.valueOf(job.dispatches));
                return Boolean.TRUE;
            }
        });
        return proceed.booleanValue();
    }

    /**
     * 心跳保鲜：跑着的时候定期把认领时间戳续上（对齐 hermes {@code jobs.py:1673 heartbeat_run_claim}）。
     *
     * <p>compare-and-refresh：只有 {@code expectedOwner} 还是这条认领的主人才续。
     * 一个睡了很久的旧跑者醒来后不能给"已经被别人接管的认领"续命。</p>
     *
     * @return true = 续上了；false = 任务没了 / 没认领 / 主人不是它
     */
    public boolean heartbeatRunClaim(final String jobId, final String expectedOwner) {
        final Instant now = clock.get();
        Boolean beat = withStore(new LockedCall<Boolean>() {
            @Override
            public Boolean call(StoreTxn tx) {
                CronJob job = findIn(tx.jobs(), jobId);
                if (job == null || job.runClaim == null || !job.runClaim.heldBy(expectedOwner)) {
                    return Boolean.FALSE;
                }
                job.runClaim.beat(now);
                tx.markChanged();
                LOG.info("[cron] {} 心跳保鲜：认领续期到 {}（owner={}）", jobId, job.runClaim.at, expectedOwner);
                return Boolean.TRUE;
            }
        });
        return beat.booleanValue();
    }

    /** 执行一条任务：run.lock 防同条任务并发，claim 防跨进程/跨重启重跑，结果记回并投递。 */
    void execute(CronJob job) {
        final String jobId = job.id;
        File lock = new File(dir, jobId + ".run.lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw");
             FileChannel ch = raf.getChannel();
             FileLock held = ch.tryLock()) {
            if (held == null) {
                LOG.info("[cron] {} 正在被其他进程执行，跳过", jobId);
                return;
            }
            if (!claimDispatch(jobId)) {
                return;
            }
            Thread heartbeat = startClaimHeartbeat(jobId);
            String reply;
            try {
                reply = runner.run(job.prompt);
            } catch (Exception e) {
                reply = "执行失败: " + e.getMessage();
            } finally {
                stopClaimHeartbeat(heartbeat);
            }
            CronJob snapshot = findJob(jobId);
            String deliveryConclusion = deliverResult(snapshot == null ? job : snapshot, reply);
            finishRun(jobId, reply, deliveryConclusion);
        } catch (IOException e) {
            LOG.warn("[cron] {} 执行失败: {}", jobId, e.getMessage());
        }
    }

    /**
     * 跑完了：清认领、写台账，一次性任务摘除（认领账留在被摘前的最后一次落盘里）。
     *
     * @param conclusion {@link #deliverResult} 给出的<b>投递结论</b>，进 {@link CronJob#lastDelivery}
     */
    void finishRun(final String jobId, final String result, final String conclusion) {
        withStore(new LockedCall<Void>() {
            @Override
            public Void call(StoreTxn tx) {
                CronJob job = findIn(tx.jobs(), jobId);
                if (job == null) {
                    LOG.info("[cron] {} 已被摘除（别的进程或上一轮收尾），不再回写台账", jobId);
                    return null;
                }
                job.markRun(result);
                job.lastDelivery = conclusion == null ? "ok" : conclusion;
                job.runClaim = null;   // 她的 mark_job_run 同样在这儿清 run_claim（jobs.py:1542）
                boolean drop = job.isOneShot();
                if (drop) {
                    tx.jobs().remove(job);
                }
                tx.markChanged();
                if (drop) {
                    LOG.info("[cron] {} 一次性任务跑完并已投递（{}）—— 摘除", jobId, job.lastDelivery);
                }
                return null;
            }
        });
    }

    /**
     * 投递一次结果，返回要写进 {@link CronJob#lastDelivery} 的<b>结论</b>（永不为 null）。
     *
     * <p>异常不外溢、也不静默吞：投递口的 {@code null} 约定是"成功"，而"没东西可投"是另一件事，
     * 两者都写成 ok 就等于台账在说谎（空输出的任务其实一条消息都没发出去）。</p>
     */
    private String deliverResult(CronJob job, String text) {
        if (text == null || text.trim().isEmpty()) {
            LOG.warn("[cron] {} 没有任何输出，跳过投递（lastResult 仍记录本次）", job.id);
            return "skipped: 没有可投递的输出";
        }
        try {
            String err = delivery.deliver(job, text);
            if (err != null) {
                LOG.warn("[cron] {} 投递失败: {}", job.id, err);
                return err;
            }
            return "ok";
        } catch (Exception e) {
            LOG.warn("[cron] {} 投递口抛异常: {}", job.id, e.toString());
            return "delivery threw: " + e.getMessage();
        }
    }

    private Thread startClaimHeartbeat(final String jobId) {
        final long every = heartbeatMillis;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(every);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        heartbeatRunClaim(jobId, ownerId);
                    } catch (Exception e) {
                        LOG.warn("[cron] {} 心跳保鲜失败: {}", jobId, e.getMessage());
                    }
                }
            }
        }, "zbot-cron-claim-heartbeat");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void stopClaimHeartbeat(Thread t) {
        if (t == null) {
            return;
        }
        t.interrupt();
        try {
            t.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== jobs.json 关键区（两层锁 + 重入计数） =====

    /** 当前线程的重入深度（0 = 不在关键区里）。 */
    int jobsLockDepth() {
        Integer d = jobsDepth.get();
        return d == null ? 0 : d.intValue();
    }

    /** 真去向 OS 抢 flock 的次数：重入必须不增加它（否则同 JVM 二次 tryLock 直接抛）。 */
    int flockAcquisitions() {
        return flockAcquisitions.get();
    }

    /** 拿不到跨进程锁、只用进程内锁就干了一次的次数（超上限/被抢/异常三类都记这里）。 */
    public int jobsLockDegradedCount() {
        return jobsLockDegraded.get();
    }

    /** 在 jobs.json 关键区里跑一段（可重入；嵌套复用同一把跨进程锁）。 */
    void withJobsLock(VoidCall body) {
        acquireJobsLock();
        try {
            body.run();
        } finally {
            releaseJobsLock();
        }
    }

    /** 读盘 → 改 → （改过才）写盘 → 同步内存，整段在跨进程锁里。 */
    <T> T withStore(LockedCall<T> body) {
        acquireJobsLock();
        try {
            List<CronJob> store = readStore();
            StoreTxn tx = new StoreTxn(store);
            T out = body.call(tx);
            if (tx.changed()) {
                writeStore(store);
                syncMemory(store);
            }
            return out;
        } finally {
            releaseJobsLock();
        }
    }

    /** 只在锁里读盘，不写。 */
    private List<CronJob> readStoreUnderLock() {
        acquireJobsLock();
        try {
            return readStore();
        } finally {
            releaseJobsLock();
        }
    }

    private void acquireJobsLock() {
        Integer depth = jobsDepth.get();
        if (depth != null && depth.intValue() > 0) {
            jobsDepth.set(Integer.valueOf(depth.intValue() + 1));
            return;
        }
        jobsMonitor.lock();
        FileChannel ch = null;
        FileLock lock = null;
        try {
            ch = FileChannel.open(jobsLockFile.toPath(),
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
            long deadline = System.currentTimeMillis() + JOBS_LOCK_TIMEOUT_MILLIS;
            while (true) {
                try {
                    lock = ch.tryLock(0L, Long.MAX_VALUE, false);
                } catch (OverlappingFileLockException e) {
                    // 本 JVM 的另一个线程已经持着这段 —— 只有"重入计数算错了"才可能走到这里。
                    // 等下去也没意义（同进程的锁不会因为我们多等就松开），当场报出来 + 降级。
                    jobsLockDegraded.incrementAndGet();
                    LOG.error("[cron] 重入计数失衡：同一 JVM 已有线程持着 {} 的锁，"
                            + "本次只用进程内锁（这说明 withJobsLock 的嵌套判定被改坏了）",
                            jobsLockFile.getName());
                    closeQuietly(ch);
                    ch = null;
                    lock = null;
                    break;
                }
                if (lock != null) {
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    jobsLockDegraded.incrementAndGet();
                    LOG.error("[cron] 等 {} 的跨进程锁超过 {}ms，另一个进程卡住了；"
                            + "本次降级为只用进程内锁，好过整个调度器僵死（hermes #60703 同型）",
                            jobsLockFile.getName(), Long.valueOf(JOBS_LOCK_TIMEOUT_MILLIS));
                    closeQuietly(ch);
                    ch = null;
                    lock = null;
                    break;
                }
                try {
                    Thread.sleep(JOBS_LOCK_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    jobsLockDegraded.incrementAndGet();
                    closeQuietly(ch);
                    ch = null;
                    lock = null;
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            jobsLockDegraded.incrementAndGet();
            LOG.warn("[cron] 跨进程锁不可用（{}）；只用进程内锁继续", e.toString());
            closeQuietly(ch);
            ch = null;
            lock = null;
        }
        jobsLockChannel = ch;
        jobsLockHandle = lock;
        if (lock != null) {
            flockAcquisitions.incrementAndGet();
        }
        jobsDepth.set(Integer.valueOf(1));
    }

    private void releaseJobsLock() {
        Integer depth = jobsDepth.get();
        if (depth == null || depth.intValue() <= 0) {
            jobsMonitor.unlock();   // 不该发生；真发生了也别把锁永久扣住
            throw new IllegalStateException("jobs 锁未持有却调用释放：重入计数失衡");
        }
        if (depth.intValue() > 1) {
            jobsDepth.set(Integer.valueOf(depth.intValue() - 1));
            return;
        }
        jobsDepth.remove();
        try {
            FileLock lock = jobsLockHandle;
            FileChannel ch = jobsLockChannel;
            jobsLockHandle = null;
            jobsLockChannel = null;
            if (lock != null && lock.isValid()) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                    // 通道马上关，关不掉也会随 fd 关闭而释放
                }
            }
            closeQuietly(ch);
        } finally {
            jobsMonitor.unlock();
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) {
            return;
        }
        try {
            ch.close();
        } catch (IOException ignored) {
            // 关不掉就算了
        }
    }

    private static String computeOwnerId() {
        String pid = "0";
        long born = 0L;
        try {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            int at = jvmName.indexOf('@');
            pid = at > 0 ? jvmName.substring(0, at) : jvmName;
            born = ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable ignored) {
            // 拿不到 pid 也别把调度器点着
        }
        return pid + "@" + born + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ===== 持久化 =====

    private void load() {
        List<CronJob> store = readStoreUnderLock();
        jobs.clear();
        jobs.addAll(store);
    }

    /** 读盘并逐条消毒（坏表达式留档但禁用）；不写盘。只在持有 jobs 锁时调用。 */
    private List<CronJob> readStore() {
        List<CronJob> out = new ArrayList<CronJob>();
        if (!jobsFile.isFile()) {
            return out;
        }
        try {
            CronJob[] arr = JSON.readValue(jobsFile, CronJob[].class);
            for (CronJob j : arr) {
                if (j == null || j.id == null) {
                    LOG.warn("[cron] jobs.json 里有缺 id 的记录，忽略该条");
                    continue;
                }
                try {
                    j.schedule();
                } catch (IllegalArgumentException e) {
                    j.enabled = false; // 坏表达式不再触发但保留记录
                }
                out.add(j);
            }
        } catch (IOException e) {
            LOG.warn("[cron] 读取 jobs.json 失败: {}", e.getMessage());
        }
        return out;
    }

    private void writeStore(List<CronJob> store) {
        try {
            File tmp = new File(dir, "jobs.json.tmp");
            Files.write(tmp.toPath(), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(store).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), jobsFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("[cron] 保存 jobs.json 失败: {}", e.getMessage());
        }
    }

    /** 把盘上的最新事实搬回内存（别的进程 pause 过就不会被我这边的旧副本冲掉）。 */
    private void syncMemory(List<CronJob> store) {
        jobs.clear();
        jobs.addAll(store);
    }

    static CronJob findIn(List<CronJob> list, String idOrPrefix) {
        if (idOrPrefix == null) {
            return null;
        }
        for (CronJob j : list) {
            if (j.id.equals(idOrPrefix) || j.id.startsWith(idOrPrefix)) {
                return j;
            }
        }
        return null;
    }
}
