package com.zifang.z.bot;

import com.zifang.z.bot.channel.HttpChannel;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.slash.CommandCatalog;
import org.junit.Test;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * README 里每一条定量主张都必须能从代码/台账重算出来 —— 广告出去的话就是承诺。
 *
 * <p>为什么要有这一层：roadmap 与工单里已经三次出现过"文档抄了一份数、代码改了数没改文档"
 * （z-vector 的 {@code /health} 两处版本字面量一起漂就是同一形状）。README 是对外第一面，
 * 它写 "26 行 / 23 条路径 / 29 行 / 10 支子命令 / 内核 pin 0.2.1"，
 * 而没有任何一把尺去问代码"现在还是吗"，那这些数字的保质期就是上一次编辑的时刻。</p>
 *
 * <p>口径：缺一条主张 = 红（不许"正则没匹配上就跳过"，那正是空跑）；
 * 数值不等 = 红且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 尺自己的牙口由 {@link #auditActuallyCondemnsWrongNumbers()} 用合成 README 证明，
 * 而"不许冤枉健康页"由 {@link #healthyReadmeIsNotCondemned()} 证明。</p>
 */
public class ReadmeClaimsTest {

    private static File root;

    /** 一条定量主张：名字 + README 里的原文正则（第 1 组是被钉的数）+ 代码侧重算值。 */
    private static final class Claim {
        final String name;
        final Pattern pattern;
        final String recomputed;

        Claim(String name, String regex, int recomputed) {
            this(name, regex, String.valueOf(recomputed));
        }

        Claim(String name, String regex, String recomputed) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
            this.recomputed = recomputed;
        }
    }

    private static List<Claim> claims() {
        String pom = readRepoFile("pom.xml");
        BotConfig defaults = emptyProfileDefaults();
        return Arrays.asList(
                new Claim("当前源码版本", "\\| 当前源码版本 \\| `([^`]+)`", revisionFrom(pom)),
                new Claim("内核 pin", "\\| 内核 pin \\| `z-agent-kernel\\.version=([^`]+)`",
                        tag(pom, "z-agent-kernel.version")),
                new Claim("路由台账行数", "\\*\\*(\\d+) 行（方法粒度）",
                        HttpChannel.routes().size()),
                new Claim("路由台账路径数", "行（方法粒度）/ (\\d+) 条路径",
                        HttpChannel.paths().size()),
                new Claim("/api/commands 行数", "实测返回 \\*\\*(\\d+) 行\\*\\*",
                        CommandCatalog.defs().size()),
                new Claim("顶层子命令支数", "顶层子命令共 (\\d+) 支",
                        subcommandSpecs().size()),
                // P27c：委托这一族三个缺省值都从代码重算（键写在 README 里就得对得上现值，
                // 不能拿"hermes 那个数是 24000"当第二手证据）。
                new Claim("委托深度缺省", "深度上限缺省 \\*\\*(\\d+)\\*\\*",
                        defaults.getDelegateMaxDepth()),
                new Claim("异步并发宽度缺省", "异步并发宽度缺省 \\*\\*(\\d+)\\*\\*",
                        defaults.getDelegateMaxChildren()),
                new Claim("摘要字符上限缺省", "字符上限缺省 \\*\\*(\\d+)\\*\\*",
                        defaults.getDelegateMaxSummaryChars()));
    }

    /**
     * 一份**空** profile 的 {@link BotConfig}：{@code load(dir)} 只读 {@code <dir>/config.properties}，
     * 文件不存在就整份用字段缺省，不会回落到 {@code ~/.zbot}（红线 1）。
     */
    private static BotConfig emptyProfileDefaults() {
        try {
            File dir = Files.createTempDirectory("zbot-readme-defaults").toFile();
            assertFalse("空 profile 却读到了配置 ⇒ 这份缺省不可信", dir.list().length > 0);
            return BotConfig.load(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("造不出空 profile ⇒ 委托缺省值无从重算: " + e, e);
        }
    }

    /** 逐条对账，返回不成立的条目（含 claimed/recomputed）；主张整条不见了也算不成立。 */
    private static List<String> audit(String readme) {
        return audit(readme, new ArrayList<String>());
    }

    /**
     * @param checkedOut _outparam_：每一条**真的核过且成立**的主记名。拿它和表里声明的条数比，
     *                   这样"某条被静默跳过"就不可能伪装成"全绿"。
     */
    private static List<String> audit(String readme, List<String> checkedOut) {
        List<String> findings = new ArrayList<String>();
        for (Claim c : claims()) {
            Matcher m = c.pattern.matcher(readme);
            if (!m.find()) {
                findings.add(c.name + "：README 里找不到这条主张（重算值 " + c.recomputed
                        + "）—— 删掉主张不等于它成立");
                continue;
            }
            String claimed = m.group(1);
            if (!claimed.equals(c.recomputed)) {
                findings.add(c.name + "：README 写 " + claimed + "，代码/台账重算是 " + c.recomputed);
            } else {
                checkedOut.add(c.name);
            }
        }
        int beforeNames = findings.size();
        List<String> names = subcommandNamesIn(readme);
        if (names.isEmpty()) {
            findings.add("子命令名单：README 里点不出任何一支（重算值 " + subcommandSpecs() + "）");
        } else {
            Set<String> real = new LinkedHashSet<String>();
            for (String s : subcommandSpecs()) {
                real.add(s);
            }
            Set<String> listed = new LinkedHashSet<String>(names);
            for (String s : real) {
                if (!listed.contains(s)) {
                    findings.add("子命令名单：代码里有 " + s + " 但 README 没列");
                }
            }
            for (String s : names) {
                if (!real.contains(s) && !isAlias(s)) {
                    findings.add("子命令名单：README 列了 " + s + " 但代码里没有这一支"
                            + "（真实名单 " + real + "）");
                }
            }
        }
        if (findings.size() == beforeNames) {
            checkedOut.add("子命令名单");
        }
        return findings;
    }

    // ---- 代码侧真值 ----

    private static String revisionFrom(String pom) {
        return tag(pom, "revision");
    }

    private static String tag(String pom, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(pom);
        if (!m.find()) {
            fail("pom.xml 里找不到 <" + tag + "> —— 尺的参照集为空，不许读成\"README 没错\"");
        }
        return m.group(1).trim();
    }

    /**
     * 顶层子命令的 primary 名。参照系取 {@code @Command(subcommands=…)} 注解本身，
     * 不取 picocli 的 {@code CommandSpec.subcommands()} —— 那张 Map 把别名也各占一格
     * （{@code repl} 与 {@code interactive} 两格），拿来当"支数"会多算一支。
     */
    private static List<String> subcommandSpecs() {
        List<String> out = new ArrayList<String>();
        for (Class<?> c : subcommandClasses()) {
            out.add(c.getAnnotation(CommandLine.Command.class).name());
        }
        return out;
    }

    private static boolean isAlias(String name) {
        for (Class<?> c : subcommandClasses()) {
            for (String a : c.getAnnotation(CommandLine.Command.class).aliases()) {
                if (a.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Class<?>[] subcommandClasses() {
        return ZBot.class.getAnnotation(CommandLine.Command.class).subcommands();
    }

    /** 从 "顶层子命令共 N 支：…" 那**一句**（README 里它会折行）里取出反引号包住的命令名。 */
    private static List<String> subcommandNamesIn(String text) {
        Matcher m = Pattern.compile("顶层子命令共 \\d+ 支：(.*?)。", Pattern.DOTALL).matcher(text);
        List<String> out = new ArrayList<String>();
        if (!m.find()) {
            return out;
        }
        Matcher t = Pattern.compile("`([^`]+)`").matcher(m.group(1));
        while (t.find()) {
            out.add(t.group(1));
        }
        return out;
    }

    // ---- 对真实 README 的判定 ----

    @Test
    public void everyQuantitativeReadmeClaimMatchesTheRecomputation() throws Exception {
        String readme = new String(Files.readAllBytes(repoFile("README.md").toPath()),
                StandardCharsets.UTF_8);
        List<String> checked = new ArrayList<String>();
        List<String> findings = audit(readme, checked);
        assertTrue("README 的定量主张与代码不同源：\n  - " + join(findings), findings.isEmpty());
        // 阳性对照（结构性）：绿的那一遍必须真核过表里每一条，否则"全绿"= 静默跳过。
        assertEquals("核过并成立的条数 != 声明的条数（有主张被静默跳过）",
                claims().size() + 1, checked.size());
    }

    @Test
    public void auditActuallyCondemnsWrongNumbers() {
        String real = readRepoFile("README.md");
        // 每支数字都改成"代码重算值 + 1"，尺必须逐条点名。
        String[] probes = {
                "\\| 当前源码版本 \\| `[^`]+`",
                "\\| 内核 pin \\| `z-agent-kernel\\.version=[^`]+`",
                "\\*\\*\\d+ 行（方法粒度）",
                "行（方法粒度）/ \\d+ 条路径",
                "实测返回 \\*\\*\\d+ 行\\*\\*",
                "顶层子命令共 \\d+ 支",
                "深度上限缺省 \\*\\*\\d+\\*\\*",
                "异步并发宽度缺省 \\*\\*\\d+\\*\\*",
                "字符上限缺省 \\*\\*\\d+\\*\\*",
        };
        String[] names = {"当前源码版本", "内核 pin", "路由台账行数", "路由台账路径数",
                "/api/commands 行数", "顶层子命令支数",
                "委托深度缺省", "异步并发宽度缺省", "摘要字符上限缺省"};
        for (int i = 0; i < probes.length; i++) {
            String wrong = bumpOne(real, probes[i]);
            List<String> findings = audit(wrong);
            boolean named = false;
            for (String f : findings) {
                if (f.startsWith(names[i] + "：")) {
                    named = true;
                }
            }
            assertTrue("把 " + names[i] + " 改成错的数，尺没点名 ⇒ 这条主张其实是空跑", named);
        }
        // 名单也要有牙：把 acp 换成一支不存在的命令，必须报"代码里没有这一支"。
        String drifted = real.replace("`acp`", "`acpx`");
        assertTrue("内联名单漂了一支不存在的命令，尺没抓到",
                containsPrefix(audit(drifted), "子命令名单：README 列了 acpx"));
        // 反向：删掉整条主张也必须红（不许"看不见就当成立"）。
        String deleted = real.replaceAll("实测返回 \\*\\*\\d+ 行\\*\\*", "实测返回若干行");
        assertTrue("删掉整条主张，尺当它不存在",
                containsPrefix(audit(deleted), "/api/commands 行数：README 里找不到"));
    }

    @Test
    public void healthyReadmeIsNotCondemned() {
        assertTrue("一份没动的 README 被判有问题（尺冤枉人）", audit(readRepoFile("README.md")).isEmpty());
    }

    @Test
    public void emptyReferenceSetIsFatalNotGreen() {
        // 参照集为空（pom 里找不到那个 tag）时，尺必须炸，而不是安静放行 —— 见"空输入必 FATAL"。
        try {
            tag("<project><foo>1</foo></project>", "revision");
            fail("参照集为空却返回了值");
        } catch (AssertionError expected) {
            assertTrue("报错文案没说清是参照集为空：" + expected.getMessage(),
                    expected.getMessage().contains("参照集为空"));
        }
    }

    // ---- 小工具 ----

    private static String bumpOne(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            fail("探针正则没命中，README 形状变了：/m" + regex + "/");
        }
        String hit = m.group();
        Matcher d = Pattern.compile("\\d+").matcher(hit);
        if (!d.find()) {
            fail("探针命中的片段里没有数字：" + hit);
        }
        String bumped = new StringBuilder(hit).replace(d.start(), d.end(),
                String.valueOf(Integer.parseInt(d.group()) + 1)).toString();
        return text.replace(hit, bumped);
    }

    private static boolean containsPrefix(List<String> rows, String prefix) {
        for (String r : rows) {
            if (r.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            if (sb.length() > 0) {
                sb.append("\n  - ");
            }
            sb.append(r);
        }
        return sb.toString();
    }

    private static String readRepoFile(String relative) {
        try {
            return new String(Files.readAllBytes(repoFile(relative).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 " + relative + " 失败：" + e, e);
        }
    }

    private static File repoFile(String relative) {
        return new File(repoRoot(), relative);
    }

    /**
     * 仓根 = 从 CWD 往上，第一份 pom.xml 里带 {@code <revision>} 的目录。
     *
     * <p>不能按"文件存在"逐个挑：surefire 的 CWD 是 {@code z-bot-core}，那里也有一份
     * {@code pom.xml}（子 pom，不含 {@code <revision>}），先命中它就等于拿子 pom 当版本尺的
     * 参照集 —— 参照集为空，整把尺会静默变成"没数字可对"。所以根只由"这份 pom 带
     * {@code <revision>}"决定，且 {@code pom.xml} 与 {@code README.md} 一律从同一个根起算。</p>
     */
    private static synchronized File repoRoot() {
        if (root != null) {
            return root;
        }
        File dir = new File(".").getAbsoluteFile();
        for (int up = 0; dir != null && up < 6; up++) {
            File pom = new File(dir, "pom.xml");
            if (pom.isFile() && readQuietly(pom).contains("<revision>")) {
                root = dir.getAbsoluteFile();
                return root;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到仓根：从 " + new File(".").getAbsolutePath()
                + " 往上 6 层内没有一份 pom.xml 含 <revision>");
    }

    private static String readQuietly(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
