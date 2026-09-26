package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.session.MsgCodec;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code <configDir>/state.db} 会话持久化 — SQLite + WAL + 版本化 schema。
 *
 * <h3>表（head = {@link SchemaMigrations#HEAD_VERSION}）</h3>
 * <ul>
 *   <li>{@code sessions} — 会话元信息；P15 起多 {@code parent_session_id}（压缩血统/子会话分叉）、
 *       {@code ended_at}+{@code end_reason}（"已结束/在飞"）、{@code archived}（软归档）；</li>
 *   <li>{@code messages} — 消息行，{@code (session_id, idx)} 唯一，{@code payload} 是完整事实来源
 *       （{@code MsgCodec} 的 JSON），所以 P15 <b>不给 messages 加列</b>：加列=双写，必分叉；</li>
 *   <li>{@code session_model_usage} — 模型×任务记账；</li>
 *   <li>{@code async_delegations} — 异步委托台账（P5）；</li>
 *   <li>{@code schema_version} / {@code state_meta} / {@code compression_locks}（P14 抢锁）/
 *       {@code gateway_routing}（P16 路由）；</li>
 *   <li>{@code messages_fts} — FTS5 虚拟表；运行时没有 FTS5 就整体降级 LIKE，不算坏库。</li>
 * </ul>
 *
 * <h3>P15 换掉的三件事</h3>
 * <ol>
 *   <li>DDL 不再由 {@code CREATE TABLE IF NOT EXISTS} 兜着，走 {@link SchemaMigrations} 阶梯 +
 *       声明式收口，打开时自动升到 head；</li>
 *   <li>写路径从"Java {@code synchronized} + busy_timeout=5000"改成
 *       {@code BEGIN IMMEDIATE} + 短 busy_timeout + 20–150ms 抖动重试（{@link SqliteTx}），
 *       并把撞锁/重试/放弃次数记进 {@link #writeCounters()}；</li>
 *   <li>打开库先 {@link DbRecovery#probe} 体检：FTS 索引烂掉就地重建（消息零丢失），
 *       真坏库先 {@code rename} 成 {@code .corrupt-<ts>} 再建空库，备份失败就直接抛、不许往下走。</li>
 * </ol>
 *
 * <p>{@code prune} 从"硬删空会话"扩成三态（{@link PruneCriteria}）：只删已结束、父删连带子会话
 * （沿 {@code parent_session_id}）、软 archive。{@code auto_prune} 默认关（她的默认也是关，
 * {@code cli.py:1914}）。旧签名 {@link #prune(int)} 行为保持兼容：过期 + 0 消息 + 未结束但从未用过。</p>
 */
public class StateStore {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** state_meta 里记迁移台账的键（{@code state_meta} 表本期的真实消费者）。 */
    public static final String META_LAST_MIGRATION = "schema_last_migration";
    public static final String META_LAST_AUTO_PRUNE = "last_auto_prune_at";

    /**
     * 打开/写库的参数。
     *
     * <p><b>刻意不读 {@code BotConfig}</b>：P11 那位代理正在改它，本期所有默认值放在这里，
     * 主编接配置时按 {@link #configKeys()} 的键名映射即可。</p>
     */
    public static final class Options implements SqliteTx.Config {
        private int busyTimeoutMillis = 1000;
        private int writeRetries = 15;
        private long retryMinMillis = 20;
        private long retryMaxMillis = 150;
        private boolean beginImmediate = true;
        private int checkpointEveryNWrites = 50;
        private boolean autoRecoverCorrupt = true;
        private boolean verifyOnOpen = true;
        private boolean autoPrune = false;
        private int retentionDays = 90;
        private boolean propagateWriteFailures = false;

        public static Options defaults() {
            return new Options();
        }

        /** 撞锁后的额外重试次数；0 = 关（只用于 A/B 实测，见类注释）。 */
        public Options writeRetries(int n) {
            this.writeRetries = Math.max(0, n);
            return this;
        }

        public Options busyTimeoutMillis(int ms) {
            this.busyTimeoutMillis = Math.max(0, ms);
            return this;
        }

        public Options retryWindowMillis(long min, long max) {
            this.retryMinMillis = Math.max(0, min);
            this.retryMaxMillis = Math.max(this.retryMinMillis, max);
            return this;
        }

        /**
         * 关掉 {@code BEGIN IMMEDIATE}（退回 SQLite 默认 deferred）。
         *
         * <p>生产不许关：这一条是给"去掉守卫必须变红"的 A/B 实测留的开关，
         * 关掉后读后写事务在 WAL 下会立即 {@code SQLITE_BUSY} 且不认 busy_timeout。</p>
         */
        public Options beginImmediate(boolean on) {
            this.beginImmediate = on;
            return this;
        }

        public Options checkpointEveryNWrites(int n) {
            this.checkpointEveryNWrites = n;
            return this;
        }

        /** 坏库处置：{@code false} = 只报错不备份重建。 */
        public Options autoRecoverCorrupt(boolean on) {
            this.autoRecoverCorrupt = on;
            return this;
        }

        /** 打开后校验 head schema（表/列齐不齐、版本到不到位）。 */
        public Options verifyOnOpen(boolean on) {
            this.verifyOnOpen = on;
            return this;
        }

        /** 自动清理（默认关）。打开后 {@link #maybeAutoPrune()} 才会真删。 */
        public Options autoPrune(boolean on) {
            this.autoPrune = on;
            return this;
        }

        public Options retentionDays(int days) {
            this.retentionDays = Math.max(0, days);
            return this;
        }

        /** 写事务重试耗尽后是否上抛。默认 {@code false}（沿用 P2 行为：记错误 + 计数，不打断会话）。 */
        public Options propagateWriteFailures(boolean on) {
            this.propagateWriteFailures = on;
            return this;
        }

        public boolean autoRecoverCorrupt() {
            return autoRecoverCorrupt;
        }

        public boolean verifyOnOpen() {
            return verifyOnOpen;
        }

        public boolean autoPrune() {
            return autoPrune;
        }

        public int retentionDays() {
            return retentionDays;
        }

        public boolean propagateWriteFailures() {
            return propagateWriteFailures;
        }

        @Override
        public int busyTimeoutMillis() {
            return busyTimeoutMillis;
        }

        @Override
        public int writeRetries() {
            return writeRetries;
        }

        @Override
        public long retryMinMillis() {
            return retryMinMillis;
        }

        @Override
        public long retryMaxMillis() {
            return retryMaxMillis;
        }

        @Override
        public boolean beginImmediate() {
            return beginImmediate;
        }

        @Override
        public int checkpointEveryNWrites() {
            return checkpointEveryNWrites;
        }

        /** 待主编接进 {@code BotConfig} 的键清单（本期代码里不读配置）。 */
        public static List<String> configKeys() {
            return Collections.unmodifiableList(java.util.Arrays.asList(
                    "agent.state.db.busy.timeout.ms", "agent.state.db.write.retries",
                    "agent.state.db.write.retry.min.ms", "agent.state.db.write.retry.max.ms",
                    "agent.state.db.write.begin.immediate", "agent.state.db.checkpoint.every.n",
                    "agent.state.db.auto.recover.corrupt", "agent.state.db.verify.on.open",
                    "agent.state.prune.auto", "agent.state.prune.retention.days"));
        }
    }

    /** 保留策略的一次性筛选条件（P15 三态）。 */
    public static final class PruneCriteria {
        private int beforeDays = 7;
        private boolean requireEnded = true;
        private boolean treatUnusedEmptyAsEnded = true;
        private boolean cascadeChildren = false;
        private boolean detachOrphans = true;
        private boolean includeArchived = true;
        private boolean archivedOnly = false;
        private boolean dryRun = false;
        private String source = null;
        private Integer maxMessages = null;

        public static PruneCriteria olderThanDays(int days) {
            PruneCriteria c = new PruneCriteria();
            c.beforeDays = days;
            return c;
        }

        public PruneCriteria beforeDays(int days) {
            this.beforeDays = days;
            return this;
        }

        /** (a) 只删"已结束"的会话；在飞的永不删。 */
        public PruneCriteria requireEnded(boolean on) {
            this.requireEnded = on;
            return this;
        }

        /** 兼容 P2 语义：0 消息且从未用过的会话按"已结束"处理。 */
        public PruneCriteria treatUnusedEmptyAsEnded(boolean on) {
            this.treatUnusedEmptyAsEnded = on;
            return this;
        }

        /** (b) 父被删时，子会话（沿 parent_session_id 递归）一起删。 */
        public PruneCriteria cascadeChildren(boolean on) {
            this.cascadeChildren = on;
            return this;
        }

        /** 子会话不在删除集合内时，把它的 parent_session_id 置空而不是连带删（她的做法）。 */
        public PruneCriteria detachOrphans(boolean on) {
            this.detachOrphans = on;
            return this;
        }

        public PruneCriteria includeArchived(boolean on) {
            this.includeArchived = on;
            return this;
        }

        /** 只在"已归档"的会话里选（软归档之后再硬删用）。 */
        public PruneCriteria archivedOnly(boolean on) {
            this.archivedOnly = on;
            if (on) {
                this.includeArchived = true;
            }
            return this;
        }

        public Integer maxMessages() {
            return maxMessages;
        }

        public String source() {
            return source;
        }

        public boolean archivedOnly() {
            return archivedOnly;
        }

        public boolean includeArchived() {
            return includeArchived;
        }

        public boolean detachOrphans() {
            return detachOrphans;
        }

        public boolean treatUnusedEmptyAsEnded() {
            return treatUnusedEmptyAsEnded;
        }

        public PruneCriteria source(String s) {
            this.source = s;
            return this;
        }

        /** 只删消息数 &lt;= 该值的会话；{@code null} = 不看消息数。 */
        public PruneCriteria maxMessages(Integer n) {
            this.maxMessages = n;
            return this;
        }

        public PruneCriteria dryRun(boolean on) {
            this.dryRun = on;
            return this;
        }

        public int beforeDays() {
            return beforeDays;
        }

        public boolean requireEnded() {
            return requireEnded;
        }

        public boolean cascadeChildren() {
            return cascadeChildren;
        }

        public boolean dryRun() {
            return dryRun;
        }

        String where() {
            StringBuilder sb = new StringBuilder("updated_at < ?");
            if (requireEnded) {
                sb.append(" AND (ended_at IS NOT NULL");
                if (treatUnusedEmptyAsEnded) {
                    sb.append(" OR message_count = 0");
                }
                sb.append(")");
            }
            if (maxMessages != null) {
                sb.append(" AND message_count <= ?");
            }
            if (source != null && !source.trim().isEmpty()) {
                sb.append(" AND source = ?");
            }
            if (archivedOnly) {
                sb.append(" AND archived = 1");
            } else if (!includeArchived) {
                sb.append(" AND archived = 0");
            }
            return sb.toString();
        }
    }

    /** 一次 prune/archive 的读数。 */
    public static final class PruneReport {
        public int matched;
        public int deleted;
        public int deletedChildren;
        public int detachedChildren;
        public int archived;
        public boolean dryRun;
        public final List<String> ids = new ArrayList<String>();

        public int totalDeleted() {
            return deleted + deletedChildren;
        }

        @Override
        public String toString() {
            return "matched=" + matched + " deleted=" + totalDeleted()
                    + " (根=" + deleted + " 连带子=" + deletedChildren + ")"
                    + " detached=" + detachedChildren + " archived=" + archived
                    + (dryRun ? " dryRun=true" : "");
        }
    }

    /** 会话血统链上的一行（{@link #lineageOf}）。 */
    public static final class LineageStep {
        public final String sessionId;
        public final String parentSessionId;
        public final String endReason;
        public final boolean ended;

        LineageStep(String sessionId, String parentSessionId, String endReason, boolean ended) {
            this.sessionId = sessionId;
            this.parentSessionId = parentSessionId;
            this.endReason = endReason;
            this.ended = ended;
        }
    }

    /** 网关路由行（{@code gateway_routing}，P16 消费）。 */
    public static final class GatewayRoute {
        public final String scope;
        public final String sessionKey;
        public final String entryJson;
        public final long updatedAtMillis;

        GatewayRoute(String scope, String sessionKey, String entryJson, long updatedAtMillis) {
            this.scope = scope;
            this.sessionKey = sessionKey;
            this.entryJson = entryJson;
            this.updatedAtMillis = updatedAtMillis;
        }
    }

    private final File dbFile;
    private final Options options;
    private final SqliteTx.Counters counters = new SqliteTx.Counters();
    /** P26：{@code session_model_usage} 是否已带 cache 两列（只探测一次）。 */
    private boolean cacheColumnsProbed;
    private boolean cacheColumnsPresent;
    private boolean ftsEnabled;
    private SchemaMigrations.Report lastMigration;
    private PruneReport lastAutoPrune;
    private File recoveredFromBackup;
    private String lastError;

    public StateStore(File dbFile) {
        this(dbFile, Options.defaults());
    }

    public StateStore(File dbFile, Options options) {
        this.dbFile = dbFile;
        this.options = options == null ? Options.defaults() : options;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc 不在 classpath", e);
        }
        openWithSelfHealing();
        // hermes 的启动路径：auto_prune 开着就在开库时清一次（默认 off ⇒ 完全 no-op）
        this.lastAutoPrune = maybeAutoPrune();
    }

    /** 本次开库的自动清理结果；{@code null} = 没跑（默认，{@code auto_prune} 关着）。 */
    public PruneReport lastAutoPrune() {
        return lastAutoPrune;
    }

    // ===== 打开 / 体检 / 自愈 =====

    /**
     * 体检 → 迁移 → 校验；不行就 <b>就地修 FTS</b>（零数据丢失）或 <b>备份 + 重建</b>。
     * 备份失败一定上抛，绝不"先删了再说"。
     */
    private void openWithSelfHealing() {
        SQLException lastSchema = null;
        for (int pass = 0; pass < 3; pass++) {
            DbRecovery.Probe probe = DbRecovery.probe(dbFile, options);
            if (!probe.healthy()) {
                if (!options.autoRecoverCorrupt()) {
                    throw new IllegalStateException("state.db 体检不过且已关闭自愈: " + probe);
                }
                if (probe.repairableInPlace()) {
                    dropFtsAndRetry("FTS 索引不可用（" + probe + "）");
                    continue;
                }
                backupAndRebuild(probe.reason == null ? probe.toString() : probe.reason);
                continue;
            }
            Connection c = null;
            try {
                c = SqliteTx.open(dbFile, options);
                lastMigration = SchemaMigrations.migrate(c, options, counters);
                ftsEnabled = initFts(c);
                if (options.verifyOnOpen()) {
                    List<String> problems = SchemaMigrations.checkHead(c);
                    if (!problems.isEmpty()) {
                        throw new SQLException("schema 校验未通过: " + problems);
                    }
                }
                if (!lastMigration.appliedSteps.isEmpty()) {
                    writeMigrationLedger(c, lastMigration);
                }
                return;
            } catch (SchemaMigrations.SchemaTooNewException e) {
                // 版本比程序新：不是坏库，重建就是丢数据，必须原地停下
                throw new IllegalStateException(e.getMessage(), e);
            } catch (SQLException e) {
                lastSchema = e;
                if (DbRecovery.isBusy(e)) {
                    continue; // 别的进程在迁移：抖动一下再来
                }
                if (!options.autoRecoverCorrupt()) {
                    throw new IllegalStateException("state.db 初始化失败: " + e.getMessage(), e);
                }
                if (DbRecovery.isCorruptionLike(e) && ftsObjectsExist()) {
                    dropFtsAndRetry("写路径撞上 FTS 损坏（" + e.getMessage() + "）");
                    continue;
                }
                backupAndRebuild(e.getMessage());
            } finally {
                SqliteTx.close(c);
            }
        }
        throw new IllegalStateException("state.db 反复打不开: " + dbFile.getAbsolutePath()
                + (lastSchema == null ? "" : " — " + lastSchema.getMessage()), lastSchema);
    }

    /** 就地修：删掉 FTS 虚拟表/触发器，下一轮 {@link #initFts} 会重建并从 messages 回填。 */
    private void dropFtsAndRetry(String reason) {
        int dropped;
        try {
            dropped = DbRecovery.dropFtsSchema(dbFile, options);
        } catch (SQLException e) {
            backupAndRebuild(reason + "；就地修失败: " + e.getMessage());
            return;
        }
        ftsEnabled = false;
        System.err.println("[StateStore] 就地修复 FTS（消息表未动、零丢失）: " + reason
                + "，已删除 " + dropped + " 个 FTS 对象，将从 messages 重建索引");
    }

    private boolean ftsObjectsExist() {
        Connection c = null;
        try {
            c = SqliteTx.open(dbFile, options);
            return SchemaMigrations.hasTable(c, "messages_fts");
        } catch (SQLException e) {
            return false;
        } finally {
            SqliteTx.close(c);
        }
    }

    /** 最后一档：原文件挪成 {@code .corrupt-<ts>}，原路径重建空库。备份失败即抛，不再往下走。 */
    private void backupAndRebuild(String reason) {
        try {
            recoveredFromBackup = DbRecovery.backupAside(dbFile, reason);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("state.db 不可用（" + reason + "）且备份失败，"
                    + "为防丢数据已停止重建: " + e.getMessage(), e);
        }
        ftsEnabled = false;
        System.err.println("[StateStore] 警告：state.db 判为不可用（" + reason + "），"
                + "原文件已备份到 " + recoveredFromBackup.getAbsolutePath() + "，正在重建空库；"
                + "旧数据需人工从备份恢复，不会被自动合并。");
    }

    private void writeMigrationLedger(Connection c, SchemaMigrations.Report report) {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO state_meta(key,value) VALUES(?,?)"
                        + " ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, META_LAST_MIGRATION);
            ps.setString(2, LocalDateTime.now().format(SchemaMigrations.TS) + " | " + report.summary());
            ps.executeUpdate();
        } catch (SQLException e) {
            counters.lastError = "state_meta 迁移台账写入失败: " + e.getMessage();
        }
    }

    /**
     * FTS5 索引：虚拟表 + 插入/删除触发器，索引行 {@code rowid == messages.seq}。
     *
     * <p>老库（P2）的触发器只插不删、且不绑定 rowid，索引里可能残留已删消息，所以检测到
     * {@code messages_ad} 缺失就整表重建一次。FTS 是派生数据，重建零丢失。</p>
     */
    private boolean initFts(Connection c) {
        try {
            boolean hasTable;
            boolean hasDeleteTrigger;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT (SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='messages_fts'),"
                            + " (SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='messages_ad')")) {
                hasTable = rs.next() && rs.getInt(1) > 0;
                hasDeleteTrigger = rs.getInt(2) > 0;
            }
            if (hasTable && !hasDeleteTrigger) {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP TABLE messages_fts");
                }
                hasTable = false;
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5("
                        + "session_id UNINDEXED, content)");
                st.execute("CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN"
                        + " INSERT INTO messages_fts(rowid, session_id, content)"
                        + " VALUES (new.seq, new.session_id, new.content); END");
                st.execute("CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN"
                        + " DELETE FROM messages_fts WHERE rowid = old.seq; END");
                if (!hasTable) {
                    // 首次建表（或就地修/重建之后）从 messages 回填，保证索引与表内容一致
                    st.execute("INSERT INTO messages_fts(rowid, session_id, content)"
                            + " SELECT seq, session_id, content FROM messages");
                }
            }
            return true;
        } catch (SQLException e) {
            System.err.println("[StateStore] FTS5 不可用，全文检索降级 LIKE: " + e.getMessage());
            return false;
        }
    }

    // ===== 只读/写通道 =====

    private <T> T read(SqliteTx.Body<T> body) {
        try {
            return SqliteTx.read(dbFile, options, counters, body);
        } catch (SQLException e) {
            lastError = e.getMessage();
            counters.lastError = e.getMessage();
            System.err.println("[StateStore] 读失败: " + e.getMessage());
            return null;
        }
    }

    private <T> T write(SqliteTx.Body<T> body) {
        try {
            return SqliteTx.write(dbFile, options, counters, body);
        } catch (SQLException e) {
            lastError = e.getMessage();
            counters.lastError = e.getMessage();
            if (options.propagateWriteFailures()) {
                throw new IllegalStateException("state.db 写失败: " + e.getMessage(), e);
            }
            System.err.println("[StateStore] 写失败（已重试 " + options.writeRetries()
                    + " 次仍不可得）: " + e.getMessage());
            return null;
        }
    }

    public File getDbFile() {
        return dbFile;
    }

    /** FTS5 可用（全文检索走 MATCH）；false 表示 LIKE 降级。 */
    public boolean isFtsEnabled() {
        return ftsEnabled;
    }

    public Options options() {
        return options;
    }

    /** 撞锁/重试/放弃/checkpoint 计数（"零 SQLITE_BUSY 冒到上层"的可观测出口）。 */
    public SqliteTx.Counters writeCounters() {
        return counters;
    }

    /** 最近一次读/写失败的原始错误；null = 没失败过。 */
    public String lastError() {
        return lastError;
    }

    /** 本次打开跑掉的迁移（含老库升级）；未迁移时 steps 为空。 */
    public SchemaMigrations.Report lastMigration() {
        return lastMigration;
    }

    /** 库当前 schema 版本。 */
    public synchronized int schemaVersion() {
        Integer v = read(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                return SchemaMigrations.currentVersion(c);
            }
        });
        return v == null ? -1 : v.intValue();
    }

    /** 自愈时把原文件备份到了哪里；null = 没备份过。 */
    public File recoveredFromBackup() {
        return recoveredFromBackup;
    }

    /** 手工触发一次 {@code wal_checkpoint(TRUNCATE)}。 */
    public synchronized boolean checkpointNow() {
        return SqliteTx.checkpointNow(dbFile, options, counters);
    }

    /** 立即跑一次 schema 校验（CLI {@code sessions version --check}）。 */
    public synchronized List<String> verifySchema() {
        List<String> problems = read(new SqliteTx.Body<List<String>>() {
            @Override
            public List<String> run(Connection c) throws SQLException {
                return SchemaMigrations.checkHead(c);
            }
        });
        return problems == null ? Collections.<String>emptyList() : problems;
    }

    // ===== sessions =====

    public synchronized void upsertSession(String id, String title, String model, String provider,
                                           int messageCount, long tokens, long apiCalls) {
        upsertSession(id, title, model, provider, messageCount, tokens, apiCalls, null);
    }

    /**
     * 带 source 的完整版；{@code null} 走 DDL 缺省 {@code 'cli'}。
     * <p>{@code created_at} 只在插入时写，冲突更新不覆盖（P2 的写法一样，但这里显式排除）。</p>
     */
    public synchronized void upsertSession(String id, String title, String model, String provider,
                                           final int messageCount, final long tokens, final long apiCalls,
                                           final String source) {
        final String now = LocalDateTime.now().format(SchemaMigrations.TS);
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO sessions(id,title,model,provider,message_count,tokens,api_calls,"
                                + "created_at,updated_at,source) VALUES(?,?,?,?,?,?,?,?,?,?)"
                                + " ON CONFLICT(id) DO UPDATE SET title=excluded.title, model=excluded.model,"
                                + " provider=excluded.provider, message_count=excluded.message_count,"
                                + " tokens=excluded.tokens, api_calls=excluded.api_calls,"
                                + " updated_at=excluded.updated_at, source=excluded.source");
                try {
                    ps.setString(1, id);
                    ps.setString(2, title == null ? "新会话" : title);
                    ps.setString(3, model);
                    ps.setString(4, provider);
                    ps.setInt(5, messageCount);
                    ps.setLong(6, tokens);
                    ps.setLong(7, apiCalls);
                    ps.setString(8, now);
                    ps.setString(9, now);
                    ps.setString(10, source == null ? "cli" : source);
                    ps.executeUpdate();
                } finally {
                    close(ps);
                }
                return null;
            }
        });
    }

    public synchronized void deleteSession(String id) {
        deleteSession(id, false);
    }

    /**
     * @param cascade {@code true} 时沿 {@code parent_session_id} 连带删子会话；
     *                {@code false} 时把子会话的父指针置空（留成根会话，她的做法）
     */
    public synchronized void deleteSession(String id, boolean cascade) {
        final String sid = id;
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                Set<String> victims = cascade ? expandLineage(c, Collections.singleton(sid))
                        : Collections.singleton(sid);
                if (!cascade) {
                    detachChildren(c, victims);
                }
                for (String victim : victims) {
                    deleteMessagesOf(c, victim);
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                        ps.setString(1, victim);
                        ps.executeUpdate();
                    }
                }
                return null;
            }
        });
    }

    private void deleteMessagesOf(Connection c, String sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
        deleteFts(c, sessionId);
    }

    private void deleteFts(Connection c, String sessionId) {
        if (!ftsEnabled) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages_fts WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized boolean sessionExists(String id) {
        final String sid = id;
        Boolean exists = read(new SqliteTx.Body<Boolean>() {
            @Override
            public Boolean run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sessions WHERE id=?")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        });
        return Boolean.TRUE.equals(exists);
    }

    public synchronized List<SessionRow> listSessions() {
        return listSessions(true);
    }

    /** @param includeArchived false 时软归档的会话不出现在列表里 */
    public synchronized List<SessionRow> listSessions(final boolean includeArchived) {
        List<SessionRow> out = read(new SqliteTx.Body<List<SessionRow>>() {
            @Override
            public List<SessionRow> run(Connection c) throws SQLException {
                String sql = "SELECT id,title,source,model,provider,message_count,tokens,api_calls,"
                        + "created_at,updated_at,parent_session_id,ended_at,end_reason,archived"
                        + " FROM sessions"
                        + (includeArchived ? "" : " WHERE archived = 0")
                        + " ORDER BY updated_at DESC";
                List<SessionRow> rows = new ArrayList<SessionRow>();
                try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(toRow(rs));
                    }
                }
                return rows;
            }
        });
        return out == null ? new ArrayList<SessionRow>() : out;
    }

    // ===== 会话生命周期（已结束 / 在飞）=====

    /** 标成已结束；已经结束的 no-op（第一个 end_reason 赢，她的 {@code end_session} 语义）。 */
    public synchronized boolean endSession(String id, String reason) {
        final String sid = id;
        final String now = LocalDateTime.now().format(SchemaMigrations.TS);
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE sessions SET ended_at=?, end_reason=? WHERE id=? AND ended_at IS NULL")) {
                    ps.setString(1, now);
                    ps.setString(2, reason == null ? "unspecified" : reason);
                    ps.setString(3, sid);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    /** 清掉 ended_at/end_reason，让会话重新"在飞"（压缩后回滚/resume 用）。 */
    public synchronized boolean reopenSession(String id) {
        final String sid = id;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE sessions SET ended_at=NULL, end_reason=NULL WHERE id=?")) {
                    ps.setString(1, sid);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    public synchronized boolean sessionEnded(String id) {
        final String sid = id;
        Boolean ended = read(new SqliteTx.Body<Boolean>() {
            @Override
            public Boolean run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM sessions WHERE id=? AND ended_at IS NOT NULL")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        });
        return Boolean.TRUE.equals(ended);
    }

    // ===== 血统（P14 的硬依赖）=====

    /**
     * 挂父：{@code childId} 的 {@code parent_session_id = parentId}。
     *
     * <p>防环的方向要说清：挂上去会成环，当且仅当 {@code childId} 已经在 {@code parentId} 的祖先链上
     * （即父是我的后代）。所以从 <b>parentId</b> 往上走找 childId。</p>
     *
     * @return false = 参数非法（自己挂自己 / 父不存在 / 会成环），库未改动
     */
    public synchronized boolean setParentSession(String childId, String parentId) {
        if (childId == null || childId.equals(parentId)) {
            return false;
        }
        if (parentId != null && !sessionExists(parentId)) {
            return false;
        }
        if (parentId != null && (parentId.equals(childId) || reachesAncestor(parentId, childId))) {
            return false; // 会成环
        }
        final String child = childId;
        final String parent = parentId;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE sessions SET parent_session_id=? WHERE id=?")) {
                    ps.setString(1, parent);
                    ps.setString(2, child);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    /** 建一个"分叉会话"：插入子会话并把父标成 {@code end_reason}。P14 压缩分叉走这一条。 */
    public synchronized String forkSession(String parentId, String childId, String title,
                                           String source, String endReason) {
        if (childId == null || childId.trim().isEmpty() || !sessionExists(childId.trim())) {
            return null;
        }
        String child = childId.trim();
        if (parentId != null && !setParentSession(child, parentId)) {
            return null;
        }
        if (parentId != null) {
            endSession(parentId, endReason == null ? "fork" : endReason);
        }
        return child;
    }

    public synchronized String parentOf(String sessionId) {
        final String sid = sessionId;
        String parent = read(new SqliteTx.Body<String>() {
            @Override
            public String run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT parent_session_id FROM sessions WHERE id=?")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }
        });
        return parent;
    }

    public synchronized List<SessionRow> listChildSessions(String parentId) {
        final String pid = parentId;
        List<SessionRow> out = read(new SqliteTx.Body<List<SessionRow>>() {
            @Override
            public List<SessionRow> run(Connection c) throws SQLException {
                List<SessionRow> rows = new ArrayList<SessionRow>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id,title,source,model,provider,message_count,tokens,api_calls,created_at,"
                                + "updated_at,parent_session_id,ended_at,end_reason,archived FROM sessions"
                                + " WHERE parent_session_id=? ORDER BY created_at")) {
                    ps.setString(1, pid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(toRow(rs));
                        }
                    }
                }
                return rows;
            }
        });
        return out == null ? new ArrayList<SessionRow>() : out;
    }

    /** 从自己往上走到根（最多 32 跳，防手工改库改出环）。 */
    public synchronized List<LineageStep> lineageOf(String sessionId) {
        final String sid = sessionId;
        List<LineageStep> out = read(new SqliteTx.Body<List<LineageStep>>() {
            @Override
            public List<LineageStep> run(Connection c) throws SQLException {
                List<LineageStep> chain = new ArrayList<LineageStep>();
                String cur = sid;
                for (int hop = 0; cur != null && hop < 32; hop++) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT parent_session_id, ended_at, end_reason FROM sessions WHERE id=?")) {
                        ps.setString(1, cur);
                        String parent;
                        boolean ended;
                        String reason;
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                break;
                            }
                            parent = rs.getString(1);
                            ended = rs.getString(2) != null;
                            reason = rs.getString(3);
                        }
                        chain.add(new LineageStep(cur, parent, reason, ended));
                        cur = parent;
                    }
                }
                Collections.reverse(chain);
                return chain;
            }
        });
        return out == null ? new ArrayList<LineageStep>() : out;
    }

    private boolean reachesAncestor(String fromId, String candidate) {
        String cur = fromId;
        for (int hop = 0; hop < 32 && cur != null; hop++) {
            if (cur.equals(candidate)) {
                return true;
            }
            cur = parentOf(cur);
        }
        return false;
    }

    /** 软归档 / 取消归档。 */
    public synchronized boolean archiveSession(String id, boolean archived) {
        final String sid = id;
        final int v = archived ? 1 : 0;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement("UPDATE sessions SET archived=? WHERE id=?")) {
                    ps.setInt(1, v);
                    ps.setString(2, sid);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    // ===== messages =====

    /** 全量替换某会话的消息（与内存里 {@code ConversationMemory} 保持一一对应）。 */
    public synchronized void saveMessages(String sessionId, List<Msg> messages) {
        final String sid = sessionId;
        final List<Msg> msgs = messages == null ? new ArrayList<Msg>() : new ArrayList<Msg>(messages);
        final String now = LocalDateTime.now().format(SchemaMigrations.TS);
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM messages WHERE session_id=?")) {
                    del.setString(1, sid);
                    del.executeUpdate();
                }
                deleteFts(c, sid);
                PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO messages(session_id,idx,role,content,content_type,tool_name,"
                                + "tool_call_id,payload,timestamp) VALUES(?,?,?,?,?,?,?,?,?)");
                try {
                    for (int i = 0; i < msgs.size(); i++) {
                        Map<String, Object> plain = MsgCodec.toPlain(msgs.get(i));
                        ins.setString(1, sid);
                        ins.setInt(2, i);
                        ins.setString(3, str(plain.get("role")));
                        ins.setString(4, str(plain.get("content")));
                        ins.setString(5, str(plain.get("contentType")));
                        ins.setString(6, str(plain.get("toolName")));
                        ins.setString(7, str(plain.get("toolCallId")));
                        ins.setString(8, MAPPER.writeValueAsString(plain));
                        ins.setString(9, now);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new SQLException("payload 序列化失败: " + e.getMessage(), e);
                } finally {
                    close(ins);
                }
                return null;
            }
        });
    }

    /**
     * 追加一条消息：一个事务里 <b>先读</b> {@code MAX(idx)} <b>再写</b>。
     *
     * <p>这条是 {@code BEGIN IMMEDIATE} 的靶子——deferred 事务在 WAL 下"读后升级写锁"必然立即
     * {@code SQLITE_BUSY}（SQLite 文档：这种升级不等 busy_timeout）。P14 逐条落消息也走它，
     * 不再让每轮全量重写 transcript。</p>
     *
     * @return 新行的 idx；失败返回 -1
     */
    public synchronized int appendMessage(String sessionId, Msg message) {
        final String sid = sessionId;
        final Msg msg = message;
        final String now = LocalDateTime.now().format(SchemaMigrations.TS);
        final int attempts = Math.max(1, options.writeRetries() + 1);
        for (int attempt = 0; attempt < attempts; attempt++) {
            Integer idx = write(new SqliteTx.Body<Integer>() {
                @Override
                public Integer run(Connection c) throws SQLException {
                    int next;
                    try (PreparedStatement q = c.prepareStatement(
                            "SELECT COALESCE(MAX(idx), -1) FROM messages WHERE session_id=?")) {
                        q.setString(1, sid);
                        try (ResultSet rs = q.executeQuery()) {
                            next = rs.next() ? rs.getInt(1) + 1 : 0;
                        }
                    }
                    Map<String, Object> plain = MsgCodec.toPlain(msg);
                    PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO messages(session_id,idx,role,content,content_type,tool_name,"
                                    + "tool_call_id,payload,timestamp) VALUES(?,?,?,?,?,?,?,?,?)");
                    try {
                        ins.setString(1, sid);
                        ins.setInt(2, next);
                        ins.setString(3, str(plain.get("role")));
                        ins.setString(4, str(plain.get("content")));
                        ins.setString(5, str(plain.get("contentType")));
                        ins.setString(6, str(plain.get("toolName")));
                        ins.setString(7, str(plain.get("toolCallId")));
                        ins.setString(8, MAPPER.writeValueAsString(plain));
                        ins.setString(9, now);
                        ins.executeUpdate();
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new SQLException("payload 序列化失败: " + e.getMessage(), e);
                    } finally {
                        close(ins);
                    }
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE sessions SET message_count=?, updated_at=?"
                                    + " WHERE id=? AND message_count<?")) {
                        up.setInt(1, next + 1);
                        up.setString(2, now);
                        up.setString(3, sid);
                        up.setInt(4, next + 1);
                        up.executeUpdate();
                    }
                    return next;
                }
            });
            if (idx != null) {
                return idx.intValue();
            }
            // idx 撞了 UNIQUE(session_id,idx)（跨进程同刻追加）：整段事务重放，重读 MAX(idx)
            if (!isConstraintViolation()) {
                return -1;
            }
        }
        return -1;
    }

    private boolean isConstraintViolation() {
        if (lastError == null) {
            return false;
        }
        String m = lastError.toLowerCase();
        return m.contains("constraint") || m.contains("unique") || m.contains("sqlite_constraint");
    }

    public synchronized List<Msg> loadMessages(String sessionId) {
        final String sid = sessionId;
        List<Msg> out = read(new SqliteTx.Body<List<Msg>>() {
            @Override
            public List<Msg> run(Connection c) throws SQLException {
                List<Msg> msgs = new ArrayList<Msg>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT payload FROM messages WHERE session_id=? ORDER BY idx")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                Map<String, Object> plain = MAPPER.readValue(rs.getString(1),
                                        new com.fasterxml.jackson.core.type.TypeReference
                                                <Map<String, Object>>() {
                                        });
                                Msg m = MsgCodec.fromPlain(plain);
                                if (m != null) {
                                    msgs.add(m);
                                }
                            } catch (java.io.IOException e) {
                                System.err.println("[StateStore] payload 解析失败（跳过该行）: " + e.getMessage());
                            }
                        }
                    }
                }
                return msgs;
            }
        });
        return out == null ? new ArrayList<Msg>() : out;
    }

    public synchronized int messageCount(String sessionId) {
        final String sid = sessionId;
        Integer n = read(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM messages WHERE session_id=?")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }
        });
        return n == null ? 0 : n.intValue();
    }

    // ===== 全文检索 =====

    /** 按关键词搜消息，返回命中 (session_id, 片段) 列表；FTS5 不可用时走 LIKE。 */
    public synchronized List<SearchHit> search(String keyword, int limit) {
        final String kw = keyword == null ? null : keyword.trim();
        final int n = limit;
        if (kw == null || kw.isEmpty()) {
            return new ArrayList<SearchHit>();
        }
        List<SearchHit> out = read(new SqliteTx.Body<List<SearchHit>>() {
            @Override
            public List<SearchHit> run(Connection c) throws SQLException {
                List<SearchHit> hits = new ArrayList<SearchHit>();
                if (ftsEnabled) {
                    // FTS5 MATCH 语法对引号/运算符敏感，包成短语字面量
                    String phrase = "\"" + kw.replace("\"", "\"\"") + "\"";
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT session_id, content FROM messages_fts WHERE messages_fts MATCH ? LIMIT ?")) {
                        ps.setString(1, phrase);
                        ps.setInt(2, n);
                        collect(ps, hits, kw);
                    }
                }
                if (hits.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT session_id, content FROM messages"
                                    + " WHERE content LIKE ? ORDER BY seq DESC LIMIT ?")) {
                        ps.setString(1, "%" + kw + "%");
                        ps.setInt(2, n);
                        collect(ps, hits, kw);
                    }
                }
                return hits;
            }
        });
        return out == null ? new ArrayList<SearchHit>() : out;
    }

    private static void collect(PreparedStatement ps, List<SearchHit> out, String kw) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String content = rs.getString(2);
                int at = content == null ? -1 : content.indexOf(kw);
                String snippet = at < 0 ? content
                        : content.substring(Math.max(0, at - 20),
                        Math.min(content.length(), at + kw.length() + 40));
                out.add(new SearchHit(rs.getString(1), snippet));
            }
        }
    }

    // ===== 统计 / 清理 =====

    public synchronized Stats stats() {
        Stats s = read(new SqliteTx.Body<Stats>() {
            @Override
            public Stats run(Connection c) throws SQLException {
                Stats st = new Stats();
                st.schemaVersion = SchemaMigrations.currentVersion(c);
                try (Statement q = c.createStatement(); ResultSet rs = q.executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(message_count),0), COALESCE(SUM(tokens),0),"
                                + " COALESCE(SUM(api_calls),0), SUM(CASE WHEN archived=1 THEN 1 ELSE 0 END),"
                                + " SUM(CASE WHEN ended_at IS NOT NULL THEN 1 ELSE 0 END),"
                                + " SUM(CASE WHEN parent_session_id IS NOT NULL THEN 1 ELSE 0 END) FROM sessions")) {
                    if (rs.next()) {
                        st.sessions = rs.getInt(1);
                        st.messages = rs.getLong(2);
                        st.tokens = rs.getLong(3);
                        st.apiCalls = rs.getLong(4);
                        st.archived = rs.getInt(5);
                        st.ended = rs.getInt(6);
                        st.lineageForks = rs.getInt(7);
                    }
                }
                try (Statement q = c.createStatement(); ResultSet rs = q.executeQuery(
                        "SELECT model, COUNT(*) FROM session_model_usage GROUP BY model")) {
                    while (rs.next()) {
                        st.usageByModel.put(rs.getString(1), rs.getLong(2));
                    }
                }
                try (Statement q = c.createStatement(); ResultSet rs = q.executeQuery(
                        "SELECT COUNT(*) FROM messages")) {
                    if (rs.next()) {
                        st.messageRows = rs.getLong(1);
                    }
                }
                return st;
            }
        });
        return s == null ? new Stats() : s;
    }

    /**
     * 兼容 P2 的 prune：删 {@code updated_at} 早于 N 天、且（已结束 <b>或</b> 0 消息）的会话。
     *
     * <p>与旧实现的两点差别：一并清 {@code messages} 与 FTS 行（旧实现只删 sessions 行，
     * 0 消息会话本来就没消息，所以等价）；不连带子会话（那是 {@link PruneCriteria#cascadeChildren}）。</p>
     */
    public synchronized int prune(int beforeDays) {
        return prune(PruneCriteria.olderThanDays(beforeDays));
    }

    public synchronized int prune(PruneCriteria criteria) {
        return pruneDetailed(criteria).totalDeleted();
    }

    /** 命中的会话 id（供 CLI {@code --dry-run} 与测试点名）。 */
    public synchronized List<String> listPruneCandidates(PruneCriteria c) {
        final PruneCriteria pc = c == null ? new PruneCriteria() : c;
        List<String> out = read(new SqliteTx.Body<List<String>>() {
            @Override
            public List<String> run(Connection conn) throws SQLException {
                List<String> ids = new ArrayList<String>();
                String sql = "SELECT id FROM sessions WHERE " + pc.where() + " ORDER BY updated_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindCutoffAndFilters(ps, pc);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getString(1));
                        }
                    }
                }
                return ids;
            }
        });
        return out == null ? new ArrayList<String>() : out;
    }

    /**
     * 三态保留策略落地：
     * (a) {@code requireEnded} 只删已结束；(b) {@code cascadeChildren} 沿血统连带删子会话、
     * 不在窗口内的子会话 {@code detachOrphans} 置空父指针；(c) 软归档走 {@link #archiveMatching}。
     */
    public synchronized PruneReport pruneDetailed(PruneCriteria criteria) {
        final PruneCriteria c = criteria == null ? new PruneCriteria() : criteria;
        final PruneReport report = new PruneReport();
        report.dryRun = c.dryRun();
        PruneReport done = write(new SqliteTx.Body<PruneReport>() {
            @Override
            public PruneReport run(Connection conn) throws SQLException {
                List<String> roots = new ArrayList<String>();
                String sql = "SELECT id FROM sessions WHERE " + c.where() + " ORDER BY updated_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindCutoffAndFilters(ps, c);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            roots.add(rs.getString(1));
                        }
                    }
                }
                report.matched = roots.size();
                if (roots.isEmpty() || c.dryRun()) {
                    report.ids.addAll(roots);
                    return report;
                }
                Set<String> victims = c.cascadeChildren()
                        ? expandLineage(conn, new LinkedHashSet<String>(roots))
                        : new LinkedHashSet<String>(roots);
                if (c.detachOrphans()) {
                    report.detachedChildren = detachChildren(conn, victims);
                }
                int actuallyDeleted = 0;
                for (String id : victims) {
                    deleteMessagesOf(conn, id);
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                        ps.setString(1, id);
                        actuallyDeleted += Math.max(0, ps.executeUpdate());
                    }
                    report.ids.add(id);
                }
                report.deleted = Math.min(actuallyDeleted, roots.size());
                report.deletedChildren = actuallyDeleted - report.deleted;
                return report;
            }
        });
        return done == null ? report : done;
    }

    /** (c) 软归档：只打标记不删数据；血统链整组归档（有未归档父链的分支不孤立）。 */
    public synchronized PruneReport archiveMatching(PruneCriteria criteria) {
        final PruneCriteria c = criteria == null ? new PruneCriteria() : criteria;
        final PruneReport report = new PruneReport();
        report.dryRun = c.dryRun();
        PruneReport done = write(new SqliteTx.Body<PruneReport>() {
            @Override
            public PruneReport run(Connection conn) throws SQLException {
                List<String> ids = new ArrayList<String>();
                String sql = "SELECT id FROM sessions WHERE " + c.where() + " AND archived = 0"
                        + " ORDER BY updated_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    bindCutoffAndFilters(ps, c);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getString(1));
                        }
                    }
                }
                report.matched = ids.size();
                if (ids.isEmpty() || c.dryRun()) {
                    report.ids.addAll(ids);
                    return report;
                }
                Set<String> group = expandLineage(conn, new LinkedHashSet<String>(ids));
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < group.size(); i++) {
                    in.append(i == 0 ? "?" : ",?");
                }
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE sessions SET archived=1 WHERE id IN (" + in + ") AND archived=0")) {
                    int i = 1;
                    for (String id : group) {
                        up.setString(i++, id);
                    }
                    report.archived = Math.max(0, up.executeUpdate());
                }
                report.ids.addAll(group);
                return report;
            }
        });
        return done == null ? report : done;
    }

    /** 硬删"已归档 & 早于 N 天"的会话（archive 之后再腾地方，两步走）。 */
    public synchronized int deleteArchived(int beforeDays) {
        return prune(PruneCriteria.olderThanDays(beforeDays)
                .requireEnded(false).archivedOnly(true).cascadeChildren(false));
    }

    /**
     * {@code auto_prune} 入口：默认关（她的 {@code cli.py:1914} 也是 {@code cfg.get("auto_prune", False)}）。
     * 开着时每天最多跑一次（记在 {@code state_meta}），只删 {@code retentionDays} 之前的已结束会话。
     *
     * @return null = 没跑（关着 / 今天已跑过）
     */
    public synchronized PruneReport maybeAutoPrune() {
        if (!options.autoPrune()) {
            return null;
        }
        String last = getMeta(META_LAST_AUTO_PRUNE);
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        if (last != null && last.startsWith(today)) {
            return null;
        }
        PruneReport r = pruneDetailed(PruneCriteria.olderThanDays(options.retentionDays())
                .requireEnded(true).cascadeChildren(false));
        setMeta(META_LAST_AUTO_PRUNE, LocalDateTime.now().format(SchemaMigrations.TS));
        return r;
    }

    private void bindCutoffAndFilters(PreparedStatement ps, PruneCriteria c) throws SQLException {
        String cutoff = LocalDateTime.now().minusDays(Math.max(0, c.beforeDays()))
                .format(SchemaMigrations.TS);
        ps.setString(1, cutoff);
        int i = 2;
        if (c.maxMessages() != null) {
            ps.setInt(i++, c.maxMessages().intValue());
        }
        if (c.source() != null && !c.source().trim().isEmpty()) {
            ps.setString(i++, c.source());
        }
    }

    /** 沿 parent_session_id 向下闭包（含自身）；最多 32 层，防手工改库改出环。 */
    private Set<String> expandLineage(Connection c, Set<String> seed) throws SQLException {
        Set<String> all = new LinkedHashSet<String>(seed);
        Set<String> frontier = new LinkedHashSet<String>(seed);
        for (int depth = 0; depth < 32 && !frontier.isEmpty(); depth++) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < frontier.size(); i++) {
                in.append(i == 0 ? "?" : ",?");
            }
            Set<String> next = new LinkedHashSet<String>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM sessions WHERE parent_session_id IN (" + in + ")")) {
                int i = 1;
                for (String id : frontier) {
                    ps.setString(i++, id);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String child = rs.getString(1);
                        if (all.add(child)) {
                            next.add(child);
                        }
                    }
                }
            }
            frontier = next;
        }
        return all;
    }

    /** 把"父在删除集合里、自己不在"的子会话的父指针置空，返回处理条数。 */
    private int detachChildren(Connection c, Set<String> victims) throws SQLException {
        if (victims.isEmpty()) {
            return 0;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < victims.size(); i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sessions SET parent_session_id=NULL WHERE parent_session_id IN (" + in + ")")) {
            int i = 1;
            for (String id : victims) {
                ps.setString(i++, id);
            }
            return Math.max(0, ps.executeUpdate());
        }
    }

    /** 导出某会话全部消息为 JSONL（每行一条消息的 payload）；sessionId 为 null 时导全部。 */
    public synchronized List<String> exportJsonl(String sessionId) {
        final String sid = sessionId;
        List<String> out = read(new SqliteTx.Body<List<String>>() {
            @Override
            public List<String> run(Connection c) throws SQLException {
                String sql = sid == null
                        ? "SELECT payload FROM messages ORDER BY session_id, idx"
                        : "SELECT payload FROM messages WHERE session_id=? ORDER BY idx";
                List<String> lines = new ArrayList<String>();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (sid != null) {
                        ps.setString(1, sid);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            lines.add(rs.getString(1));
                        }
                    }
                }
                return lines;
            }
        });
        return out == null ? new ArrayList<String>() : out;
    }

    // ===== 记账 =====

    public synchronized void recordUsage(String sessionId, String model,
                                         Integer promptTokens, Integer completionTokens, int apiCalls) {
        recordUsage(sessionId, model, promptTokens, completionTokens, apiCalls, null, null);
    }

    /**
     * P26：带 cache 读/写命中的记账。
     *
     * <p>口径：{@code prompt_tokens} 是上游报的输入总量（含 cache 读命中），
     * {@code cache_read_tokens} 是其中命中缓存的部分；两者分开记才能算出折后价
     * （{@code ModelUsage.Record#getBillablePromptTokens()}）。</p>
     *
     * <p>本仓不允许改 {@link SchemaMigrations}，所以 cache 列在**没迁移**的库上会自动退化为
     * 旧 5 列写入（不抛、不吞账）；列在位（迁移落地后）自动写全 7 列。列存在性只探测一次并缓存。</p>
     */
    public synchronized void recordUsage(String sessionId, String model,
                                         Integer promptTokens, Integer completionTokens, int apiCalls,
                                         final Long cacheReadTokens, final Long cacheWriteTokens) {
        final String sid = sessionId;
        final String m = model;
        final Integer pt = promptTokens;
        final Integer ct = completionTokens;
        final int calls = apiCalls;
        final boolean withCache = hasCacheColumns();
        final String now = LocalDateTime.now().format(SchemaMigrations.TS);
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                if (withCache) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO session_model_usage(session_id,model,prompt_tokens,completion_tokens,"
                                    + "api_calls,ts,cache_read_tokens,cache_write_tokens) VALUES(?,?,?,?,?,?,?,?)")) {
                        ps.setString(1, sid);
                        ps.setString(2, m);
                        ps.setObject(3, pt);
                        ps.setObject(4, ct);
                        ps.setInt(5, calls);
                        ps.setString(6, now);
                        ps.setObject(7, cacheReadTokens);
                        ps.setObject(8, cacheWriteTokens);
                        ps.executeUpdate();
                    }
                    return null;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO session_model_usage(session_id,model,prompt_tokens,completion_tokens,"
                                + "api_calls,ts) VALUES(?,?,?,?,?,?)")) {
                    ps.setString(1, sid);
                    ps.setString(2, m);
                    ps.setObject(3, pt);
                    ps.setObject(4, ct);
                    ps.setInt(5, calls);
                    ps.setString(6, now);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    /** {@code session_model_usage} 是否已经有 cache 两列（迁移没落地 ⇒ false，走旧写入）。 */
    public synchronized boolean hasCacheColumns() {
        if (cacheColumnsProbed) {
            return cacheColumnsPresent;
        }
        Boolean present = read(new SqliteTx.Body<Boolean>() {
            @Override
            public Boolean run(Connection c) throws SQLException {
                int read = 0;
                int write = 0;
                try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(session_model_usage)");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        if ("cache_read_tokens".equals(name)) {
                            read++;
                        } else if ("cache_write_tokens".equals(name)) {
                            write++;
                        }
                    }
                }
                return Boolean.valueOf(read == 1 && write == 1);
            }
        });
        cacheColumnsPresent = present != null && present.booleanValue();
        cacheColumnsProbed = true;
        return cacheColumnsPresent;
    }

    /** 探测结果作废（测试里对同一个实例 ALTER 过表之后必须重新探测）。 */
    public synchronized void invalidateCacheColumnProbe() {
        cacheColumnsProbed = false;
    }

    /**
     * 读一个 session 的用量合计：{@code [prompt, completion, apiCalls, cacheRead, cacheWrite]}。
     * cache 列不在位时后两项是 0（不是"没记"，是"记不下"——迁移方案见 EVIDENCE §11）。
     */
    public synchronized long[] usageTotals(final String sessionId) {
        final boolean withCache = hasCacheColumns();
        long[] got = read(new SqliteTx.Body<long[]>() {
            @Override
            public long[] run(Connection c) throws SQLException {
                long[] out = new long[] {0L, 0L, 0L, 0L, 0L};
                String sql = withCache
                        ? "SELECT COALESCE(SUM(prompt_tokens),0),COALESCE(SUM(completion_tokens),0),"
                          + "COALESCE(SUM(api_calls),0),COALESCE(SUM(cache_read_tokens),0),"
                          + "COALESCE(SUM(cache_write_tokens),0) FROM session_model_usage WHERE session_id=?"
                        : "SELECT COALESCE(SUM(prompt_tokens),0),COALESCE(SUM(completion_tokens),0),"
                          + "COALESCE(SUM(api_calls),0) FROM session_model_usage WHERE session_id=?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, sessionId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out[0] = rs.getLong(1);
                            out[1] = rs.getLong(2);
                            out[2] = rs.getLong(3);
                            if (withCache) {
                                out[3] = rs.getLong(4);
                                out[4] = rs.getLong(5);
                            }
                        }
                    }
                }
                return out;
            }
        });
        return got == null ? new long[] {0L, 0L, 0L, 0L, 0L} : got;
    }

    /** 异步委托台账落库：状态/回复覆盖更新，完成时补 finished_at。 */
    public synchronized void upsertDelegation(String id, String task, String status, String reply) {
        final String did = id;
        final String t = task;
        final String s = status;
        final String r = reply == null ? "" : reply;
        final boolean finished = "DONE".equals(status) || "FAILED".equals(status);
        final String now = java.time.Instant.now().toString();
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO async_delegations(id,task,status,reply,created_at,finished_at)"
                                + " VALUES(?,?,?,?,?,?)"
                                + " ON CONFLICT(id) DO UPDATE SET status=excluded.status,"
                                + " reply=excluded.reply, finished_at=excluded.finished_at")) {
                    ps.setString(1, did);
                    ps.setString(2, t);
                    ps.setString(3, s);
                    ps.setString(4, r);
                    ps.setString(5, now);
                    ps.setString(6, finished ? now : null);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    /** 最近 limit 条异步委托（新的在前），跨重启可见。 */
    public synchronized List<DelegationRow> listDelegations(int limit) {
        final int n = limit;
        List<DelegationRow> out = read(new SqliteTx.Body<List<DelegationRow>>() {
            @Override
            public List<DelegationRow> run(Connection c) throws SQLException {
                List<DelegationRow> rows = new ArrayList<DelegationRow>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, task, status, reply, created_at, finished_at FROM async_delegations"
                                + " ORDER BY created_at DESC LIMIT ?")) {
                    ps.setInt(1, n);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new DelegationRow(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getString(6)));
                        }
                    }
                }
                return rows;
            }
        });
        return out == null ? new ArrayList<DelegationRow>() : out;
    }

    // ===== state_meta =====

    public synchronized String getMeta(String key) {
        final String k = key;
        String v = read(new SqliteTx.Body<String>() {
            @Override
            public String run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM state_meta WHERE key=?")) {
                    ps.setString(1, k);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }
        });
        return v;
    }

    public synchronized void setMeta(String key, String value) {
        final String k = key;
        final String v = value;
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO state_meta(key,value) VALUES(?,?)"
                                + " ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                    ps.setString(1, k);
                    ps.setString(2, v);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    public synchronized boolean removeMeta(String key) {
        final String k = key;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM state_meta WHERE key=?")) {
                    ps.setString(1, k);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    // ===== 压缩锁（P14 消费；表在本期落地并带真实读写）=====

    /**
     * 抢同会话的压缩锁：过期即视为无主（她的 {@code compression_locks} 语义），
     * 所以持锁进程崩了不用人工清。
     */
    public synchronized boolean tryAcquireCompressionLock(String sessionId, String holder, long ttlMillis) {
        final String sid = sessionId;
        final String h = holder;
        final long now = System.currentTimeMillis();
        final long expires = now + Math.max(1000L, ttlMillis);
        Boolean ok = write(new SqliteTx.Body<Boolean>() {
            @Override
            public Boolean run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT holder, expires_at FROM compression_locks WHERE session_id=?")) {
                    ps.setString(1, sid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long live = rs.getLong(2);
                            if (rs.getString(1) != null && !rs.getString(1).equals(h) && live > now) {
                                return Boolean.FALSE; // 别人还在飞
                            }
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO compression_locks(session_id,holder,acquired_at,expires_at)"
                                + " VALUES(?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET"
                                + " holder=excluded.holder, acquired_at=excluded.acquired_at,"
                                + " expires_at=excluded.expires_at")) {
                    ps.setString(1, sid);
                    ps.setString(2, h);
                    ps.setLong(3, now);
                    ps.setLong(4, expires);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            }
        });
        return Boolean.TRUE.equals(ok);
    }

    /** 释放锁；只有持锁人能释放（{@code holder} 不匹配返回 false）。 */
    public synchronized boolean releaseCompressionLock(String sessionId, String holder) {
        final String sid = sessionId;
        final String h = holder;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM compression_locks WHERE session_id=? AND holder=?")) {
                    ps.setString(1, sid);
                    ps.setString(2, h);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    public synchronized String compressionLockHolder(String sessionId) {
        final String sid = sessionId;
        return read(new SqliteTx.Body<String>() {
            @Override
            public String run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT holder FROM compression_locks WHERE session_id=? AND expires_at>?")) {
                    ps.setString(1, sid);
                    ps.setLong(2, System.currentTimeMillis());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }
        });
    }

    /** 清掉过期锁，返回条数（崩溃自愈用）。 */
    public synchronized int clearExpiredCompressionLocks() {
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM compression_locks WHERE expires_at<=?")) {
                    ps.setLong(1, System.currentTimeMillis());
                    return ps.executeUpdate();
                }
            }
        });
        return n == null ? 0 : n.intValue();
    }

    // ===== 网关路由（P16 消费；表在本期落地并带真实读写）=====

    public synchronized void putGatewayRoute(String scope, String sessionKey, String entryJson) {
        final String sc = scope == null ? "" : scope;
        final String key = sessionKey;
        final String json = entryJson;
        final long now = System.currentTimeMillis();
        write(new SqliteTx.Body<Object>() {
            @Override
            public Object run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO gateway_routing(scope,session_key,entry_json,updated_at)"
                                + " VALUES(?,?,?,?) ON CONFLICT(scope,session_key) DO UPDATE SET"
                                + " entry_json=excluded.entry_json, updated_at=excluded.updated_at")) {
                    ps.setString(1, sc);
                    ps.setString(2, key);
                    ps.setString(3, json);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return null;
            }
        });
    }

    public synchronized String getGatewayRoute(String scope, String sessionKey) {
        final String sc = scope == null ? "" : scope;
        final String key = sessionKey;
        return read(new SqliteTx.Body<String>() {
            @Override
            public String run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT entry_json FROM gateway_routing WHERE scope=? AND session_key=?")) {
                    ps.setString(1, sc);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            }
        });
    }

    public synchronized List<GatewayRoute> listGatewayRoutes(String scope) {
        final String sc = scope == null ? "" : scope;
        List<GatewayRoute> out = read(new SqliteTx.Body<List<GatewayRoute>>() {
            @Override
            public List<GatewayRoute> run(Connection c) throws SQLException {
                List<GatewayRoute> rows = new ArrayList<GatewayRoute>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT scope, session_key, entry_json, updated_at FROM gateway_routing"
                                + " WHERE scope=? ORDER BY session_key")) {
                    ps.setString(1, sc);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new GatewayRoute(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getLong(4)));
                        }
                    }
                }
                return rows;
            }
        });
        return out == null ? new ArrayList<GatewayRoute>() : out;
    }

    public synchronized boolean deleteGatewayRoute(String scope, String sessionKey) {
        final String sc = scope == null ? "" : scope;
        final String key = sessionKey;
        Integer n = write(new SqliteTx.Body<Integer>() {
            @Override
            public Integer run(Connection c) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM gateway_routing WHERE scope=? AND session_key=?")) {
                    ps.setString(1, sc);
                    ps.setString(2, key);
                    return ps.executeUpdate();
                }
            }
        });
        return n != null && n.intValue() > 0;
    }

    // ===== 行类型 =====

    public static class SessionRow {
        public final String id;
        public final String title;
        public final String model;
        public final String provider;
        public final int messageCount;
        public final long tokens;
        public final long apiCalls;
        public final String createdAt;
        public final String updatedAt;
        /** P15 起有值：压缩分叉/子代理会话的血统。 */
        public final String source;
        public final String parentSessionId;
        public final String endedAt;
        public final String endReason;
        public final boolean archived;

        public SessionRow(String id, String title, String model, String provider, int messageCount,
                          long tokens, long apiCalls, String createdAt, String updatedAt) {
            this(id, title, model, provider, messageCount, tokens, apiCalls, createdAt, updatedAt,
                    "cli", null, null, null, false);
        }

        public SessionRow(String id, String title, String model, String provider, int messageCount,
                          long tokens, long apiCalls, String createdAt, String updatedAt,
                          String source, String parentSessionId, String endedAt, String endReason,
                          boolean archived) {
            this.id = id;
            this.title = title;
            this.model = model;
            this.provider = provider;
            this.messageCount = messageCount;
            this.tokens = tokens;
            this.apiCalls = apiCalls;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.source = source;
            this.parentSessionId = parentSessionId;
            this.endedAt = endedAt;
            this.endReason = endReason;
            this.archived = archived;
        }

        public boolean isEnded() {
            return endedAt != null;
        }
    }

    public static class SearchHit {
        public final String sessionId;
        public final String snippet;

        public SearchHit(String sessionId, String snippet) {
            this.sessionId = sessionId;
            this.snippet = snippet;
        }
    }

    public static class DelegationRow {
        public final String id;
        public final String task;
        public final String status;
        public final String reply;
        public final String createdAt;
        public final String finishedAt;

        public DelegationRow(String id, String task, String status, String reply,
                             String createdAt, String finishedAt) {
            this.id = id;
            this.task = task;
            this.status = status;
            this.reply = reply;
            this.createdAt = createdAt;
            this.finishedAt = finishedAt;
        }
    }

    public static class Stats {
        public int sessions;
        public long messages;
        public long tokens;
        public long apiCalls;
        /** P15 新增读数。 */
        public int archived;
        public int ended;
        public int lineageForks;
        public long messageRows;
        public int schemaVersion;
        public final Map<String, Long> usageByModel = new java.util.LinkedHashMap<String, Long>();
    }

    /** 结果集行 → {@link SessionRow}（按列名取，SELECT 顺序变了也不会错位）。 */
    static SessionRow toRow(ResultSet rs) throws SQLException {
        return new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("model"),
                rs.getString("provider"), rs.getInt("message_count"), rs.getLong("tokens"),
                rs.getLong("api_calls"), rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("source"), rs.getString("parent_session_id"), rs.getString("ended_at"),
                rs.getString("end_reason"), rs.getInt("archived") != 0);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static void close(PreparedStatement ps) {
        try {
            ps.close();
        } catch (SQLException ignored) {
        }
    }
}
