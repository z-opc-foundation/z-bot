package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 坏库自愈：{@code integrity_check} + 读写双探针 → <b>先备份原文件</b>再重建；
 * 备份失败必须上抛并停止（"不许静默丢数据"）。FTS 单独烂掉时只就地重建索引，不动 canonical 表。
 */
public class DbRecoveryTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Set<PosixFilePermission> DIR_RWX = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> DIR_RX = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir() throws Exception {
        return tmp.newFolder("rec" + System.nanoTime());
    }

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<ToolCall>(), new HashMap<String, Object>());
    }

    private static void writeBytes(File f, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(UTF8));
        } finally {
            out.close();
        }
    }

    /** magic 不对、且长度远超页头 —— SQLite 必报 "file is not a database"。 */
    private File garbageDb(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append("这不是 sqlite 文件，是一段恰好够长的垃圾字节 xyz0123456789\n");
        }
        writeBytes(f, sb.toString());
        return f;
    }

    private static byte[] readAll(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    private static int countRows(File f, String table) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(table + " 读失败: " + e, e);
        }
    }

    /** 只数主备份文件（不含跟着挪走的 -wal/-shm）。 */
    private static List<File> backups(File parent) {
        File[] all = parent.listFiles();
        assertNotNull(all);
        List<File> out = new ArrayList<File>();
        for (File f : all) {
            String name = f.getName();
            if (name.contains(".corrupt-") && !name.endsWith("-wal") && !name.endsWith("-shm")) {
                out.add(f);
            }
        }
        return out;
    }

    // ===== 探针本身 =====

    @Test
    public void probeSaysHealthyForAnIntactDatabase() throws Exception {
        File f = new File(dir(), "state.db");
        StateStore store = new StateStore(f);
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.saveMessages("s1", Arrays.asList(user("正文")));

        DbRecovery.Probe p = DbRecovery.probe(f, StateStore.Options.defaults());
        assertTrue("好库该过体检: " + p, p.healthy());
        assertTrue(p.openable);
        assertFalse(p.ftsBroken);
        assertNullish(p.integrityProblem);
        assertEquals("ok", p.toString());
    }

    private static void assertNullish(String value) {
        assertTrue("体检不该报问题", value == null);
    }

    @Test
    public void probeRejectsFileThatIsNotADatabase() throws Exception {
        File f = garbageDb(new File(dir(), "state.db"));
        DbRecovery.Probe p = DbRecovery.probe(f, StateStore.Options.defaults());
        assertFalse("垃圾文件不该算健康", p.healthy());
        assertNotNull("必须给出可日志化的原因: " + p, p.reason);
        assertFalse("垃圾文件不能只修 FTS 就放过", p.repairableInPlace());
    }

    // ===== ① 坏库：先备份，再重建 =====

    @Test
    public void corruptFileIsBackedUpBeforeRebuild() throws Exception {
        File dir = dir();
        File f = garbageDb(new File(dir, "state.db"));
        byte[] original = readAll(f);

        StateStore store = new StateStore(f);

        List<File> kept = backups(dir);
        assertEquals("该有一份备份: " + kept, 1, kept.size());
        assertTrue("备份名要带 .corrupt-<时间戳>: " + kept.get(0).getName(),
                kept.get(0).getName().matches("state\\.db\\.corrupt-\\d{8}_\\d{6}(-\\d+)?"));
        assertTrue("备份内容必须是被挪走的原文件", Arrays.equals(original, readAll(kept.get(0))));

        // 原路径是全新可用的库
        assertEquals(f.getAbsoluteFile(), store.getDbFile());
        assertEquals(SchemaMigrations.HEAD_VERSION, store.schemaVersion());
        assertEquals(0, countRows(f, "sessions"));
        assertEquals("SQLite magic", "SQLite format 3\u0000",
                new String(readAll(f), 0, 16, UTF8));
        assertEquals(kept.get(0), store.recoveredFromBackup());

        // 新库照常能用
        store.upsertSession("after", "重建后", null, null, 0, 0, 0);
        assertTrue(store.sessionExists("after"));
    }

    @Test
    public void selfHealingKeepsWorkingAcrossReopens() throws Exception {
        File dir = dir();
        File f = garbageDb(new File(dir, "state.db"));
        StateStore first = new StateStore(f);
        first.upsertSession("keep", "重建后的会话", null, null, 0, 0, 0);

        StateStore second = new StateStore(f);
        assertEquals(1, second.listSessions().size());
        assertEquals("重建后的会话", second.listSessions().get(0).title);
        assertEquals("重开不该再备份一次: " + backups(dir), 1, backups(dir).size());
        assertTrue(second.recoveredFromBackup() == null);
    }

    // ===== ② 备份失败 ⇒ 停止，绝不重建 =====

    @Test
    public void backupFailureAbortsInsteadOfWipingTheFile() throws Exception {
        File dir = dir();
        File f = garbageDb(new File(dir, "state.db"));
        byte[] original = readAll(f);
        Path dirPath = dir.toPath();
        try {
            Files.setPosixFilePermissions(dirPath, DIR_RX);
            boolean threw = false;
            try {
                new StateStore(f);
            } catch (IllegalStateException e) {
                threw = true;
                assertTrue("报错要说明是备份失败: " + e.getMessage(), e.getMessage().contains("备份失败"));
            }
            assertTrue("目录写不了 ⇒ 备份失败 ⇒ 必须上抛", threw);
            // 原字节一个不少，也没有生成半截备份
            assertTrue(Arrays.equals(original, readAll(f)));
            assertTrue(backups(dir).isEmpty());
        } finally {
            Files.setPosixFilePermissions(dirPath, DIR_RWX);
        }
    }

    @Test
    public void autoRecoverSwitchOffRefusesToTouchAnything() throws Exception {
        File dir = dir();
        File f = garbageDb(new File(dir, "state.db"));
        byte[] original = readAll(f);
        StateStore.Options off = StateStore.Options.defaults().autoRecoverCorrupt(false);
        try {
            new StateStore(f, off);
            fail("关掉自愈时必须上抛，不能默默给一个空库");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("state.db"));
        }
        assertTrue(Arrays.equals(original, readAll(f)));
        assertTrue("关着自愈就不许产生备份: " + backups(dir), backups(dir).isEmpty());
    }

    // ===== ③ FTS 烂了：就地重建索引，零数据丢失 =====

    @Test
    public void brokenFtsIsRepairedInPlaceWithoutBackupOrDataLoss() throws Exception {
        File dir = dir();
        File f = new File(dir, "state.db");
        StateStore store = new StateStore(f);
        store.upsertSession("s1", "t", null, null, 0, 0, 0);
        store.saveMessages("s1", Arrays.asList(user("QUANTUM 在旧库里")));
        assertEquals(1, countRows(f, "messages"));

        // 模拟 hermes #50502 那一类：messages_fts 变成一张读不出 MATCH 的表，canonical 表完好
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS messages_fts");
            st.execute("DROP TRIGGER IF EXISTS messages_ai");
            st.execute("DROP TRIGGER IF EXISTS messages_ad");
            st.execute("CREATE TABLE messages_fts (session_id TEXT, content TEXT)");
            st.execute("INSERT INTO messages_fts VALUES('ghost','幽灵行')");
        }
        DbRecovery.Probe broken = DbRecovery.probe(f, StateStore.Options.defaults());
        assertFalse("坏 FTS 该被判为不健康: " + broken, broken.healthy());
        assertTrue("这一类该能就地修: " + broken, broken.repairableInPlace());

        StateStore healed = new StateStore(f);
        assertTrue("重建后 FTS 可用", healed.isFtsEnabled());
        assertTrue("就地修不该产生备份: " + backups(dir), backups(dir).isEmpty());
        assertEquals("消息一行没丢", 1, countRows(f, "messages"));
        assertEquals("幽灵行随 FTS 表一起消失", 1, countRows(f, "messages_fts"));
        assertEquals("QUANTUM 在旧库里", healed.loadMessages("s1").get(0).getContent());
        assertFalse("重建后 FTS 得能搜: ", healed.search("QUANTUM", 5).isEmpty());
        assertEquals(SchemaMigrations.HEAD_VERSION, healed.schemaVersion());
    }

    // ===== ④ backupAside 原语级断言 =====

    @Test
    public void backupAsideMovesSidecarsTogetherWithTheMainFile() throws Exception {
        File dir = dir();
        File f = new File(dir, "state.db");
        writeBytes(f, "x");
        writeBytes(new File(dir, "state.db-wal"), "wal-bytes");
        writeBytes(new File(dir, "state.db-shm"), "shm-bytes");

        File backup = DbRecovery.backupAside(f, "test 手动");

        assertTrue(backup.exists());
        assertFalse("主文件必须腾出来", f.exists());
        assertFalse(new File(dir, "state.db-wal").exists());
        assertFalse(new File(dir, "state.db-shm").exists());
        assertTrue(new File(dir, backup.getName() + "-wal").exists());
        assertTrue(new File(dir, backup.getName() + "-shm").exists());
        assertEquals(1, backups(dir).size());

        // 同一秒里第二次备份不能覆盖前一份
        writeBytes(new File(dir, "state.db"), "y");
        File second = DbRecovery.backupAside(new File(dir, "state.db"), "test 第二次");
        assertFalse("备份不许互相覆盖: " + backup + " vs " + second,
                backup.getAbsolutePath().equals(second.getAbsolutePath()));
        assertEquals(2, backups(dir).size());
    }

    @Test
    public void backupAsideRefusesWhenThereIsNothingToKeep() throws Exception {
        File dir = dir();
        try {
            DbRecovery.backupAside(new File(dir, "nope.db"), "没有文件");
            fail("没有原文件时必须拒绝，而不是返回一个假路径");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("没有可备份"));
        }
    }

    @Test
    public void checkHeadCatchesAMissingHeadTable() throws Exception {
        File f = new File(dir(), "state.db");
        new StateStore(f);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try {
            assertTrue(SchemaMigrations.checkHead(c).isEmpty());
            try (Statement st = c.createStatement()) {
                st.execute("DROP TABLE gateway_routing");
            }
            List<String> problems = SchemaMigrations.checkHead(c);
            assertTrue("删了 head 声明的表必须被抓出来: " + problems,
                    problems.toString().contains("gateway_routing"));
        } finally {
            c.close();
        }
    }
}
