package com.zifang.z.bot.tool.env;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小 JSON 读写（P22 docker 侧专用）。
 *
 * <p>红线 6 = {@code pom.xml} 禁区，不许为 docker-java/jackson 新增依赖；仓里 {@code main} 侧
 * 也没有可复用的 JSON 工具（{@code git grep -l 'Json\.' src/main} 只命中
 * {@code agent/BotAgent}，它自己拼字符串）。这里只写后端真正需要的两点：
 * 顶层字段抽取 + 嵌套一层的取值，以及一个按键序稳定的写出器（变异体/假 dockerd
 * 要比对请求体，字段序必须可重现）。</p>
 */
final class JsonLite {

    private JsonLite() {
    }

    /** 顶层字符串字段（{@code "Id":"abc"}）。找不到返 null。 */
    static String string(String json, String key) {
        Object v = get(json, key);
        return v == null ? null : String.valueOf(v);
    }

    /** 顶层或一层嵌套的整数（{@code {"State":{"ExitCode":7}}}）。找不到返 fallback。 */
    static long number(String json, String key, long fallback) {
        Object v = get(json, key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean bool(String json, String key, boolean fallback) {
        Object v = get(json, key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return fallback;
    }

    /**
     * 扫描式取值：支持顶层 {@code key} 与 {@code "parent":{"key":…}} 一层嵌套。
     *
     * <p>不做完整 DOM —— 假 dockerd 的响应就是我手写的那几种形状，够用且可测。</p>
     */
    static Object get(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        while (i >= 0) {
            int j = i + needle.length();
            while (j < json.length() && (json.charAt(j) == ' ' || json.charAt(j) == ':')) {
                j++;
            }
            Object parsed = parseValueAt(json, j);
            if (parsed != MISSING) {
                return parsed;
            }
            i = json.indexOf(needle, i + needle.length());
        }
        return null;
    }

    /** 顶层对象数组里每个元素的某个字符串字段（{@code [{"Id":"a"},{"Id":"b"}]}）。 */
    static List<String> stringList(String json, String key) {
        List<String> out = new ArrayList<String>();
        if (json == null) {
            return out;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        while (i >= 0) {
            int j = i + needle.length();
            while (j < json.length() && (json.charAt(j) == ' ' || json.charAt(j) == ':')) {
                j++;
            }
            Object v = parseValueAt(json, j);
            if (v instanceof String) {
                out.add((String) v);
            }
            i = json.indexOf(needle, i + needle.length());
        }
        return out;
    }

    private static final Object MISSING = new Object();

    private static Object parseValueAt(String s, int at) {
        if (at >= s.length()) {
            return MISSING;
        }
        char c = s.charAt(at);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            int i = at + 1;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '\\' && i + 1 < s.length()) {
                    char nx = s.charAt(i + 1);
                    switch (nx) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        default:
                            sb.append(nx);
                    }
                    i += 2;
                    continue;
                }
                if (ch == '"') {
                    return sb.toString();
                }
                sb.append(ch);
                i++;
            }
            return MISSING;
        }
        int end = at;
        while (end < s.length() && s.charAt(end) != ',' && s.charAt(end) != '}' && s.charAt(end) != ']') {
            end++;
        }
        String token = s.substring(at, end).trim();
        if ("true".equals(token)) {
            return Boolean.TRUE;
        }
        if ("false".equals(token)) {
            return Boolean.FALSE;
        }
        if ("null".equals(token)) {
            return null;
        }
        try {
            return token.indexOf('.') >= 0 ? (Object) Double.valueOf(token) : (Object) Long.valueOf(token);
        } catch (NumberFormatException ignored) {
            return MISSING;
        }
    }

    /** 键序 = 插入序；{@link LinkedHashMap} 保证写出的请求体逐字节可重现。 */
    static String write(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            string_(sb, e.getKey()).append(':');
            value(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static StringBuilder string_(StringBuilder sb, String v) {
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static void value(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            sb.append(write((Map<String, Object>) v));
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                value(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(String.valueOf(v));
        } else {
            string_(sb, String.valueOf(v));
        }
    }
}
