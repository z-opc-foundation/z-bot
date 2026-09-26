package com.zifang.z.bot.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 配置里可能带 secret 的串的<b>统一脱敏</b>入口（P21 安全红线）。
 *
 * <p>MCP server 的 {@code headers}（{@code Authorization: Bearer ...}）、url 里的
 * user-info/query 都可能是真凭证，而它们会顺着三条路漏出去：
 * ① {@code toString()}，② 给前端/HTTP 看的 {@code toMapList()}，③ 异常与日志文本。
 * 所以这里同时提供"结构化脱敏"（{@link #maskAll(Map)}）和"文本脱敏"
 * （{@link #scrub(String, Collection)}）两种工具，三条路各自走一条。</p>
 *
 * <p>默认口径：<b>所有</b> header 值一律打码，不按 key 名白名单放行 ——
 * "这个 key 看起来不像 secret" 这种判断一旦错一次就是凭证泄漏，而打码错了只是少点可读性。</p>
 */
public final class SecretRedaction {

    /** 打码后仍然想留一点可辨识度的最小长度；短于它直接全星。 */
    private static final int MIN_KEEP_LENGTH = 12;

    private SecretRedaction() {
    }

    /**
     * 单个值打码：保留首 2 尾 2（长度 ≥ {@value #MIN_KEEP_LENGTH} 时），中间定长 8 个星。
     *
     * <p>中间用<b>定长</b>星而不是等长星：等长会把 secret 的真实长度泄露到日志里。</p>
     */
    public static String mask(String value) {
        if (value == null) {
            return "null";
        }
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() < MIN_KEEP_LENGTH) {
            return "********";
        }
        return value.substring(0, 2) + "********" + value.substring(value.length() - 2);
    }

    /** key 全留、value 全打码（见类注释：不按 key 名放行）。 */
    public static Map<String, String> maskAll(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.put(e.getKey(), mask(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 只要配了几个 header，就把它们的名字暴露出去（名字不是 secret）。 */
    public static List<String> headerNames(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(headers.keySet());
    }

    /**
     * 文本脱敏：把已知 secret 的字面量从任意字符串里换成 {@code <redacted:headername>}。
     *
     * <p>用于异常 message / 日志行 —— 对端在 401 响应体里回显请求头、或者 URL 里带了
     * {@code ?token=} 时，光靠"不打 headers 本身"拦不住。</p>
     *
     * @return 不含任何已知 secret 字面量的文本
     */
    public static String scrub(String text, Map<String, String> secretsByKey) {
        if (text == null || text.isEmpty() || secretsByKey == null || secretsByKey.isEmpty()) {
            return text;
        }
        String out = text;
        // 先长后短：短值先替会把长值的前缀吃掉，留一个没打过的尾巴。
        List<String> keys = new ArrayList<String>(secretsByKey.keySet());
        final Map<String, String> byLen = new LinkedHashMap<String, String>(secretsByKey);
        Collections.sort(keys, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                String va = byLen.get(a);
                String vb = byLen.get(b);
                int la = va == null ? 0 : va.length();
                int lb = vb == null ? 0 : vb.length();
                return lb - la;
            }
        });
        for (String key : keys) {
            String value = byLen.get(key);
            if (value == null || value.isEmpty()) {
                continue;
            }
            out = replaceAllLiteral(out, value, "<redacted:" + key.toLowerCase(Locale.ROOT) + ">");
        }
        return out;
    }

    /** 不带正则语义的全文替换（{@link String#replace} 在 Java 8 上就是字面量替换）。 */
    private static String replaceAllLiteral(String text, String from, String to) {
        return text.replace(from, to);
    }

    /**
     * URL 脱敏：query 全砍、user-info 全砍，只留 scheme://host:port/path。
     *
     * <p>{@code https://user:pw@host/mcp?key=abc} 里 {@code ?key=} 与 password 都是凭证，
     * 而它们会出现在"连不上"的异常文本里。</p>
     */
    public static String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int scheme = s.indexOf("://");
        int authorityStart = scheme < 0 ? 0 : scheme + 3;
        int at = s.lastIndexOf('@', s.length() - 1);
        if (at >= authorityStart) {
            s = s.substring(0, authorityStart) + "***@" + s.substring(at + 1);
        }
        return s;
    }
}
