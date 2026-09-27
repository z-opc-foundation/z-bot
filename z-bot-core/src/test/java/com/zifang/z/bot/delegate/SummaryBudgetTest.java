package com.zifang.z.bot.delegate;

import com.zifang.z.bot.config.BotConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P27c 第二条：子代理回复进父上下文前有一道字符预算，超限 ⇒ 头尾各留一段、全文溢出落文件，
 * footer 里说清"看见多少 / 共多少 / 怎么翻页读中间那段"。
 *
 * <p>出处（权威副本 {@code ~/.hermes/hermes-agent} @ {@code cbc1054e2}，
 * 每条都是本期现量，见 {@code _doc/acceptance/p27c/EVIDENCE.md} §0）：
 * {@code tools/delegate_tool.py:587-590}（"0 disables the ceiling" +
 * {@code DEFAULT_MAX_SUMMARY_CHARS = 24000}）、{@code :1615-1637}
 * {@code _spill_summary_to_file}（best-effort）、{@code :1640-1693}
 * {@code _trim_summary_with_footer}（75/25、吸附到行边界、footer 给
 * {@code read_file path=… offset=…}）、{@code :1735-1780} {@code _apply_summary_budget}
 * （配置键 {@code max_summary_chars}）。</p>
 */
public class SummaryBudgetTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ===== 常量与配置键 =====

    @Test
    public void defaultCapIsTheHermesNumberAndTheConfigDefaultAgrees() throws Exception {
        assertEquals("hermes delegate_tool.py:590", 24000,
                SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS);
        BotConfig missing = BotConfig.load(new File(tmp.getRoot(), "no-such-profile"));
        assertEquals("缺省必须等于她那个数", SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS,
                missing.getDelegateMaxSummaryChars());
    }

    @Test
    public void configKeyIsParsedAndZeroMeansOff() throws Exception {
        assertEquals(512, write("agent.delegate.max.summary.chars=512\n").getDelegateMaxSummaryChars());
        assertEquals("0 = 关掉上限（她的 \"0 disables the ceiling\"）", 0,
                write("agent.delegate.max.summary.chars=0\n").getDelegateMaxSummaryChars());
    }

    /**
     * 键必须真的有逻辑读点：解析出来没人读的配置键是个假装饰 —— 用户改了它、行为不变，
     * 而界面上没有任何地方会说谎。行为那一段在 {@code DelegateSummaryWiringTest} 里，
     * 这里钉的是"读点还留在源码里"，专门防将来有人把出口换成写死的常量。
     */
    @Test
    public void capKeyIsActuallyReadByTheDelegateExits() throws Exception {
        String src = readMain("delegate/DelegateManager.java");
        assertTrue("summaryCap() 不再问配置 ⇒ 键成了装饰品",
                src.contains("config.getDelegateMaxSummaryChars()"));
        int exits = countOccurrences(src, "SummaryBudget.trim(");
        assertEquals("进父上下文的出口各裁一次（同步回执 + /background result）", 2, exits);
        assertEquals("两处都走同一把尺同一个溢出目录", 2,
                countOccurrences(src, "summaryCap(), summariesRoot()"));
    }

    // ===== 裁切本身 =====

    @Test
    public void underCapTextIsReturnedUntouched() {
        String text = liney(3);
        SummaryBudget.Trimmed t = SummaryBudget.trim(text, 1000);
        assertFalse(t.truncated);
        assertNull(t.spillPath);
        assertEquals(text, t.text);
    }

    @Test
    public void capZeroOrNegativeDisablesTrimming() {
        String text = liney(200);
        assertEquals(text, SummaryBudget.trim(text, 0).text);
        assertFalse(SummaryBudget.trim(text, 0).truncated);
        assertEquals("负数与 0 同义（都是关掉）", text, SummaryBudget.trim(text, -1).text);
    }

    @Test
    public void overCapKeepsHeadAndTailAndSpillsFullText() throws Exception {
        String text = liney(50);                       // 50 * 101 = 5050 字符
        File dir = tmp.newFolder("spill");
        SummaryBudget.Trimmed t = SummaryBudget.trim(text, 1000, dir, "bg1-1");

        assertTrue(t.truncated);
        assertNotNull("有目录就该有指针", t.spillPath);
        String spilled = new String(Files.readAllBytes(new File(t.spillPath).toPath()), StandardCharsets.UTF_8);
        assertEquals("溢出文件必须是完整原文，不是裁过的", text, spilled);
        assertTrue(new File(t.spillPath).getName(), t.spillPath.endsWith(".txt"));
        assertTrue(t.spillPath, new File(t.spillPath).getName().startsWith("subagent-summary-bg1-1-"));

        int marker = t.text.indexOf("[... 中间段已省略");
        int rule = t.text.indexOf("────────");
        assertTrue(marker > 0);
        assertTrue(rule > marker);
        String head = t.text.substring(0, marker);
        String tail = t.text.substring(marker, rule);
        // cap=1000 ⇒ head 预算 750（吸附后覆盖前 6 整行 + 第 7 行前缀）、tail 预算 250（吸附到第 49 行起）
        assertTrue(head, head.contains(line(0)));
        assertTrue(head, head.contains(line(5)));
        assertFalse("中间段必须真的被省略: " + head, head.contains(line(10)));
        assertFalse(tail, tail.contains(line(45)));
        assertTrue(tail, tail.contains(line(49)));

        String footer = t.text.substring(rule);
        assertTrue(footer, footer.contains("[SUMMARY TRUNCATED]"));
        assertTrue(footer, footer.contains("原文共 " + text.length() + " 字符"));
        assertTrue(footer, footer.contains("read_file path=\"" + t.spillPath + "\""));
        assertTrue(footer, footer.contains("offset="));
        // 裁切是为了护住父上下文：结果必须落回预算之内（footer 是固定开销，不计入头尾预算）
        assertTrue(t.text.length() + "", t.text.length() < 1000 + 600);
    }

    @Test
    public void footerOffsetPointsAtTheOmittedMiddleAndIsOneIndexed() throws Exception {
        String text = liney(50);
        File dir = tmp.newFolder("spill2");
        SummaryBudget.Trimmed t = SummaryBudget.trim(text, 1000, dir, "bg2");
        String head = t.text.substring(0, t.text.indexOf("\n\n[... 中间段已省略"));
        int offset = offsetInFooter(t.text);
        assertEquals("offset = 头里已有的行数 + 2（1 基，跳过最后那条已显示的头行）",
                countNewlines(head) + 2, offset);
        // 指针真的落得下：溢出文件行数足够从这一行往下读
        List<String> lines = Files.readAllLines(new File(t.spillPath).toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.size() + "", offset >= 1 && offset <= lines.size());
        assertEquals("从 offset 那一行起就是被省略的中间段", line(7), lines.get(offset - 1));
    }

    /**
     * 三条"落不成"的路各自都要只回 null，不能把裁切本身带走：
     * {@code dir==null}（推不出根目录）、目录建不出来（{@code mkdirs} 返回 false，压根没异常）、
     * 目录在但写不进去（open 抛 {@code FileNotFoundException}，走 catch）。
     * 前两支都在 catch 之前返回，只拿"是个文件"当 fixture 是量不到第三支的 ——
     * 杠② run1 的 D3 因此恒绿过一次（见 EVIDENCE §4）。
     */
    @Test
    public void spillDirectoryUnwritableStillTrimsButSaysSo() throws Exception {
        File dir = tmp.newFolder("spill-blocked");
        assertTrue("fixture 没成立：目录改不成只读", dir.setWritable(false));
        SummaryBudget.Trimmed t;
        try {
            t = SummaryBudget.trim(liney(50), 1000, dir, "bg3");
        } finally {
            dir.setWritable(true);
        }
        assertTrue("落盘失败不能连裁切一起放弃", t.truncated);
        assertNull(t.spillPath);
        assertTrue(t.text, t.text.contains("（全文没能落盘，上面的头尾就是保留下来的全部）"));
        assertFalse(t.text, t.text.contains("read_file path="));
        assertEquals("写不进去就不该留下半截文件", 0, dir.listFiles().length);
    }

    @Test
    public void spillDirectoryUnbuildableStillTrimsButSaysSo() throws Exception {
        File notADir = tmp.newFile("blocked");   // 是个文件 ⇒ mkdirs 必失败（无异常，走早退那一支）
        SummaryBudget.Trimmed t = SummaryBudget.trim(liney(50), 1000, notADir, "bg3");
        assertTrue("落盘失败不能连裁切一起放弃", t.truncated);
        assertNull(t.spillPath);
        assertTrue(t.text, t.text.contains("（全文没能落盘，上面的头尾就是保留下来的全部）"));
        assertFalse(t.text, t.text.contains("read_file path="));
    }

    @Test
    public void twoArgOverloadHasNoPointer() {
        SummaryBudget.Trimmed t = SummaryBudget.trim(liney(50), 1000);
        assertTrue(t.truncated);
        assertNull(t.spillPath);
        assertTrue(t.text, t.text.contains("全文没能落盘"));
    }

    @Test
    public void nullAndEmptySurviveWithoutThrowing() {
        assertEquals("", SummaryBudget.trim(null, 100).text);
        assertFalse(SummaryBudget.trim(null, 100).truncated);
        assertEquals("", SummaryBudget.trim("", 100).text);
    }

    // ===== helpers =====

    private BotConfig write(String... lines) throws Exception {
        File dir = tmp.newFolder("cfg", String.valueOf(System.nanoTime()));
        try (FileWriter w = new FileWriter(new File(dir, "config.properties"))) {
            for (String line : lines) {
                w.write(line + "\n");
            }
        }
        return BotConfig.load(dir);
    }

    /** 每行 100 个可辨识字符 + 换行：任何"半行"都能在断言里看出来。 */
    private static String liney(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(line(i)).append('\n');
        }
        return sb.toString();
    }

    private static String line(int i) {
        StringBuilder sb = new StringBuilder(String.format("L%03d|", i));
        while (sb.length() < 100) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static int offsetInFooter(String trimmed) {
        int i = trimmed.indexOf("offset=");
        assertTrue("footer 里没有 offset 指针: " + trimmed.substring(Math.max(0, trimmed.length() - 400)), i > 0);
        int j = i + "offset=".length();
        int k = j;
        while (k < trimmed.length() && Character.isDigit(trimmed.charAt(k))) {
            k++;
        }
        return Integer.parseInt(trimmed.substring(j, k));
    }

    private static String readMain(String rel) throws Exception {
        List<String> candidates = new ArrayList<String>();
        candidates.add("z-bot-core/src/main/java/com/zifang/z/bot/" + rel);
        candidates.add("src/main/java/com/zifang/z/bot/" + rel);
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到主源码 " + rel + "（试过 " + candidates + "）");
    }
}
