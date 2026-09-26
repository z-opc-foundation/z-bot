package com.zifang.z.bot.memory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MemoryDriftGuard} —— 漂移三信号 + 快照取证 + 还原对账。
 * 双向都测：坏内容进得来（外部改过要判得出）、判出之后还原的字节必须等于原字节。
 */
public class MemoryDriftGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String name, String content) throws Exception {
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String md5(File f) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", Byte.valueOf(b)));
        }
        return sb.toString();
    }

    private List<File> backups(File dir, String prefix) {
        File[] all = dir.listFiles();
        assertTrue("目录读不出来", all != null);
        return Arrays.stream(all).filter(f -> f.getName().startsWith(prefix)).sorted(
                (a, b) -> a.getName().compareTo(b.getName())).collect(java.util.stream.Collectors.toList());
    }

    // ===== 解析 / 渲染 =====

    @Test
    public void parseAndRenderRoundTrip() {
        List<String> lines = MemoryDriftGuard.parse("- [t1] 甲\n\n  - [t2] 乙  \n");
        assertEquals(Arrays.asList("- [t1] 甲", "- [t2] 乙"), lines);
        assertEquals("- [t1] 甲\n- [t2] 乙", MemoryDriftGuard.render(lines));
        assertEquals("", MemoryDriftGuard.render(Collections.<String>emptyList()));
        assertEquals(Collections.emptyList(), MemoryDriftGuard.parse(null));
        assertEquals(Collections.emptyList(), MemoryDriftGuard.parse("   \n  \n"));
    }

    @Test
    public void entryLineShapeRecognition() {
        assertTrue(MemoryDriftGuard.isEntryLine("- [2026-09-26T10:00:00Z] 正文"));
        assertFalse("缺右括号不算条目", MemoryDriftGuard.isEntryLine("- [2026 正文"));
        assertFalse("空时间戳不算条目", MemoryDriftGuard.isEntryLine("- [] 正文"));
        assertFalse("右括号后没空格不算条目", MemoryDriftGuard.isEntryLine("- [t]正文"));
        assertFalse("散文行不是条目", MemoryDriftGuard.isEntryLine("今天聊了部署"));
        assertFalse("只有前缀没有正文不算条目", MemoryDriftGuard.isEntryLine("- [t]"));
        assertFalse(MemoryDriftGuard.isEntryLine(null));
    }

    @Test
    public void entryBodyRoundTripsThroughMarker() {
        String line = MemoryDriftGuard.entryLine("2026-09-26T10:00:00Z", "偏好 A");
        assertEquals("- [2026-09-26T10:00:00Z] 偏好 A", line);
        assertEquals("偏好 A", MemoryDriftGuard.entryBody(line));
        assertEquals("散文原样返回", "散文原样返回", MemoryDriftGuard.entryBody("散文原样返回"));
    }

    // ===== 三条漂移信号 =====

    @Test
    public void cleanAndMissingFilesAreNotDrift() throws Exception {
        assertNull(MemoryDriftGuard.detect(new File(tmp.getRoot(), "MEMORY.md"),
                MemorySection.MEMORY));
        assertNull("空白文件不算漂移", MemoryDriftGuard.detect(
                file("MEMORY.md", "  \n "), MemorySection.MEMORY));
        File clean = file("MEMORY.md", "- [t1] 甲\n- [t2] 乙");
        assertNull(MemoryDriftGuard.detect(clean, MemorySection.MEMORY));
        assertTrue("干净判定不许留快照", backups(tmp.getRoot(), "MEMORY.md.bak").isEmpty());
    }

    @Test
    public void signalShapeCatchesManuallyAppendedProse() throws Exception {
        String original = "- [t1] 甲\n今天有人手工加了一段散文\n";
        File f = file("MEMORY.md", original);
        MemoryDriftGuard.Drift d = MemoryDriftGuard.detect(f, MemorySection.MEMORY);
        assertNotNull("手工散文行必须判成漂移", d);
        assertEquals("shape", d.signal());
        assertTrue(d.detail(), d.detail().contains("今天有人手工加了一段散文"));
        assertTrue("备份确实在盘上", d.backedUp());
        assertArrayEquals("快照必须是原字节", original.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(d.backup().toPath()));
        assertArrayEquals("判漂移不许动被检文件一个字节", original.getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(f.toPath()));
        String msg = d.message("MEMORY.md");
        assertTrue(msg, msg.contains("拒绝写入 MEMORY.md"));
        assertTrue("要把快照路径报给人（她的 bak_path）", msg.contains(d.backup().getAbsolutePath()));
        assertTrue("要指出补救出口", msg.contains("rewritePage"));
    }

    @Test
    public void signalOversizeCatchesFreeFormBlob() throws Exception {
        StringBuilder big = new StringBuilder("- [t] ");
        for (int i = 0; i < MemorySection.MEMORY.charLimit(); i++) {
            big.append('x');
        }
        File f = file("MEMORY.md", big.toString());
        MemoryDriftGuard.Drift d = MemoryDriftGuard.detect(f, MemorySection.MEMORY);
        assertNotNull("单行超过整份预算 ⇒ 外部塞的自由文本", d);
        assertEquals("oversize", d.signal());
        assertTrue(d.detail(), d.detail().contains("整份预算 " + MemorySection.MEMORY.charLimit()));
    }

    @Test
    public void signalRoundTripCatchesInnerBlankLine() throws Exception {
        File f = file("USER.md", "- [t1] 甲\n\n- [t2] 乙");
        MemoryDriftGuard.Drift d = MemoryDriftGuard.detect(f, MemorySection.USER);
        assertNotNull("中间夹空行 ⇒ 再渲染不回原字节", d);
        assertEquals("round-trip", d.signal());
    }

    @Test
    public void pageSectionsAreNeverDriftChecked() throws Exception {
        File soul = file("SOUL.md", "# 随便写的散文\n\n第二段");
        assertNull("整页散文不做条目往返判定", MemoryDriftGuard.detect(soul, MemorySection.SOUL));
    }

    // ===== 快照命名 / 还原 =====

    @Test
    public void sameTimestampBackupsDoNotClobberEachOther() throws Exception {
        File dir = tmp.getRoot();
        File a = MemoryDriftGuard.backupFileFor(dir, "MEMORY.md", 123L);
        assertEquals("MEMORY.md.bak.123", a.getName());
        assertFalse(a.exists());
        Files.write(a.toPath(), "第一枚证据".getBytes(StandardCharsets.UTF_8));
        File b = MemoryDriftGuard.backupFileFor(dir, "MEMORY.md", 123L);
        assertEquals("同一毫秒的第二次快照不许复用同一个名字",
                "MEMORY.md.bak.123.1", b.getName());
        Files.write(b.toPath(), "第二枚证据".getBytes(StandardCharsets.UTF_8));
        File c = MemoryDriftGuard.backupFileFor(dir, "MEMORY.md", 123L);
        assertEquals("MEMORY.md.bak.123.2", c.getName());
        assertFalse(c.exists());
        assertEquals("前两枚证据必须同时在盘上（第三枚只是取名字，没落盘）",
                2, backups(dir, "MEMORY.md.bak.").size());
    }

    @Test
    public void snapshotReturnsNullForMissingAndCopiesBytesForExisting() throws Exception {
        assertNull(MemoryDriftGuard.snapshot(new File(tmp.getRoot(), "NOPE.md")));
        File f = file("MEMORY.md", "- [t] 原样");
        File bak = MemoryDriftGuard.snapshot(f);
        assertNotNull(bak);
        assertTrue(bak.getName(), bak.getName().startsWith("MEMORY.md.bak."));
        assertArrayEquals(Files.readAllBytes(f.toPath()), Files.readAllBytes(bak.toPath()));
    }

    @Test
    public void restoreBringsBackExactBytesEvenIfTargetGone() throws Exception {
        File f = file("MEMORY.md", "- [t] 原始内容\n第二行");
        File bak = MemoryDriftGuard.snapshot(f);
        String before = md5(f);
        Files.write(f.toPath(), "- [t] 被半写坏了".getBytes(StandardCharsets.UTF_8));
        assertFalse(md5(f), before.equals(md5(f)));
        MemoryDriftGuard.restore(f, bak);
        assertEquals("还原后字节必须等于写之前", before, md5(f));
        Files.delete(f.toPath());
        MemoryDriftGuard.restore(f, bak);
        assertEquals(before, md5(f));
    }

    @Test
    public void restoreWithoutBackupReportsLoudly() throws Exception {
        File f = file("MEMORY.md", "x");
        try {
            MemoryDriftGuard.restore(f, new File(tmp.getRoot(), "没有这个快照.bak.1"));
            fail("快照不在 ⇒ 还原不许静默成功");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    @Test
    public void atomicReplaceLeavesNoTempFile() throws Exception {
        File f = new File(tmp.getRoot(), "MEMORY.md");
        MemoryDriftGuard.atomicReplace(f, "- [t] 新".getBytes(StandardCharsets.UTF_8));
        assertEquals("- [t] 新", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertFalse(".tmp 必须清掉", new File(tmp.getRoot(), "MEMORY.md.tmp").exists());
        MemoryDriftGuard.atomicReplace(f, "- [t] 旧".getBytes(StandardCharsets.UTF_8));
        assertEquals("- [t] 旧", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(tmp.getRoot(), "MEMORY.md.tmp").exists());
    }
}
