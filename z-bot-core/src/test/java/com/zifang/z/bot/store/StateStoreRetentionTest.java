package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P15 保留策略三态：(a) 只删"已结束"的、(b) 血统连带删 / 置空父指针、(c) 软归档。
 * {@code auto_prune} 默认关（与 hermes {@code cli.py} 的 {@code cfg.get("auto_prune", False)} 一致）。
 */
public class StateStoreRetentionTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbFile() throws Exception {
        return new File(tmp.newFolder("prune" + System.nanoTime()), "state.db");
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<ToolCall>(), new HashMap<String, Object>());
    }

    /** 把 updated_at 改旧（保留 created_at），模拟"很久没动过"。 */
    private static void age(File db, int days, String... ids) {
        String cutoff = LocalDateTime.now().minusDays(days).format(TS);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            in.append(i == 0 ? "'" : ",'").append(ids[i]).append("'");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("UPDATE sessions SET updated_at='" + cutoff + "' WHERE id IN (" + in + ")");
        } catch (Exception e) {
            throw new AssertionError("改旧失败: " + e, e);
        }
    }

    // ===== (a) 只删已结束 =====

    @Test
    public void pruneSkipsInFlightSessionsEvenWhenOld() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("in-flight", "在飞", null, null, 5, 0, 0);
        store.upsertSession("ended", "已结束", null, null, 5, 0, 0);
        store.saveMessages("in-flight", Arrays.asList(
                user("在飞1"), user("在飞2"), user("在飞3"), user("在飞4"), user("在飞5")));
        store.saveMessages("ended", Arrays.asList(
                user("收摊1"), user("收摊2"), user("收摊3"), user("收摊4"), user("收摊5")));
        store.endSession("ended", "user_closed");
        age(f, 30, "in-flight", "ended");

        StateStore.PruneReport r = store.pruneDetailed(StateStore.PruneCriteria.olderThanDays(7));
        assertEquals("只有已结束的命中: " + r.ids, Arrays.asList("ended"), r.ids);
        assertEquals(1, r.totalDeleted());
        assertTrue("在飞会话必须活着", store.sessionExists("in-flight"));
        assertFalse(store.sessionExists("ended"));
        assertEquals("在飞会话的 5 行不动，已结束的 5 行连带删掉", 5, count(f, "messages"));
        assertEquals("FTS 同步：只留在飞的 5 行", 5, count(f, "messages_fts"));
        assertTrue(store.search("收摊1", 5).isEmpty());
        assertFalse(store.search("在飞1", 5).isEmpty());
    }

    @Test
    public void requireEndedOffDeletesAnythingOld() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("in-flight", "在飞", null, null, 1, 0, 0);
        age(f, 30, "in-flight");
        int deleted = store.prune(StateStore.PruneCriteria.olderThanDays(7).requireEnded(false));
        assertEquals(1, deleted);
        assertFalse(store.sessionExists("in-flight"));
    }

    @Test
    public void dryRunReportsMatchedWithoutDeleting() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("gone-ish", "旧空会话", null, null, 0, 0, 0);
        age(f, 30, "gone-ish");

        StateStore.PruneReport preview = store.pruneDetailed(
                StateStore.PruneCriteria.olderThanDays(7).dryRun(true));
        assertTrue(preview.dryRun);
        assertEquals(1, preview.matched);
        assertEquals("dry-run 不许删", 0, preview.totalDeleted());
        assertEquals(Arrays.asList("gone-ish"), preview.ids);
        assertTrue(store.sessionExists("gone-ish"));

        // listPruneCandidates 与 dry-run 必须给同一批 id
        assertEquals(preview.ids, store.listPruneCandidates(StateStore.PruneCriteria.olderThanDays(7)));
    }

    @Test
    public void filtersBySourceAndMessageCount() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("cli-empty", "cli 空", null, null, 0, 0, 0, "cli");
        store.upsertSession("web-empty", "web 空", null, null, 0, 0, 0, "web");
        store.upsertSession("cli-bulk", "cli 有货且已结束", null, null, 9, 0, 0, "cli");
        store.endSession("cli-bulk", "done");
        age(f, 30, "cli-empty", "web-empty", "cli-bulk");

        assertEquals(Arrays.asList("cli-empty", "cli-bulk"),
                store.listPruneCandidates(StateStore.PruneCriteria.olderThanDays(7).source("cli")));
        assertEquals("不带 source 时两个空会话都在窗口里",
                Arrays.asList("cli-empty", "web-empty"),
                store.listPruneCandidates(StateStore.PruneCriteria.olderThanDays(7)
                        .maxMessages(Integer.valueOf(0))));
        // 只留 0 消息的空会话之外的一切：bulk 不在窗口内
        StateStore.PruneReport r = store.pruneDetailed(StateStore.PruneCriteria.olderThanDays(7)
                .source("cli").maxMessages(Integer.valueOf(0)));
        assertEquals(1, r.totalDeleted());
        assertTrue(store.sessionExists("cli-bulk"));
        assertTrue(store.sessionExists("web-empty"));
    }

    // ===== (b) 血统连带 / 置空 =====

    @Test
    public void cascadeDeletesWholeLineage() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("root", "父", null, null, 2, 0, 0);
        store.upsertSession("child", "子", null, null, 2, 0, 0);
        store.upsertSession("grandchild", "孙", null, null, 2, 0, 0);
        store.saveMessages("root", Arrays.asList(user("父消息"), user("父消息2")));
        store.saveMessages("child", Arrays.asList(user("子消息"), user("子消息2")));
        store.saveMessages("grandchild", Arrays.asList(user("孙消息"), user("孙消息2")));
        store.setParentSession("child", "root");
        store.setParentSession("grandchild", "child");
        store.endSession("root", "compacted");
        age(f, 30, "root"); // 只有父在窗口内，子/孙都刚写过

        StateStore.PruneReport r = store.pruneDetailed(StateStore.PruneCriteria.olderThanDays(7)
                .cascadeChildren(true).detachOrphans(false));
        assertEquals("命中 1 个根: " + r.matched, 1, r.matched);
        assertEquals("连带删掉 2 个后代: " + r, 2, r.deletedChildren);
        assertTrue(r.ids.contains("grandchild"));
        assertFalse(store.sessionExists("child"));
        assertFalse(store.sessionExists("grandchild"));
        assertEquals("血统链上的消息也清了", 0, count(f, "messages"));
    }

    @Test
    public void detachOrphansClearsParentPointerByDefault() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("root", "父", null, null, 1, 0, 0);
        store.upsertSession("child", "子", null, null, 1, 0, 0);
        store.saveMessages("root", Arrays.asList(user("父消息")));
        store.saveMessages("child", Arrays.asList(user("子消息")));
        store.setParentSession("child", "root");
        store.endSession("root", "user_closed");
        age(f, 30, "root");

        StateStore.PruneReport r = store.pruneDetailed(StateStore.PruneCriteria.olderThanDays(7));
        assertEquals(1, r.totalDeleted());
        assertEquals("子会话被置空父指针而不是连带删: " + r, 1, r.detachedChildren);
        assertTrue(store.sessionExists("child"));
        assertNull(store.parentOf("child"));
        assertEquals("子的消息不动", 1, count(f, "messages"));
    }

    @Test
    public void deleteSessionCascadeRemovesChildrenOnlyOfThatSession() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("a", "A", null, null, 1, 0, 0);
        store.upsertSession("a1", "A1", null, null, 1, 0, 0);
        store.upsertSession("b", "B", null, null, 1, 0, 0);
        store.saveMessages("a", Arrays.asList(user("a 消息")));
        store.saveMessages("a1", Arrays.asList(user("a1 消息")));
        store.saveMessages("b", Arrays.asList(user("b 消息")));
        store.setParentSession("a1", "a");

        store.deleteSession("a"); // 老语义：不连带，子会话留任并脱链
        assertTrue(store.sessionExists("a1"));
        assertNull(store.parentOf("a1"));

        store.deleteSession("b", true);
        assertFalse(store.sessionExists("b"));
    }

    // ===== (c) 软归档 =====

    @Test
    public void softArchiveHidesThenHardDeleteFreesSpace() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("old", "旧会话", null, null, 2, 0, 0);
        store.saveMessages("old", Arrays.asList(user("留着呢"), user("还留着")));
        store.endSession("old", "idle_timeout");
        age(f, 30, "old");

        StateStore.PruneReport archived = store.archiveMatching(StateStore.PruneCriteria.olderThanDays(7));
        assertEquals(1, archived.matched);
        assertEquals(1, archived.archived);
        assertEquals("软归档不删数据", 2, count(f, "messages"));
        assertTrue(store.sessionExists("old"));
        assertFalse("列表默认不含归档", contains(store.listSessions(false), "old"));
        assertTrue("全量列表仍可见", contains(store.listSessions(true), "old"));
        assertEquals(1, store.stats().archived);

        // 归档过的会话不会被普通 prune 再处理一遍（archivedOnly 才点名）
        assertEquals(0, store.prune(StateStore.PruneCriteria.olderThanDays(7).includeArchived(false)));
        assertEquals("取消归档后回到可见列表", true,
                store.archiveSession("old", false) && contains(store.listSessions(false), "old"));
        store.archiveSession("old", true);
        age(f, 30, "old");
        assertEquals(1, store.deleteArchived(7));
        assertFalse(store.sessionExists("old"));
        assertEquals(0, count(f, "messages"));
    }

    @Test
    public void archiveCoversTheWholeLineageGroup() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("p", "父", null, null, 0, 0, 0);
        store.upsertSession("c", "子", null, null, 0, 0, 0);
        store.setParentSession("c", "p");
        store.endSession("p", "compacted");
        age(f, 30, "p"); // 只有父过窗口，但整组该一起归档，免得留下"父已归档、子还露着"

        StateStore.PruneReport r = store.archiveMatching(StateStore.PruneCriteria.olderThanDays(7));
        assertEquals("整组 2 个会话都归档", 2, r.archived);
        assertTrue(r.ids.contains("c"));
        assertFalse(contains(store.listSessions(false), "c"));
    }

    // ===== auto_prune 默认关 =====

    @Test
    public void autoPruneIsOffByDefault() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("ancient", "远古已结束", null, null, 3, 0, 0);
        store.endSession("ancient", "user_closed");
        age(f, 400, "ancient");
        assertNull("关着就不该跑", store.maybeAutoPrune());
        assertTrue(store.sessionExists("ancient"));

        // 重开（默认 Options）也绝不自动删
        StateStore reopened = new StateStore(f);
        assertTrue("auto_prune 默认 off：开库不许动数据", reopened.sessionExists("ancient"));
    }

    @Test
    public void autoPruneRunsOnOpenAndOncePerDayWhenEnabled() throws Exception {
        File f = dbFile();
        StateStore seed = new StateStore(f);
        seed.upsertSession("ancient", "远古已结束", null, null, 3, 0, 0);
        seed.upsertSession("recent", "近来的", null, null, 3, 0, 0);
        seed.endSession("ancient", "user_closed");
        seed.endSession("recent", "user_closed");
        age(f, 400, "ancient");
        age(f, 1, "recent");

        StateStore enabled = new StateStore(f, StateStore.Options.defaults().autoPrune(true)
                .retentionDays(90));
        assertFalse("开库即清 retentionDays 之前的已结束会话", enabled.sessionExists("ancient"));
        assertTrue("窗口内的不动", enabled.sessionExists("recent"));
        assertNotNull("执行时间要落 state_meta 台账", enabled.getMeta(StateStore.META_LAST_AUTO_PRUNE));
        assertNull("同一天不重复跑", enabled.maybeAutoPrune());
    }

    // ===== stats 里的可重算读数 =====

    @Test
    public void statsExposeArchivedEndedAndLineageCounts() throws Exception {
        File f = dbFile();
        StateStore store = new StateStore(f);
        store.upsertSession("a", "A", null, null, 0, 0, 0);
        store.upsertSession("b", "B", null, null, 0, 0, 0);
        store.upsertSession("c", "C", null, null, 0, 0, 0);
        store.setParentSession("b", "a");
        store.setParentSession("c", "a");
        store.endSession("a", "user_closed");
        store.archiveSession("c", true);

        StateStore.Stats s = store.stats();
        assertEquals(3, s.sessions);
        assertEquals(1, s.ended);
        assertEquals(1, s.archived);
        assertEquals("两条父子边: " + s.lineageForks, 2, s.lineageForks);
        assertEquals(SchemaMigrations.HEAD_VERSION, s.schemaVersion);
    }

    private static boolean contains(List<StateStore.SessionRow> rows, String id) {
        for (StateStore.SessionRow r : rows) {
            if (r.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static int count(File f, String table) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(table + " 读失败: " + e, e);
        }
    }
}
