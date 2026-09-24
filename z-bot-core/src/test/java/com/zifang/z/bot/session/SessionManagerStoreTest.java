package com.zifang.z.bot.session;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.bot.store.StateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** store 模式 SessionManager：sqlite 为事实来源 + 旧 JSON 会话一次性迁移。 */
public class SessionManagerStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    @Test
    public void jsonModeStillWorksWhenStoreNull() throws Exception {
        File dir = tmp.newFolder("sessions");
        SessionManager sm = new SessionManager(dir, null);
        assertNull(sm.getStore());
        String id = sm.createSession();
        sm.saveMessages(id, Arrays.asList(user("你好，世界")));
        assertTrue(new File(dir, id + ".json").exists());
        List<Msg> loaded = sm.loadMessages(id);
        assertEquals(1, loaded.size());
        assertEquals("你好，世界", loaded.get(0).getContent());
        assertEquals(1, sm.listSessions().size());
    }

    @Test
    public void storeModePersistsAcrossInstances() throws Exception {
        File dir = tmp.newFolder("sessions");
        File db = new File(dir.getParentFile(), "state.db");
        SessionManager sm1 = new SessionManager(dir, new StateStore(db));
        String id = sm1.createSession();
        sm1.saveMessages(id, Arrays.asList(user("会话内容 ABC")));

        // 新实例（模拟进程重启）：直接从 sqlite 恢复
        SessionManager sm2 = new SessionManager(dir, new StateStore(db));
        assertEquals(1, sm2.listSessions().size());
        List<Msg> loaded = sm2.loadMessages(id);
        assertEquals(1, loaded.size());
        assertEquals("会话内容 ABC", loaded.get(0).getContent());
        assertEquals(id, sm2.listSessions().get(0).id);
    }

    @Test
    public void legacyJsonSessionsMigratedOnce() throws Exception {
        File dir = tmp.newFolder("sessions");
        // 造一个旧格式 JSON 会话文件
        File legacy = new File(dir, "session_1000.json");
        Files.write(legacy.toPath(),
                "[{\"role\":\"user\",\"content\":\"旧会话里的关键信息 ZZZ\"}]".getBytes(StandardCharsets.UTF_8));
        legacy.setLastModified(1700000000000L);

        File db = new File(dir.getParentFile(), "state.db");
        SessionManager sm = new SessionManager(dir, new StateStore(db));

        // 迁移进库：能搜到、能读回
        List<StateStore.SearchHit> hits = sm.getStore().search("ZZZ", 10);
        assertEquals(1, hits.size());
        assertEquals("session_1000", hits.get(0).sessionId);
        List<Msg> loaded = sm.loadMessages("session_1000");
        assertEquals(1, loaded.size());
        assertEquals("旧会话里的关键信息 ZZZ", loaded.get(0).getContent());
        // 原文件保留
        assertTrue(legacy.exists());

        // 第二次打开不再重复迁移（库非空直接复用）
        SessionManager sm2 = new SessionManager(dir, new StateStore(db));
        assertEquals(1, sm2.listSessions().size());
    }

    @Test
    public void storeModeDeleteRemovesRowAndMessages() throws Exception {
        File dir = tmp.newFolder("sessions");
        File db = new File(dir.getParentFile(), "state.db");
        SessionManager sm = new SessionManager(dir, new StateStore(db));
        String id = sm.createSession();
        sm.saveMessages(id, Arrays.asList(user("待删除")));
        sm.deleteSession(id);
        assertFalse(sm.getStore().sessionExists(id));
        assertTrue(sm.loadMessages(id).isEmpty());
        assertEquals(0, sm.listSessions().size());
    }

    @Test
    public void titleAutoSetFromFirstUserMessage() throws Exception {
        File dir = tmp.newFolder("sessions");
        File db = new File(dir.getParentFile(), "state.db");
        SessionManager sm = new SessionManager(dir, new StateStore(db));
        String id = sm.createSession();
        sm.saveMessages(id, Arrays.asList(user("帮我统计一下上个季度的销售额")));
        boolean titled = false;
        for (StateStore.SessionRow r : sm.getStore().listSessions()) {
            if (r.id.equals(id)) {
                titled = true;
                assertTrue("标题应取首条用户消息，实际=" + r.title, r.title.contains("帮我统计"));
            }
        }
        assertTrue(titled);
        assertNotNull(sm.getStore());
    }
}
