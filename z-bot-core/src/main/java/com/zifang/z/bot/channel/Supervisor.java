package com.zifang.z.bot.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 长活后台任务的监督器（对标 {@code hermes gateway/run.py:8199 _spawn_supervised} +
 * {@code gateway/restart_loop_guard.py} 151 行）。
 *
 * <p>她监督的是 asyncio task，我们监督的是线程，语义一一对应：</p>
 * <ul>
 *   <li><b>干净退出绝不重启</b>：正常返回 = 有意关停或自我停用的 watcher，重启它就是空转烧 CPU；</li>
 *   <li><b>抛异常才重启</b>，指数退避封顶（她的 {@code min(60, 2**min(attempt,6))} 秒），
 *       连崩 {@value #MAX_SUPERVISED_RESTARTS} 次且每次都在 {@value #SUPERVISED_HEALTHY_SECS} 秒内
 *       ⇒ 放弃重启（大声记 ERROR，不静默丢任务）；</li>
 *   <li><b>活过健康窗口的崩溃算新账</b>：任一实例跑满 {@value #SUPERVISED_HEALTHY_SECS} 秒才死 ⇒
 *       连续计数归零。否则一个跑了几天的守护进程偶发崩几次就会被永久放弃（她在 NS 里踩过）。</li>
 * </ul>
 *
 * <p>第二件事是<b>熔断</b>（她 #30719 的 defense-3）：一个反复把网关弄死的逻辑会因为
 * 「启动 → 自动续跑中断的活 → 又死」被 supervisor（launchd/systemd）拖进紧密重启动循环，
 * 每 ~10 秒一次直到人工介入。故每次「带着待续跑的活启动」都记一个时间戳，<b>跨进程持久化</b>
 * （每次启动都是新进程，内存里的计数毫无用处），窗口内次数达 {@value #DEFAULT_MAX_RESTARTS}
 * ⇒ 本次启动<b>跳过自动续跑</b>：网关照常起来收真消息，只是不再重放那个反复弄死它的活，把人放回回路。
 * 熔断器自身读写失败一律 <b>fail-open</b>（不熔断）—— 坏掉的断路器绝不能楔死一个健康的网关。</p>
 */
public final class Supervisor {

    private static final Logger LOG = LoggerFactory.getLogger(Supervisor.class);

    /** 连续快速崩溃多少次后放弃重启（她 {@code _MAX_SUPERVISED_RESTARTS = 5}）。 */
    public static final int MAX_SUPERVISED_RESTARTS = 5;
    /** 跑满这么多毫秒算「健康过」，崩溃计数归零（她 {@code _SUPERVISED_HEALTHY_SECS = 300}）。 */
    public static final long SUPERVISED_HEALTHY_MILLIS = 300_000L;
    /** 退避封顶秒数（她 {@code min(60, ...)}）。 */
    public static final long BACKOFF_CEILING_SECONDS = 60L;

    /** 熔断默认阈值：60 秒窗口内 3 次带活启动即熔断（她 {@code DEFAULT_MAX_RESTARTS=3}/{@code DEFAULT_WINDOW_SECONDS=60}）。 */
    public static final int DEFAULT_MAX_RESTARTS = 3;
    public static final int DEFAULT_WINDOW_SECONDS = 60;

    /** 被监督的任务体：正常返回 = 干净退出（不重启）；抛出 = 崩溃（按策略重启）。 */
    public interface Task {
        void run() throws Exception;
    }

    /** 一次监督（含其所有重启代次）的把手与观测面。 */
    public static final class Handle {

        private final String name;
        private final AtomicLong startedAtMillis = new AtomicLong();
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger restartAttempts = new AtomicInteger();
        private final AtomicBoolean abandoned = new AtomicBoolean(false);
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private volatile Throwable lastError;

        Handle(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        /** 实际跑过多少轮（含重启后的代次）。 */
        public int runCount() {
            return runs.get();
        }

        /** 因崩溃而重启的次数。 */
        public int restartCount() {
            return restartAttempts.get();
        }

        /** 连崩到顶 ⇒ 已放弃重启。 */
        public boolean isAbandoned() {
            return abandoned.get();
        }

        /** 是否被要求停止（干净退出的正常路径）。 */
        public boolean isStopRequested() {
            return stopped.get();
        }

        public Throwable lastError() {
            return lastError;
        }

        /** 请求停止：任务体自己看 {@link #isStopRequested()} 返回 ⇒ 走「干净退出、不重启」。 */
        public void requestStop() {
            stopped.set(true);
        }
    }

    private final File restartLoopFile;
    private final File instanceLockFile;
    private final List<Handle> handles = Collections.synchronizedList(new ArrayList<Handle>());
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final long backoffUnitMillis;
    private final long healthyMillis;

    public Supervisor(File configDir) {
        this(configDir, 1000L, SUPERVISED_HEALTHY_MILLIS);
    }

    /**
     * @param backoffUnitMillis 退避单位（生产 1000=秒；测试里调小，这样「多次崩溃 + 退避」能在毫秒级跑完）
     * @param healthyMillis     健康窗口毫秒
     */
    public Supervisor(File configDir, long backoffUnitMillis, long healthyMillis) {
        this.restartLoopFile = configDir == null ? null
                : new File(configDir, "gateway" + File.separator + "restart_loop.json");
        this.instanceLockFile = configDir == null ? null
                : new File(configDir, "gateway" + File.separator + "gateway.lock");
        this.backoffUnitMillis = Math.max(1L, backoffUnitMillis);
        this.healthyMillis = Math.max(1L, healthyMillis);
    }

    /** 熔断状态文件（{@code null} = 纯内存模式，不落盘）。 */
    public File restartLoopStateFile() {
        return restartLoopFile;
    }

    /** 网关实例锁文件（{@code null} = 无 profile，不落盘）。 */
    public File instanceLockFile() {
        return instanceLockFile;
    }

    public List<Handle> handles() {
        synchronized (handles) {
            return new ArrayList<Handle>(handles);
        }
    }

    /** 关掉监督器：之后的崩溃不再重启（进程正在收尾）。 */
    public void shutdown() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 启动一个被监督的任务。
     *
     * @param restartOnCrash {@code false} = 崩了也只记日志不重启（她 {@code restart=} 参数同义）
     */
    public Handle spawn(final String name, final Task task, final boolean restartOnCrash) {
        final Handle handle = new Handle(name);
        handles.add(handle);
        spawnAttempt(handle, task, restartOnCrash, 0);
        return handle;
    }

    public Handle spawn(String name, Task task) {
        return spawn(name, task, true);
    }

    private void spawnAttempt(final Handle handle, final Task task, final boolean restartOnCrash,
            final int attempt) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long started = System.currentTimeMillis();
                handle.startedAtMillis.set(started);
                handle.runs.incrementAndGet();
                Throwable error = null;
                try {
                    task.run();
                } catch (Throwable caught) {
                    error = caught;
                }
                if (error == null) {
                    // 干净返回 = 有意关停 / 自我停用的 watcher ⇒ 绝不重启（重启就是 busy-spin）
                    return;
                }
                handle.lastError = error;
                LOG.error("[supervisor] 被监督任务 {} 崩了: {}", handle.name(), error.toString(), error);
                if (!restartOnCrash || !running.get()) {
                    return;
                }
                long ranFor = System.currentTimeMillis() - started;
                int effective = ranFor >= healthyMillis ? 0 : attempt;
                if (effective >= MAX_SUPERVISED_RESTARTS) {
                    handle.abandoned.set(true);
                    LOG.error("[supervisor] 任务 {} 连续 {} 次快速崩溃（每次都在启动后 {}ms 内）"
                            + " —— 放弃重启", handle.name(), effective, healthyMillis);
                    return;
                }
                long backoff = backoffMillis(effective);
                handle.restartAttempts.incrementAndGet();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running.get()) {
                    spawnAttempt(handle, task, restartOnCrash, effective + 1);
                }
            }
        }, "z-bot-supervised-" + handle.name());
        t.setDaemon(true);
        t.start();
    }

    /** 指数退避封顶（她 {@code min(60, 2 ** min(attempt, 6))} 秒，单位由构造参数决定）。 */
    public long backoffMillis(int attempt) {
        int capped = Math.min(6, Math.max(0, attempt));
        long seconds = 1L << capped;
        return Math.min(BACKOFF_CEILING_SECONDS * backoffUnitMillis, seconds * backoffUnitMillis);
    }

    // ===== 熔断（跨进程）=====

    /**
     * 记一次「带着待续跑的活启动」，并回报是否已经熔断 ⇒ 调用方据此<b>跳过</b>本次自动续跑。
     *
     * <p>只有真有活要续跑的启动才计数：一次干净启动不许给熔断器攒账。读写失败一律 fail-open
     * （不熔断）。</p>
     *
     * @return {@code true} = 窗口内已达阈值，本次跳过自动续跑
     */
    public boolean checkAndRecordInterruptedBoot(int maxRestarts, int windowSeconds, Long nowOverride) {
        long now = nowOverride == null ? System.currentTimeMillis() : nowOverride.longValue();
        List<Long> boots;
        try {
            boots = recordBoot(now, windowSeconds);
        } catch (RuntimeException e) {
            LOG.debug("[supervisor] 熔断器读写失败（fail-open，不熔断）: {}", e.getMessage());
            return false;
        }
        boolean tripped = maxRestarts > 0 && boots.size() >= maxRestarts;
        if (tripped) {
            LOG.warn("[supervisor] 熔断触发：{}s 窗口内已有 {} 次带待续跑活的启动（阈值 {}）——"
                            + " 本次跳过自动续跑以打断重启动循环；台账里的义务留在原地，下一条真消息 /"
                            + " 下次启动会继续。误判可删 {}",
                    windowSeconds, boots.size(), maxRestarts, restartLoopFile);
        }
        return tripped;
    }

    /** 便捷档：用她的默认阈值 3 次 / 60 秒。 */
    public boolean checkAndRecordInterruptedBoot() {
        return checkAndRecordInterruptedBoot(DEFAULT_MAX_RESTARTS, DEFAULT_WINDOW_SECONDS, null);
    }

    /** 窗口内还剩多少次带活启动（不含本次）。 */
    public List<Long> recentInterruptedBoots(int windowSeconds, Long nowOverride) {
        long now = nowOverride == null ? System.currentTimeMillis() : nowOverride.longValue();
        long cutoff = now - Math.max(1L, windowSeconds) * 1000L;
        List<Long> out = new ArrayList<Long>();
        for (Long t : loadBoots()) {
            if (t.longValue() >= cutoff) {
                out.add(t);
            }
        }
        return out;
    }

    /** 干净关停 / 测试收尾时清掉熔断账。 */
    public void clearInterruptedBoots() {
        if (restartLoopFile == null) {
            return;
        }
        try {
            if (restartLoopFile.isFile()) {
                restartLoopFile.delete();
            }
        } catch (RuntimeException e) {
            LOG.debug("[supervisor] 清理熔断账本失败（忽略）: {}", e.getMessage());
        }
    }

    private synchronized List<Long> recordBoot(long now, int windowSeconds) {
        long cutoff = now - Math.max(1L, windowSeconds) * 1000L;
        List<Long> boots = new ArrayList<Long>();
        for (Long t : loadBoots()) {
            if (t.longValue() >= cutoff) {
                boots.add(t);
            }
        }
        boots.add(Long.valueOf(now));
        saveBoots(boots);
        return boots;
    }

    private List<Long> loadBoots() {
        List<Long> out = new ArrayList<Long>();
        if (restartLoopFile == null || !restartLoopFile.isFile()) {
            return out;
        }
        String raw = readSmall(restartLoopFile);
        if (raw == null) {
            return out;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            com.fasterxml.jackson.databind.JsonNode boots = node.path("boots");
            if (boots.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode e : boots) {
                    if (e.isNumber() || e.isTextual()) {
                        out.add(Long.valueOf(e.asLong()));
                    }
                }
            }
        } catch (IOException e) {
            // 坏文件 = 空账本（fail-open）
            LOG.debug("[supervisor] 熔断账本解析失败（按空处理）: {}", e.getMessage());
            return new ArrayList<Long>();
        }
        return out;
    }

    private void saveBoots(List<Long> boots) {
        if (restartLoopFile == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("{\"boots\":[");
        for (int i = 0; i < boots.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(boots.get(i).longValue());
        }
        sb.append("]}");
        File dir = restartLoopFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return;
        }
        try {
            File tmp = new File(restartLoopFile.getAbsolutePath() + ".tmp");
            OutputStream os = new java.io.FileOutputStream(tmp);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), restartLoopFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), restartLoopFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.debug("[supervisor] 熔断账本落盘失败（忽略，内存账还在）: {}", e.getMessage());
        }
    }

    private static String readSmall(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int len;
            while ((len = in.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读都读完了，关不掉不影响
                }
            }
        }
    }

    // ===== 陈旧实例锁自愈 =====

    /**
     * 一次网关实例占用的锁。身份 = <b>pid + 进程启动时刻</b>（与
     * {@link DeliveryLedger.OsProcessLiveness} 同一套判据），所以「pid 被回收给别的进程」
     * 不会让新网关误信旧锁还有效。
     */
    public static final class InstanceLock {

        private final File file;
        private final long pid;
        private final Long startedAt;
        private final boolean onDisk;
        private final String note;
        private final AtomicBoolean released = new AtomicBoolean(false);

        InstanceLock(File file, long pid, Long startedAt, boolean onDisk, String note) {
            this.file = file;
            this.pid = pid;
            this.startedAt = startedAt;
            this.onDisk = onDisk;
            this.note = note;
        }

        /** 锁是否真落到了盘上（{@code false} = 目录不可写，纯内存记账，仍然算拿到锁）。 */
        public boolean isPersisted() {
            return onDisk;
        }

        public boolean isReleased() {
            return released.get();
        }

        public long pid() {
            return pid;
        }

        /** 供 {@code z-bot status} 与人读诊断的一行摘要。 */
        public String diagnostic() {
            return "pid=" + pid + " started_at=" + startedAt + " persisted=" + onDisk
                    + (note == null ? "" : " note=" + note);
        }

        /** 干净收尾时删掉自己的锁（幂等；只删内容仍是本实例的那份，绝不误删别人的新锁）。 */
        public boolean release() {
            if (file == null || !released.compareAndSet(false, true)) {
                return false;
            }
            try {
                String raw = Supervisor.readSmall(file);
                if (raw != null && raw.contains("\"pid\":" + pid)) {
                    return file.delete();
                }
                return false;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "InstanceLock(" + diagnostic() + ")";
        }
    }

    /**
     * 抢网关实例锁，带<b>陈旧锁自愈</b>：
     * <ul>
     *   <li>锁文件不存在 / 内容读不出 / pid 已死 / 启动时刻对不上（pid 被复用）⇒ 判陈旧，抢过来；</li>
     *   <li>锁文件归属一个真活着的进程 ⇒ 返回 {@code null}，调用方不该再起一个网关
     *       （两个网关同时 {@code sweepRecoverable} 会把重投预算烧双份，且互相踩 transcript）。</li>
     * </ul>
     *
     * <p>无 profile（{@code instanceLockFile == null}）时返回一个不落盘的锁 —— 单进程内测试场景
     * 不该被文件锁挡住，但也不该往真实 {@code ~/.zbot} 写东西（红线 1）。</p>
     */
    public InstanceLock tryAcquireInstanceLock() {
        return tryAcquireInstanceLock(new DeliveryLedger.OsProcessLiveness());
    }

    /** 存活判定可注入（单测要造「pid 在但启动时刻不同」这种真机器上造不出来的现场）。 */
    public synchronized InstanceLock tryAcquireInstanceLock(DeliveryLedger.ProcessLiveness probe) {
        long pid = DeliveryLedger.ownPid();
        Long startedAt = DeliveryLedger.ownStartedAt();
        if (instanceLockFile == null) {
            return new InstanceLock(null, pid, startedAt, false, "no-profile: 锁不落盘");
        }
        DeliveryLedger.ProcessLiveness p = probe == null ? new DeliveryLedger.OsProcessLiveness() : probe;
        String note = null;
        String existing = readSmall(instanceLockFile);
        if (existing != null) {
            Long ownerPid = parsePid(existing);
            Long ownerStarted = parseStartedAt(existing);
            if (ownerPid != null && ownerPid.longValue() != pid && p.alive(ownerPid.longValue(), ownerStarted)) {
                LOG.error("[supervisor] 网关锁被活着的实例持有（pid {} started_at {}）—— 拒绝再起一个实例；"
                        + "锁文件 {}", ownerPid, ownerStarted, instanceLockFile);
                return null;
            }
            note = "steal-stale";
            LOG.warn("[supervisor] 发现陈旧网关锁（pid {} started_at {}，归属进程已死）—— 自愈接管",
                    ownerPid, ownerStarted);
        } else if (instanceLockFile.isFile()) {
            note = "unreadable-lock-file"; // 文件在但读不出：按陈旧处理
        }
        try {
            File dir = instanceLockFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return new InstanceLock(instanceLockFile, pid, startedAt, false, "mkdirs-failed");
            }
            String body = "{\"pid\":" + pid + ",\"started_at\":"
                    + (startedAt == null ? "null" : startedAt.toString()) + "}";
            File tmp = new File(instanceLockFile.getAbsolutePath() + ".tmp");
            OutputStream os = new java.io.FileOutputStream(tmp);
            try {
                os.write(body.getBytes("UTF-8"));
            } finally {
                os.close();
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), instanceLockFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), instanceLockFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("[supervisor] 网关锁落盘失败（{}）—— 仍视为拿到锁（内存记账），不影响本次启动", e.getMessage());
            return new InstanceLock(instanceLockFile, pid, startedAt, false, "write-failed");
        }
        return new InstanceLock(instanceLockFile, pid, startedAt, true, note);
    }

    /** 手写解析（不引 JSON 库依赖，锁文件是我们自己的一行格式）。 */
    static Long parsePid(String raw) {
        return parseLongField(raw, "pid");
    }

    static Long parseStartedAt(String raw) {
        return parseLongField(raw, "started_at");
    }

    private static Long parseLongField(String raw, String field) {
        if (raw == null) {
            return null;
        }
        String marker = "\"" + field + "\":";
        int at = raw.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int i = at + marker.length();
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) {
            i++;
        }
        if (i + 4 <= raw.length() && raw.startsWith("null", i)) {
            return null;
        }
        int start = i;
        while (i < raw.length() && Character.isDigit(raw.charAt(i))) {
            i++;
        }
        if (i == start) {
            return null;
        }
        try {
            return Long.valueOf(raw.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
