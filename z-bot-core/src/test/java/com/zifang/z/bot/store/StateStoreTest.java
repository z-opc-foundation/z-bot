package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** StateStore 的 CRUD / WAL / FTS(降级 LIKE) / 记账 / 清理 覆盖。 */
public class StateStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbFile() throws Exception {
        return new File(tmp.newFolder("db"), "state.db");
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    private static Msg assistant(String text) {
        return new Msg(MessageRole.ASSISTANT, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    @Test
    public void walModeIsEnabled() throws Exception {
        StateStore store = new StateStore(dbFile());
        // xerial 驱动连上第一个连接会自己建 -wal/-shm；journal_mode 读回来必须是 wal
        java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + store.getDbFile().getAbsolutePath());
        java.sql.ResultSet rs = c.createStatement().executeQuery("PRAGMA journal_mode");
        String mode = rs.next() ? rs.getString(1) : "";
        c.close();
        assertEquals("wal", mode.toLowerCase());
    }

    @Test
    public void sessionCrudAndMessagesRoundTrip() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "标题A", "bench", "openai", 0, 0, 0);
        assertTrue(store.sessionExists("s1"));
        assertFalse(store.sessionExists("s2"));

        List<Msg> msgs = Arrays.asList(user("调用 time 工具查时间"), assistant("当前时间戳是 1790265834"));
        store.saveMessages("s1", msgs);
        List<Msg> loaded = store.loadMessages("s1");
        assertEquals(2, loaded.size());
        assertEquals("调用 time 工具查时间", loaded.get(0).getContent());
        assertEquals(MessageRole.USER, loaded.get(0).getRole());
        assertEquals(MessageRole.ASSISTANT, loaded.get(1).getRole());

        store.upsertSession("s1", "标题A", "bench", "openai", 2, 123L, 2L);
        assertEquals(1, store.listSessions().size());
        assertEquals(2, store.listSessions().get(0).messageCount);

        store.deleteSession("s1");
        assertFalse(store.sessionExists("s1"));
        assertTrue(store.loadMessages("s1").isEmpty());
    }

    @Test
    public void fullReplaceKeepsNoStaleRows() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.saveMessages("s1", Arrays.asList(user("a"), user("b"), user("c")));
        store.saveMessages("s1", Arrays.asList(user("final")));
        List<Msg> loaded = store.loadMessages("s1");
        assertEquals(1, loaded.size());
        assertEquals("final", loaded.get(0).getContent());
    }

    @Test
    public void searchFindsKeywordWithSnippet() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.saveMessages("s1", Arrays.asList(
                user("帮我查一下 QUANTUM_FIELDS 配置在哪里"),
                assistant("QUANTUM_FIELDS 在 z-config 的默认配置里")));
        List<StateStore.SearchHit> hits = store.search("QUANTUM_FIELDS", 10);
        assertFalse("FTS 或 LIKE 至少要命中", hits.isEmpty());
        assertEquals("s1", hits.get(0).sessionId);
        assertTrue(hits.get(0).snippet.contains("QUANTUM_FIELDS"));
        assertTrue(store.search("不存在的词xyz", 10).isEmpty());
    }

    @Test
    public void searchEmptyKeywordReturnsEmpty() throws Exception {
        StateStore store = new StateStore(dbFile());
        assertTrue(store.search("", 10).isEmpty());
        assertTrue(store.search("  ", 10).isEmpty());
    }

    @Test
    public void usageRecordedAndStatsAggregated() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.recordUsage("s1", "bench", 100, 20, 1);
        store.recordUsage("s1", "bench", 150, 30, 2);
        store.recordUsage("s2", "gpt", 10, 5, 1);
        StateStore.Stats s = store.stats();
        assertEquals(2L, s.usageByModel.get("bench").longValue());
        assertEquals(1L, s.usageByModel.get("gpt").longValue());
    }

    @Test
    public void pruneRemovesOnlyEmptyOldSessions() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("old-empty", "旧空会话", null, null, 0, 0, 0);
        store.upsertSession("new-empty", "新空会话", null, null, 0, 0, 0);
        store.upsertSession("old-full", "旧有货", null, null, 3, 0, 0);
        // 把 old-empty 的 updated_at 手工改旧
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + store.getDbFile().getAbsolutePath());
             java.sql.Statement st = c.createStatement()) {
            st.execute("UPDATE sessions SET updated_at='2020-01-01 00:00:00' WHERE id IN ('old-empty','old-full')");
        }
        int removed = store.prune(7);
        assertEquals(1, removed);
        assertTrue(store.sessionExists("old-full"));
        assertTrue(store.sessionExists("new-empty"));
        assertFalse(store.sessionExists("old-empty"));
    }

    @Test
    public void exportJsonlWritesOneMessagePerLine() throws Exception {
        StateStore store = new StateStore(dbFile());
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.saveMessages("s1", Arrays.asList(user("第一"), assistant("第二")));
        List<String> lines = store.exportJsonl("s1");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"role\":\"user\""));
        assertTrue(lines.get(1).contains("\"role\":\"assistant\""));
        assertEquals(2, store.exportJsonl(null).size());
    }
}
