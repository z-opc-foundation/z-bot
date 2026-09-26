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
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        tk.setMaxResultChars(1_000L);
        tk.setPreviewChars(200L);
        registering(tk, "reader", new ToolDescriptor(Toolsets.FILE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.UNBOUNDED_RESULT_CHARS, null));
        String body = text(50_000, 'R');
        ToolResult r = tk.execute("reader", payload(body));
        assertEquals("声明不设限的工具（防 persist→read→persist 死循环）必须原样返回",
                body, r.getContent());
        assertEquals(0, dirList(tmp.getRoot()).size());

        // 同一条里必须同时钉住"活的猎物"：换一个没做 UNBOUNDED 声明（NO_MAX_RESULT_CHARS，
        // 即"跟着全局上限走"）的工具、喂同一份 50_000 字符正文 —— 这一把必须被截。
        // 少了这一半，上一条的"原样返回"就说不清是哨兵救的还是"正文本来就没超上限"救的
        // （杠② TK5 上一棒记的就是这个形状的空跑）。
        registering(tk, "reader_bounded", new ToolDescriptor(Toolsets.FILE, false, null,
                Toolkit.DEFAULT_OWNER, 30_000L, 60_000L,
                ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult bounded = tk.execute("reader_bounded", payload(body));
        assertFalse("没声明不设限的同尺寸正文必须被全局上限截掉", body.equals(bounded.getContent()));
        assertTrue("回执要明说截它的就是那条全局上限: " + firstLine(bounded),
                bounded.getContent().contains("超单工具上限 1000"));
        assertEquals("被截那把的全文落在溢出目录里（说明正文确实过了线，不是假猎物）",
                1, dirList(tmp.getRoot()).size());
        assertEquals("两把钥匙唯一的差别就是 UNBOUNDED 声明",
                ToolDescriptor.UNBOUNDED_RESULT_CHARS, tk.resultCapFor("reader"));
        assertEquals(1_000L, tk.resultCapFor("reader_bounded"));
    }

    /**
     * 溢出目录第三级解析 <b>{@code $ZBOT_HOME/tool-results}</b> 的活猎物（杠② TK9 上一棒记
     * "JUnit 进程改不了自己的 env ⇒ 结构上打不到"）。
     *
     * <p>本条不去改本进程 env，而是<b>起一个真带着 {@code ZBOT_HOME} 的子 JVM</b>跑
     * {@link OverflowDirEnvProbe}，所以这一级第一次真的被判到了：</p>
     * <ol>
     *   <li>正向：{@code ZBOT_HOME=<临时根>} ⇒ 解析必须落在 {@code <临时根>/tool-results}，
     *       超限正文的<b>全文文件必须真的落在那个根下</b>；</li>
     *   <li>反向（否则整个子进程探针又是空跑）：同一把探针摘掉 {@code ZBOT_HOME} ⇒ 解析必须是
     *       {@code NULL}、回执必须明说"未配置溢出目录"、一个字节都不许多落盘；</li>
     *   <li>红线 1 的笼子：两把子 JVM 都带 {@code -Duser.home=<假 home>} ⇒ 万一有人把解析改回
     *       写死 {@code ~/.zbot}，那个 {@code ~} 只会指向这里的假 home，测试当场判红，
     *       而<b>真的 {@code ~/.zbot} 一个字节都不会多出来</b>（杠④）。</li>
     * </ol>
     */
    @Test
    public void zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot() throws Exception {
        File profileRoot = tmp.newFolder("profile-root");
        File fakeHome = tmp.newFolder("fake-home");
        int bodyLen = 4_000;

        ProbeOut withHome = fork(profileRoot, fakeHome, bodyLen);
        assertEquals("探针子 JVM 必须真收到 ZBOT_HOME（否则这三行断言全是空跑）",
                profileRoot.getAbsolutePath(), withHome.zbotHomeEnv);
        assertEquals("子 JVM 的 user.home 必须被换到假 home（杠④ 的笼子）",
                fakeHome.getAbsolutePath(), withHome.userHome);
        assertEquals("$ZBOT_HOME 那一级必须解析成 <profile>/tool-results，而不是写死的 ~/.zbot",
                new File(profileRoot, "tool-results").getAbsolutePath(), withHome.overflowDir);
        assertEquals("超限正文确实超了线（活的猎物在场）", "yes", withHome.truncated);
        assertEquals("全文文件必须落在那个根下，且只许一个: " + withHome.dirList,
                "1", withHome.dirCount);
        assertEquals("落盘的就是全文", String.valueOf(bodyLen), withHome.spilledBytes);

        ProbeOut withoutHome = fork(null, fakeHome, bodyLen);
        assertEquals("反向腿：探针这次确实没有 ZBOT_HOME", "NULL", withoutHome.zbotHomeEnv);
        assertEquals("三级都没命中 ⇒ 不落盘（宁可截断也不凭空造 ~/.zbot）",
                "NULL", withoutHome.overflowDir);
        assertEquals("正文照样被截", "yes", withoutHome.truncated);
        assertTrue("回执要明说是未配置目录: " + withoutHome.note,
                withoutHome.note.contains("未配置溢出目录") && withoutHome.note.contains("全文未落盘"));
        assertFalse("一个字节都不许写进假 home 下的 .zbot", new File(fakeHome, ".zbot").exists());
        assertEquals("假 home 底下必须还是空的（这一跑没在 ~/.zbot 那一形状上写过任何东西）",
                0, dirList(fakeHome).size());
    }

    /** 探针 stdout 的机器可读行；缺一行就是 FATAL，不许"看不见就算过"。 */
    private static final class ProbeOut {
        private final Map<String, String> kv = new java.util.HashMap<String, String>();
        private String zbotHomeEnv, userHome, overflowDir, truncated, dirList, dirCount, spilledBytes, note;

        static ProbeOut parse(String stdout, String cmd) {
            ProbeOut out = new ProbeOut();
            for (String line : stdout.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.startsWith("PROBE_")) {
                    out.kv.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            for (String key : new String[] {"PROBE_ZBOT_HOME_ENV", "PROBE_USER_HOME",
                    "PROBE_OVERFLOW_DIR", "PROBE_TRUNCATED", "PROBE_DIR_LIST", "PROBE_DIR_COUNT",
                    "PROBE_SPILLED_BYTES", "PROBE_NOTE"}) {
                assertNotNull("探针没打这一行 ⇒ 子 JVM 没跑到/输出形状变了（" + cmd + "）: "
                        + key + "\n" + stdout, out.kv.get(key));
            }
            assertNull("探针自己炸了就不许再算读数: " + stdout, out.kv.get("PROBE_FATAL"));
            out.zbotHomeEnv = out.kv.get("PROBE_ZBOT_HOME_ENV");
            out.userHome = out.kv.get("PROBE_USER_HOME");
            out.overflowDir = out.kv.get("PROBE_OVERFLOW_DIR");
            out.truncated = out.kv.get("PROBE_TRUNCATED");
            out.dirList = out.kv.get("PROBE_DIR_LIST");
            out.dirCount = out.kv.get("PROBE_DIR_COUNT");
            out.spilledBytes = out.kv.get("PROBE_SPILLED_BYTES");
            out.note = out.kv.get("PROBE_NOTE");
            return out;
        }
    }

    /** {@code zbotHome == null} 时把 ZBOT_HOME 从这个子 JVM 的环境里摘干净。 */
    private ProbeOut fork(File zbotHome, File fakeHome, int bodyLen) throws Exception {
        String javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java")
                .getAbsolutePath();
        List<String> cmd = new java.util.ArrayList<String>(java.util.Arrays.asList(
                javaBin, "-cp", childClasspath(),
                "-Duser.home=" + fakeHome.getAbsolutePath(),
                OverflowDirEnvProbe.class.getName(), String.valueOf(bodyLen), "P"));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (zbotHome == null) {
            pb.environment().remove(Toolkit.ZBOT_HOME_ENV);
        } else {
            pb.environment().put(Toolkit.ZBOT_HOME_ENV, zbotHome.getAbsolutePath());
        }
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            fail("子 JVM 120 s 没退出，不许把这条判据挂在等待上: " + cmd);
        }
        assertEquals("探针子 JVM 退出码（输出在下面）: " + cmd + "\n" + sb, 0, p.exitValue());
        return ProbeOut.parse(sb.toString(), String.join(" ", cmd));
    }

    /**
     * 子 JVM 的 classpath 不赌 surefire 怎么传的（`java.class.path` 可能是 manifest-only 的
     * booter jar）：先把三个真身（被测类 / 探针类 / 内核 jar）的 CodeSource 摆进去，
     * 再把父进程的 {@code java.class.path} 原样接上兜底。
     */
    private static String childClasspath() throws Exception {
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<String>();
        addCodeSource(parts, Toolkit.class);
        addCodeSource(parts, OverflowDirEnvProbe.class);
        addCodeSource(parts, com.zifang.z.agent.kernel.tool.ToolRegistry.class);
        addCodeSource(parts, com.zifang.z.agent.kernel.tool.ToolResult.class);
        String own = System.getProperty("java.class.path");
        for (String e : own.split(java.io.File.pathSeparator)) {
            if (!e.trim().isEmpty()) {
                parts.add(e);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(p);
        }
        assertTrue("子 JVM 的 classpath 里必须有 test-classes（探针类在那儿）: " + sb,
                sb.toString().contains("test-classes"));
        return sb.toString();
    }

    private static void addCodeSource(java.util.Collection<String> out, Class<?> c) throws Exception {
        java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
        assertNotNull("拿不到 " + c.getName() + " 的 CodeSource", cs);
        assertNotNull("拿不到 " + c.getName() + " 的 CodeSource.location", cs.getLocation());
        out.add(new java.io.File(cs.getLocation().toURI()).getAbsolutePath());
    }

    private static String firstLine(ToolResult r) {
        String c = r.getContent();
        int i = c.indexOf('\n');
        return i < 0 ? c : c.substring(0, i);
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
