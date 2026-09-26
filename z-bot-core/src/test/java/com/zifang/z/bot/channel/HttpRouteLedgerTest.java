package com.zifang.z.bot.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * p28a §1.1 —— <b>路由台账单源化</b>的三面漂移卫兵。
 *
 * <p>{@link HttpChannel#routes()} 是对位面唯一的分母。本类不测业务，只测"这张表是不是真的
 * 就是产品里那张表"：</p>
 * <ol>
 *   <li>表 ⇄ {@code dispatch()} 源码里的 {@code "<path>".equals(path)} 字面量（从源码机械复算，
 *       与工单 §0.2 那条 awk 同口径）；</li>
 *   <li>表 ⇄ {@code _doc/acceptance/p28/ROUTES.tsv}（真进程 E2E 的分母就取自这份文件）；</li>
 *   <li>表自身的一致性：方法粒度不重复、鉴权/绑址两列不许有人偷偷改口、
 *       记了缺陷号的路由必须在 EVIDENCE.md 里点名。</li>
 * </ol>
 *
 * <p>台账的每一行都真起了一个 JDK {@link HttpServer}（端口 0）来验"表上写的东西服务端真兑现了"，
 * 见 {@link HttpRouteShapeTest}；本类只管表。</p>
 */
public class HttpRouteLedgerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** dispatch 里的路由字面量 —— 与工单 §0.2 的 awk 同一条规则。 */
    private static final Pattern DISPATCH_ROUTE_LITERAL =
            Pattern.compile("\"(/[A-Za-z0-9_./-]*)\"\\s*\\.equals\\(path\\)");
    private static final Pattern DISPATCH_METHOD = Pattern.compile("private void dispatch\\(HttpExchange");

    @Test
    public void ledgerPathsMatchDispatchLiterals() throws IOException {
        String src = readMainSource("channel/HttpChannel.java");
        Set<String> fromSource = dispatchPathLiterals(src);
        List<String> fromLedger = HttpChannel.paths();

        Set<String> onlyInSource = new LinkedHashSet<String>(fromSource);
        onlyInSource.removeAll(fromLedger);
        Set<String> onlyInLedger = new LinkedHashSet<String>(fromLedger);
        onlyInLedger.removeAll(fromSource);

        assertEquals("dispatch() 里有、台账里没登记的路由（说明有人加了分支没进单源表）: "
                + onlyInSource, Collections.emptySet(), onlyInSource);
        assertEquals("台账里登记了、dispatch() 里已经不存在的死路由: " + onlyInLedger,
                Collections.emptySet(), onlyInLedger);
        assertEquals("路径条数对不上 —— 分母只有一个口径",
                fromSource.size(), fromLedger.size());
        assertTrue("台账空转：一条路由都没登记", fromLedger.size() >= 20);
    }

    @Test
    public void ledgerRowsAreUniqueMethodPathPairs() {
        Set<String> seen = new LinkedHashSet<String>();
        List<String> dup = new ArrayList<String>();
        for (HttpChannel.Route r : HttpChannel.routes()) {
            String key = r.method() + " " + r.path();
            if (!seen.add(key)) {
                dup.add(key);
            }
        }
        assertEquals("(方法, 路径) 重复的台账行: " + dup, Collections.emptyList(), dup);
        // 22 个路径 / 25 行（sync 有 GET+POST 两个方法面）—— 分母写在表里，不写在断言里
        assertEquals("每一行都得有方法", Integer.valueOf(0),
                Integer.valueOf(countRowsWithoutMethod()));
    }

    private static int countRowsWithoutMethod() {
        int n = 0;
        for (HttpChannel.Route r : HttpChannel.routes()) {
            if (r.method() == null || r.method().trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void everyRowCarriesTheLoopbackOnlyAuthAndBindScope() {
        for (HttpChannel.Route r : HttpChannel.routes()) {
            assertEquals(r + " 的鉴权口径漂移（本期 HTTP 面不许出现要求凭据的路由）",
                    HttpChannel.AUTH_NONE_LOOPBACK_ONLY, r.auth());
            assertEquals(r + " 的绑址口径漂移（暴露到非回环必须是 --host 显式 opt-in）",
                    HttpChannel.BIND_LOOPBACK_DEFAULT, r.bindScope());
            assertNotNull(r + " 没有形状契约", r.fields());
            assertFalse(r + " 的形状契约是空串", r.fields().trim().isEmpty());
        }
    }

    @Test
    public void routesTsvIsInSyncWithLedger() throws IOException {
        File committed = repoFileOrMake("_doc/acceptance/p28/ROUTES.tsv");
        String generated = renderRoutesTsv();
        if (!committed.exists()) {
            fail("ROUTES.tsv 不在盘上；生成命令：mvn -o test -Dtest=HttpRouteLedgerTest"
                    + " -Dp28.routes.write=true（当前指向 " + committed + "）");
        }
        String onDisk = new String(Files.readAllBytes(committed.toPath()), StandardCharsets.UTF_8);
        if (!onDisk.equals(generated)) {
            File out = targetFile("ROUTES.tsv");
            Files.write(out.toPath(), generated.getBytes(StandardCharsets.UTF_8));
            fail("ROUTES.tsv 与 HttpChannel.routes() 不同源了。逐字节差异已写到 " + out
                    + " —— 用 cp 覆盖 _doc/acceptance/p28/ROUTES.tsv 后重跑");
        }
        // 表里的行数与 E2E 的分母一致（E2E 读的就是这份文件）
        assertEquals("ROUTES.tsv 行数 != 台账行数",
                Integer.valueOf(HttpChannel.routes().size()),
                Integer.valueOf(countDataLines(onDisk)));
    }

    @Test
    public void everyBookedDefectIsNamedInEvidence() throws IOException {
        String evidence = new String(Files.readAllBytes(
                repoFile("_doc/acceptance/p28/EVIDENCE.md").toPath()), StandardCharsets.UTF_8);
        Set<String> booked = new LinkedHashSet<String>();
        for (HttpChannel.Route r : HttpChannel.routes()) {
            if (!r.defect().isEmpty()) {
                assertTrue("台账记了缺陷 " + r.defect() + "（" + r + "）却没在 EVIDENCE.md 点名 —— 哑巴缺陷",
                        evidence.contains(r.defect()));
                booked.add(r.defect());
            }
        }
        assertFalse("一条缺陷都没记 —— 本期不可能这么干净", booked.isEmpty());
    }

    // ===== 真进程一面的表侧断言：未登记的路径与未登记的方法各回各的码 =====

    private P28HttpFixture fx;

    @Before
    public void startServer() throws Exception {
        fx = P28HttpFixture.start(tmp);
    }

    @After
    public void stopServer() {
        if (fx != null) {
            fx.close();
        }
    }

    @Test
    public void unknownPathStillFourOhFourWithJsonErrorShape() throws Exception {
        HttpURLConnection c = open("GET", "/bot/statushippo");
        assertEquals(404, c.getResponseCode());
        JsonNode body = JSON.readTree(stream(c));
        assertFalse("404 必须说 ok:false", body.get("ok").asBoolean());
        assertTrue("404 的 error 里要带上被拒的路径", body.get("error").asText().contains("/bot/statushippo"));
    }

    @Test
    public void unadvertisedMethodGetsFourOhFiveWithAllowHeader() throws Exception {
        HttpURLConnection c = open("PUT", "/bot/status");
        assertEquals("表上只登记了 GET，PUT 要回 405 而不是 200", 405, c.getResponseCode());
        assertEquals("GET, OPTIONS", c.getHeaderField("Allow"));
        JsonNode body = JSON.readTree(stream(c));
        assertFalse(body.get("ok").asBoolean());
        assertTrue(body.get("error").asText().contains("PUT"));

        // CORS 预检的 Allow-Methods 与台账口径必须一致（三张嘴不能说三套话）
        HttpURLConnection preflight = open("OPTIONS", "/bot/status");
        assertEquals(204, preflight.getResponseCode());
        assertEquals("GET, POST, OPTIONS", preflight.getHeaderField("Access-Control-Allow-Methods"));
    }

    @Test
    public void consoleRouteServesExactlyTheBundledResource() throws Exception {
        byte[] resource = Bytes.of(HttpChannel.class.getResourceAsStream("/web/index.html"));
        HttpURLConnection c = open("GET", "/index.html");
        assertEquals(200, c.getResponseCode());
        assertEquals("text/html; charset=utf-8", c.getHeaderField("Content-Type"));
        byte[] served = Bytes.of(c.getInputStream());
        assertEquals("控制台回的不是 classpath 里那份 index.html",
                resource.length, served.length);
    }

    // ===== 小工具 =====

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(fx.base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        return c;
    }

    private static String stream(HttpURLConnection c) throws IOException {
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return new String(Bytes.of(is), StandardCharsets.UTF_8);
    }

    private static Set<String> dispatchPathLiterals(String src) {
        int start = indexOf(DISPATCH_METHOD, src);
        String body = src.substring(start, endOfDispatchMethod(src, start));
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = DISPATCH_ROUTE_LITERAL.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static int indexOf(Pattern p, String src) {
        Matcher m = p.matcher(src);
        assertTrue("在 HttpChannel.java 里找不到 dispatch() 的签名 —— 卫兵的锚漂了", m.find());
        return m.start();
    }

    /** 从 dispatch 开头扫到它自己那层花括号闭合（数括号的粗糙法对这一个方法够用）。 */
    private static int endOfDispatchMethod(String src, int start) {
        int depth = 0;
        boolean started = false;
        for (int i = start; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
                started = true;
            } else if (ch == '}') {
                depth--;
                if (started && depth == 0) {
                    return i + 1;
                }
            } else if (ch == '"') {
                i = skipString(src, i);
            } else if (ch == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            }
        }
        fail("dispatch() 的花括号没闭合 —— 文件被截断了？");
        return -1;
    }

    private static int skipString(String src, int quote) {
        for (int i = quote + 1; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                return i;
            }
        }
        return quote;
    }

    static String renderRoutesTsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("# p28a 路由台账 —— 由 HttpChannel.routes() 渲染，别手写这一份\n");
        sb.append("# 复算/再生成: mvn -o test -Dtest=HttpRouteLedgerTest#routesTsvIsInSyncWithLedger")
                .append(" -Dp28.routes.write=true\n");
        sb.append("# 分母: ").append(HttpChannel.routes().size()).append(" 行 (方法粒度) / ")
                .append(HttpChannel.paths().size()).append(" 条路径 (dispatch 字面量口径)\n");
        sb.append("method\tpath\tshape\tauth\tbind_scope\tshape_contract\trow_count_from\tdefect\n");
        for (HttpChannel.Route r : HttpChannel.routes()) {
            sb.append(r.method()).append('\t').append(r.path()).append('\t')
                    .append(r.shape().name()).append('\t').append(r.auth()).append('\t')
                    .append(r.bindScope()).append('\t').append(r.fields()).append('\t')
                    .append(r.rowCountFrom().isEmpty() ? "-" : r.rowCountFrom()).append('\t')
                    .append(r.defect().isEmpty() ? "-" : r.defect()).append('\n');
        }
        return sb.toString();
    }

    private static int countDataLines(String tsv) {
        int n = 0;
        for (String line : tsv.split("\n")) {
            if (!line.startsWith("#") && !line.startsWith("method\t")) {
                n++;
            }
        }
        return n;
    }

    private static int targetWritten = 0;

    private File targetFile(String name) throws IOException {
        File dir = new File("target/p28");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("建不了 " + dir.getAbsolutePath());
        }
        return new File(dir, name);
    }

    /**
     * {@code -Dp28.routes.write=true} 时把生成的 TSV 直接覆盖到仓里那份 —— 只给再生成用，
     * 平时这条路径不开，比对逻辑仍然是"committed 必须逐字节等于渲染结果"。
     */
    @Before
    public void maybeRegenerate() throws Exception {
        if (targetWritten > 0 || !Boolean.getBoolean("p28.routes.write")) {
            return;
        }
        targetWritten++;
        File committed = repoFileOrMake("_doc/acceptance/p28/ROUTES.tsv");
        Files.write(committed.toPath(), renderRoutesTsv().getBytes(StandardCharsets.UTF_8));
        System.out.println("[p28] ROUTES.tsv 已按台账重新生成: " + committed);
    }

    private static String readMainSource(String relativeInMain) throws IOException {
        File f = repoFile("z-bot-core/src/main/java/com/zifang/z/bot/" + relativeInMain);
        assertTrue("找不到源码 " + f.getAbsolutePath() + " —— 卫兵会静默空跑，先让它具名红",
                f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** surefire 的 cwd 是模块目录；往上找一层就是仓根。 */
    private static File repoFile(String relative) {
        File f = repoFileOrNull(relative);
        if (f == null) {
            throw new IllegalStateException("仓根里找不到 " + relative
                    + "（从 " + new File(".").getAbsolutePath() + " 起找）");
        }
        return f;
    }

    /** 存在就返回；不存在返回"该在哪"的路径（父目录已备好），给"文件还没生成"那条路用。 */
    private static File repoFileOrMake(String relative) throws IOException {
        File f = repoFileOrNull(relative);
        if (f != null) {
            return f;
        }
        File probe = new File(new File("..").getAbsoluteFile(), relative);
        if (!probe.getParentFile().isDirectory() && !probe.getParentFile().mkdirs()) {
            throw new IOException("建不了 " + probe.getParentFile());
        }
        return probe.getAbsoluteFile();
    }

    private static File repoFileOrNull(String relative) {
        List<File> bases = Arrays.asList(new File(".").getAbsoluteFile(),
                new File("..").getAbsoluteFile(), new File("../..").getAbsoluteFile());
        for (File base : bases) {
            File f = new File(base, relative);
            if (f.exists()) {
                return f.getAbsoluteFile();
            }
        }
        return null;
    }

    private static final class Bytes {
        private Bytes() {
        }

        static byte[] of(InputStream is) throws IOException {
            if (is == null) {
                return new byte[0];
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            } finally {
                is.close();
            }
            return bos.toByteArray();
        }
    }
}
