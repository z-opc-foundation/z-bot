package com.zifang.z.bot.session;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Before;
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

/**
 * {@link SessionManager} 单测：会话 id 唯一性 + 多进程共用目录时的索引收编。
 *
 * <p>每个用例都跑在 {@link TemporaryFolder} 里，不碰 {@code ~/.zbot/sessions}。</p>
 */
public class SessionManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = tmp.newFolder("sessions");
    }

    @Test
    public void createSessionIsUniqueWithinSameMillisecond() {
        SessionManager manager = new SessionManager(dir);

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 50; i++) {
            ids.add(manager.createSession());
        }

        assertEquals("同毫秒内连续新建不能撞 id", 50, new java.util.LinkedHashSet<String>(ids).size());
    }

    @Test
    public void orphansFromAnotherProcessAreAdopted() {
        // b 先启动（目录还空着），a 后写入的会话就会被 b 的 _index.json 覆盖掉
        SessionManager b = new SessionManager(dir);
        SessionManager a = new SessionManager(dir);
        String fromA = a.createSession();
        a.saveMessages(fromA, Arrays.asList(msg(MessageRole.USER, "A 的会话"), msg(MessageRole.ASSISTANT, "ok")));

        String fromB = b.createSession();
        b.saveMessages(fromB, Arrays.asList(msg(MessageRole.USER, "B 的会话")));
        assertFalse("索引被 B 覆盖后，A 的会话暂时列不出来", ids(b).contains(fromA));
        assertTrue(new File(dir, fromA + ".json").exists());

        SessionManager c = new SessionManager(dir);
        assertTrue("新进程要按消息文件收编 A 的会话", ids(c).contains(fromA));
        assertTrue(ids(c).contains(fromB));

        SessionManager.SessionSummary adopted = summary(c, fromA);
        assertEquals(2, adopted.messageCount);
        assertEquals("A 的会话", adopted.title);
    }

    @Test
    public void deletedSessionIsNotAdoptedAgain() {
        SessionManager writer = new SessionManager(dir);
        String id = writer.createSession();
        writer.saveMessages(id, Arrays.asList(msg(MessageRole.USER, "会被删掉")));

        SessionManager reader = new SessionManager(dir);
        reader.deleteSession(id);

        assertFalse(ids(new SessionManager(dir)).contains(id));
        assertFalse(new File(dir, id + ".json").exists());
    }

    // ===== helpers =====

    private static Msg msg(MessageRole role, String content) {
        return new Msg(role, content);
    }

    private static List<String> ids(SessionManager manager) {
        List<String> out = new ArrayList<String>();
        for (SessionManager.SessionSummary s : manager.listSessions()) {
            out.add(s.id);
        }
        return out;
    }

    private static SessionManager.SessionSummary summary(SessionManager manager, String id) {
        for (SessionManager.SessionSummary s : manager.listSessions()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        throw new AssertionError("列表里没有会话 " + id);
    }
}
