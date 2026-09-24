package com.zifang.z.bot.ui;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 Markdown → ANSI 渲染器 — 对标 OpenCode / Hermes / Claude Code TUI。
 *
 * <p>不依赖任何第三方库（无 jline / commonmark / pegdown），纯正则转换。
 * 支持：</p>
 * <ul>
 *   <li>{@code #} / {@code ##} / {@code ###} 标题 — 粗体 + 主题色</li>
 *   <li>{@code **bold**} / {@code *italic*} / {@code ~~strike~~} — 文本样式</li>
 *   <li>{@code `inline code`} — 反显</li>
 *   <li>{@code ```code block```} — 多行反显（关键字 / 字符串 / 数字 / 注释 着色）</li>
 *   <li>{@code > blockquote} — 灰色 + 缩进</li>
 *   <li>无序列表 {@code - item} / {@code * item} / {@code [ ]} / {@code [x]} — checkbox 支持</li>
 *   <li>{@code [text](url)} — 蓝色 + 链接</li>
 *   <li>水平线 {@code ---}</li>
 * </ul>
 *
 * <p>所有样式都用 ANSI escape sequence 实现，被渲染的字符串可直接 print 到任何
 * 支持 ANSI 的终端（iTerm2 / Windows Terminal / WezTerm / Linux 主流终端）。</p>
 */
public final class MarkdownRenderer {

    public static final String RESET = "\u001B[0m";
    public static final String PRIMARY = "\u001B[38;5;51m";      // 亮青
    public static final String ACCENT = "\u001B[38;5;213m";       // 粉
    public static final String SUCCESS = "\u001B[38;5;114m";      // 绿
    public static final String WARN = "\u001B[38;5;221m";         // 黄
    public static final String ERROR = "\u001B[38;5;203m";        // 红
    public static final String DIM = "\u001B[38;5;244m";          // 灰
    public static final String BOLD = "\u001B[1m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String STRIKE = "\u001B[9m";
    public static final String INVERSE = "\u001B[7m";
    public static final String CODE_FG = "\u001B[38;5;223m";

    private static final Pattern FENCE = Pattern.compile("^\\s*```(\\w*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern HR = Pattern.compile("^([-*_])\\1{2,}$");
    private static final Pattern UL_ITEM = Pattern.compile(
            "^(\\s*)(?:([-*+])|\\[([ xX])\\])\\s+(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern STRIKE_PATTERN = Pattern.compile("~~([^~]+)~~");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "static", "final", "class", "interface", "extends",
            "implements", "return", "void", "if", "else", "for", "while", "do", "switch", "case",
            "break", "continue", "new", "this", "super", "import", "package", "try", "catch",
            "finally", "throw", "throws", "abstract", "enum", "true", "false", "null",
            "function", "var", "let", "const", "async", "await", "yield", "export", "from",
            "def", "elif", "lambda", "pass", "with", "as", "fn", "pub", "struct",
            "impl", "use", "mod"
    ));

    private MarkdownRenderer() {
    }

    /**
     * 把一整段 Markdown 文本渲染成带 ANSI 的多行字符串。
     */
    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    String lang = fence.group(1);
                    if (lang != null && !lang.isEmpty()) {
                        out.append(DIM).append("─── ").append(lang).append(" ───").append(RESET).append('\n');
                    }
                } else {
                    inCodeBlock = false;
                }
                continue;
            }
            if (inCodeBlock) {
                out.append(renderCodeLine(line)).append('\n');
                continue;
            }
            if (line.trim().isEmpty()) {
                out.append('\n');
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                int level = h.group(1).length();
                out.append(renderHeading(h.group(2), level)).append('\n');
                continue;
            }
            if (HR.matcher(line.trim()).matches()) {
                out.append(DIM).append("──────────────────────────────").append(RESET).append('\n');
                continue;
            }
            if (line.startsWith("> ")) {
                out.append(DIM).append("│ ").append(RESET)
                        .append(renderInline(DIM + line.substring(2) + RESET)).append('\n');
                continue;
            }
            Matcher ul = UL_ITEM.matcher(line);
            if (ul.matches()) {
                String indent = ul.group(1) == null ? "" : ul.group(1);
                String bullet = ul.group(2);
                String checkbox = ul.group(3);
                String text = ul.group(4);
                String prefix;
                if (checkbox != null) {
                    if ("x".equalsIgnoreCase(checkbox)) {
                        prefix = SUCCESS + "☑ " + RESET;
                    } else {
                        prefix = DIM + "☐ " + RESET;
                    }
                } else {
                    prefix = ACCENT + bullet + " " + RESET;
                }
                out.append(indent).append(prefix).append(renderInline(text)).append('\n');
                continue;
            }
            out.append(renderInline(line)).append('\n');
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String renderCodeLine(String line) {
        StringBuilder sb = new StringBuilder(CODE_FG);
        sb.append("  ");
        for (String token : line.split("(?<=\\s)|(?=\\s)")) {
            sb.append(highlightCodeToken(token));
        }
        sb.append(RESET);
        return sb.toString();
    }

    private static String highlightCodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        if (token.matches("\".*\"|'.*'")) {
            return WARN + token + CODE_FG;
        }
        if (token.matches("\\d+(\\.\\d+)?")) {
            return ACCENT + token + CODE_FG;
        }
        if (token.startsWith("//") || token.startsWith("#") || token.startsWith("--")) {
            return DIM + token + CODE_FG;
        }
        if (KEYWORDS.contains(token)) {
            return PRIMARY + token + CODE_FG;
        }
        return token;
    }

    private static String renderHeading(String text, int level) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOLD);
        switch (level) {
            case 1:
                sb.append(PRIMARY).append("▌ ");
                break;
            case 2:
                sb.append(PRIMARY).append("  ▎ ");
                break;
            case 3:
                sb.append(ACCENT).append("    ▏ ");
                break;
            default:
                sb.append(DIM).append("      ");
                break;
        }
        sb.append(renderInlineSpans(text));
        sb.append(RESET);
        return sb.toString();
    }

    private static String renderInline(String text) {
        return renderInlineSpans(text);
    }

    private static String renderInlineSpans(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 抽出 inline code 占位
        List<String> codeStorage = new ArrayList<>();
        Matcher code = INLINE_CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (code.find()) {
            String placeholder = "\u0000CODE" + idx + "\u0000";
            codeStorage.add(code.group(1));
            code.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            idx++;
        }
        code.appendTail(sb);
        String s = sb.toString();
        // bold / italic / strike
        s = BOLD_PATTERN.matcher(s).replaceAll(BOLD + "$1" + RESET);
        s = ITALIC_PATTERN.matcher(s).replaceAll(ITALIC + "$1" + RESET);
        s = STRIKE_PATTERN.matcher(s).replaceAll(STRIKE + "$1" + RESET);
        // link
        Matcher lm = LINK_PATTERN.matcher(s);
        StringBuffer lsb = new StringBuffer();
        while (lm.find()) {
            String replaced = PRIMARY + lm.group(1) + RESET + DIM
                    + " (" + lm.group(2) + ")" + RESET;
            lm.appendReplacement(lsb, Matcher.quoteReplacement(replaced));
        }
        lm.appendTail(lsb);
        s = lsb.toString();
        // 还原 inline code
        for (int i = 0; i < codeStorage.size(); i++) {
            s = s.replace("\u0000CODE" + i + "\u0000", INVERSE + CODE_FG + codeStorage.get(i) + RESET);
        }
        return s;
    }
}
