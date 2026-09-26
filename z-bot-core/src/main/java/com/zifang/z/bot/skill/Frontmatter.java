package com.zifang.z.bot.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md frontmatter 的极简 YAML 子集解析器（hermes 用 PyYAML，z-bot 不引依赖）。
 *
 * <p>支持：{@code key: value}、嵌套 map（缩进）、行内列表 {@code [a, b]}、
 * 块列表（{@code - item}）、引号包裹的值。不支持锚点/多行标量 ——
 * 现网 SKILL.md 的 frontmatter 不需要这些。</p>
 */
final class Frontmatter {

    private Frontmatter() {
    }

    /** 一行一个键值/列表项；缩进决定嵌套。 */
    static Map<String, Object> parse(String text) {
        List<Line> lines = new ArrayList<Line>();
        for (String raw : text.split("\n")) {
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) {
                continue;
            }
            lines.add(new Line(indentOf(raw), raw.trim()));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        parseInto(lines, 0, 0, root);
        return root;
    }

    private static int parseInto(List<Line> lines, int from, int indent, Map<String, Object> out) {
        int i = from;
        while (i < lines.size()) {
            Line ln = lines.get(i);
            if (ln.indent < indent) {
                return i;                       // 交给上一层
            }
            String content = ln.content;
            int colon = content.indexOf(':');
            if (colon <= 0) {
                i++;
                continue;
            }
            String key = content.substring(0, colon).trim();
            String value = content.substring(colon + 1).trim();
            if (!value.isEmpty()) {
                out.put(key, value);
                i++;
                continue;
            }
            // 值为空：下面要么是同缩进更深的块（map 或 list），要么真的是空值
            int nextIndent = i + 1 < lines.size() ? lines.get(i + 1).indent : -1;
            boolean nextIsItem = i + 1 < lines.size()
                    && lines.get(i + 1).content.startsWith("- ");
            if (i + 1 >= lines.size() || nextIndent <= ln.indent) {
                out.put(key, "");
                i++;
                continue;
            }
            if (nextIsItem) {
                List<String> items = new ArrayList<String>();
                int j = i + 1;
                while (j < lines.size() && lines.get(j).indent >= nextIndent
                        && lines.get(j).content.startsWith("- ")) {
                    items.add(unquote(lines.get(j).content.substring(2).trim()));
                    j++;
                }
                out.put(key, items);
                i = j;
                continue;
            }
            Map<String, Object> child = new LinkedHashMap<String, Object>();
            out.put(key, child);
            i = parseInto(lines, i + 1, nextIndent, child);
        }
        return i;
    }

    private static int indentOf(String raw) {
        int n = 0;
        while (n < raw.length() && (raw.charAt(n) == ' ' || raw.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    private static final class Line {
        final int indent;
        final String content;

        Line(int indent, String content) {
            this.indent = indent;
            this.content = content;
        }
    }
}
