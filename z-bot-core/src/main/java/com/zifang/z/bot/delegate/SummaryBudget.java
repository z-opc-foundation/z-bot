package com.zifang.z.bot.delegate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 子代理结果摘要的预算：超过上限时<b>头尾各留一段 + 全文溢出落文件</b>，
 * 让父代理读到的是一个"知道自己在看多少"的片段，而不是一堵把上下文窗糊掉的墙。
 *
 * <p>出处（hermes 权威副本 {@code ~/.hermes/hermes-agent} @ {@code cbc1054e2}）：
 * {@code tools/delegate_tool.py:590} {@code DEFAULT_MAX_SUMMARY_CHARS = 24000}
 * （{@code :588} 那句注释把它定位成"动态预算之上再叠一层硬顶，0 表示关掉"）、
 * {@code :1735-1780} {@code _apply_summary_budget}（超限 ⇒ 全文落盘、上下文里只留
 * 头切片 + 指针，并记 {@code summary_truncated} / {@code summary_full_path}）、
 * {@code :1640-1693} {@code _trim_summary_with_footer}（头 75% / 尾 25%、都吸附到行边界、
 * footer 说清"看见多少 / 共多少 / 怎么翻页读中间那段"）、
 * {@code :1615-1637} {@code _spill_summary_to_file}（落盘失败是 best-effort：
 * 裁切照常返回，只是没有指针）。</p>
 *
 * <p><b>我们只做静态那一层</b>：她还有第二把尺 {@code _parent_summary_char_budget}
 * （{@code :1695-1733}：取父代理<b>剩余</b> headroom 的 {@code _SUMMARY_HEADROOM_FRACTION=0.5}
 * 按批大小分摊，{@code ~4 chars/token} 换算，地板 {@code _MIN_SUMMARY_CHARS=2000}；
 * 两个常量在 {@code :595} / {@code :598}）。
 * 那道尺是为 batch 扇出造的（一次返回 N 份完整摘要），而 z-bot 的 delegate 面一次只回一条
 * —— {@code delegate_task} 只有单个 {@code task} 参数，"分摊"这一步没有形状可算。
 * 父代理剩余 headroom 的读数是有的（{@code context/CompressorEngine} 的 {@code contextWindow}），
 * 要接先得把批形状造出来；这条已记进 {@code _doc/001_arch/hermes-roadmap.md} 的欠账，不留在这里当幽灵功能。</p>
 */
public final class SummaryBudget {

    /** hermes {@code delegate_tool.py:590} 的同名常量；{@code 0} = 关掉上限（她那句 "0 disables the ceiling"）。 */
    public static final int DEFAULT_MAX_SUMMARY_CHARS = 24000;

    /** 裁切后的产物：进父上下文的文本 + 两个"这事发生过"的取证字段。 */
    public static final class Trimmed {
        public final String text;
        public final boolean truncated;
        /** 全文落盘位置；{@code null} = 没截断，或截断了但落盘失败（best-effort）。 */
        public final String spillPath;

        Trimmed(String text, boolean truncated, String spillPath) {
            this.text = text;
            this.truncated = truncated;
            this.spillPath = spillPath;
        }
    }

    private SummaryBudget() {
    }

    /** 不落地（只裁不存）：调用方拿不到指针时用它。 */
    public static Trimmed trim(String summary, int cap) {
        return trim(summary, cap, null, "summary");
    }

    /**
     * @param cap      字符上限；{@code <=0} 表示关掉（原文照回）
     * @param spillDir 溢出目录；{@code null} 表示写不了盘（只裁切，footer 里如实说明）
     * @param id       委托现场 id，进溢出文件名，便于反查是哪一条委托
     */
    public static Trimmed trim(String summary, int cap, File spillDir, String id) {
        if (summary == null || summary.isEmpty()) {
            return new Trimmed(summary == null ? "" : summary, false, null);
        }
        if (cap <= 0 || summary.length() <= cap) {
            return new Trimmed(summary, false, null);
        }
        int headBudget = (int) (cap * 0.75);
        int tailBudget = cap - headBudget;
        String head = summary.substring(0, headBudget);
        String tail = summary.substring(summary.length() - tailBudget);
        // 两处都吸附到行边界，否则会在一行中间劈一刀（她的 :1658-1665 同此）。
        int nl = head.lastIndexOf('\n');
        if (nl > headBudget * 0.5) {
            head = head.substring(0, nl);
        }
        int tn = tail.indexOf('\n');
        if (tn >= 0 && tn < tailBudget * 0.5) {
            tail = tail.substring(tn + 1);
        }
        String spill = spill(spillDir, id, summary);

        StringBuilder sb = new StringBuilder(head.length() + tail.length() + 512);
        sb.append(head);
        sb.append("\n\n[... 中间段已省略，见文末 footer ...]\n\n");
        sb.append(tail);
        sb.append("\n────────\n");
        sb.append(" [SUMMARY TRUNCATED]\n");
        sb.append("────────\n");
        sb.append("显示 head ").append(head.length()).append(" 字符 + tail ").append(tail.length())
                .append(" 字符，原文共 ").append(summary.length()).append(" 字符")
                .append(" —— 裁切是为了护住父代理的上下文窗口。\n");
        if (spill != null) {
            // read_file 的 offset 是 1 基，+2 跳过最后那条已显示的头行。
            sb.append("子代理完整输出已存: ").append(spill).append('\n');
            sb.append("要读被省略的中间段: read_file path=\"").append(spill)
                    .append("\" offset=").append(countNewlines(head) + 2)
                    .append(" limit=200（这份文件是完整摘要，offset 自己往前后翻）\n");
        } else {
            sb.append("（全文没能落盘，上面的头尾就是保留下来的全部）\n");
        }
        return new Trimmed(sb.toString(), true, spill);
    }

    /** 溢出落盘；任何一步失败都只回 {@code null}（裁切照常，指针没有）。 */
    private static String spill(File dir, String id, String full) {
        if (dir == null) {
            return null;
        }
        Writer w = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            File f = new File(dir, "subagent-summary-" + safe(id) + "-" + stamp + ".txt");
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(full);
            w.flush();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignore) {
                    // 已尽力；失败不影响裁切
                }
            }
        }
    }

    private static String safe(String id) {
        return id == null || id.isEmpty() ? "unknown" : id.replaceAll("[^0-9A-Za-z_.-]", "-");
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
}
