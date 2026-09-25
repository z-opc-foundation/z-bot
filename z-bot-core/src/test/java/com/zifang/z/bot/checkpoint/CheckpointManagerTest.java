package com.zifang.z.bot.checkpoint;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CheckpointManager} 机制单测：真 git、真文件，仓库与沙箱都落在 {@link TemporaryFolder}。
 */
public class CheckpointManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File store;
    private File work;
    private CheckpointManager mgr;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("本机无 git，跳过 checkpoint 测试", CheckpointManager.gitAvailable());
        store = tmp.newFolder("store");
        work = tmp.newFolder("ws");
        mgr = new CheckpointManager(store, work);
    }

    @Test
    public void snapshotReturnsShortIdAndAppearsInList() throws Exception {
        write("a.txt", "v1");

        String id = mgr.snapshot("write_file");

        assertTrue("id 应为 12 位十六进制: " + id, id.matches("[0-9a-f]{12}"));
        List<CheckpointManager.Entry> entries = mgr.list();
        assertEquals(1, entries.size());
        assertEquals(id, entries.get(0).id);
        assertTrue(entries.get(0).subject, entries.get(0).subject.contains("write_file"));
        // 影子仓库在 store 下，沙箱里不能出现 .git
        assertTrue(new File(store, ".git").isDirectory());
        assertEquals(false, new File(work, ".git").exists());
    }

    @Test
    public void rollbackRestoresModifiedFileAndRemovesNewFile() throws Exception {
        write("a.txt", "v1");
        String id = mgr.snapshot("t");
        write("a.txt", "v2");
        write("b.txt", "created-after");

        String back = mgr.rollback(id);

        assertEquals(id, back);
        assertEquals("v1", read("a.txt"));
        assertEquals(false, new File(work, "b.txt").exists());
    }

    @Test
    public void rollbackWithoutIdRestoresLatestSnapshot() throws Exception {
        write("a.txt", "v1");
        mgr.snapshot("t");
        write("a.txt", "v2");
        mgr.snapshot("t");
        write("a.txt", "v3");

        mgr.rollback(null);

        assertEquals("v2", read("a.txt"));
    }

    @Test
    public void pruneKeepsNewestAndDeletesRest() throws Exception {
        write("a.txt", "1");
        mgr.snapshot("t");
        write("a.txt", "2");
        mgr.snapshot("t");
        write("a.txt", "3");
        mgr.snapshot("t");

        int deleted = mgr.prune(1);

        assertEquals(2, deleted);
        assertEquals(1, mgr.list().size());
        // 剩下的是最新那次（内容 "3" 对应的前置状态）——至少 id 还能回滚
        List<CheckpointManager.Entry> left = mgr.list();
        mgr.rollback(left.get(0).id);
    }

    @Test
    public void discardRemovesJustCreatedSnapshot() throws Exception {
        write("a.txt", "v1");
        String id = mgr.snapshot("exec");

        mgr.discard(id);

        assertEquals(0, mgr.list().size());
    }

    @Test
    public void rollbackRejectsNonHexId() throws Exception {
        write("a.txt", "v1");
        mgr.snapshot("t");

        try {
            mgr.rollback("../../etc/passwd");
            fail("非十六进制 id 应被拒绝");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("非法"));
        }
    }

    @Test
    public void rollbackOnEmptyStoreFailsWithHint() throws Exception {
        try {
            mgr.rollback(null);
            fail("无快照时应回滚失败");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("暂无可回滚"));
        }
    }

    // ===== helpers =====

    private void write(String name, String content) throws Exception {
        try (FileWriter w = new FileWriter(new File(work, name))) {
            w.write(content);
        }
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(work, name).toPath()), StandardCharsets.UTF_8);
    }
}
