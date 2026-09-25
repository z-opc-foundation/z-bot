package com.zifang.z.bot.cli;

import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.bot.store.StateStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code z-bot sessions} 的 CLI 面单测（P15 扩了 version/lineage/end/archive/prune 三态）。
 *
 * <p>重点盯"删除面"：这个命令在 P2 只能删 0 消息会话，P15 有了三态保留策略后
 * 很容易把默认值做成"顺手把有货的已结束会话一起抹掉"。默认必须仍是保守的那一档，
 * 扩面只能靠显式开关 —— 结束状态不等于删除授权。</p>
 */
public class SessionsCommandTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File db;
    private PrintStream realOut;
    private ByteArrayOutputStream captured;

    @Before
    public void setUp() throws Exception {
        db = new File(tmp.newFolder("cli" + System.nanoTime()), "state.db");
        realOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
    }

    @After
    public void tearDown() {
        System.setOut(realOut);
    }

    private String out() {
        return new String(captured.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private int run(String... args) {
        return new CommandLine(SessionsCommand.class).execute(args);
    }

    private int runPrune(String... args) {
        List<String> all = new ArrayList<String>(Arrays.asList("--db", db.getAbsolutePath(), "prune"));
        all.addAll(Arrays.asList(args));
        return run(all.toArray(new String[0]));
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<ToolCall>(), new HashMap<String, Object>());
    }

    /** 建一个"已结束 + 有 N 条消息 + 30 天没动过"的会话。 */
    private StateStore seedEnded(String id, int messages) throws Exception {
        StateStore store = new StateStore(db);
        store.upsertSession(id, "t-" + id, null, null, messages, 0, 0);
        List<Msg> msgs = new ArrayList<Msg>();
        for (int i = 0; i < messages; i++) {
            msgs.add(user(id + "-body-" + i));
        }
        if (!msgs.isEmpty()) {
            store.saveMessages(id, msgs);
        }
        assertTrue(store.endSession(id, "user_closed"));
        age(id);
        return store;
    }

    private void age(String... ids) throws Exception {
        String cutoff = LocalDateTime.now().minusDays(30).format(TS);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            in.append(i == 0 ? "'" : ",'").append(ids[i]).append("'");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("UPDATE sessions SET updated_at='" + cutoff + "' WHERE id IN (" + in + ")");
        }
    }

    private boolean exists(String id) {
        return new StateStore(db).sessionExists(id);
    }

    private int rows(String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    // ===== prune 的默认档必须是保守的 =====

    @Test
    public void pruneDefaultNeverTouchesEndedSessionWithMessages() throws Exception {
        seedEnded("full", 3);
        assertEquals(0, runPrune("--days", "7"));
        assertTrue("默认档把有消息的会话删了: " + out(), exists("full"));
        assertEquals(3, rows("messages"));
        assertTrue("默认档要说明自己钉在 0 消息: " + out(), out().contains("--include-non-empty"));
    }

    @Test
    public void pruneDefaultStillCleansEmptyEndedSession() throws Exception {
        seedEnded("empty", 0);
        assertEquals(0, runPrune("--days", "7"));
        assertFalse("0 消息且已结束必须照删（P2 语义不回退）: " + out(), exists("empty"));
    }

    @Test
    public void includeNonEmptyIsTheOnlyWayToDeleteContent() throws Exception {
        seedEnded("full", 3);
        assertEquals(0, runPrune("--days", "7", "--include-non-empty"));
        assertFalse("显式开关后应删掉", exists("full"));
        assertEquals("消息行要连带清掉", 0, rows("messages"));
        assertEquals("FTS 不能留孤儿", 0, rows("messages_fts"));
    }

    @Test
    public void dryRunNamesTheSessionAndChangesNothing() throws Exception {
        seedEnded("full", 2);
        assertEquals(0, runPrune("--days", "7", "--include-non-empty", "--dry-run"));
        assertTrue("dry-run 不许动手", exists("full"));
        assertTrue("dry-run 要点名: " + out(), out().contains("full"));
        assertTrue("dry-run 要自报没落盘: " + out(), out().contains("dry-run"));
    }

    @Test
    public void inFlightNeedsItsOwnSwitch() throws Exception {
        StateStore store = new StateStore(db);
        store.upsertSession("live", "t-live", null, null, 1, 0, 0);
        store.saveMessages("live", Arrays.asList(user("live-body")));
        age("live");

        assertEquals(0, runPrune("--days", "7", "--include-non-empty"));
        assertTrue("在飞会话不能被有货开关顺带删掉: " + out(), exists("live"));
        assertEquals(0, runPrune("--days", "7", "--include-non-empty", "--include-in-flight"));
        assertFalse("两个开关都给才动在飞", exists("live"));
    }

    @Test
    public void emptyInFlightSessionIsOnlyGoneWithItsOwnSwitch() throws Exception {
        // 真进程 E2E 抓到的形状：0 消息的在飞会话。store 层的 P2 兼容档把它算作"可删"
        // （ended_at IS NULL 但 message_count=0），CLI 照抄的话 --include-in-flight 就成了空话。
        StateStore store = new StateStore(db);
        store.upsertSession("live-empty", "t-live", null, null, 0, 0, 0);
        age("live-empty");

        assertEquals(0, runPrune("--days", "7"));
        assertTrue("默认档删掉了 0 消息的在飞会话: " + out(), exists("live-empty"));
        assertEquals(0, runPrune("--days", "7", "--include-non-empty"));
        assertTrue("扩了消息范围也不该顺带动在飞: " + out(), exists("live-empty"));
        assertEquals(0, runPrune("--days", "7", "--include-in-flight"));
        assertFalse("给了在飞自己的开关才动", exists("live-empty"));
    }

    @Test
    public void archiveIsReversibleAndHidesFromDefaultList() throws Exception {
        seedEnded("full", 2);
        assertEquals(0, runPrune("--days", "7", "--archive"));
        assertTrue("软归档不许删数据", exists("full"));
        assertEquals(2, rows("messages"));

        StateStore store = new StateStore(db);
        assertTrue("默认 list 要藏起归档项", store.listSessions(false).isEmpty());
        assertEquals("--all 才看得见", 1, store.listSessions(true).size());
        assertEquals(0, run("--db", db.getAbsolutePath(), "archive", "full", "--unarchive"));
        assertTrue(exists("full"));
    }

    // ===== version / lineage =====

    @Test
    public void versionCheckPassesOnFreshDb() throws Exception {
        assertEquals(0, run("--db", db.getAbsolutePath(), "version", "--check"));
        assertTrue(out(), out().contains("schema_version="));
        assertTrue("校验要真过: " + out(), out().contains("schema 校验通过"));
    }

    @Test
    public void lineagePrintsChainAndEndReason() throws Exception {
        StateStore store = new StateStore(db);
        store.upsertSession("root", "t-root", null, null, 1, 0, 0);
        store.saveMessages("root", Arrays.asList(user("root-body")));
        store.upsertSession("kid", "t-kid", null, null, 0, 0, 0);
        assertEquals("kid", store.forkSession("root", "kid", "t-kid", "cli", "compression"));

        assertEquals(0, run("--db", db.getAbsolutePath(), "lineage", "kid"));
        assertTrue("要往上到根: " + out(), out().contains("root"));
        assertTrue("要标出父的结束原因: " + out(), out().contains("compression"));
    }
}
