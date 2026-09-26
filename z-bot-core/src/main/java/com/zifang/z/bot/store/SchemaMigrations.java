package com.zifang.z.bot.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code state.db} 的版本化迁移阶梯（P15 起，替代 "只有 {@code CREATE TABLE IF NOT EXISTS}" 的假迁移）。
 *
 * <h3>为什么必须有版本号</h3>
 * <p>{@code ae5aff7} 的 {@code StateStore.initSchema} 只有 4 张表的 {@code CREATE TABLE IF NOT EXISTS}：
 * 表存在就整段跳过，<b>老库里永远加不上新列</b>，也没有任何地方能回答"这个库是什么 schema 版本"。
 * 加 {@code schema_version} 之后，DDL 变更才有一条可以一直走下去的阶梯。</p>
 *
 * <h3>两层守卫，缺一不可</h3>
 * <ul>
 *   <li><b>版本阶梯</b>：{@link #steps()}，每步只在 {@code from &lt; step.version} 时跑；步内所有 DDL/DML
 *       都可重入（{@code IF NOT EXISTS} / {@link #ensureColumn} / 归一后再跑就是 0 行），
 *       所以"把版本号拨回去再打开"既不炸也不重复加列；</li>
 *   <li><b>声明式收口</b>：阶梯跑完再 {@link #reconcile} 一次，把 head 声明的表/列补齐。
 *       抄的是 hermes {@code _reconcile_columns}（{@code hermes_state.py:2684}）的理由——
 *       版本号会因插入/重排而漏跑某一步，声明式 diff 是唯一的兜底。</li>
 * </ul>
 *
 * <h3>并发安全</h3>
 * <p>整段迁移在一个 {@code BEGIN IMMEDIATE} 事务里完成（{@link SqliteTx#writeOn}）。这不是风格问题：
 * deferred 事务里先 {@code PRAGMA table_info} 再 {@code ALTER TABLE}，在 WAL 下升级写锁会撞
 * {@code SQLITE_BUSY_SNAPSHOT}（SQLite 文档明确：这种升级不等 busy_timeout，立刻 BUSY），
 * 两个进程同时首开同一个新库就会有一个带着半套 schema 退出。</p>
 *
 * <h3>sessions 列对齐 hermes 的逐列结论</h3>
 * <p>她 46 列（{@code hermes_state.py:872-919}）。逐列的"要 / 不要 / 占位"理由表在
 * <b>{@code _doc/acceptance/p15b/sessions_column_alignment.md}</b>（P15 当时欠着这笔账，
 * 类注释里那句"理由表见本期 notes"当时指向一个不存在的东西，P15b 把表补上并改指向真文件）。
 * 结论摘要：实测判定分布 <b>要 14 / 不要 14 / 占位 18</b>（合计 46）。"要"的 14 列全部落在我们已有的
 * 15 列之内，其中 4 列是 P15 新加的（{@link #SESSIONS_ADDED}：{@code parent_session_id} 血统、
 * {@code archived} 软归档、{@code ended_at} + {@code end_reason} 的"已结束/在飞"闸门）。
 * 其余 32 列里 14 列判"不要"（内核没有数据源 / 是进程级而非会话级事实 / 功能根本不存在）、
 * 18 列判"占位"并各自点名了解锁期号（P12 token 与成本分档 7 列、P14 血统与压缩 4 列、
 * P16 通道身份与路由 7 列）。
 * 注意"占位"不是"以后再说"：无理由堆列等于把红线 2 的"0 消费者抽象"搬进 schema，
 * 所以这张表必须能被 {@code _doc/acceptance/p15b/p15b_check.py} 机械复算，
 * 而 {@code SessionsColumnAlignmentTest} 把"head 声明的每一列都得被表里的判定=要认领"
 * 钉在单测里 —— 以后谁往 sessions 加列而不进这张表，单测当场红。</p>
 */
public final class SchemaMigrations {

    /** 阶梯 head；加新步时递增，<b>永不复用或重排已有版本号</b>。 */
    public static final int HEAD_VERSION = 3;

    /** 无 {@code schema_version} 表（P2 的 4 表老库 / 全新库）时读到的版本。 */
    public static final int UNVERSIONED = 0;

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String TABLE_SCHEMA_VERSION = "schema_version";
    static final String TABLE_STATE_META = "state_meta";
    static final String TABLE_COMPRESSION_LOCKS = "compression_locks";
    static final String TABLE_GATEWAY_ROUTING = "gateway_routing";

    /** 本期新增的 sessions 列：列名 → 声明。顺序即加列顺序。 */
    static final Map<String, String> SESSIONS_ADDED = new LinkedHashMap<String, String>();

    static {
        SESSIONS_ADDED.put("parent_session_id", "TEXT");
        SESSIONS_ADDED.put("ended_at", "TEXT");
        SESSIONS_ADDED.put("end_reason", "TEXT");
        SESSIONS_ADDED.put("archived", "INTEGER NOT NULL DEFAULT 0");
    }

    /** head 期望存在的表（FTS 虚拟表不在内：那是可降级能力，见 {@code StateStore#isFtsEnabled()}）。 */
    public static List<String> requiredTables() {
        return Collections.unmodifiableList(Arrays.asList(
                "sessions", "messages", "session_model_usage", "async_delegations",
                TABLE_SCHEMA_VERSION, TABLE_STATE_META, TABLE_COMPRESSION_LOCKS, TABLE_GATEWAY_ROUTING));
    }

    /** head 期望存在的列（表 → 列名清单）；{@link #checkHead} 用它做校验。 */
    public static Map<String, List<String>> requiredColumns() {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put(TABLE_SCHEMA_VERSION, Arrays.asList("version"));
        m.put("sessions", Arrays.asList(
                "id", "title", "source", "model", "provider", "message_count", "tokens", "api_calls",
                "created_at", "updated_at", "metadata",
                "parent_session_id", "ended_at", "end_reason", "archived"));
        m.put("messages", Arrays.asList(
                "seq", "session_id", "idx", "role", "content", "content_type", "tool_name",
                "tool_call_id", "payload", "timestamp"));
        m.put("session_model_usage", Arrays.asList(
                "id", "session_id", "model", "prompt_tokens", "completion_tokens", "api_calls", "ts"));
        m.put("async_delegations", Arrays.asList(
                "id", "task", "status", "reply", "created_at", "finished_at"));
        m.put(TABLE_STATE_META, Arrays.asList("key", "value"));
        m.put(TABLE_COMPRESSION_LOCKS, Arrays.asList("session_id", "holder", "acquired_at", "expires_at"));
        m.put(TABLE_GATEWAY_ROUTING, Arrays.asList("scope", "session_key", "entry_json", "updated_at"));
        return m;
    }

    // ===== 阶梯 =====

    /** 一步迁移；{@link #apply} 必须可重入。 */
    public static final class Step {
        public final int version;
        public final String name;
        public final String what;

        Step(int version, String name, String what) {
            this.version = version;
            this.name = name;
            this.what = what;
        }

        void apply(Connection c, Report report) throws SQLException {
            if (version == 1) {
                baseline(c, report);
            } else if (version == 2) {
                p15Alignment(c, report);
            } else if (version == 3) {
                p15TimestampNormalization(c, report);
            } else {
                throw new SQLException("未知的迁移步骤版本: " + version);
            }
        }

        @Override
        public String toString() {
            return "v" + version + " " + name;
        }
    }

    private static final List<Step> ALL_STEPS = Collections.unmodifiableList(Arrays.asList(
            new Step(1, "legacy-baseline",
                    "P2 的 4 张表（sessions/messages/session_model_usage/async_delegations）"),
            new Step(2, "p15-schema-alignment",
                    "schema_version/state_meta/compression_locks/gateway_routing + sessions 加 "
                            + "parent_session_id/ended_at/end_reason/archived + 索引"),
            new Step(3, "p15-timestamp-normalization",
                    "messages/sessions 的 ISO 'T' 时间戳统一成 'yyyy-MM-dd HH:mm:ss'")));

    /** 阶梯（不可变视图）。 */
    public static List<Step> steps() {
        return ALL_STEPS;
    }

    /** v1：P2 原样的 4 张表。老库上整段是 no-op（全是 IF NOT EXISTS）。 */
    private static void baseline(Connection c, Report report) throws SQLException {
        exec(c, report, "CREATE TABLE IF NOT EXISTS sessions ("
                + " id TEXT PRIMARY KEY,"
                + " title TEXT NOT NULL DEFAULT '新会话',"
                + " source TEXT NOT NULL DEFAULT 'cli',"
                + " model TEXT, provider TEXT,"
                + " message_count INTEGER NOT NULL DEFAULT 0,"
                + " tokens INTEGER NOT NULL DEFAULT 0,"
                + " api_calls INTEGER NOT NULL DEFAULT 0,"
                + " created_at TEXT NOT NULL,"
                + " updated_at TEXT NOT NULL,"
                + " metadata TEXT)");
        exec(c, report, "CREATE TABLE IF NOT EXISTS messages ("
                + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " session_id TEXT NOT NULL,"
                + " idx INTEGER NOT NULL,"
                + " role TEXT, content TEXT, content_type TEXT,"
                + " tool_name TEXT, tool_call_id TEXT,"
                + " payload TEXT NOT NULL,"
                + " timestamp TEXT NOT NULL,"
                + " UNIQUE(session_id, idx))");
        exec(c, report, "CREATE TABLE IF NOT EXISTS session_model_usage ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " session_id TEXT NOT NULL,"
                + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
                + " api_calls INTEGER, ts TEXT NOT NULL)");
        exec(c, report, "CREATE TABLE IF NOT EXISTS async_delegations ("
                + " id TEXT PRIMARY KEY,"
                + " task TEXT NOT NULL,"
                + " status TEXT NOT NULL,"
                + " reply TEXT NOT NULL DEFAULT '',"
                + " created_at TEXT NOT NULL,"
                + " finished_at TEXT)");
    }

    /** v2：P15 的表/列/索引对齐。 */
    private static void p15Alignment(Connection c, Report report) throws SQLException {
        ensureTable(c, TABLE_SCHEMA_VERSION, "version INTEGER NOT NULL", report);
        // 键值簿记：迁移报告、自动 prune 上次执行时间等库级元数据落这里（本期真实消费者：迁移台账）
        ensureTable(c, TABLE_STATE_META, "key TEXT PRIMARY KEY, value TEXT", report);
        // 压缩锁（P14 抢锁）：expires_at 用 epoch 毫秒做数值比较，不碰文本时间戳
        ensureTable(c, TABLE_COMPRESSION_LOCKS,
                "session_id TEXT PRIMARY KEY, holder TEXT NOT NULL,"
                        + " acquired_at INTEGER NOT NULL, expires_at INTEGER NOT NULL", report);
        // 网关路由（P16 送达三件套的地基）：scope 隔离 profile/平台，session_key 是路由键
        ensureTable(c, TABLE_GATEWAY_ROUTING,
                "scope TEXT NOT NULL DEFAULT '', session_key TEXT NOT NULL, entry_json TEXT NOT NULL,"
                        + " updated_at INTEGER NOT NULL, PRIMARY KEY (scope, session_key)", report);

        for (Map.Entry<String, String> e : SESSIONS_ADDED.entrySet()) {
            ensureColumn(c, "sessions", e.getKey(), e.getValue(), report);
        }
    }

    /**
     * v3：数据迁移（声明式收口做不了的那一类）。
     *
     * <p>P2 的两条写路径用了两种时间格式：{@code upsertSession} 写
     * {@code yyyy-MM-dd HH:mm:ss}，{@code saveMessages} 写 {@code LocalDateTime.toString()}
     * （{@code 2024-01-05T09:12:03.123}，秒为 0 时还会把 {@code :00} 整个省掉）。混格式在字符串序
     * 比较下必错（{@code ' ' < 'T'}），prune 的 cutoff 与排序都会挑错行。
     * 幂等：归一后 LIKE 不再命中，重跑影响 0 行。</p>
     */
    private static void p15TimestampNormalization(Connection c, Report report) throws SQLException {
        report.normalizedTimestamps = normalizeIsoTimestamps(c, "messages", "timestamp", "seq", true)
                + normalizeIsoTimestamps(c, "sessions", "updated_at", "id", false)
                + normalizeIsoTimestamps(c, "sessions", "created_at", "id", false);
    }

    private static int normalizeIsoTimestamps(Connection c, String table, String column, String pk,
                                              boolean pkIsLong) throws SQLException {
        List<String> keys = new ArrayList<String>();
        List<String> values = new ArrayList<String>();
        String select = "SELECT " + q(pk) + ", " + q(column) + " FROM " + q(table)
                + " WHERE " + q(column) + " LIKE '____-__-__T%'";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(select)) {
            while (rs.next()) {
                String raw = rs.getString(2);
                String norm = toCanonical(raw);
                if (norm == null || norm.equals(raw)) {
                    continue;
                }
                keys.add(rs.getString(1));
                values.add(norm);
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }
        int n = 0;
        String sql = "UPDATE " + q(table) + " SET " + q(column) + "=? WHERE " + q(pk) + "=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < keys.size(); i++) {
                ps.setString(1, values.get(i));
                if (pkIsLong) {
                    ps.setLong(2, Long.parseLong(keys.get(i)));
                } else {
                    ps.setString(2, keys.get(i));
                }
                ps.addBatch();
            }
            for (int r : ps.executeBatch()) {
                n += Math.max(0, r);
            }
        }
        return n;
    }

    /** ISO-8601（可缺秒/带纳秒）→ {@code yyyy-MM-dd HH:mm:ss}；解析不了就原样返回。 */
    static String toCanonical(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim()).format(TS);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    // ===== 公共入口 =====

    /** 迁移结果；给 CLI/日志/断言用，不参与业务判断。 */
    public static final class Report {
        public int fromVersion = UNVERSIONED;
        public int toVersion = UNVERSIONED;
        public final List<String> appliedSteps = new ArrayList<String>();
        public final List<String> createdTables = new ArrayList<String>();
        public final List<String> createdIndexes = new ArrayList<String>();
        public final List<String> addedColumns = new ArrayList<String>();
        public int normalizedTimestamps;
        public boolean healedByReconcile;

        public String summary() {
            StringBuilder sb = new StringBuilder("schema ").append(fromVersion).append("->").append(toVersion);
            if (!appliedSteps.isEmpty()) {
                sb.append(" steps=").append(appliedSteps);
            }
            if (!createdTables.isEmpty()) {
                sb.append(" tables=").append(createdTables);
            }
            if (!addedColumns.isEmpty()) {
                sb.append(" columns=").append(addedColumns);
            }
            if (!createdIndexes.isEmpty()) {
                sb.append(" indexes=").append(createdIndexes);
            }
            if (normalizedTimestamps > 0) {
                sb.append(" timestampsNormalized=").append(normalizedTimestamps);
            }
            if (healedByReconcile) {
                sb.append(" reconciled=true");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return summary();
        }
    }

    /** 库的 schema 版本比本程序认识的高（老程序配新库）——这不是"坏库"，绝不许走重建。 */
    public static final class SchemaTooNewException extends SQLException {
        SchemaTooNewException(String msg) {
            super(msg);
        }
    }

    /** 读当前版本；没有 {@code schema_version} 表（P2 老库/新库）返回 {@link #UNVERSIONED}。 */
    public static int currentVersion(Connection c) throws SQLException {
        if (!hasTable(c, TABLE_SCHEMA_VERSION)) {
            return UNVERSIONED;
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM " + TABLE_SCHEMA_VERSION + " LIMIT 1")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return UNVERSIONED;
        }
    }

    /**
     * 打开库时的完整升级：读版本 → 按阶梯跑未应用的步 → 声明式收口 → 版本写到 head。
     * 整段在一个 {@code BEGIN IMMEDIATE} 事务里（撞锁按 {@link SqliteTx} 的抖动重试）。
     *
     * @throws SchemaTooNewException 库版本高于 {@link #HEAD_VERSION}（调用方必须停下来，不许重建）
     */
    public static Report migrate(Connection c, SqliteTx.Config cfg, SqliteTx.Counters counters)
            throws SQLException {
        final Report report = new Report();
        SqliteTx.writeOn(c, cfg, counters, new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection conn) throws SQLException {
                int from = currentVersion(conn);
                report.fromVersion = from;
                if (from > HEAD_VERSION) {
                    throw new SchemaTooNewException("state.db 的 schema 版本是 v" + from
                            + "，高于本程序支持的 v" + HEAD_VERSION + "；拒绝降级，也拒绝当成坏库重建");
                }
                ensureTable(conn, TABLE_SCHEMA_VERSION, "version INTEGER NOT NULL", report);
                for (Step step : ALL_STEPS) {
                    if (from >= step.version) {
                        continue;
                    }
                    step.apply(conn, report);
                    report.appliedSteps.add(step.name);
                    setVersion(conn, step.version); // 逐步落版本：中途崩溃后能从下一步续跑
                }
                int before = report.addedColumns.size() + report.createdTables.size();
                reconcile(conn, report);
                report.healedByReconcile =
                        (report.addedColumns.size() + report.createdTables.size()) > before;
                setVersion(conn, HEAD_VERSION);
                report.toVersion = HEAD_VERSION;
                return null;
            }
        });
        return report;
    }

    /** head 声明的表/列全在？返回问题清单（空 = 通过）。 */
    public static List<String> checkHead(Connection c) throws SQLException {
        List<String> problems = new ArrayList<String>();
        for (String table : requiredTables()) {
            if (!hasTable(c, table)) {
                problems.add("缺表: " + table);
                continue;
            }
            List<String> declared = requiredColumns().get(table);
            if (declared == null) {
                continue;
            }
            List<String> live = columnNames(c, table);
            for (String col : declared) {
                if (!live.contains(col)) {
                    problems.add("缺列: " + table + "." + col);
                }
            }
        }
        int v = currentVersion(c);
        if (v != HEAD_VERSION) {
            problems.add("schema_version=" + v + " != head " + HEAD_VERSION);
        }
        return problems;
    }

    /** 声明式收口（幂等）：补齐 head 的表、后续版本加的列和索引。对应 hermes {@code _reconcile_columns}。 */
    public static void reconcile(Connection c, Report report) throws SQLException {
        ensureTable(c, TABLE_SCHEMA_VERSION, "version INTEGER NOT NULL", report);
        ensureTable(c, TABLE_STATE_META, "key TEXT PRIMARY KEY, value TEXT", report);
        ensureTable(c, TABLE_COMPRESSION_LOCKS,
                "session_id TEXT PRIMARY KEY, holder TEXT NOT NULL,"
                        + " acquired_at INTEGER NOT NULL, expires_at INTEGER NOT NULL", report);
        ensureTable(c, TABLE_GATEWAY_ROUTING,
                "scope TEXT NOT NULL DEFAULT '', session_key TEXT NOT NULL, entry_json TEXT NOT NULL,"
                        + " updated_at INTEGER NOT NULL, PRIMARY KEY (scope, session_key)", report);
        if (hasTable(c, "sessions")) {
            for (Map.Entry<String, String> e : SESSIONS_ADDED.entrySet()) {
                ensureColumn(c, "sessions", e.getKey(), e.getValue(), report);
            }
        }
        index(c, report, "idx_messages_session", "messages(session_id, idx)");
        index(c, report, "idx_sessions_parent", "sessions(parent_session_id)");
        index(c, report, "idx_sessions_ended", "sessions(ended_at)");
        index(c, report, "idx_sessions_updated", "sessions(updated_at)");
        index(c, report, "idx_async_delegations_created", "async_delegations(created_at)");
        index(c, report, "idx_session_model_usage_session", "session_model_usage(session_id)");
    }

    // ===== 原语 =====

    /** 幂等加列：{@code PRAGMA table_info} 里没有这一列才 ALTER。去掉这个守卫，第二次打开必炸。 */
    static boolean ensureColumn(Connection c, String table, String column, String decl, Report report)
            throws SQLException {
        if (hasColumn(c, table, column)) {
            return false;
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + q(table) + " ADD COLUMN " + q(column) + " " + decl);
        }
        if (report != null) {
            report.addedColumns.add(table + "." + column);
        }
        return true;
    }

    private static void ensureTable(Connection c, String table, String columnDefs, Report report)
            throws SQLException {
        boolean existed = hasTable(c, table);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + q(table) + " (" + columnDefs + ")");
        }
        if (!existed && report != null && !report.createdTables.contains(table)) {
            report.createdTables.add(table);
        }
    }

    /** 建表原语：只有"这次真的建了表"才记进 {@code createdTables}（幂等重开必须是 no-op）。 */
    private static void exec(Connection c, Report report, String ddl) throws SQLException {
        String table = nameOfCreate(ddl);
        boolean known = table != null && hasTable(c, table);
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
        if (table != null && !known && report != null && !report.createdTables.contains(table)) {
            report.createdTables.add(table);
        }
    }

    private static boolean objectExists(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE name=? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 从 {@code CREATE ... IF NOT EXISTS <name> ...} 里取对象名；取不到返回 null。 */
    private static String nameOfCreate(String ddl) {
        String m = ddl.trim();
        String upper = m.toUpperCase();
        if (!upper.startsWith("CREATE TABLE") && !upper.startsWith("CREATE VIRTUAL TABLE")) {
            return null;
        }
        int exists = upper.indexOf("EXISTS");
        if (exists < 0) {
            return null;
        }
        String rest = m.substring(exists + 6).trim();
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '(' || Character.isWhitespace(ch)) {
                end = i;
                break;
            }
        }
        return unquote(rest.substring(0, end).trim());
    }

    private static String unquote(String ident) {
        if (ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"")) {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }

    private static void index(Connection c, Report report, String name, String defs) throws SQLException {
        boolean known = objectExists(c, name);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS " + q(name) + " ON " + defs);
        }
        // 索引进独立清单：混进 createdTables 会让"重开一次"看起来像建了 6 张表
        if (!known && report != null && !report.createdIndexes.contains(name)) {
            report.createdIndexes.add(name);
        }
    }

    public static boolean hasTable(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=? LIMIT 1")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        return columnNames(c, table).contains(column);
    }

    /** {@code PRAGMA table_info} 的列名清单；表不存在返回空表。 */
    public static List<String> columnNames(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + q(table) + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    /** 库里的用户表（不含 sqlite_ 影子表；含 FTS 虚拟表与其影子表，按名字可辨）。 */
    public static List<String> tableNames(Connection c) throws SQLException {
        List<String> out = new ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                             + " ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    static void setVersion(Connection c, int version) throws SQLException {
        try (Statement st = c.createStatement()) {
            int updated = st.executeUpdate("UPDATE " + TABLE_SCHEMA_VERSION + " SET version=" + version);
            if (updated == 0) {
                st.execute("INSERT INTO " + TABLE_SCHEMA_VERSION + " (version) VALUES (" + version + ")");
            }
        }
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private SchemaMigrations() {
    }
}
