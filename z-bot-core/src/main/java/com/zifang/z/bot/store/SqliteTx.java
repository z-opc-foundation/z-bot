package com.zifang.z.bot.store;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code state.db} 的连接与事务原语。
 *
 * <p>三件事，都是 hermes {@code hermes_state.py} 的口径（锚点见各方法注释），但按 z-bot 的
 * "一次操作一条连接" 形状重写：</p>
 * <ol>
 *   <li><b>WAL + 短 busy_timeout</b>：{@code journal_mode=WAL}、{@code synchronous=FULL}
 *       （macOS 上 NORMAL + 断电会留下坏 btree，她的 macOS 分支也是强制 FULL）、
 *       {@code busy_timeout} 默认 <b>1000ms</b> 而不是 5000ms —— 她的
 *       {@code SessionDB.__init__} 用的是 {@code timeout=1.0}，"长时间干等" 交给应用层抖动重试；</li>
 *   <li><b>{@code BEGIN IMMEDIATE}</b>：写事务在 <i>开始时</i> 就拿 WAL 写锁，
 *       而不是 commit 时才拿。WAL 下"先读后写"的 deferred 事务升级写锁时<b>不认 busy_timeout</b>
 *       （SQLite 文档：会立即 SQLITE_BUSY，因为等下去必然死锁），所以这一条不是优化而是必要条件，
 *       见 {@link StateStore#appendMessage} 的读后写事务；</li>
 *   <li><b>20–150ms 抖动重试</b>：撞锁就释放 Java 侧锁、随机睡 20–150ms 再战
 *       （她 {@code _execute_write} 的 {@code random.uniform(0.020, 0.150)} × 15 次），
 *       打散"多进程同刻重试"的 convoy 效应；每 {@code checkpointEveryNWrites} 次成功写
 *       做一次 best-effort {@code wal_checkpoint(TRUNCATE)}。</li>
 * </ol>
 *
 * <p>{@link Counters} 是<b>可观测性</b>：重试次数、撞锁次数、放弃次数、checkpoint 次数都计数，
 * 供单测/探针读数（"零 SQLITE_BUSY 冒到上层" 必须能量出来，不能靠断言者记忆）。</p>
 */
public final class SqliteTx {

    /** 事务参数；由 {@link StateStore.Options} 实现，避免两份配置面。 */
    public interface Config {
        /** 打开连接时的 {@code PRAGMA busy_timeout}（毫秒）。 */
        int busyTimeoutMillis();

        /** 撞锁后的<b>额外</b>重试次数；0 = 关闭重试（只用于 A/B 实测，见 {@link StateStore.Options}）。 */
        int writeRetries();

        /** 抖动下限（毫秒），她的 20ms。 */
        long retryMinMillis();

        /** 抖动上限（毫秒），她的 150ms。 */
        long retryMaxMillis();

        /** {@code true} = 写事务显式 {@code BEGIN IMMEDIATE}；{@code false} = SQLite 默认 deferred。 */
        boolean beginImmediate();

        /** 每 N 次成功写做一次 wal_checkpoint；&lt;=0 = 关。 */
        int checkpointEveryNWrites();
    }

    /** 写路径计数器（进程级，不是库级——同一 db 的多个 StateStore 实例各记各的）。 */
    public static final class Counters {
        public final AtomicLong writes = new AtomicLong();
        public final AtomicLong commits = new AtomicLong();
        public final AtomicLong busyCollisions = new AtomicLong();
        public final AtomicLong retries = new AtomicLong();
        public final AtomicLong givenUp = new AtomicLong();
        public final AtomicLong checkpoints = new AtomicLong();
        public volatile String lastError;

        @Override
        public String toString() {
            return "writes=" + writes.get() + " commits=" + commits.get()
                    + " busy=" + busyCollisions.get() + " retries=" + retries.get()
                    + " givenUp=" + givenUp.get() + " checkpoints=" + checkpoints.get()
                    + (lastError == null ? "" : " lastError=" + lastError);
        }
    }

    /** 事务体：拿到连接写就行，提交/回滚由本类负责（她 {@code _execute_write} 的 fn 同形）。 */
    public interface Body<T> {
        T run(Connection c) throws SQLException;
    }

    /** 一个"自成一统"的写事务：本方法负责 BEGIN/COMMIT/ROLLBACK，不做重试。 */
    public static <T> T transaction(Connection c, Config cfg, Body<T> body) throws SQLException {
        if (!c.getAutoCommit()) {
            // 驱动的 autoCommit=false 会自己包一层 BEGIN，与显式 BEGIN 冲突（xerial 直接报
            // "cannot start a transaction within a transaction"）；本类只在 autoCommit 下工作。
            c.setAutoCommit(true);
        }
        try (Statement st = c.createStatement()) {
            st.execute(cfg.beginImmediate() ? "BEGIN IMMEDIATE" : "BEGIN");
        }
        boolean done = false;
        try {
            T out = body.run(c);
            try (Statement st = c.createStatement()) {
                st.execute("COMMIT");
            }
            done = true;
            return out;
        } finally {
            if (!done) {
                try (Statement st = c.createStatement()) {
                    st.execute("ROLLBACK");
                } catch (SQLException ignored) {
                    // 已经在上抛了，回滚失败不覆盖原始异常
                }
            }
        }
    }

    /** 开一条连接并打上 WAL/busy_timeout/synchronous PRAGMA。 */
    public static Connection open(File dbFile, Config cfg) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=" + Math.max(0, cfg.busyTimeoutMillis()));
            st.execute("PRAGMA synchronous=FULL");
        } catch (SQLException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
        return c;
    }

    /**
     * 一次写事务：开连接 → {@code BEGIN IMMEDIATE} → 写 → 提交，撞锁则睡 20–150ms 重来
     * （整段事务重放，所以事务体必须可重入——{@code StateStore} 的写全是幂等的 upsert/替换）。
     *
     * @throws SQLException 非撞锁错误，或重试次数用尽
     */
    public static <T> T write(File dbFile, Config cfg, Counters counters, Body<T> body) throws SQLException {
        final int attempts = Math.max(1, cfg.writeRetries() + 1);
        SQLException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                counters.retries.incrementAndGet();
                if (!sleepJitter(cfg)) {
                    break; // 被中断：不再重试
                }
            }
            Connection c = null;
            try {
                counters.writes.incrementAndGet();
                c = open(dbFile, cfg);
                T out = transaction(c, cfg, body);
                counters.commits.incrementAndGet();
                maybeCheckpoint(c, cfg, counters);
                return out;
            } catch (SQLException e) {
                last = e;
                if (isBusy(e)) {
                    counters.busyCollisions.incrementAndGet();
                    continue;
                }
                counters.givenUp.incrementAndGet();
                counters.lastError = e.getMessage();
                throw e;
            } finally {
                close(c);
            }
        }
        counters.givenUp.incrementAndGet();
        counters.lastError = last == null ? "retry loop exited without an exception" : last.getMessage();
        throw (last != null ? last : new SQLException("state.db 写事务失败（原因未知）"));
    }

    /** 只读事务/查询：不开写锁，撞锁同样抖动重试（读在 WAL 下通常不阻塞，除非 schema 变更）。 */
    public static <T> T read(File dbFile, Config cfg, Counters counters, Body<T> body) throws SQLException {
        final int attempts = Math.max(1, cfg.writeRetries() + 1);
        SQLException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                counters.retries.incrementAndGet();
                if (!sleepJitter(cfg)) {
                    break;
                }
            }
            Connection c = null;
            try {
                c = open(dbFile, cfg);
                return body.run(c);
            } catch (SQLException e) {
                last = e;
                if (isBusy(e)) {
                    counters.busyCollisions.incrementAndGet();
                    continue;
                }
                counters.lastError = e.getMessage();
                throw e;
            } finally {
                close(c);
            }
        }
        if (last != null) {
            counters.lastError = last.getMessage();
            throw last;
        }
        return null;
    }

    /** 在<b>已有</b>连接上跑一次带抖动重试的写事务（迁移阶梯用：整段 DDL 要重放）。 */
    public static <T> T writeOn(Connection c, Config cfg, Counters counters, Body<T> body) throws SQLException {
        final int attempts = Math.max(1, cfg.writeRetries() + 1);
        SQLException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                counters.retries.incrementAndGet();
                if (!sleepJitter(cfg)) {
                    break;
                }
            }
            try {
                counters.writes.incrementAndGet();
                T out = transaction(c, cfg, body);
                counters.commits.incrementAndGet();
                return out;
            } catch (SQLException e) {
                last = e;
                if (isBusy(e)) {
                    counters.busyCollisions.incrementAndGet();
                    continue;
                }
                counters.givenUp.incrementAndGet();
                counters.lastError = e.getMessage();
                throw e;
            }
        }
        counters.givenUp.incrementAndGet();
        counters.lastError = last == null ? null : last.getMessage();
        throw (last != null ? last : new SQLException("state.db 迁移事务失败（原因未知）"));
    }

    /** 每 N 次成功写做一次 wal_checkpoint；失败只记不抛（checkpoint 是 best-effort 收缩 WAL）。 */
    static void maybeCheckpoint(Connection c, Config cfg, Counters counters) {
        int n = cfg.checkpointEveryNWrites();
        if (n <= 0 || counters.commits.get() % n != 0) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            counters.checkpoints.incrementAndGet();
        } catch (SQLException e) {
            counters.lastError = "wal_checkpoint: " + e.getMessage();
        }
    }

    /** 立即做一次 wal_checkpoint（CLI {@code sessions checkpoint} / 测试用）。 */
    public static boolean checkpointNow(File dbFile, Config cfg, Counters counters) {
        Connection c = null;
        try {
            c = open(dbFile, cfg);
            maybeForceCheckpoint(c, counters);
            return true;
        } catch (SQLException e) {
            counters.lastError = "wal_checkpoint: " + e.getMessage();
            return false;
        } finally {
            close(c);
        }
    }

    private static void maybeForceCheckpoint(Connection c, Counters counters) {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            counters.checkpoints.incrementAndGet();
        } catch (SQLException e) {
            counters.lastError = "wal_checkpoint: " + e.getMessage();
        }
    }

    /**
     * SQLITE_BUSY / SQLITE_LOCKED 判定：只看主结果码（{@code SQLITE_BUSY_SNAPSHOT}=513、
     * {@code SQLITE_BUSY_RECOVERY}=277 这些扩展码的主码同样是 5），message 兜底给
     * 不带结果码的驱动实现。
     */
    public static boolean isBusy(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                int code = ((SQLException) t).getErrorCode() & 0xff;
                if (code == 5 || code == 6) {
                    return true;
                }
            }
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("database is locked") || m.contains("database table is locked")
                        || m.contains("sqlite_busy") || m.contains("sqlite_locked")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 随机睡 min..max 毫秒；返回 false 表示线程被中断（调用方应停止重试）。 */
    private static boolean sleepJitter(Config cfg) {
        long min = Math.max(0, cfg.retryMinMillis());
        long max = Math.max(min, cfg.retryMaxMillis());
        long sleep = min + (max == min ? 0 : ThreadLocalRandom.current().nextLong(max - min + 1));
        try {
            Thread.sleep(sleep);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void close(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException ignored) {
        }
    }

    private SqliteTx() {
    }
}
