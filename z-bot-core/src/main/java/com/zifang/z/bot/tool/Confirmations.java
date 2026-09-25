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
    /** 该请求在 {@link ApprovalService} 每会话 FIFO 里的编号（exec 闸门产出，其它工具可为空）。 */
    public static final String REQUEST_ID_KEY = "approvalRequestId";

    /** confirmTool 回灌时由调用方盖的章，工具据此跳过确认。 */
    public static final String CONFIRMED_ARG = "__confirmed__";

    /**
     * 人的决议档位参数（{@link ApprovalService.Resolution} 的 once/session/always/deny）。
     *
     * <p>只允许出现在<b>人</b>提交的 {@code argsJson} 里；模型自带的同名键在
     * {@code BotAgent.parseArgs} 中被无条件剥除（同 {@link #CONFIRMED_ARG}，红线 7）。</p>
     */
    public static final String RESOLUTION_ARG = "__approval_resolution__";

    /** 命令参数（{@code exec.command}）；闸门与 FIFO 都要用。 */
    public static final String COMMAND_ARG = "command";

    private Confirmations() {
    }

    public static ToolResult needsConfirmation(String reason) {
        return needsConfirmation(reason, null);
    }

    /** 带 FIFO 请求号：{@link ApprovalService} 已把这条请求排进当前会话的待批队列。 */
    public static ToolResult needsConfirmation(String reason, String requestId) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put(KEY, Boolean.TRUE);
        meta.put(REASON_KEY, reason == null ? "该操作需要人工确认" : reason);
        if (requestId != null && !requestId.isEmpty()) {
            meta.put(REQUEST_ID_KEY, requestId);
        }
        return new ToolResult(null, null, reason, false, Collections.unmodifiableMap(meta));
    }

    /** 该待批结果对应的 FIFO 请求号；没有则 null。 */
    public static String requestId(ToolResult result) {
        Object v = result == null || result.getMetadata() == null
                ? null : result.getMetadata().get(REQUEST_ID_KEY);
        return v == null ? null : v.toString();
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
