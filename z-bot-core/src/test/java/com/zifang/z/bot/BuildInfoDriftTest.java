package com.zifang.z.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * 版本线漂移守卫（P21 之后的补尺）。
 *
 * <p><b>要防的事故</b>：抬版只改了根 pom 的 {@code <revision>}，源码里那几处自报版本
 * （bot 中心注册串、{@code --version}、MCP 握手 client/server 版本）还停在旧面值。
 * 改之前这件事<b>没有任何尺会读</b>——原有测试只断"自报版本不许等于 0.2.0"，把面值改成
 * 0.1.9 照样绿；jar manifest 那条路在单测里恒为 null（跑在 target/classes 下），所以
 * "跟 jar 走"的实现本身也测不到面值。</p>
 *
 * <p><b>判据</b>：① 根 pom 的 {@code <revision>} 必须与 {@link BuildInfo#REVISION} 逐字相等；
 * ② {@code src/main/java} 的非注释代码行里，凡是形如 {@code x.y.z} / {@code x.y.z-suffix} 的
 * 字符串字面量（含 {@code "z-bot 0.2.1"}、{@code "z-bot/0.2.1"} 这种带前缀的），版本段必须等于
 * 当前 revision，或者等于 {@code revision + "-dev"}。</p>
 *
 * <p><b>不空跑</b>：pom 里 {@code <revision>} 命中数必须是 1（命中 0 或 &gt;1 一律判红，不是"跳过"）；
 * 扫描必须至少读到 1 处字面量——即 {@link BuildInfo} 自己那一行，读不到说明 root 找错了目录；
 * 拒绝分支由同一判据喂一行假代码（{@code "9.9.9"}）现场验证，不是靠人眼看正则。</p>
 *
 * <p>只扫 {@code src/main/java}：{@code src/main/resources} 下有前端库自带的 semver 面值，
 * 一并扫会把尺磨成"永远要加白名单"的钝刀。那侧的现状是 2026-10-01 现读
 * {@code grep -rn '0\.2\.[0-9]' z-bot-core/src/main/resources} 为空。</p>
 */
public class BuildInfoDriftTest {

    /** 版本面值形状：三段数字，可带 -suffix（dev / p20 这类快照尾巴）。 */
    private static final Pattern VERSIONISH = Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?$");

    /** 字符串字面量（不处理转义，够用：源码里版本串没有引号嵌套）。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\\\n]*)\"");

    /** 根 pom 里的 {@code <revision>}。 */
    private static final Pattern REVISION_TAG = Pattern.compile("<revision>([^<]+)</revision>");

    /**
     * 第三方版本面值的显式豁免：每项写 {@code 文件相对路径|行号|坐标}，并且<b>必须真命中</b>——
     * 命中不到就判红，防止豁免清单自己变陈旧或变成"顺手放行一切"。
     */
    private static final List<String> THIRD_PARTY_ALLOWLIST = Arrays.asList();

    @Test
    public void revisionInPomAndSourceAreTheSameNumber() throws Exception {
        File root = repoRoot();
        File pom = new File(root, "pom.xml");
        String pomText = read(pom);

        Matcher m = REVISION_TAG.matcher(pomText);
        List<String> hits = new ArrayList<String>();
        while (m.find()) {
            hits.add(m.group(1).trim());
        }
        assertEquals("根 pom 的 <revision> 该恰好命中 1 次，实得 " + hits.size()
                + " (" + hits + ")，pom=" + pom.getAbsolutePath(), 1, hits.size());
        String revision = hits.get(0);

        assertTrue("revision 面值不成形状，判定链往下全是空的: " + revision,
                VERSIONISH.matcher(revision).matches());
        assertEquals("源码侧 BuildInfo.REVISION 与根 pom 的 <revision> 不一致 —— 抬版两处要一起改",
                revision, BuildInfo.REVISION);

        // 派生串也得跟着：这三条是对外说出去的版本线。
        assertEquals(revision + "-dev", BuildInfo.DEV);
        assertEquals("z-bot/" + revision, BuildInfo.BOT_VERSION);
        assertEquals("z-bot " + revision, BuildInfo.CLI_VERSION);
    }

    @Test
    public void noStaleVersionLiteralOutsideBuildInfo() throws Exception {
        File root = repoRoot();
        File srcMain = new File(root, "z-bot-core/src/main/java");
        assertTrue("找不到 src/main/java，扫描根本没跑起来: " + srcMain.getAbsolutePath(),
                srcMain.isDirectory());

        List<String> offenders = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        Set<String> allowlistMatched = new LinkedHashSet<String>();
        List<File> files = javaFiles(srcMain);
        for (File f : files) {
            String rel = relativize(root, f);
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                String code = stripComment(raw);
                if (code == null) {
                    continue;
                }
                Matcher lit = STRING_LITERAL.matcher(code);
                while (lit.find()) {
                    String value = lit.group(1);
                    String candidate = versionSegmentOf(value);
                    if (candidate == null) {
                        continue;
                    }
                    seen.add(rel + ":" + (i + 1) + " = " + value);
                    if (isCurrentLine(candidate)) {
                        continue;
                    }
                    String key = rel + "|" + (i + 1);
                    if (matchesAllowlist(key)) {
                        allowlistMatched.add(key);
                        continue;
                    }
                    offenders.add(rel + ":" + (i + 1) + " 的字面量 \"" + value
                            + "\" 里带着版本面值 " + candidate
                            + "，而当前 <revision> 是 " + BuildInfo.REVISION);
                }
            }
        }

        // 阳性对照：扫描器必须真读到东西——BuildInfo 自己那一行 REVISION 面值必然在集合里。
        assertTrue("扫描器一行版本面值都没读到（读了 " + files.size() + " 个文件），"
                + "空集下的\"没有陈旧面值\"是假绿", !seen.isEmpty());
        assertTrue("扫描没读到 BuildInfo.REVISION 那一行，说明它扫的不是本仓的 src/main: " + seen,
                containsAny(seen, "BuildInfo.java"));

        for (String key : THIRD_PARTY_ALLOWLIST) {
            assertTrue("豁免清单里的 " + key + " 已经命中不到了 —— 要么面值早变了，"
                    + "要么这行没了，清单该删这一项", allowlistMatched.contains(key));
        }

        if (!offenders.isEmpty()) {
            fail("版本线漂移，逐处点名:\n  " + join(offenders));
        }
    }

    /**
     * 同一判据必须会拒绝：把一行"抬版没带走"的代码喂给它，必须报违规。
     * 没有这条，上面的绿可能只是正则压根没匹配上。
     */
    @Test
    public void theSamePredicateRejectsAForeignVersionLiteral() throws Exception {
        String bad = "        String clientVersion = \"9.9.9\";";
        String good = "        String clientVersion = BuildInfo.REVISION; // " + BuildInfo.REVISION;
        String badValue = versionSegmentOf(extractFirstLiteral(bad));
        assertNotNull("判据认不出 9.9.9 这种形状，说明它对\"漏改的那一行\"是瞎的", badValue);
        assertFalse("判据居然接受 9.9.9 —— 那\"没有陈旧面值\"这条绿毫无意义",
                isCurrentLine(badValue));

        String goodValue = versionSegmentOf(extractFirstLiteral(good));
        if (goodValue != null) {
            assertTrue("带 revision 面值的注释行反被拒: " + goodValue, isCurrentLine(goodValue));
        }
        assertNull("纯代码引用（没有字面量）不该被读出一个版本面值",
                versionSegmentOf(extractFirstLiteral(
                        "    public static final String BOT_VERSION = BuildInfo.BOT_VERSION;")));
    }

    // ---------- 判据零件 ----------

    /** 从字符串字面量里取出版本段；不是版本形状返回 null。 */
    private static String versionSegmentOf(String literal) {
        String v = literal.trim();
        if (VERSIONISH.matcher(v).matches()) {
            return v;
        }
        for (String prefix : new String[] {"z-bot/", "z-bot "}) {
            if (v.startsWith(prefix)) {
                String tail = v.substring(prefix.length());
                if (VERSIONISH.matcher(tail).matches()) {
                    return tail;
                }
            }
        }
        return null;
    }

    /** 当前版本线允许的两种面值：revision 本身，和 manifest 读不到时的 dev 串。 */
    private static boolean isCurrentLine(String versionSegment) {
        return versionSegment.equals(BuildInfo.REVISION)
                || versionSegment.equals(BuildInfo.DEV);
    }

    private static boolean matchesAllowlist(String key) {
        for (String entry : THIRD_PARTY_ALLOWLIST) {
            String[] parts = entry.split("\\|");
            if (parts.length >= 2 && (parts[0] + "|" + parts[1]).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static String extractFirstLiteral(String line) {
        Matcher m = STRING_LITERAL.matcher(stripComment(line) == null ? "" : stripComment(line));
        return m.find() ? m.group(1) : "";
    }

    /** 返回去掉注释后的代码部分；整行是注释（javadoc 里的历史面值不算漂移）返回 null。 */
    private static String stripComment(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")
                || trimmed.startsWith("/*")) {
            return null;
        }
        int idx = trimmed.indexOf("//");
        String code = idx >= 0 ? trimmed.substring(0, idx) : trimmed;
        // 行尾注释里写"当前 0.2.1"这类说明是允许的；但 javadoc 式多行注释已整行被上面挡掉。
        int blockStart = code.indexOf("/*");
        if (blockStart >= 0) {
            code = code.substring(0, blockStart);
        }
        return code.trim();
    }

    // ---------- 找路径 ----------

    /** 根目录：surefire 的 user.dir 通常是模块目录，从 reactor 根直跑时是仓库根，两种都要认。 */
    private static File repoRoot() {
        String[] candidates = {".", ".."};
        for (String c : candidates) {
            File dir = new File(c);
            File pom = new File(dir, "pom.xml");
            if (pom.isFile() && isThisRepo(pom)) {
                return dir.getAbsoluteFile();
            }
        }
        fail("既不在 z-bot-core 里跑、也不在仓库根里跑，找不到带 <revision> 的 z-bot pom —— "
                + "这条尺拒绝用猜的路径继续");
        return null;
    }

    private static boolean isThisRepo(File pom) {
        try {
            String text = read(pom);
            return text.contains("<artifactId>z-bot</artifactId>") && REVISION_TAG.matcher(text).find();
        } catch (IOException e) {
            return false;
        }
    }

    private static List<File> javaFiles(File dir) throws IOException {
        List<File> out = new ArrayList<File>();
        collect(dir, out);
        assertTrue("扫描到 0 个 .java 文件，判据没跑在真源码上: " + dir.getAbsolutePath(),
                !out.isEmpty());
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        assertNotNull("目录读不出清单（权限/竞态）: " + dir.getAbsolutePath(), children);
        for (File f : children) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static String relativize(File root, File f) {
        return root.getAbsoluteFile().toPath().relativize(f.getAbsoluteFile().toPath()).toString();
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean containsAny(Set<String> rows, String needle) {
        for (String r : rows) {
            if (r.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append("\n  ");
            }
            sb.append(rows.get(i));
        }
        return sb.toString();
    }
}
