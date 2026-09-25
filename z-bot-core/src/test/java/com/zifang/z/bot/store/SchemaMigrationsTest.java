package com.zifang.z.bot.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P15 迁移阶梯：老库（{@code ae5aff7} 的 P2 DDL）→ head 的真迁移、幂等、声明式收口、拒降级。
 *
 * <p>老库 DDL 在本测试里是<b>手抄的字面量</b>，不从生产代码取——否则"迁移"等于自证。</p>
 */
public class SchemaMigrationsTest {

    /** ae5aff7 的 StateStore.initSchema() + initFts() 原文（4 张表 + 老索引 + 老 FTS）。 */
    private static final String[] LEGACY_DDL = {
            "CREATE TABLE sessions ("
                    + " id TEXT PRIMARY KEY,"
                    + " title TEXT NOT NULL DEFAULT '新会话',"
                    + " source TEXT NOT NULL DEFAULT 'cli',"
                    + " model TEXT, provider TEXT,"
                    + " message_count INTEGER NOT NULL DEFAULT 0,"
                    + " tokens INTEGER NOT NULL DEFAULT 0,"
                    + " api_calls INTEGER NOT NULL DEFAULT 0,"
                    + " created_at TEXT NOT NULL,"
                    + " updated_at TEXT NOT NULL,"
                    + " metadata TEXT)",
            "CREATE TABLE messages ("
                    + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " idx INTEGER NOT NULL,"
                    + " role TEXT, content TEXT, content_type TEXT,"
                    + " tool_name TEXT, tool_call_id TEXT,"
                    + " payload TEXT NOT NULL,"
                    + " timestamp TEXT NOT NULL,"
                    + " UNIQUE(session_id, idx))",
            "CREATE INDEX idx_messages_session ON messages(session_id, idx)",
            "CREATE TABLE session_model_usage ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
                    + " api_calls INTEGER, ts TEXT NOT NULL)",
            "CREATE TABLE async_delegations ("
                    + " id TEXT PRIMARY KEY,"
                    + " task TEXT NOT NULL,"
                    + " status TEXT NOT NULL,"
                    + " reply TEXT NOT NULL DEFAULT '',"
                    + " created_at TEXT NOT NULL,"
                    + " finished_at TEXT)",
            // 老库的 FTS：只有 AFTER INSERT 触发器、rowid 不绑定 messages.seq（P15 要修的洞）
            "CREATE VIRTUAL TABLE messages_fts USING fts5(session_id UNINDEXED, content)",
            "CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN"
                    + " INSERT INTO messages_fts(session_id, content) VALUES (new.session_id, new.content);"
                    + " END",
    };

    /** 老库里的真实数据：sessions 用 'yyyy-MM-dd HH:mm:ss'，messages 用 LocalDateTime.toString()。 */
    private static final String[] LEGACY_ROWS = {
            "INSERT INTO sessions(id,title,source,model,provider,message_count,tokens,api_calls,"
                    + "created_at,updated_at) VALUES"
                    + "('old-1','老会话一','cli','gpt-4o','openai',2,100,3,"
                    + "'2024-01-02 10:00:00','2024-01-02 11:00:00')",
            "INSERT INTO sessions(id,title,source,model,provider,message_count,tokens,api_calls,"
                    + "created_at,updated_at) VALUES"
                    + "('old-2','老会话二','web','glm-4','zhipu',1,50,1,"
                    + "'2024-01-03 09:00:00','2024-01-03 09:30:00')",
            "INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp) VALUES"
                    + "('old-1',0,'user','老消息一','TEXT',"
                    + "'{\"role\":\"user\",\"content\":\"老消息一\"}','2024-01-05T09:12:03.123')",
            "INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp) VALUES"
                    + "('old-1',1,'assistant','老消息二','TEXT',"
                    + "'{\"role\":\"assistant\",\"content\":\"老消息二\"}','2024-01-05T09:12:04')",
            "INSERT INTO messages(session_id,idx,role,content,content_type,payload,timestamp) VALUES"
                    + "('old-2',0,'user','QUANTUM 关键词','TEXT',"
                    + "'{\"role\":\"user\",\"content\":\"QUANTUM 关键词\"}','2024-01-05T09:13:00.5')",
            "INSERT INTO session_model_usage(session_id,model,prompt_tokens,completion_tokens,api_calls,ts)"
                    + " VALUES('old-1','gpt-4o',10,5,3,'2024-01-02T11:00:00')",
            "INSERT INTO async_delegations(id,task,status,reply,created_at,finished_at)"
                    + " VALUES('d-1','老委派','done','回话','2024-01-02 12:00:00','2024-01-02 12:05:00')",
    };

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbFile() throws Exception {
        // 每个用例独立目录，避免 WAL sidecar 串台
        return new File(tmp.newFolder("mig" + System.nanoTime()), "state.db");
    }

    private static void exec(Connection c, String... sql) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    /** 按 ae5aff7 的 DDL 手工建一个"改造之前的库"，并灌入数据。 */
    private File buildLegacyDb() throws Exception {
        Class.forName("org.sqlite.JDBC");
        File f = dbFile();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try {
            exec(c, LEGACY_DDL);
            exec(c, LEGACY_ROWS);
        } finally {
            c.close();
        }
        assertEquals("老库该有 3 行消息", 3, count(f, "messages"));
        return f;
    }

    private static int count(File f, String table) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception e) {
            throw new AssertionError("读 " + table + " 失败: " + e, e);
        }
    }

    private static int readVersion(File f) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception e) {
            throw new AssertionError("schema_version 读不到: " + e, e);
        }
    }

    private static String readOne(File f, String sql) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new AssertionError(sql + " 读失败: " + e, e);
        }
    }

    // ===== ① 真迁移：老库 → head，一行不丢 =====

    @Test
    public void migratesLegacyDatabaseWithoutLosingAnything() throws Exception {
        File f = buildLegacyDb();
        StateStore store = new StateStore(f);

        SchemaMigrations.Report r = store.lastMigration();
        assertNotNull("迁移报告不该为 null", r);
        assertEquals("老库是未登记的 v0", SchemaMigrations.UNVERSIONED, r.fromVersion);
        assertEquals(SchemaMigrations.HEAD_VERSION, r.toVersion);
        assertEquals("三级阶梯全跑: " + r.appliedSteps, 3, r.appliedSteps.size());
        assertTrue("parent_session_id 该是这次加的（P14 硬依赖）",
                r.addedColumns.contains("sessions.parent_session_id"));
        assertEquals("加了 4 列: " + r.addedColumns, 4, r.addedColumns.size());
        assertTrue(r.createdTables.contains("gateway_routing"));
        assertEquals("老库已有的 4 张表不该被记成『新建』: " + r.createdTables,
                Arrays.asList("schema_version", "state_meta", "compression_locks", "gateway_routing"),
                r.createdTables);
        assertTrue("血统/生命周期索引该由收口补齐: " + r.createdIndexes,
                r.createdIndexes.contains("idx_sessions_parent"));
        assertFalse("索引不许混进 tables: " + r.createdTables,
                r.createdTables.contains("idx_sessions_parent"));

        // 旧数据一行不少
        assertEquals(2, count(f, "sessions"));
        assertEquals(3, count(f, "messages"));
        assertEquals(1, count(f, "session_model_usage"));
        assertEquals(1, count(f, "async_delegations"));
        List<StateStore.SessionRow> rows = store.listSessions();
        assertEquals(2, rows.size());
        assertEquals("老会话二", rows.get(0).title); // updated_at DESC
        assertEquals(2, store.loadMessages("old-1").size());
        assertEquals("老消息一", store.loadMessages("old-1").get(0).getContent());

        // 老库没有 parent/ended/archived，迁移后必须是可读的空值而不是崩
        assertEquals(SchemaMigrations.HEAD_VERSION, store.schemaVersion());
        assertFalse(store.sessionEnded("old-1"));
        assertNullRow(store.parentOf("old-1"));
        assertEquals(1, store.listDelegations(10).size());
        assertEquals("老委派", store.listDelegations(10).get(0).task);
    }

    private static void assertNullRow(String parent) {
        assertTrue("新列初值该是 NULL，而不是空串/异常", parent == null);
    }

    @Test
    public void headSchemaIsReachedAndVerifiedOnLegacyDb() throws Exception {
        File f = buildLegacyDb();
        new StateStore(f);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            List<String> problems = SchemaMigrations.checkHead(c);
            assertTrue("迁移后不该还有缺项: " + problems, problems.isEmpty());
            for (String t : SchemaMigrations.requiredTables()) {
                assertTrue("缺表 " + t, SchemaMigrations.hasTable(c, t));
            }
        }
        assertEquals(SchemaMigrations.HEAD_VERSION, readVersion(f));
    }

    // ===== ② 幂等：反复开库不重复 ALTER、不动数据 =====

    @Test
    public void repeatedOpensAreIdempotent() throws Exception {
        File f = buildLegacyDb();
        new StateStore(f);
        int messagesAfterFirst = count(f, "messages");
        int sessionsAfterFirst = count(f, "sessions");

        for (int i = 0; i < 3; i++) {
            StateStore store = new StateStore(f);
            SchemaMigrations.Report r = store.lastMigration();
            assertTrue("第 " + i + " 次重开不该再跑步骤: " + r.appliedSteps, r.appliedSteps.isEmpty());
            assertTrue("重开不该再补列: " + r.addedColumns, r.addedColumns.isEmpty());
            assertTrue("重开不该再建表: " + r.createdTables, r.createdTables.isEmpty());
            assertFalse("schema 本就对齐，不该报自愈: " + r.summary(), r.healedByReconcile);
            assertEquals(SchemaMigrations.HEAD_VERSION, r.toVersion);
            assertEquals(SchemaMigrations.HEAD_VERSION, store.schemaVersion());
        }
        assertEquals(messagesAfterFirst, count(f, "messages"));
        assertEquals(sessionsAfterFirst, count(f, "sessions"));
        assertEquals("schema_version 只能有一行", SchemaMigrations.HEAD_VERSION, readVersion(f));
    }

    @Test
    public void rollingVersionBackReRunsStepsWithoutDuplicatingColumns() throws Exception {
        File f = buildLegacyDb();
        new StateStore(f);
        // 模拟"迁移跑到 v1 就掉电"：版本退回 1，步骤 2/3 必须能重放
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            exec(c, "UPDATE schema_version SET version=1");
        }
        StateStore store = new StateStore(f);
        SchemaMigrations.Report r = store.lastMigration();
        assertEquals(1, r.fromVersion);
        assertEquals("只剩 v2/v3: " + r.appliedSteps, 2, r.appliedSteps.size());
        assertFalse("列已在，不该重复 ALTER: " + r.addedColumns, r.addedColumns.contains("sessions.archived"));
        assertTrue(r.addedColumns.isEmpty());
        assertEquals(SchemaMigrations.HEAD_VERSION, store.schemaVersion());
        assertEquals(3, count(f, "messages"));
    }

    @Test
    public void reconcileHealsMissingColumnOutOfBand() throws Exception {
        File f = buildLegacyDb();
        new StateStore(f);
        // 别的版本/手工改库把列搞没了：声明式收口该补回来，而不是当坏库重建
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            exec(c, "ALTER TABLE sessions DROP COLUMN archived");
            assertFalse(SchemaMigrations.hasColumn(c, "sessions", "archived"));
        }
        StateStore store = new StateStore(f);
        assertTrue("收口应报自愈: " + store.lastMigration().summary(),
                store.lastMigration().healedByReconcile);
        assertTrue(store.lastMigration().addedColumns.contains("sessions.archived"));
        assertEquals("行没丢", 3, count(f, "messages"));
        assertTrue("体检该过: " + store.verifySchema(), store.verifySchema().isEmpty());
        for (File sibling : store.getDbFile().getParentFile().listFiles()) {
            assertFalse("不该走备份重建: " + sibling.getName(), sibling.getName().contains(".corrupt-"));
        }
    }

    // ===== ③ 数据迁移（v3：混格式时间戳归一） =====

    @Test
    public void mixedTimestampsAreNormalizedOnce() throws Exception {
        File f = buildLegacyDb();
        StateStore store = new StateStore(f);
        assertTrue("该有行被归一: " + store.lastMigration().normalizedTimestamps,
                store.lastMigration().normalizedTimestamps >= 3);
        assertEquals("messages.timestamp 不该再有 ISO 'T' 格式", "0",
                readOne(f, "SELECT COUNT(*) FROM messages WHERE timestamp LIKE '____-__-__T%'"));
        assertEquals("2024-01-05 09:12:03",
                readOne(f, "SELECT timestamp FROM messages WHERE session_id='old-1' ORDER BY idx LIMIT 1"));
        // 归一后 prune 的字符串比较才有意义
        assertEquals("2024-01-05 09:13:00",
                readOne(f, "SELECT timestamp FROM messages WHERE session_id='old-2'"));

        StateStore again = new StateStore(f);
        assertEquals("重跑影响 0 行", 0, again.lastMigration().normalizedTimestamps);
    }

    // ===== ④ 版本比程序新：拒降级，绝不重建 =====

    @Test
    public void refusesToOpenDatabaseNewerThanHead() throws Exception {
        File f = buildLegacyDb();
        new StateStore(f);
        int tooNew = SchemaMigrations.HEAD_VERSION + 7;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            exec(c, "UPDATE schema_version SET version=" + tooNew);
        }
        try {
            new StateStore(f);
            fail("版本比 head 新时必须停下，不许当成坏库重建");
        } catch (IllegalStateException e) {
            assertTrue("报错要说清是版本过高: " + e.getMessage(), e.getMessage().contains("schema"));
        }
        // 关键：文件没被挪走、数据还在
        assertTrue(f.exists());
        assertEquals(3, count(f, "messages"));
        for (File sibling : f.getParentFile().listFiles()) {
            assertFalse("不该产生 .corrupt 备份: " + sibling.getName(),
                    sibling.getName().contains(".corrupt-"));
        }
        assertEquals(tooNew, readVersion(f));
    }

    // ===== ⑤ 新库 & 老 FTS 重建 =====

    @Test
    public void freshDatabaseStartsAtHead() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        assertEquals(SchemaMigrations.HEAD_VERSION, store.schemaVersion());
        List<String> problems = store.verifySchema();
        assertTrue("新库对齐检查该是空的: " + problems, problems.isEmpty());
        assertEquals(3, store.lastMigration().appliedSteps.size());
        // 迁移台账落在 state_meta（新表的本期真实消费者）
        String ledger = store.getMeta(StateStore.META_LAST_MIGRATION);
        assertNotNull(ledger);
        assertTrue(ledger, ledger.contains("p15-schema-alignment"));
    }

    @Test
    public void legacyFtsWithoutDeleteTriggerIsRebuiltAndBackfilled() throws Exception {
        File f = buildLegacyDb();
        assertEquals("老库 FTS 里已有 3 行（rowid 与 seq 无关）", 3, count(f, "messages_fts"));
        StateStore store = new StateStore(f);
        assertTrue(store.isFtsEnabled());
        assertEquals("重建后索引行 rowid == messages.seq", "3",
                readOne(f, "SELECT COUNT(*) FROM messages_fts WHERE rowid IN"
                        + " (SELECT seq FROM messages)"));
        assertEquals("老库只有 messages_ai，重开后必须补上删除触发器", "1",
                readOne(f, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'"
                        + " AND name='messages_ad'"));
        // 删会话后 FTS 不该留幽灵行
        store.deleteSession("old-1");
        assertEquals(1, count(f, "messages"));
        assertEquals("FTS 里的已删消息必须一起走", 1, count(f, "messages_fts"));
        assertTrue(store.search("老消息一", 5).isEmpty());
        assertFalse(store.search("QUANTUM", 5).isEmpty());
    }

    @Test
    public void currentVersionIsZeroOnUnopenedFile() throws Exception {
        Class.forName("org.sqlite.JDBC");
        File f = dbFile();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY)");
        }
        try {
            assertEquals(SchemaMigrations.UNVERSIONED, SchemaMigrations.currentVersion(c));
        } finally {
            c.close();
        }
        assertTrue(f.exists());
    }
}
