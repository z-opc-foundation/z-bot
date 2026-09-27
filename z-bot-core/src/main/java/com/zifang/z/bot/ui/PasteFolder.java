package com.zifang.z.bot.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 括号粘贴折叠：阈值 5 行 / 2000 字符命中才折成 {@code [[ head.. [Nl lines] .. tail ]]} 标记，
 * 原文进 {@link Snips} 台账，且只在提交那一刻展开。
 */
public final class PasteFolder {

    public static final int COLLAPSE_LINES = 5;
    public static final int COLLAPSE_CHARS = 2000;

    static final Pattern TOKEN = Pattern.compile("\\[\\[[^\\n]*?\\]\\]");

    static final int SNIP_MAX_COUNT = 32;
    static final int SNIP_MAX_TOTAL_CHARS = 4 * 1024 * 1024;

    private static final int PREVIEW_HEAD = 16;
    private static final int PREVIEW_TAIL = 28;
    private static final String PREVIEW_SEP = ".. ";

    private static final Pattern WS_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private PasteFolder() {
    }

    public static boolean shouldFold(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return lineCount(text) >= COLLAPSE_LINES || text.length() >= COLLAPSE_CHARS;
    }

    /** 与 JS {@code split('\n').length} 同形：结尾换行也算出一行空尾。 */
    static int lineCount(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    public static String token(String text) {
        return token(text, lineCount(text));
    }

    static String token(String text, int lines) {
        String preview = edgePreview(text);
        String count = "[" + compact(lines) + " lines]";
        if (preview.isEmpty()) {
            return "[[ " + count + " ]]";
        }
        // 只切第一个分隔符：预览里再出现 ".. " 时标记形状才和她那边一致
        int sep = preview.indexOf(PREVIEW_SEP);
        if (sep < 0) {
            return "[[ " + preview + " " + count + " ]]";
        }
        String head = trimEnd(preview.substring(0, sep));
        String tail = trimStart(preview.substring(sep + PREVIEW_SEP.length()));
        return "[[ " + head + PREVIEW_SEP + count + " .. " + tail + " ]]";
    }

    static String edgePreview(String s) {
        String one = collapseWhitespace(s).replace("]]", "] ]");
        if (one.isEmpty()) {
            return "";
        }
        if (one.length() <= PREVIEW_HEAD + PREVIEW_TAIL + 4) {
            return one;
        }
        return trimEnd(one.substring(0, PREVIEW_HEAD)) + PREVIEW_SEP
                + trimStart(one.substring(one.length() - PREVIEW_TAIL));
    }

    static String compact(int n) {
        if (n < 1000) {
            return String.valueOf(n);
        }
        if (n < 1000000) {
            return unit(n, 1000, "k");
        }
        if (n < 1000000000) {
            return unit(n, 1000000, "m");
        }
        return unit(n, 1000000000, "b");
    }

    private static String unit(int n, int base, String suffix) {
        double v = Math.rint((double) n / (double) base * 10d) / 10d;
        return v == Math.rint(v) ? (long) v + suffix : String.format(Locale.US, "%.1f", v) + suffix;
    }

    private static String collapseWhitespace(String s) {
        return WS_RUN.matcher(s).replaceAll(" ").trim();
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String trimStart(String s) {
        int start = 0;
        while (start < s.length() && s.charAt(start) == ' ') {
            start++;
        }
        return s.substring(start);
    }

    /** 标记 → 原文队列；{@link #expand(String)} 一趟内 FIFO 消耗，台账本身留到提交才清。 */
    public static final class Snips {

        private final Map<String, Deque<String>> byLabel = new HashMap<>();
        private final List<String> labels = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();

        public void record(String label, String text) {
            Deque<String> queue = byLabel.get(label);
            if (queue == null) {
                queue = new ArrayDeque<>();
                byLabel.put(label, queue);
            }
            queue.addLast(text);
            labels.add(label);
            texts.add(text);
            trim();
        }

        private void trim() {
            int total = 0;
            for (String t : texts) {
                total += t.length();
            }
            while (!texts.isEmpty() && (texts.size() > SNIP_MAX_COUNT || total > SNIP_MAX_TOTAL_CHARS)) {
                total -= texts.remove(0).length();
                String oldest = labels.remove(0);
                Deque<String> queue = byLabel.get(oldest);
                if (queue != null) {
                    queue.pollFirst();
                    if (queue.isEmpty()) {
                        byLabel.remove(oldest);
                    }
                }
            }
        }

        public String expand(String buffer) {
            if (buffer == null || buffer.isEmpty() || byLabel.isEmpty()) {
                return buffer;
            }
            Map<String, Deque<String>> copies = new HashMap<>();
            for (Map.Entry<String, Deque<String>> e : byLabel.entrySet()) {
                copies.put(e.getKey(), new ArrayDeque<>(e.getValue()));
            }
            Matcher m = TOKEN.matcher(buffer);
            StringBuffer out = new StringBuffer(buffer.length());
            while (m.find()) {
                String tok = m.group();
                Deque<String> queue = copies.get(tok);
                String replacement = queue == null || queue.isEmpty() ? tok : queue.pollFirst();
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }

        public void clear() {
            byLabel.clear();
            labels.clear();
            texts.clear();
        }

        public int size() {
            return texts.size();
        }
    }
}
