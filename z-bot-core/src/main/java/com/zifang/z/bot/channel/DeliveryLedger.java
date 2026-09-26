package com.zifang.z.bot.channel;

import com.zifang.z.bot.store.SqliteTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 送达义务台账（对标 {@code hermes gateway/delivery_ledger.py} 341 行）：
 * 「已经生成但还没确认投递出去」的那条最终回复，是网关唯一会<b>无声丢掉</b>的产物
 * （token 已经烧了、文本只在栈上、finalize 与平台 ACK 之间崩一次就什么都不剩）。
 * 红线 8 在这里的落法就是本类：<b>先落账，再产生副作用</b>。
 *
 * <p>发送前后的三个检查点（与她同序）：</p>
 * <pre>
 *   recordObligation()   state='pending'     任何发送尝试之前
 *   markAttempting()     state='attempting'  紧贴 send()
 *   markDelivered()  /   state='delivered'   只在 send() 成功之后
 *   markFailed()     /   state='failed'      明确的拒绝
 * </pre>
 *
 * <p>启动时 {@link #sweepRecoverable} 认领「归属进程已死」的未投递行交给网关重投。
 * 崩溃语义对歧义<b>显式</b>（她 #61790 那版 outbox 就是因为静默重发被关掉）：</p>
 * <ul>
 *   <li>{@code pending} —— 发送根本没开始：直接重投，无重复风险；</li>
 *   <li>{@code attempting} —— 死在 await 里：平台<b>可能已经</b>有这条了 ⇒ 重投带
 *       {@link #RECOVERED_MARKER} 前缀，让 at-least-once 的契约在用户可见处诚实；</li>
 *   <li>{@code failed} —— 被明确拒过一次，重启是天然的Retry 边界 ⇒ 同样带前缀；</li>
 *   <li>{@code delivered} —— 无事可做，保留期后清。</li>
 * </ul>
 *
 * <p>毒行不许空转：次数封顶 {@link #MAX_ATTEMPTS}、过期行 {@link #STALE_AFTER_MILLIS} 作废，
 * 两种都转 {@code abandoned}（留一小段时间供观察，然后被清）。owner 用 <b>pid + 进程启动时刻</b>
 * 标身份，防 pid 复用把新进程的活行当成旧进程的遗骨抢走。</p>
 *
 * <p>本类全程 best-effort：台账失败绝不能阻塞或拖慢真发送（调用方一律包 try/catch）。
 * 表由本类自己在 {@code state.db} 上 {@code CREATE TABLE IF NOT EXISTS}（她
 * {@code delivery_ledger._connect()} 同形），所以 schema 阶梯里没这张表时也不影响跑；
 * 写路径复用 P15 的 {@link SqliteTx}（WAL + {@code BEGIN IMMEDIATE} + 20–150ms 抖动重试 +
 * {@code synchronous=FULL}），跨进程双网关同时 sweep 不会双认领。</p>
 */
public final class DeliveryLedger {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryLedger.class);

    /** 表名（她同名）。 */
    public static final String TABLE = "delivery_obligations";

    /** 重投标记：崩溃于发送中途 / 被拒后重试 ⇒ 明示「可能是重复」。 */
    public static final String RECOVERED_MARKER =
            "♻️ 断点重投 —— 网关在投递途中重启过，这条可能是重复：\n\n";

    public static final int MAX_ATTEMPTS = 3;
    public static final long STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000;
    static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    static final int MAX_ROWS = 500;

    public static final String PENDING = "pending";
    public static final String ATTEMPTING = "attempting";
    public static final String DELIVERED = "delivered";
    public static final String FAILED = "failed";
    public static final String ABANDONED = "abandoned";

    /** 认领到的一条待重投义务。 */
    public static final class Claimed {

        public final String obligationId;
        public final String sessionKey;
        public final String platform;
        public final String chatId;
        public final String content;
        public final String stateBeforeClaim;
        public final int attemptsAfterClaim;
        private final boolean needsMarker;

        Claimed(String obligationId, String sessionKey, String platform, String chatId, String content,
                String stateBeforeClaim, boolean needsMarker, int attemptsAfterClaim) {
            this.obligationId = obligationId;
            this.sessionKey = sessionKey;
            this.platform = platform;
            this.chatId = chatId;
            this.content = content;
            this.stateBeforeClaim = stateBeforeClaim;
            this.attemptsAfterClaim = attemptsAfterClaim;
            this.needsMarker = needsMarker;
        }

        /** {@code true} = 歧义窗口（attempting/failed）⇒ 重投必须带 {@link #RECOVERED_MARKER}。 */
        public boolean needsMarker() {
            return needsMarker;
        }

        /** 把内容按本条的歧义性质包一次（幂等：已有标记不再叠）。 */
        public String contentForDelivery() {
            if (!needsMarker || content == null || content.startsWith(RECOVERED_MARKER)) {
                return content;
            }
            return RECOVERED_MARKER + content;
        }

        @Override
        public String toString() {
            return "Claimed(" + obligationId + " " + platform + ":" + chatId + " state=" + stateBeforeClaim
                    + " attempts=" + attemptsAfterClaim + " marker=" + needsMarker + ")";
        }
    }

    /**
     * 进程存活判定（她的 {@code _owner_alive}：pid + 启动时刻）。
     * 抽成接口不是装饰：{@link #sweepRecoverable} 用真实现，单测用桩注入「pid 存在但启动时刻不同」
     * 这种在测试机器上造不出来的状态。
     */
    public interface ProcessLiveness {
        boolean alive(long pid, Long startedAt);
    }

    private final File dbFile;
    private final SqliteTx.Config txConfig;
    private final SqliteTx.Counters counters = new SqliteTx.Counters();
    private final AtomicLong records = new AtomicLong();
    private final ProcessLiveness liveness;

    public DeliveryLedger(File dbFile, SqliteTx.Config cfg) {
        this(dbFile, cfg, new OsProcessLiveness());
    }

    public DeliveryLedger(File dbFile, SqliteTx.Config cfg, ProcessLiveness liveness) {
        this.dbFile = dbFile;
        this.txConfig = cfg == null ? com.zifang.z.bot.store.StateStore.Options.defaults() : cfg;
        this.liveness = liveness == null ? new OsProcessLiveness() : liveness;
        ensureTable();
    }

    // ===== 身份：pid + 进程启动时刻 =====

    /** 本进程 pid。 */
    public static long ownPid() {
        return OsProcessLiveness.currentPid();
    }

    /** 本进程启动时刻（与存活探测同源；读不出来则 {@code null}，按 hermes 语义退化成「只看 pid 在不在」）。 */
    public static Long ownStartedAt() {
        return OsProcessLiveness.currentStartStamp();
    }

    /**
     * 真·OS 存活探测（她的 {@code _owner_alive} 的 Java 等价）：
     * <ol>
     *   <li>{@code /proc/<pid>} 在 → 存在（Linux 快路径）；不在但 {@code ps -p <pid>} 说没有 → 死；</li>
     *   <li>启动时刻取 {@code /proc/<pid>/stat} 第 22 字段（tick 数，严格稳定）或
     *       {@code ps -o etimes=} 反推（macOS，epoch 毫秒）；</li>
     *   <li>只在<b>同源</b>前提下比大小（同一台机器只会走同一条路，与她 status.py 的注释同理），
     *       差出容差 ⇒ pid 已被复用 ⇒ 原主已死。</li>
     * </ol>
     */
    public static final class OsProcessLiveness implements ProcessLiveness {

        /** macOS/无 /proc 路径：{@code ps -o lstart} 解析出的 epoch 毫秒，同一进程两次读必然同值，留 2s 余量。 */
        static final long DERIVED_SLACK_MILLIS = 2_000L;
        /** /proc 路径：第 22 字段是「开机后 tick 数」，同一进程恒定；只允许 2 tick 的差。 */
        static final long PROC_TICK_SLACK = 2L;
        /** 数量级判源：epoch 毫秒 ≫ tick 数，据此选容差（同一台机器只会走同一条路，与她 status.py 同理）。 */
        static final long TICK_VS_MILLIS = 100_000_000_000L;

        private static volatile Long ownStamp;
        private static volatile long ownPid = -1L;

        @Override
        public boolean alive(long pid, Long startedAt) {
            if (pid <= 0L) {
                return false;
            }
            if (pid == currentPid()) {
                return true; // 自己拥有的行：本进程还活着
            }
            if (!exists(pid)) {
                return false;
            }
            Long live = startStampOf(pid);
            if (live == null) {
                return true; // 进程在但启动时刻读不出（权限/瞬时）—— 保守当活，不抢别人的行
            }
            if (startedAt == null) {
                return true;
            }
            long slack = Math.abs(live.longValue()) < TICK_VS_MILLIS ? PROC_TICK_SLACK : DERIVED_SLACK_MILLIS;
            long diff = live.longValue() - startedAt.longValue();
            return diff <= slack && diff >= -slack;
        }

        /** 本进程 pid（一次算好缓存；{@code RuntimeMXBean.getPid()} 是 Java 8 就有的 API）。 */
        public static long currentPid() {
            long cached = ownPid;
            if (cached >= 0L) {
                return cached;
            }
            long pid = 0L;
            try {
                String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                int at = name.indexOf('@');
                pid = Long.parseLong(at > 0 ? name.substring(0, at) : name.trim());
            } catch (RuntimeException e) {
                pid = 0L;
            }
            ownPid = pid;
            return pid;
        }

        /** 本进程启动时刻（与 {@link #startStampOf} 同源，一次算好缓存，避免每次投递 fork 一个 ps）。 */
        public static Long currentStartStamp() {
            Long cached = ownStamp;
            if (cached != null) {
                return cached;
            }
            Long stamp = startStampOf(currentPid());
            if (stamp != null) {
                ownStamp = stamp;
            }
            return stamp;
        }

        /** 进程是否存在：Linux 先看 {@code /proc/<pid>}，没有就退到 {@code ps -p}。 */
        static boolean exists(long pid) {
            if (pid <= 0L) {
                return false;
            }
            if (new File("/proc/" + pid).isDirectory()) {
                return true;
            }
            if (new File("/proc").isDirectory()) {
                return false; // 有 /proc 却没有这个目录 = 进程不在（Linux 语义），不必再问 ps
            }
            String out = runPs(pid, "pid=");
            return out != null && out.contains(String.valueOf(pid));
        }

        /** 进程启动时刻指纹（跨进程可比的同源值）；读不出来返回 {@code null}。 */
        static Long startStampOf(long pid) {
            if (pid <= 0L) {
                return null;
            }
            File stat = new File("/proc/" + pid + "/stat");
            if (stat.isFile()) {
                try {
                    String raw = readFileOneLine(stat);
                    // comm 字段可能带空格/括号：从最后一个 ')' 之后开始数，第 22 字段 = 下标 19（'(' 之前占 2 项）
                    int close = raw.lastIndexOf(')');
                    if (close < 0) {
                        return null;
                    }
                    String[] tail = raw.substring(close + 1).trim().split(" +");
                    // tail[0] 是第 3 字段 ⇒ 第 22 字段 = tail[19]
                    if (tail.length < 20) {
                        return null;
                    }
                    return Long.valueOf(Long.parseLong(tail[19]));
                } catch (IOException e) {
                    return null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            String lstart = runPs(pid, "lstart=");
            if (lstart == null) {
                return null;
            }
            return parseLstart(lstart);
        }

        /** {@code ps} 的 {@code lstart} 形如 {@code Fri Sep 25 21:05:15 2026}（本地时区，同机同源可比）。 */
        static Long parseLstart(String lstart) {
            if (lstart == null) {
                return null;
            }
            String normalized = lstart.trim().replaceAll(" +", " ");
            if (normalized.isEmpty()) {
                return null;
            }
            try {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                        "EEE MMM d HH:mm:ss yyyy", java.util.Locale.US);
                return Long.valueOf(fmt.parse(normalized).getTime());
            } catch (java.text.ParseException e) {
                return null;
            }
        }

        private static String runPs(long pid, String format) {
            Process p = null;
            try {
                // 不合并 stderr：ps 对不存在/越界的 pid 会把提示打到 stderr，
                // 那文本里就带着这个 pid 数字，合并进来会让 exists() 误判成「进程还在」。
                p = new ProcessBuilder("ps", "-p", String.valueOf(pid), "-o", format).start();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream is = p.getInputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                p.waitFor();
                return new String(bos.toByteArray(), "UTF-8").trim();
            } catch (Exception e) {
                if (p != null) {
                    p.destroy();
                }
                return null;
            }
        }

        private static String readFileOneLine(File f) throws IOException {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return new String(bos.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        }
    }

    // ===== DDL =====

    private void ensureTable() {
        try {
            SqliteTx.write(dbFile, txConfig, counters, new SqliteTx.Body<Object>() {
                @Override
                public Object run(Connection c) throws SQLException {
                    java.sql.Statement st = c.createStatement();
                    try {
                        st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                                + " obligation_id TEXT PRIMARY KEY,"
                                + " session_key TEXT NOT NULL,"
                                + " platform TEXT NOT NULL,"
                                + " chat_id TEXT NOT NULL,"
                                + " content TEXT NOT NULL,"
                                + " state TEXT NOT NULL,"
                                + " attempts INTEGER NOT NULL DEFAULT 0,"
                                + " created_at INTEGER NOT NULL,"
                                + " updated_at INTEGER NOT NULL,"
                                + " owner_pid INTEGER,"
                                + " owner_started_at INTEGER,"
                                + " last_error TEXT)");
                    } finally {
                        st.close();
                    }
                    return null;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("打不开送达台账（" + dbFile + "）: " + e.getMessage(), e);
        }
    }

    // ===== 稳定 id =====

    /**
     * {@code sha256(sessionKey|messageRef|content)} 前 24 位：同一轮 + 同一内容重复记账是幂等的，
     * 而同一 chat 下不同线程/不同触发消息永不撞（{@code messageRef} 是触发本轮的入站消息标识）。
     */
    public static String computeObligationId(String sessionKey, String messageRef, String content) {
        String payload = str(sessionKey) + "|" + str(messageRef) + "|" + str(content);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12 && i < digest.length; i++) {
                String hex = Integer.toHexString(digest[i] & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            // 退化路径：内容哈希不可用时用字面拼接的 hashCode + 长度（仍稳定，只是防撞变弱）
            return Integer.toHexString(payload.hashCode()) + "-" + payload.length();
        }
    }

    // ===== 三个检查点 =====

    /** 记下一条「欠平台一次投递」的最终回复（state='pending'）。 */
    public void recordObligation(final String obligationId, final String sessionKey, final String platform,
            final String chatId, final String content) {
        final long now = System.currentTimeMillis();
        final long pid = ownPid();
        final Long started = ownStartedAt();
        try {
            SqliteTx.write(dbFile, txConfig, counters, new SqliteTx.Body<Object>() {
                @Override
                public Object run(Connection c) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "INSERT OR REPLACE INTO " + TABLE + " (obligation_id, session_key, platform,"
                                    + " chat_id, content, state, attempts, created_at, updated_at,"
                                    + " owner_pid, owner_started_at, last_error)"
                                    // 12 列：attempts 恒 0（新建）、last_error 恒 NULL，其余 10 个是参数。
                                    // 占位符个数必须 = 10 —— 多一个就是 SQLite 报「13 values for 12 columns」，
                                    // 而本类是 best-effort（异常只记 WARN 不外抛），写不进去会静默成常态。
                                    + " VALUES (?,?,?,?,?,?,0,?,?,?,?,NULL)");
                    try {
                        ps.setString(1, obligationId);
                        ps.setString(2, str(sessionKey));
                        ps.setString(3, str(platform));
                        ps.setString(4, str(chatId));
                        ps.setString(5, str(content));
                        ps.setString(6, PENDING);
                        ps.setLong(7, now);
                        ps.setLong(8, now);
                        ps.setLong(9, pid);
                        if (started == null) {
                            ps.setNull(10, java.sql.Types.INTEGER);
                        } else {
                            ps.setLong(10, started.longValue());
                        }
                        ps.executeUpdate();
                    } finally {
                        ps.close();
                    }
                    return null;
                }
            });
        } catch (SQLException e) {
            LOG.warn("[delivery-ledger] record 失败 id={}: {}", obligationId, e.getMessage());
            return;
        }
        records.incrementAndGet();
        prune();
    }

    public boolean markAttempting(String obligationId) {
        return updateState(obligationId, ATTEMPTING, null);
    }

    public boolean markDelivered(String obligationId) {
        return updateState(obligationId, DELIVERED, null);
    }

    public boolean markFailed(String obligationId, String error) {
        return updateState(obligationId, FAILED, error);
    }

    private boolean updateState(final String obligationId, final String state, final String error) {
        final long now = System.currentTimeMillis();
        final String err = error == null || error.isEmpty() ? null
                : (error.length() > 500 ? error.substring(0, 500) : error);
        try {
            Integer n = SqliteTx.write(dbFile, txConfig, counters, new SqliteTx.Body<Integer>() {
                @Override
                public Integer run(Connection c) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + TABLE + " SET state=?, updated_at=?, last_error=?"
                                    + " WHERE obligation_id=?");
                    try {
                        ps.setString(1, state);
                        ps.setLong(2, now);
                        ps.setString(3, err);
                        ps.setString(4, obligationId);
                        return ps.executeUpdate();
                    } finally {
                        ps.close();
                    }
                }
            });
            return n != null && n.intValue() > 0;
        } catch (SQLException e) {
            LOG.warn("[delivery-ledger] {} 写入失败 id={}: {}", state, obligationId, e.getMessage());
            return false;
        }
    }

    // ===== 启动清扫 =====

    /** 见 {@link #sweepRecoverable(Set, Long)}，now 取当前时刻。 */
    public List<Claimed> sweepRecoverable(final Set<String> deliverablePlatforms) {
        return sweepRecoverable(deliverablePlatforms, null);
    }

    /**
     * 认领「归属进程已死」的未投递行并交出去重投。
     *
     * <p>认领是原子的：整段在一个 {@code BEGIN IMMEDIATE} 事务里，UPDATE 用
     * {@code WHERE obligation_id=? AND (owner_pid IS ? OR owner_pid=?)} 卡住旧归属，
     * 只有 {@code rowcount>0} 才算认领成功 ⇒ 第二个网关抢同一行必然空手。
     * 超过次数上限或过期的行转 {@code abandoned} 而不是返回。</p>
     *
     * <p>{@code deliverablePlatforms} 把认领限制在「本次启动真能发」的平台上：
     * {@code attempts} 是重投预算，若为一个连不上的平台认领，就是每启动一次烧一次预算、
     * 到顶却一次都没发出去。缺平台的行原地留着，由过期上限兜底。</p>
     */
    public List<Claimed> sweepRecoverable(final Set<String> deliverablePlatforms, final Long nowOverride) {
        final long now = nowOverride == null ? System.currentTimeMillis() : nowOverride.longValue();
        final long pid = ownPid();
        final Long started = ownStartedAt();
        final ProcessLiveness probe = liveness;
        List<Claimed> claimed;
        try {
            claimed = SqliteTx.write(dbFile, txConfig, counters, new SqliteTx.Body<List<Claimed>>() {
                @Override
                public List<Claimed> run(Connection c) throws SQLException {
                    List<Claimed> out = new ArrayList<Claimed>();
                    List<Object[]> rows = new ArrayList<Object[]>();
                    PreparedStatement sel = c.prepareStatement(
                            "SELECT obligation_id, session_key, platform, chat_id, content, state,"
                                    + " attempts, created_at, owner_pid, owner_started_at FROM " + TABLE
                                    + " WHERE state IN (?,?,?) ORDER BY created_at");
                    try {
                        sel.setString(1, PENDING);
                        sel.setString(2, ATTEMPTING);
                        sel.setString(3, FAILED);
                        ResultSet rs = sel.executeQuery();
                        try {
                            while (rs.next()) {
                                rows.add(new Object[] {
                                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                        rs.getString(5), rs.getString(6), Integer.valueOf(rs.getInt(7)),
                                        Long.valueOf(rs.getLong(8)),
                                        readNullableLong(rs, 9), readNullableLong(rs, 10)
                                });
                            }
                        } finally {
                            rs.close();
                        }
                    } finally {
                        sel.close();
                    }
                    for (Object[] row : rows) {
                        final String oid = (String) row[0];
                        final String state = (String) row[5];
                        int attempts = ((Integer) row[6]).intValue();
                        long createdAt = ((Long) row[7]).longValue();
                        Long ownerPid = (Long) row[8];
                        Long ownerStarted = (Long) row[9];
                        if (probe.alive(ownerPid == null ? 0L : ownerPid.longValue(), ownerStarted)) {
                            continue; // 还活着的网关拥有这一行
                        }
                        PreparedStatement upd;
                        if (attempts + 1 > MAX_ATTEMPTS || now - createdAt > STALE_AFTER_MILLIS) {
                            upd = c.prepareStatement(
                                    "UPDATE " + TABLE + " SET state=?, updated_at=? WHERE obligation_id=?");
                            try {
                                upd.setString(1, ABANDONED);
                                upd.setLong(2, now);
                                upd.setString(3, oid);
                                upd.executeUpdate();
                            } finally {
                                upd.close();
                            }
                            continue;
                        }
                        if (deliverablePlatforms != null && !deliverablePlatforms.contains(row[2])) {
                            continue; // 本次启动发不出去 ⇒ 不认领，别白烧预算
                        }
                        upd = c.prepareStatement(
                                "UPDATE " + TABLE + " SET owner_pid=?, owner_started_at=?, attempts=attempts+1,"
                                        + " updated_at=? WHERE obligation_id=? AND (owner_pid IS ? OR owner_pid=?)");
                        try {
                            upd.setLong(1, pid);
                            if (started == null) {
                                upd.setNull(2, java.sql.Types.INTEGER);
                            } else {
                                upd.setLong(2, started.longValue());
                            }
                            upd.setLong(3, now);
                            upd.setString(4, oid);
                            if (ownerPid == null) {
                                upd.setNull(5, java.sql.Types.INTEGER);
                            } else {
                                upd.setLong(5, ownerPid.longValue());
                            }
                            if (ownerPid == null) {
                                upd.setNull(6, java.sql.Types.INTEGER);
                            } else {
                                upd.setLong(6, ownerPid.longValue());
                            }
                            if (upd.executeUpdate() > 0) {
                                // pending = 发送根本没开始 ⇒ 直接重投；attempting/failed = 歧义 ⇒ 带标记
                                out.add(new Claimed(oid, (String) row[1], (String) row[2], (String) row[3],
                                        (String) row[4], state, !PENDING.equals(state), attempts + 1));
                            }
                        } finally {
                            upd.close();
                        }
                    }
                    return out;
                }
            });
        } catch (SQLException e) {
            LOG.warn("[delivery-ledger] sweep 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        return claimed == null ? Collections.<Claimed>emptyList() : claimed;
    }

    private static Long readNullableLong(ResultSet rs, int idx) throws SQLException {
        long v = rs.getLong(idx);
        return rs.wasNull() ? null : Long.valueOf(v);
    }

    // ===== 保留策略 =====

    /** 清掉过保留期的终态行 + 超总行数的最旧行（终态优先）；best-effort。 */
    void prune() {
        prune(null);
    }

    void prune(final Long nowOverride) {
        final long now = nowOverride == null ? System.currentTimeMillis() : nowOverride.longValue();
        final long cutoff = now - RETENTION_MILLIS;
        try {
            SqliteTx.write(dbFile, txConfig, counters, new SqliteTx.Body<Object>() {
                @Override
                public Object run(Connection c) throws SQLException {
                    PreparedStatement d1 = c.prepareStatement(
                            "DELETE FROM " + TABLE + " WHERE state IN (?,?) AND updated_at < ?");
                    try {
                        d1.setString(1, DELIVERED);
                        d1.setString(2, ABANDONED);
                        d1.setLong(3, cutoff);
                        d1.executeUpdate();
                    } finally {
                        d1.close();
                    }
                    int total = countAll(c);
                    int excess = Math.max(0, total - MAX_ROWS);
                    if (excess > 0) {
                        PreparedStatement d2 = c.prepareStatement(
                                "DELETE FROM " + TABLE + " WHERE obligation_id IN (SELECT obligation_id FROM "
                                        + TABLE + " ORDER BY CASE state WHEN '" + DELIVERED + "' THEN 0"
                                        + " WHEN '" + ABANDONED + "' THEN 1 ELSE 2 END, updated_at ASC LIMIT ?)");
                        try {
                            d2.setInt(1, excess);
                            d2.executeUpdate();
                        } finally {
                            d2.close();
                        }
                    }
                    return null;
                }
            });
        } catch (SQLException e) {
            LOG.debug("[delivery-ledger] prune 失败: {}", e.getMessage());
        }
    }

    // ===== 观察面（状态收敛 / 诊断）=====

    /** 各状态的行数快照（{@code pending/attempting/delivered/failed/abandoned} 恒有键）。 */
    public Map<String, Integer> countsByState() {
        Map<String, Integer> seed = new LinkedHashMap<String, Integer>();
        seed.put(PENDING, Integer.valueOf(0));
        seed.put(ATTEMPTING, Integer.valueOf(0));
        seed.put(DELIVERED, Integer.valueOf(0));
        seed.put(FAILED, Integer.valueOf(0));
        seed.put(ABANDONED, Integer.valueOf(0));
        try {
            Map<String, Integer> got = SqliteTx.read(dbFile, txConfig, counters,
                    new SqliteTx.Body<Map<String, Integer>>() {
                        @Override
                        public Map<String, Integer> run(Connection c) throws SQLException {
                            Map<String, Integer> m = new HashMap<String, Integer>();
                            PreparedStatement ps = c.prepareStatement(
                                    "SELECT state, COUNT(*) FROM " + TABLE + " GROUP BY state");
                            try {
                                ResultSet rs = ps.executeQuery();
                                while (rs.next()) {
                                    m.put(rs.getString(1), Integer.valueOf(rs.getInt(2)));
                                }
                            } finally {
                                ps.close();
                            }
                            return m;
                        }
                    });
            if (got != null) {
                seed.putAll(got);
            }
        } catch (SQLException e) {
            LOG.warn("[delivery-ledger] countsByState 失败: {}", e.getMessage());
        }
        return seed;
    }

    public int totalRows() {
        try {
            Integer n = SqliteTx.read(dbFile, txConfig, counters, new SqliteTx.Body<Integer>() {
                @Override
                public Integer run(Connection c) throws SQLException {
                    return Integer.valueOf(countAll(c));
                }
            });
            return n == null ? 0 : n.intValue();
        } catch (SQLException e) {
            LOG.warn("[delivery-ledger] totalRows 失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 某状态的全部义务 id（收敛断言用）。 */
    public List<String> idsInState(final String state) {
        try {
            List<String> out = SqliteTx.read(dbFile, txConfig, counters, new SqliteTx.Body<List<String>>() {
                @Override
                public List<String> run(Connection c) throws SQLException {
                    List<String> ids = new ArrayList<String>();
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT obligation_id FROM " + TABLE + " WHERE state=? ORDER BY created_at");
                    try {
                        ps.setString(1, state);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            ids.add(rs.getString(1));
                        }
                    } finally {
                        ps.close();
                    }
                    return ids;
                }
            });
            return out == null ? Collections.<String>emptyList() : out;
        } catch (SQLException e) {
            return Collections.emptyList();
        }
    }

    /** 一行摘要（{@code z-bot status} / 诊断用；她 {@code debug_rows} 同形）。 */
    public String debugRows(final int limit) {
        try {
            String s = SqliteTx.read(dbFile, txConfig, counters, new SqliteTx.Body<String>() {
                @Override
                public String run(Connection c) throws SQLException {
                    StringBuilder sb = new StringBuilder();
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT obligation_id, session_key, state, attempts, created_at, updated_at,"
                                    + " owner_pid, last_error FROM " + TABLE
                                    + " ORDER BY updated_at DESC LIMIT ?");
                    try {
                        ps.setInt(1, limit <= 0 ? 20 : limit);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(' ')
                                    .append(rs.getString(3)).append(" attempts=").append(rs.getInt(4))
                                    .append(" owner=").append(rs.getLong(7))
                                    .append(" err=").append(String.valueOf(rs.getString(8)))
                                    .append(System.lineSeparator());
                        }
                        rs.close();
                    } finally {
                        ps.close();
                    }
                    return sb.toString();
                }
            });
            return s == null ? "" : s;
        } catch (SQLException e) {
            return "";
        }
    }

    /** 写路径计数器（P15 同源；E2E 用它证明「不是台账自己在骗人」）。 */
    public SqliteTx.Counters counters() {
        return counters;
    }

    /** 本轮进程内成功 record 的次数（诊断用；台账写入失败不计）。 */
    public long recordsInThisProcess() {
        return records.get();
    }

    private static int countAll(Connection c) throws SQLException {
        PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + TABLE);
        try {
            ResultSet rs = ps.executeQuery();
            int n = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            return n;
        } finally {
            ps.close();
        }
    }

    /** 取一条义务的投递内容（重投路径与测试断言用）。 */
    public String contentOf(final String obligationId) {
        try {
            String s = SqliteTx.read(dbFile, txConfig, counters, new SqliteTx.Body<String>() {
                @Override
                public String run(Connection c) throws SQLException {
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT content FROM " + TABLE + " WHERE obligation_id=?");
                    try {
                        ps.setString(1, obligationId);
                        ResultSet rs = ps.executeQuery();
                        return rs.next() ? rs.getString(1) : null;
                    } finally {
                        ps.close();
                    }
                }
            });
            return s;
        } catch (SQLException e) {
            return null;
        }
    }

    private static String str(String in) {
        return in == null ? "" : in;
    }
}
