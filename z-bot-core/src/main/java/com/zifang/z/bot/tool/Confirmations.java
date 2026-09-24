package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.ToolResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具"需要人工确认"语义。
 *
 * <p>kernel 的 {@link ToolResult} 没有 confirmation 字段，这里用 metadata 承载：
 * {@code needsConfirmation=true} + {@code confirmationReason=<给用户的说明>}。
 * agent 循环看到该标记后暂停并把工具调用回抛给使用者（/confirm 或 POST /bot/confirm）。</p>
 */
public final class Confirmations {

    public static final String KEY = "needsConfirmation";
    public static final String REASON_KEY = "confirmationReason";

    /** confirmTool 回灌时由调用方盖的章，工具据此跳过确认。 */
    public static final String CONFIRMED_ARG = "__confirmed__";

    private Confirmations() {
    }

    public static ToolResult needsConfirmation(String reason) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put(KEY, Boolean.TRUE);
        meta.put(REASON_KEY, reason == null ? "该操作需要人工确认" : reason);
        return new ToolResult(null, null, reason, false, Collections.unmodifiableMap(meta));
    }

    public static boolean isRequired(ToolResult result) {
        if (result == null || result.getMetadata() == null) {
            return false;
        }
        return Boolean.TRUE.equals(result.getMetadata().get(KEY));
    }

    public static String reason(ToolResult result) {
        Object r = result == null || result.getMetadata() == null
                ? null : result.getMetadata().get(REASON_KEY);
        return r == null ? "该操作需要人工确认" : r.toString();
    }

    /**
     * 参数里是否已带确认标记（{@link #CONFIRMED_ARG} 为 true 或字符串 "true"）。
     */
    public static boolean alreadyConfirmed(Map<String, Object> args) {
        if (args == null) {
            return false;
        }
        Object v = args.get(CONFIRMED_ARG);
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}
