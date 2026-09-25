package com.zifang.z.bot.memory;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** {@link MemoryStore} 三层读写/锁文件/默认 SOUL 单测，全落 TemporaryFolder。 */
public class MemoryStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private MemoryStore store;

    @Before
    public void setUp() throws Exception {
        store = new MemoryStore(tmp.newFolder("memories"));
    }

    @Test
    public void appendAndReadRoundTrip() throws Exception {
        assertTrue(store.readMemory().isEmpty());
        store.appendMemory("用户偏好 TUI 主题 amber");
        store.appendMemory("项目 z-bot 用 Java 8 语法");
        String mem = store.readMemory();
        assertTrue(mem, mem.contains("用户偏好 TUI 主题 amber"));
        assertTrue(mem, mem.contains("项目 z-bot 用 Java 8 语法"));
        assertEquals(2, mem.split("\n").length);
        assertTrue("锁文件应存在", new File(store.getDir(), "MEMORY.md.lock").exists());
    }

    @Test
    public void rewriteReplacesWholePage() throws Exception {
        store.appendMemory("old-1");
        store.rewriteMemory("# MEMORY\n\nnew-content-only");
        assertEquals("# MEMORY\n\nnew-content-only", store.readMemory());
        store.rewriteUser("user-pref-v2");
        assertEquals("user-pref-v2", store.readUser());
    }

    @Test
    public void ensureSoulGeneratesOnceAndKeepsEdits() throws Exception {
        store.ensureSoul();
        String first = store.readSoul();
        assertTrue(first, first.contains("z-bot"));
        store.rewriteMemory("");
        store.ensureSoul();
        // 手工改写后再次 ensure 不覆盖
        lockedEditSoul();
        store.ensureSoul();
        assertTrue(store.readSoul(), store.readSoul().contains("custom-personality"));
    }

    @Test
    public void forgetClearsButKeepsFile() throws Exception {
        store.appendMemory("x");
        assertFalse(store.isEmpty());
        store.clearMemory();
        assertEquals("", store.readMemory());
        assertTrue(new File(store.getDir(), "MEMORY.md").exists());
        store.appendUser("u");
        assertFalse(store.isEmpty());
        store.clearUser();
        assertTrue(store.isEmpty());
    }

    private void lockedEditSoul() throws Exception {
        store.rewriteUser("unused");
        // 直接用 rewrite 通道模拟手编 SOUL
        java.lang.reflect.Method m = MemoryStore.class.getDeclaredMethod(
                "lockedWrite", File.class, String.class);
        m.setAccessible(true);
        m.invoke(null, new File(store.getDir(), "SOUL.md"), "custom-personality");
    }
}
