package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 单工具结果上限 + 溢出落盘（P20，对齐 hermes {@code registry.get_max_result_size}
 * 与 {@code tools/tool_result_storage.py}）。
 *
 * <p>上限一律走内核注册表的 {@code maxResultChars(name, default)}，本类里不许出现第二个魔数。</p>
 */
public class ToolkitResultCapTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void clearProperty() {
        System.clearProperty(Toolkit.OVERFLOW_DIR_PROPERTY);
    }

    private static String text(int len, char fill) {
        char[] buf = new char[len];
        java.util.Arrays.fill(buf, fill);
        return new String(buf);
    }

    private static ToolResult big(String content) {
        return ToolResult.text(content);
    }

    private static Toolkit registering(Toolkit tk, String name, ToolDescriptor d) {
        tk.register(Toolkit.of(name, name, null, args -> big(args == null
                ? "" : String.valueOf(args.get("payload")))), d);
        return tk;
    }

    private static java.util.Map<String, Object> payload(String s) {
        return Collections.<String, Object>singletonMap("payload", s);
    }

    // ===== 上限口径 =====

    @Test
    public void defaultCapComesFromTheKernelRegistryNotALocalMagicNumber() {
        Toolkit tk = new Toolkit();
        tk.register(Toolkit.of("plain", "p", null, args -> big("x")));
        assertEquals(100_000L, tk.getMaxResultChars());
        assertEquals(com.zifang.z.agent.kernel.tool.ToolRegistry.DEFAULT_RESULT_SIZE_CHARS, tk.getMaxResultChars());
        assertEquals("未声明上限的工具走全局缺省",
                com.zifang.z.agent.kernel.tool.ToolRegistry.DEFAULT_RESULT_SIZE_CHARS, tk.resultCapFor("plain"));
        assertEquals("不存在的工具也要回一个可用上限而不是 0",
                tk.getMaxResultChars(), tk.resultCapFor("ghost"));
    }

    @Test
    public void perToolDeclarationBeatsTheGlobalDefault() {
        Toolkit tk = new Toolkit();
        registering(tk, "small", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L, 64L, null));
        tk.setMaxResultChars(9_000L);
        assertEquals(64L, tk.resultCapFor("small"));
        assertEquals(9_000L, tk.resultCapFor("unknown-tool"));
    }

    @Test
    public void unboundedSentinelMeansNoTruncationAtAll() {
        Toolkit tk = new Toolkit();
        tk.setOverflowDir(tmp.getRoot());
        registering(tk, "reader", new ToolDescriptor(Toolsets.FILE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.UNBOUNDED_RESULT_CHARS, null));
        String body = text(50_000, 'R');
        ToolResult r = tk.execute("reader", payload(body));
        assertEquals("声明不设限的工具（防 persist→read→persist 死循环）必须原样返回",
                body, r.getContent());
        assertEquals(0, dirList(tmp.getRoot()).size());
    }

    // ===== 溢出落盘 =====

    @Test
    public void oversizedResultSpillsFullTextToDiskAndKeepsOnlyAPreview() throws IOException {
        Toolkit tk = new Toolkit();
        File dir = tmp.newFolder("tool-results");
        tk.setOverflowDir(dir);
        tk.setMaxResultChars(1_000L);
        tk.setPreviewChars(200L);
        registering(tk, "echo_back", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));

        String body = text(4_321, 'x');
        ToolResult r = tk.execute("echo_back", payload(body));
        assertFalse(r.isError());
        assertTrue("预览要留着原文开头", r.getContent().startsWith(text(200, 'x')));
        assertTrue("必须明说超了多少、留了多少", r.getContent().contains("4321 字符"));
        assertTrue(r.getContent(), r.getContent().contains("超单工具上限 1000"));
        assertTrue("要把全文路径回给模型", r.getContent().contains(dir.getAbsolutePath()));
        assertEquals("上下文里只该留 200 字符正文 + 一段说明",
                200, r.getContent().indexOf("\n\n[结果"));

        List<String> files = dirList(dir);
        assertEquals(files.toString(), 1, files.size());
        File spilled = new File(dir, files.get(0));
        String onDisk = new String(Files.readAllBytes(spilled.toPath()), StandardCharsets.UTF_8);
        assertEquals("落盘的必须是<b>全文</b>，一字不差", body, onDisk);
        assertEquals(body.length(), onDisk.length());
    }

    @Test
    public void withoutAnOverflowDirTheResultIsTruncatedAndSaysSo() {
        Toolkit tk = new Toolkit();
        tk.setMaxResultChars(500L);
        tk.setPreviewChars(100L);
        registering(tk, "trunc", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("trunc", payload(text(2_000, 't')));
        assertTrue(r.getContent(), r.getContent().contains("未配置溢出目录"));
        assertTrue(r.getContent().contains("全文未落盘"));
        assertTrue("就地截断：正文只留预览", r.getContent().length() < 400);
    }

    @Test
    public void errorResultsAreCappedTheSameWayAndStayMarkedError() throws IOException {
        Toolkit tk = new Toolkit();
        File dir = tmp.newFolder("err-dir");
        tk.setOverflowDir(dir);
        tk.setMaxResultChars(100L);
        tk.setPreviewChars(20L);
        tk.register(Toolkit.of("failing", "f", null,
                args -> ToolResult.failure("call-1", "failing", text(3_000, 'E'))));
        ToolResult r = tk.execute("failing", null);
        assertTrue("溢出不能把 error 洗成 success", r.isError());
        assertEquals("failing", r.getName());
        assertEquals("call-1", r.getCallId());
        assertTrue(r.getContent(), r.getContent().startsWith(text(20, 'E')));
        assertEquals("落盘文件名要带上 callId", 1, dirList(dir).size());
        assertTrue(dirList(dir).get(0), dirList(dir).get(0).contains("call-1"));
    }

    @Test
    public void previewIsClampedToTheCapItself() {
        Toolkit tk = new Toolkit();
        tk.setMaxResultChars(300L);
        tk.setPreviewChars(10_000L); // 比上限还大 → 只能按上限留
        registering(tk, "clamp", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("clamp", payload(text(1_000, 'c')));
        assertTrue(r.getContent(), r.getContent().contains("已截断至 300")
                || r.getContent().contains("上下文只保留 300 字符预览"));
        assertTrue("正文不许超过上限", r.getContent().indexOf("\n\n[结果") == 300);
    }

    @Test
    public void exactlyAtTheCapIsNotTouched() {
        Toolkit tk = new Toolkit();
        tk.setOverflowDir(tmp.getRoot());
        tk.setMaxResultChars(120L);
        registering(tk, "edge", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L, 120L, null));
        String body = text(120, 'e');
        ToolResult r = tk.execute("edge", payload(body));
        assertEquals(body, r.getContent());
        assertEquals(0, dirList(tmp.getRoot()).size());
    }

    // ===== 溢出目录解析（红线 1：跟着 profile 走，不写死 ~/.zbot）=====

    @Test
    public void overflowDirResolvesInjectedFirstThenSystemProperty() throws IOException {
        Toolkit tk = new Toolkit();
        File injected = tmp.newFolder("injected");
        File viaProp = tmp.newFolder("via-prop");
        System.clearProperty(Toolkit.OVERFLOW_DIR_PROPERTY);
        if (System.getenv(Toolkit.ZBOT_HOME_ENV) == null) {
            assertNull("什么都没配时不落盘（宁可截断也不凭空造 ~/.zbot）",
                    new Toolkit().getOverflowDir());
        }
        tk.setOverflowDir(injected);
        System.setProperty(Toolkit.OVERFLOW_DIR_PROPERTY, viaProp.getAbsolutePath());
        assertEquals("显式注入优先于 -D", injected, tk.getOverflowDir());
        tk.setOverflowDir(null);
        assertEquals("没有注入才读 -D", viaProp, tk.getOverflowDir());
    }

    @Test
    public void spillGoesToADirectoryCreatedOnDemand() {
        Toolkit tk = new Toolkit();
        File dir = new File(tmp.getRoot(), "deep/tool-results");
        tk.setOverflowDir(dir);
        tk.setMaxResultChars(50L);
        tk.setPreviewChars(10L);
        registering(tk, "maker", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("maker", payload(text(600, 'm')));
        assertTrue("目录要能按需建出来: " + dir, dir.isDirectory());
        assertEquals(1, dirList(dir).size());
        assertTrue(r.getContent().contains(dir.getAbsolutePath()));
    }

    @Test
    public void unwritableOverflowDirDegradesToTruncationInsteadOfThrowing() {
        Toolkit tk = new Toolkit();
        File parent = new File(tmp.getRoot(), "a-file");
        try {
            Files.write(parent.toPath(), "not-a-dir".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        tk.setOverflowDir(parent);
        tk.setMaxResultChars(40L);
        tk.setPreviewChars(8L);
        registering(tk, "degrade", new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("degrade", payload(text(900, 'd')));
        assertFalse("写不进去不许炸", r.isError());
        assertTrue("原因要说清是目录不可写，而不是谎报未配置: " + r.getContent(),
                r.getContent().contains("溢出目录不可写"));
        assertTrue(r.getContent(), r.getContent().contains("全文未落盘"));
    }

    // ===== 文件名净化 =====

    @Test
    public void spilledFileNamesAreSanitizedAndSalted() {
        assertEquals("read_file-call7.txt", Toolkit.safeResultFileName("read_file", "call7"));
        String weird = Toolkit.safeResultFileName("../etc/pa ss", "a/b");
        assertFalse(weird, weird.contains("/"));
        assertFalse(weird, weird.startsWith("."));
        assertTrue(weird, weird.endsWith(".txt"));
        assertNotNull(weird);
        String longName = Toolkit.safeResultFileName(text(400, 'L'), "c");
        assertTrue(longName.length() + "", longName.length() <= 120 + 1 + 12 + ".txt".length());
    }

    private static List<String> dirList(File dir) {
        String[] names = dir.list();
        List<String> out = new java.util.ArrayList<String>();
        if (names != null) {
            Collections.addAll(out, names);
        }
        Collections.sort(out);
        return out;
    }
}
