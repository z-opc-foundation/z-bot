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
 * 会话血统（{@code parent_session_id}）+ 生命周期（{@code ended_at}/{@code end_reason}）。
 * 这是 P14"逐条落消息 + 压缩分叉"的硬依赖，所以链、环、连带关系都必须钉死。
 */
public class StateStoreLineageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dbFile() throws Exception {
        return new File(tmp.newFolder("lin" + System.nanoTime()), "state.db");
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<ToolCall>(), new HashMap<String, Object>());
    }

    private static StateStore withSessions(StateStore store, String... ids) {
        for (String id : ids) {
            store.upsertSession(id, id + " 标题", null, null, 0, 0, 0);
        }
        return store;
    }

    // ===== 挂父 / 读回 =====

    @Test
    public void parentLinkRoundTrip() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "p", "c");
        assertNull("新建的会话没有父", store.parentOf("c"));
        assertTrue(store.setParentSession("c", "p"));
        assertEquals("p", store.parentOf("c"));
        assertEquals(1, store.listChildSessions("p").size());
        assertEquals("c", store.listChildSessions("p").get(0).id);
        // SessionRow 上也带着父指针（CLI 列表要用）
        assertEquals("p", row(store, "c").parentSessionId);
        assertEquals("p", store.parentOf("c"));
        assertNull(row(store, "p").parentSessionId);
    }

    private static StateStore.SessionRow row(StateStore store, String id) {
        for (StateStore.SessionRow r : store.listSessions(true)) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        throw new AssertionError("会话不见了: " + id);
    }

    @Test
    public void rejectsSelfParentMissingParentAndCycle() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "a", "b", "c");
        store.setParentSession("b", "a");
        store.setParentSession("c", "b");

        assertFalse("自己不能当自己的父", store.setParentSession("a", "a"));
        assertNull(store.parentOf("a"));
        assertFalse("父必须存在", store.setParentSession("a", "nope"));
        assertFalse("a 已是 c 的祖先，a→c 会成环", store.setParentSession("a", "c"));
        // 库里没被改动
        assertEquals("a", store.parentOf("b"));
        assertEquals("b", store.parentOf("c"));
        assertNull(store.parentOf("a"));
        assertFalse(store.sessionEnded("a"));
    }

    @Test
    public void lineageOfWalksUpRootFirst() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "root", "mid", "leaf");
        store.setParentSession("mid", "root");
        store.setParentSession("leaf", "mid");
        store.endSession("root", "compacted");

        List<StateStore.LineageStep> chain = store.lineageOf("leaf");
        assertEquals(3, chain.size());
        assertEquals("root", chain.get(0).sessionId);
        assertEquals("mid", chain.get(1).sessionId);
        assertEquals("leaf", chain.get(2).sessionId);
        assertNull(chain.get(0).parentSessionId);
        assertEquals("root", chain.get(1).parentSessionId);
        assertTrue("父已随压缩结束", chain.get(0).ended);
        assertEquals("compacted", chain.get(0).endReason);
        assertFalse(chain.get(2).ended);
        assertEquals("leaf", chain.get(chain.size() - 1).sessionId);

        // 不存在的会话：空链而不是异常
        assertTrue(store.lineageOf("ghost").isEmpty());
    }

    @Test
    public void handMadeCycleDoesNotHangLineage() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "x", "y");
        store.setParentSession("x", "y");
        // 手工把环补上（y→x），绕过 setParentSession 的防环
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + store.getDbFile().getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("UPDATE sessions SET parent_session_id='x' WHERE id='y'");
        }
        List<StateStore.LineageStep> chain = store.lineageOf("x");
        assertTrue("最多 32 跳必须收口: " + chain.size(), chain.size() <= 32);
        assertFalse(chain.isEmpty());
    }

    // ===== 分叉（P14 压缩分叉的地基）=====

    @Test
    public void forkSessionLinksChildAndEndsParent() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "parent", "child");
        store.saveMessages("parent", Arrays.asList(user("压缩前的整段历史")));

        String forked = store.forkSession("parent", "child", "child 标题", "cli", "compacted");
        assertEquals("child", forked);
        assertEquals("parent", store.parentOf("child"));
        assertTrue("父随分叉结束", store.sessionEnded("parent"));
        assertEquals("compacted", reasonOf(store, "parent"));
        assertFalse("子是活的，不进已结束计数", store.sessionEnded("child"));
        assertEquals(1, store.listChildSessions("parent").size());
        assertEquals(1, store.stats().ended);
    }

    @Test
    public void forkRejectsUnknownChild() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "parent");
        assertNull("子会话必须已存在（P14 先建后挂）",
                store.forkSession("parent", "ghost", "t", "cli", "compacted"));
        assertFalse(store.sessionExists("ghost"));
        assertNull(store.parentOf("parent"));
    }

    // ===== 生命周期 =====

    @Test
    public void endSessionIsFirstWriterWinsAndReopenClears() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "s1");
        assertFalse(store.sessionEnded("s1"));
        assertTrue(store.endSession("s1", "user_closed"));
        assertFalse("重复结束是 no-op", store.endSession("s1", "another_reason"));
        assertTrue(store.sessionEnded("s1"));
        assertEquals("第一个 end_reason 赢", "user_closed", reasonOf(store, "s1"));
        assertNotNull(endedAtOf(store, "s1"));

        assertTrue(store.reopenSession("s1"));
        assertFalse(store.sessionEnded("s1"));
        assertNull(reasonOf(store, "s1"));
        assertFalse("不存在的会话标不了", store.endSession("ghost", "x"));
    }

    private static String reasonOf(StateStore store, String id) {
        for (StateStore.SessionRow r : store.listSessions(true)) {
            if (r.id.equals(id)) {
                return r.endReason;
            }
        }
        throw new AssertionError("会话不见了: " + id);
    }

    private static String endedAtOf(StateStore store, String id) {
        for (StateStore.SessionRow r : store.listSessions(true)) {
            if (r.id.equals(id)) {
                return r.endedAt;
            }
        }
        throw new AssertionError("会话不见了: " + id);
    }

    @Test
    public void lineageSurvivesReopenAndCounters() throws Exception {
        File f = dbFile();
        StateStore store = withSessions(new StateStore(f), "p", "c1", "c2");
        store.setParentSession("c1", "p");
        store.setParentSession("c2", "p");

        StateStore again = new StateStore(f);
        assertEquals(2, again.listChildSessions("p").size());
        assertEquals("沿血统的下挂是持久化的", 2, again.stats().lineageForks);
        assertEquals(0, again.stats().ended);
        assertEquals(0, again.stats().archived);
    }

    @Test
    public void archivedFlagIsReadableAndIndependentOfEnded() throws Exception {
        StateStore store = withSessions(new StateStore(dbFile()), "s");
        assertTrue(store.archiveSession("s", true));
        assertFalse("归档 ≠ 结束", store.sessionEnded("s"));
        StateStore.SessionRow row = store.listSessions(true).get(0);
        assertTrue(row.archived);
        assertEquals(1, store.stats().archived);
        assertTrue(store.listSessions(false).isEmpty());
        assertTrue(store.archiveSession("s", false));
        assertFalse(store.listSessions(false).isEmpty());
        assertFalse(store.archiveSession("ghost", true));
    }
}
