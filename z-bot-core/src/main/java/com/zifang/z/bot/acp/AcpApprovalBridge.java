package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.tool.ApprovalService;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批外送桥：z-bot 的待批队列 ⇄ IDE 的 {@code session/request_permission}。
 *
 * <h2>它接的是哪条既有接缝（工单 §1.2）</h2>
 * <ul>
 *   <li>队列真身 = {@code tool/ApprovalService} 的每会话 FIFO（{@code BotAgent.pendingApprovals()}
 *       → {@code ApprovalService.pending(currentSessionKey())}，{@code agent/BotAgent.java:581}）；</li>
 *   <li>放行/拒绝的唯一出口 = {@code BotAgent.confirmTool(tool,args,resolution)}
 *       （{@code agent/BotAgent.java:709}），它内部走 {@code ApprovalService.resolveNext} 并把决议
 *       最终交给 {@code tool/ExecGuard#decide} —— 硬线命令即使人在 IDE 里点了"always"也照样拒。</li>
 * </ul>
 * <b>没有第二套审批</b>：本类不持有 pending 集合、不做任何"要不要放行"的判断，
 * 只做「读队头 → 问 IDE → 把 IDE 的 optionId 翻成 {@link ApprovalService.Resolution} → 交回 confirmTool」。
 *
 * <h2>为什么摘掉它一定红</h2>
 * 队头信息（requestId / approvalKey / argsJson）只存在于 {@link ApprovalService.Request} 里，
 * {@code WAIT_CONFIRM} 串里没有 {@code requestId}。桥一旦被摘：
 * <ul>
 *   <li>不会有任何 {@code session/request_permission} 帧（测试断言帧存在）；</li>
 *   <li>队头永远堵在 FIFO 里（测试断言 {@code pendingApprovals()} 变空）；</li>
 *   <li>{@code WAIT_CONFIRM:…} 原文会被当最终答案推给 IDE（测试断言不外泄）。</li>
 * </ul>
 *
 * <p>optionId 集与档位映射沿用她的 {@code acp_adapter/permissions.py:21—27}，
 * 因为 z-bot 的 {@code ApprovalService.Resolution} 正好四档（{@code tool/ApprovalService.java:53}）。</p>
 */
public final class AcpApprovalBridge {

    /** 桥不存在时用的哨兵（{@code AcpAgentServer} 仍会走 {@link #disabled()} 分支大声失败）。 */
    public static final AcpApprovalBridge DISABLED = new AcpApprovalBridge(true);

    private final boolean disabled;

    public AcpApprovalBridge() {
        this(false);
    }

    private AcpApprovalBridge(boolean disabled) {
        this.disabled = disabled;
    }

    public static AcpApprovalBridge disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return !disabled;
    }

    /** 一次外送的完整结果。 */
    public static final class Outcome {
        private final ApprovalService.Resolution resolution;
        private final String toolOutput;
        private final ApprovalService.Request request;
        private final boolean timedOut;

        Outcome(ApprovalService.Resolution resolution, String toolOutput,
                ApprovalService.Request request, boolean timedOut) {
            this.resolution = resolution;
            this.toolOutput = toolOutput;
            this.request = request;
            this.timedOut = timedOut;
        }

        public ApprovalService.Resolution resolution() {
            return resolution;
        }

        public String toolOutput() {
            return toolOutput;
        }

        public ApprovalService.Request request() {
            return request;
        }

        public boolean timedOut() {
            return timedOut;
        }
    }

    /** 这条 chat 返回值是不是"要人审"。 */
    public static boolean isWaitConfirm(String chatResult) {
        return chatResult != null && chatResult.startsWith(BotAgent.WAIT_CONFIRM_PREFIX);
    }

    /**
     * 解析 {@code WAIT_CONFIRM:tool|args|reason}。
     *
     * <p>格式是 z-bot 自己的既有约定（{@code agent/BotAgent.java:273} 拼、
     * {@code channel/HttpChannel.java:302} 与 {@code channel/TerminalChannel.java:203} 用
     * {@code split("\\|", 3)} 切）。那种切法在 args 里含 {@code |} 时会把 args 切短
     * （{@code {"command":"a|b"}} ⇒ {@code {"command":"a}），而人审看到的就是这个残串 ——
     * 审批面不能出现"人批的和实跑的不是同一条"，所以这里改成
     * <b>第一刀取 tool、最后一刀取 reason、中间整段是 args</b>。
     * 执行用的 args 一律取 {@link ApprovalService.Request#argsJson()}，这里只影响展示。</p>
     */
    public static ParsedWait parseWaitConfirm(String chatResult) {
        if (!isWaitConfirm(chatResult)) {
            throw AcpProtocolException.internal("不是 WAIT_CONFIRM 串: " + chatResult, null);
        }
        String body = chatResult.substring(BotAgent.WAIT_CONFIRM_PREFIX.length());
        int first = body.indexOf('|');
        int last = body.lastIndexOf('|');
        String tool;
        String args;
        String reason;
        if (first < 0) {
            tool = body;
            args = "{}";
            reason = "";
        } else if (last == first) {
            tool = body.substring(0, first);
            args = body.substring(first + 1);
            reason = "";
        } else {
            tool = body.substring(0, first);
            args = body.substring(first + 1, last);
            reason = body.substring(last + 1);
        }
        return new ParsedWait(tool, args, reason);
    }

    public static final class ParsedWait {
        public final String tool;
        public final String args;
        public final String reason;

        ParsedWait(String tool, String args, String reason) {
            this.tool = tool;
            this.args = args;
            this.reason = reason;
        }
    }

    /**
     * 把一次待批外送问 IDE，并按 IDE 的选项放行/拒绝。
     *
     * @param session     当前 ACP 会话（含 z-bot target 与待批 FIFO）
     * @param waitConfirm {@code BotAgent.chat} 返回的 {@code WAIT_CONFIRM:…} 原文
     * @return 工具输出（或拒绝文案）
     * @throws AcpProtocolException 桥被禁用 / 队列为空（说明审批状态对不上，绝不猜）
     */
    public Outcome requestAndResolve(AcpConnection conn,
                                     AcpSessionRegistry.AcpSession session,
                                     String waitConfirm,
                                     boolean offerAlways) {
        return requestAndResolve(conn, session, waitConfirm, offerAlways,
                AcpConnection.CLIENT_REQUEST_TIMEOUT_MILLIS);
    }

    /**
     * 同上，但等客户端应答的上限由调用方给（测试要在一秒内验完"客户端装死"这一路）。
     */
    public Outcome requestAndResolve(AcpConnection conn,
                                     AcpSessionRegistry.AcpSession session,
                                     String waitConfirm,
                                     boolean offerAlways,
                                     long clientTimeoutMillis) {
        if (disabled) {
            throw new AcpProtocolException(JsonRpc.INTERNAL_ERROR,
                    "审批外送桥未接线（AcpApprovalBridge 被摘除）：拒绝把 " + waitConfirm
                            + " 当最终答案回给客户端，也拒绝自行放行 —— 请接回 bridge", null, null);
        }
        AcpTurnTarget target = session.target();
        ParsedWait parsed = parseWaitConfirm(waitConfirm);

        // 审批状态以 ApprovalService 的 FIFO 为准，不以 chat 返回串为准。
        List<ApprovalService.Request> pending = target.pendingApprovals();
        if (pending == null || pending.isEmpty()) {
            throw new AcpProtocolException(JsonRpc.INTERNAL_ERROR,
                    "chat 要求人审 " + parsed.tool + "，但 ApprovalService 队列为空："
                            + "待批账实不符（红线 8），拒绝猜测放行", null, null);
        }
        ApprovalService.Request head = pending.get(0);

        List<String> allowedOptionIds = new ArrayList<String>();
        ObjectNode params = JsonRpc.object();
        params.put("sessionId", session.acpSessionId());
        params.set("toolCall", permissionToolCall(head, parsed));
        params.set("options", buildOptions(head, offerAlways, allowedOptionIds));

        JsonNode result = conn.requestClient(AcpMethods.SESSION_REQUEST_PERMISSION, params,
                clientRequestTimeoutMillis(clientTimeoutMillis));

        ApprovalService.Resolution resolution = mapOutcome(result, allowedOptionIds);
        boolean timedOut = result == null;

        // 参数一律取队头记录，不取调用方此刻传来的 parsed.args —— 与 BotAgent.confirmTool
        // 的"审批绑命令"同源（agent/BotAgent.java:718—726），否则人看到的和跑的不是同一条。
        String output = target.confirmTool(head.toolName(),
                head.argsJson() == null ? "{}" : head.argsJson(), resolution);
        return new Outcome(resolution, output, head, timedOut);
    }

    /**
     * optionId → 四档。认不出来一律 {@link ApprovalService.Resolution#DENY}，
     * 与她 {@code permissions.py:98—107} 的保守映射同构（宁可少放行）。
     */
    static ApprovalService.Resolution mapOutcome(JsonNode result, List<String> allowedOptionIds) {
        if (result == null) {
            return ApprovalService.Resolution.DENY;
        }
        JsonNode outcome = result.get("outcome");
        if (outcome == null || !outcome.isObject()) {
            return ApprovalService.Resolution.DENY;
        }
        String kind = text(outcome.get("outcome"), "");
        if (!"selected".equals(kind)) {
            return ApprovalService.Resolution.DENY; // cancelled / 未知 ⇒ 不执行
        }
        String optionId = text(outcome.get("optionId"), "");
        if (!allowedOptionIds.contains(optionId)) {
            return ApprovalService.Resolution.DENY; // 对端编了个没给过它的选项
        }
        if (AcpMethods.OPTION_ALLOW_ONCE.equals(optionId)) {
            return ApprovalService.Resolution.ONCE;
        }
        if (AcpMethods.OPTION_ALLOW_SESSION.equals(optionId)) {
            return ApprovalService.Resolution.SESSION;
        }
        if (AcpMethods.OPTION_ALLOW_ALWAYS.equals(optionId)) {
            return ApprovalService.Resolution.ALWAYS;
        }
        return ApprovalService.Resolution.DENY; // deny / deny_always
    }

    /** {@code ToolCallUpdate}（wire 字段 {@code toolCallId}/{@code title}/{@code kind}/{@code status}/{@code rawInput}）。 */
    private static ObjectNode permissionToolCall(ApprovalService.Request head, ParsedWait parsed) {
        ObjectNode call = JsonRpc.object();
        call.put("toolCallId", "perm-check-" + head.id());
        call.put("title", head.toolName() + ": " + head.command());
        call.put("kind", "execute");
        call.put("status", "pending");
        ArrayNode content = call.putArray("content");
        ObjectNode text = JsonRpc.object();
        text.put("type", "text");
        text.put("text", head.reason() == null ? parsed.reason : head.reason());
        content.add(text);
        ObjectNode rawInput = JsonRpc.object();
        rawInput.put("command", head.command());
        rawInput.put("argsJson", head.argsJson());
        rawInput.put("approvalKey", head.approvalKey());
        call.set("rawInput", rawInput);
        // requestId 只经 ApprovalService 拿得到：这条 meta 就是"桥真的接在 FIFO 上"的证据。
        ObjectNode meta = JsonRpc.object();
        meta.put("approvalRequestId", head.id());
        meta.put("approvalSessionKey", head.sessionKey());
        meta.put("rule", head.rule());
        call.set("_meta", meta);
        return call;
    }

    private static ArrayNode buildOptions(ApprovalService.Request head, boolean offerAlways,
                                          List<String> allowedOptionIds) {
        ArrayNode options = JsonRpc.array();
        allowedOptionIds.clear();
        options.add(option(options, allowedOptionIds,
                AcpMethods.OPTION_ALLOW_ONCE, "allow_once", "Allow once"));
        options.add(option(options, allowedOptionIds,
                AcpMethods.OPTION_ALLOW_SESSION, "allow_always", "Allow for session"));
        if (offerAlways) {
            // ALWAYS 会落盘 config.properties（tool/ApprovalService.java:34 注释），
            // 因此只在这条路允许出现；offerAlways=false 时客户端连选项都看不到。
            options.add(option(options, allowedOptionIds,
                    AcpMethods.OPTION_ALLOW_ALWAYS, "allow_always", "Allow always"));
        }
        options.add(option(options, allowedOptionIds, AcpMethods.OPTION_DENY, "reject_once", "Deny"));
        return options;
    }

    private static ObjectNode option(ArrayNode options, List<String> allowedOptionIds,
                                     String optionId, String kind, String name) {
        allowedOptionIds.add(optionId);
        ObjectNode node = JsonRpc.object();
        node.put("optionId", optionId);
        node.put("kind", kind);
        node.put("name", name);
        return node;
    }

    /** 非正数一律退回缺省上限，免得有人传 0 变成"永远不等"。 */
    private static long clientRequestTimeoutMillis(long requested) {
        return requested > 0L ? requested : AcpConnection.CLIENT_REQUEST_TIMEOUT_MILLIS;
    }

    private static String text(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.asText();
    }
}
