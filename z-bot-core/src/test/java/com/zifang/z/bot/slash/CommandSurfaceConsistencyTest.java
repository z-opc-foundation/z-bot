package com.zifang.z.bot.slash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.bot.channel.P28HttpFixture;
import com.zifang.z.bot.channel.TerminalChannel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P19 · 一致性守卫：<b>从 {@link CommandCatalog} 派生的集合 == 各端点实际吐出的集合</b>。
 *
 * <h2>为什么必须有这一条</h2>
 * 命令面以前散在四处各自手抄，抄了四份就漂了四份（p28 {@code WIRING.md} §3.4—§3.6 量的那几笔）。
 * P19 把它收成单源之后，"漂移"这件事换了形态但没消失：<b>表里登记了一端能接，那一端其实没接</b>
 * —— 这才是新的失真方式，而它恰恰是 {@code endpoints} 这一列的全部含义。所以每一端都要拿
 * "该端实际吐出的集合"回来和派生集合对一次，而不是信注释。
 *
 * <h2>五个面，每个面的"实际集合"都是量出来的，不是再抄一遍</h2>
 * <table>
 *   <tr><th>面</th><th>派生（期望）</th><th>实际吐出（观测面）</th></tr>
 *   <li>{@code server} ⇄ {@link CommandCatalog#serverNames()}</li>
 *   <li>{@code registry}：{@code SlashRegistry.withBuiltinCommands()} 的真注册结果</li>
 *   <li>{@code http}：真起 {@code HttpChannel}（端口 0），{@code GET /api/commands} 的真字节</li>
 *   <li>{@code web}：{@code web/index.html} 里 {@code handleSlash} 真有水流的分支名 —— 正则扫源码</li>
 *   <li>{@code tui}：{@code TerminalChannel.handleSlashCommand} 真分支名 —— 正则扫源码</li>
 *   <li>{@code acp}：见 {@code acp/AcpCommandAdvertisementTest}（帧面；那里要用包内私有的测试替身）</li>
 * </table>
 *
 * <h2>卫兵自己有没有牙：每条判据都配了注入</h2>
 * 光"绿"不算数 —— 一张两边都抄同一处来的表永远绿。所以每个面都用
 * {@code *InjectionIsCaught()} 往<b>观测面</b>注入一条表里没有的命令（或反向少一条），
 * 断言 diff 非空。注入是在字符串上做的、不碰盘上文件，因此不依赖构建顺序，
 * 也不与杠② 的字节级变异重复（那是另一把尺，见 {@code _doc/acceptance/p19/LEDGER.tsv}）。
 *
 * <p><b>空输入一律 FATAL</b>：任何一面解析出 0 条名字都直接红，绝不"没扫到 ⇒ 视为相等"。</p>
 */
public class CommandSurfaceConsistencyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ObjectMapper M = new ObjectMapper();

    // ================= 面 0：表自身的内部一致 =================

    @Test
    public void catalogIsInternallyConsistent() {
        List<String> problems = CommandCatalog.selfCheck();
        assertTrue("表自身不自洽: " + problems, problems.isEmpty());
        assertEquals("服务端段 20 条", 20, CommandCatalog.serverDefs().size());
        assertEquals("通道私有一段 9 条", 9, CommandCatalog.localDefs().size());
        assertEquals("整张表 = 两段之和，不重不漏",
                29, CommandCatalog.defs().size());
    }

    // ================= 面 1：服务端注册表 ⇄ 表 =================

    @Test
    public void serverRegistryRegistersExactlyTheCatalogServerSegment() {
        SlashRegistry registry = SlashRegistry.withBuiltinCommands();
        Set<String> actual = new LinkedHashSet<String>();
        for (String name : registry.coreCommandNames()) {
            actual.add(name);
        }
        assertSameNames("服务端注册表 ⇄ CommandCatalog.serverNames()",
                expected(CommandCatalog.serverNames()), actual);
    }

    // ================= 面 2：HTTP /api/commands ⇄ 表（真进程真字节） =================

    @Test
    public void httpCommandsEndpointServesExactlyTheCatalog() throws Exception {
        P28HttpFixture fx = P28HttpFixture.start(tmp);
        try {
            // 状态码先行：404 也有 body，别把别人的应答当自己的。
            P28HttpFixture.Response r = fx.expectStatus("GET", "/api/commands", null, 200);
            JsonNode rows = M.readTree(r.body);
            assertTrue("/api/commands 必须吐数组，实到: " + r.body, rows.isArray());

            List<String> staticNames = new ArrayList<String>();
            int skillRows = 0;
            for (JsonNode row : rows) {
                String scope = row.path("scope").asText("");
                if ("skill".equals(scope)) {
                    skillRows++;
                    // 技能段的可见性也只在 skillEndpoints() 判一次，端点不许各自再判。
                    assertEquals("技能行的 endpoints 必须等于 CommandCatalog.skillEndpoints(): " + row,
                            jsonKeys(CommandCatalog.skillEndpoints()), namesOf(row.path("endpoints")));
                    continue;
                }
                staticNames.add(row.path("name").asText());
                String name = row.path("name").asText();
                CommandCatalog.Def def = CommandCatalog.find(name);
                assertTrue("HTTP 吐了表里没有的行: " + row, def != null);
                assertEquals(name + " 的说明与表不同源", def.description(), row.path("description").asText());
                assertEquals(name + " 的参数提示与表不同源", def.argsHint(), row.path("args").asText());
                assertEquals(name + " 的可见性段与表不同源",
                        jsonKeys(def.endpoints()), namesOf(row.path("endpoints")));
            }
            assertSameNames("GET /api/commands ⇄ CommandCatalog.defs()",
                    expected(namesOfAll(CommandCatalog.defs())), expected(staticNames));
            System.out.println("[p19] /api/commands 行数=" + rows.size()
                    + "（静态 " + staticNames.size() + " + 技能 " + skillRows + "）");
        } finally {
            fx.close();
        }
    }

    @Test
    public void httpFacetInjectionIsCaught() throws Exception {
        Set<String> derived = expected(namesOfAll(CommandCatalog.defs()));
        // 注入：HTTP 多吐一条表里没有的命令 ⇒ 必须红。
        Set<String> served = with(derived, "/injected-not-in-catalog");
        List<String> problems = diff("http", derived, served);
        assertTrue("注入没被抓到（卫兵是死的）: " + problems,
                problems.contains("+ http 实际吐出但表里没有: /injected-not-in-catalog"));
        // 反向注入：表里承诺了 29 条，HTTP 只吐 28 条 ⇒ 也要红。
        assertTrue("反向注入没被抓到: " + (problems = diff("http", derived, without(derived, "/usage"))),
                problems.contains("- http 表里登记了但该端没吐: /usage"));
    }

    // ================= 面 3：WEB 控制台 ⇄ 表 =================

    /** web/index.html 里 handleSlash 真有分支的命令名 —— 这就是"web 端实际接得住"的观测面。 */
    private static final Pattern JS_BRANCH =
            Pattern.compile("cmd\\s*===\\s*'(/[^']*)'|cmd\\.startsWith\\('(/[^']*)'\\)");

    static Set<String> webHandledCommands(String html) {
        String body = sliceBetween(html, "async function handleSlash(text) {", "\n    }\n");
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = JS_BRANCH.matcher(body);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return out;
    }

    @Test
    public void webConsoleHandlesExactlyTheWebSegment() throws Exception {
        Set<String> actual = webHandledCommands(readClasspath("web/index.html"));
        assertSameNames("web/index.html 的分支 ⇄ CommandCatalog 的 WEB 段",
                expected(CommandCatalog.namesFor(CommandCatalog.Endpoint.WEB)), actual);
    }

    @Test
    public void webFacetInjectionIsCaught() throws Exception {
        String html = readClasspath("web/index.html");
        Set<String> derived = expected(CommandCatalog.namesFor(CommandCatalog.Endpoint.WEB));

        // 注入 A：web 广告了一条却没有分支 ⇒ 表里给 WEB 段加一条，html 里没有 ⇒ 必须红。
        Set<String> withPhantom = new LinkedHashSet<String>(derived);
        withPhantom.add("/injected-ad-only");
        assertTrue("注入 A 没被抓到",
                !diff("web", withPhantom, webHandledCommands(html)).isEmpty());

        // 注入 B：web 写了一条没登记的分支 ⇒ 必须红（这条同时证明 /exit 那种"广告了没兑现"进不来）。
        String injectedHtml = html.replace("} else if (cmd.startsWith('/confirm')) {",
                "} else if (cmd === '/injected-branch') {\n            nope();\n        } else if (cmd.startsWith('/confirm')) {");
        assertTrue("注入 B 没被抓到",
                webHandledCommands(injectedHtml).contains("/injected-branch"));
        assertTrue("注入 B 没被抓到（diff 为空）",
                !diff("web", derived, webHandledCommands(injectedHtml)).isEmpty());
    }

    /** p28 §3.4/§3.5 的那一笔：旧版把 /exit 抄进了 web 的清单，但 web 根本没有这个分支。 */
    @Test
    public void webDoesNotAdvertiseExitAndCannotRegressSilently() throws Exception {
        String html = readClasspath("web/index.html");
        assertTrue("web 不再保留手抄的 /help 清单（旧版硬编码在 handleSlash 里）",
                !html.contains("/exit      — 退出"));
        assertTrue("/exit 不该出现在 WEB 段：web 没有退出的概念",
                !CommandCatalog.namesFor(CommandCatalog.Endpoint.WEB).contains("/exit"));
        assertTrue("web 的实际分支里也没有 /exit", !webHandledCommands(html).contains("/exit"));
    }

    // ================= 面 4：TUI ⇄ 表 =================

    /** TerminalChannel.handleSlashCommand 里 {@code "/x".equals(name)} 的那些名字（通道私有分支）。 */
    private static final Pattern JAVA_BRANCH =
            Pattern.compile("\"(/[A-Za-z?]+)\"\\.equals\\(name\\)");

    static Set<String> tuiLocalBranches(String java) {
        String body = sliceBetween(java,
                "private void handleSlashCommand(String cmd) {", "\n    }\n");
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = JAVA_BRANCH.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    public void tuiHandlesExactlyTheChannelLocalSegment() throws Exception {
        // 服务端那 20 条走 slash.find()（注册表），所以 handleSlashCommand 的分支集合
        // 应当逐名字等于"通道私有一段" —— 多一条少一条都是漂移。
        Set<String> actual = tuiLocalBranches(readMainSource(
                "src/main/java/com/zifang/z/bot/channel/TerminalChannel.java"));
        assertSameNames("TerminalChannel 分支 ⇄ CommandCatalog.localNames()",
                expected(CommandCatalog.localNames()), actual);
    }

    @Test
    public void tuiFacetInjectionIsCaught() throws Exception {
        Set<String> derived = expected(CommandCatalog.localNames());
        assertTrue("注入没被抓到", !diff("tui", with(derived, "/injected-tui"), derived).isEmpty());
        assertTrue("注入没被抓到（反向：表里有分支没了）",
                !diff("tui", derived, without(derived, "/theme")).isEmpty());
    }

    /** 终端两条输入实现的补全池必须同源（RawTerminalReader 与 JLine 路径共用表）。 */
    @Test
    public void terminalCompletionPoolIsDerivedFromCatalog() {
        assertEquals("raw 输入的本地命令池 = 表的通道私有一段",
                CommandCatalog.localNames(),
                new ArrayList<String>(com.zifang.z.bot.ui.RawTerminalReader.LOCAL_COMMANDS));
        List<String> pool = CommandCatalog.completionPoolFor(CommandCatalog.Endpoint.TUI);
        assertTrue("TUI 补全池要含通道私有名，也要含服务端名",
                pool.contains("/theme") && pool.contains("/new"));
        assertEquals("TUI 段 = 整张表（终端是全能端）", 29, pool.size());
    }

    // ================= diff 引擎 =================

    private Set<String> expected(Iterable<String> names) {
        Set<String> out = new LinkedHashSet<String>();
        for (String n : names) {
            out.add(n);
        }
        return out;
    }

    private static Set<String> with(Set<String> in, String extra) {
        Set<String> out = new LinkedHashSet<String>(in);
        out.add(extra);
        return out;
    }

    private static Set<String> without(Set<String> in, String minus) {
        Set<String> out = new LinkedHashSet<String>(in);
        out.remove(minus);
        return out;
    }

    /**
     * 两个集合的差异，形状化成人能读的一行一条。
     *
     * <p><b>空输入一律 FATAL</b> —— 解析器哪天因为改了写法扫不到东西，
     * 必须当场炸，不能给出"两边都空 ⇒ 相等 ⇒ 绿"。</p>
     */
    static List<String> diff(String facet, Set<String> derived, Set<String> actual) {
        List<String> problems = new ArrayList<String>();
        if (derived.isEmpty()) {
            problems.add("[FATAL] " + facet + " 派生集合为空 —— 表被清空了？");
            return problems;
        }
        if (actual.isEmpty()) {
            problems.add("[FATAL] " + facet + " 实际吐出集合为空 —— 解析器没扫到东西，"
                    + "不是「该端真的没有命令」；判 FATAL 不判相等");
            return problems;
        }
        for (String n : derived) {
            if (!actual.contains(n)) {
                problems.add("- " + facet + " 表里登记了但该端没吐: " + n);
            }
        }
        for (String n : actual) {
            if (!derived.contains(n)) {
                problems.add("+ " + facet + " 实际吐出但表里没有: " + n);
            }
        }
        return problems;
    }

    private void assertSameNames(String what, Set<String> derived, Set<String> actual) {
        List<String> problems = diff(what, derived, actual);
        if (!problems.isEmpty()) {
            fail(what + " 不同源：\n  " + join(problems, "\n  ")
                    + "\n  派生=" + derived + "\n  实际=" + actual);
        }
        assertEquals(what + " 条数", derived.size(), actual.size());
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    // ================= 读字节 =================

    static String readClasspath(String resource) throws IOException {
        InputStream is = CommandSurfaceConsistencyTest.class.getClassLoader()
                .getResourceAsStream(resource);
        if (is == null) {
            throw new AssertionError("[FATAL] classpath 上没有 " + resource + " —— 判 FATAL 不判通过");
        }
        try {
            return new String(readAll(is), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }

    /**
     * 读主源码文件。锚点是<b>已加载的类的字节码位置</b>（{@code <module>/target/classes}），
     * 从它回推模块目录再拼源码路径 —— 不依赖工作目录，也不硬编码任何 worktree 绝对路径
     * （p25/p26/p27 三支尺就是栽在硬编码写手树绝对路径上）。
     */
    static String readMainSource(String moduleRelative) throws IOException {
        File anchor;
        try {
            anchor = new File(TerminalChannel.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new AssertionError("定位不到 TerminalChannel 的字节码位置: " + e);
        }
        File moduleDir = new File(anchor, "../..").getCanonicalFile();   // target/classes -> <module>
        File candidate = new File(moduleDir, moduleRelative).getCanonicalFile();
        if (!candidate.isFile()) {
            File fromCwd = new File(new File("").getAbsoluteFile(), moduleRelative).getCanonicalFile();
            if (!fromCwd.isFile()) {
                throw new AssertionError("[FATAL] 找不到源文件 " + moduleRelative
                        + "（试过 " + candidate + " 与 " + fromCwd + "）");
            }
            candidate = fromCwd;
        }
        return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
    }

    private static String sliceBetween(String source, String fromMarker, String toMarker) {
        int from = source.indexOf(fromMarker);
        if (from < 0) {
            throw new AssertionError("[FATAL] 源文件里找不到锚点 \"" + fromMarker
                    + "\" —— 判 FATAL：说明解析器与写法脱钩了，不许当成「扫不到就算通过」");
        }
        int body = from + fromMarker.length();
        int to = source.indexOf(toMarker, body);
        if (to < 0) {
            throw new AssertionError("[FATAL] 锚点 \"" + fromMarker + "\" 之后找不到收尾标记");
        }
        return source.substring(body, to);
    }

    private static List<String> namesOfAll(List<CommandCatalog.Def> defs) {
        List<String> out = new ArrayList<String>();
        for (CommandCatalog.Def d : defs) {
            out.add(d.name());
        }
        return out;
    }

    private static List<String> namesOf(JsonNode arrayNode) {
        List<String> out = new ArrayList<String>();
        for (JsonNode n : arrayNode) {
            out.add(n.asText());
        }
        return out;
    }

    private static List<String> jsonKeys(Set<CommandCatalog.Endpoint> endpoints) {
        List<String> out = new ArrayList<String>();
        for (CommandCatalog.Endpoint e : CommandCatalog.Endpoint.values()) {
            if (endpoints.contains(e)) {
                out.add(e.jsonKey());
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    /** 钉住"四端"这个口径本身：端点枚举少一个，消费端就可能悄悄没人守。 */
    @Test
    public void fourConsumersAreAllDeclared() {
        assertEquals(Arrays.toString(CommandCatalog.Endpoint.values()),
                4, CommandCatalog.Endpoint.values().length);
        assertEquals("tui,http,web,acp",
                join(jsonKeys(new LinkedHashSet<CommandCatalog.Endpoint>(
                        Arrays.asList(CommandCatalog.Endpoint.values()))), ","));
        assertEquals("技能段可见性 = TUI/HTTP/ACP（web 无转发分支）",
                Arrays.asList("tui", "http", "acp"),
                jsonKeys(CommandCatalog.skillEndpoints()));
        assertEquals(Collections.emptyList(), CommandCatalog.selfCheck());
    }
}
