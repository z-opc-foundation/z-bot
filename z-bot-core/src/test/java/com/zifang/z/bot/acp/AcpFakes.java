package com.zifang.z.bot.acp;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.tool.ApprovalService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * ACP 测试替身：把"脚本化的一轮对话"接成真的 {@link AcpTurnTarget}。
 *
 * <p><b>审批状态不是假的</b>：每个替身持有一个真 {@link ApprovalService}，
 * 需要人审时按 {@code BotAgent.enqueueApprovalRequest}（{@code agent/BotAgent.java:565—578}）
 * 同样的顺序 submit 再 {@code supersedePending}，返回同样的 {@code WAIT_CONFIRM:tool|args|reason} 串。
 * 这样"摘掉 bridge ⇒ 红"测的是真队列没人消费，而不是测一个内存 set 没人清。</p>
 *
 * <p>只有 LLM 那一腿被脚本化（{@link StreamEvent} 序列 + chat 返回值）——
 * 工单 §4 红线 4 不许打厂商 API，而协议面/审批面/流式面/映射面都在这些事件之后，全都是真代码。</p>
 */
final class AcpFakes {

    private AcpFakes() {
    }

    /** 收帧用的内存通道。 */
    static final class Recorder implements AcpTransport {
        private final List<String> lines = java.util.Collections.synchronizedList(new ArrayList<String>());
        private volatile boolean closed;

        @Override
        public void sendLine(String jsonLine) {
            lines.add(jsonLine);
        }

        @Override
        public void close() {
            closed = true;
        }

        List<String> lines() {
            synchronized (lines) {
                return new ArrayList<String>(lines);
            }
        }

        String last() {
            List<String> snapshot = lines();
            return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
        }

        boolean closed() {
            return closed;
        }

        void clear() {
            lines.clear();
        }

        /** 等到至少 {@code n} 帧为止；<b>带上限</b>，超时返回当前快照（绝不无界 await）。 */
        List<String> awaitFrames(int n, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                List<String> snapshot = lines();
                if (snapshot.size() >= n) {
                    return snapshot;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return lines();
                }
            }
            return lines();
        }
    }

    /** 一轮脚本：先推这些事件，再返回这个字符串。 */
    static final class Turn {
        final List<StreamEvent> events = new ArrayList<StreamEvent>();
        String reply = "";
        boolean needsApproval;
        String approvalTool = "exec";
        String approvalArgs = "{\"command\":\"rm -rf /tmp/does-not-matter\"}";
        String approvalReason = "危险命令";

        Turn event(StreamEvent e) {
            events.add(e);
            return this;
        }

        Turn say(String text) {
            this.reply = text;
            return this;
        }

        /** 这一轮以"要人审"收尾（与 BotAgent 抛 {@code ToolConfirmationNeeded} 同形）。 */
        Turn awaitingApproval(String tool, String argsJson, String reason) {
            this.needsApproval = true;
            this.approvalTool = tool;
            this.approvalArgs = argsJson;
            this.approvalReason = reason;
            return this;
        }
    }

    /** 脚本化 target：真 ApprovalService + 假 LLM 输出。 */
    static final class Scripted implements AcpTurnTarget {
        final ApprovalService approvals = new ApprovalService();
        final String sessionKey;
        final Deque<Turn> turns = new ArrayDeque<Turn>();
        final List<String> promptsSeen = new ArrayList<String>();
        final List<ApprovalService.Resolution> resolutionsSeen =
                java.util.Collections.synchronizedList(new ArrayList<ApprovalService.Resolution>());
        final List<String> confirmArgsSeen = new ArrayList<String>();

        String zbotSessionId;
        String model = "test-model";
        int stopCalls;
        int shutdownCalls;
        boolean running;

        Scripted(String zbotSessionId) {
            this.zbotSessionId = zbotSessionId;
            this.sessionKey = "acp:" + zbotSessionId;
        }

        Turn nextTurn = new Turn();

        Scripted enqueue(Turn turn) {
            turns.add(turn);
            return this;
        }

        @Override
        public String prompt(String text, StreamListener listener) {
            promptsSeen.add(text);
            running = true;
            try {
                Turn turn = turns.isEmpty() ? nextTurn : turns.removeFirst();
                for (StreamEvent event : turn.events) {
                    listener.onEvent(event);
                }
                if (turn.needsApproval) {
                    // 与 BotAgent.enqueueApprovalRequest 同序：先 submit，再 supersede 更老的。
                    approvals.submit(sessionKey, turn.approvalTool, turn.approvalArgs,
                            turn.approvalArgs, turn.approvalTool + " " + turn.approvalArgs,
                            turn.approvalReason, "tool-confirmation");
                    List<ApprovalService.Request> pending = approvals.pending(sessionKey);
                    approvals.supersedePending(sessionKey,
                            pending.isEmpty() ? null : pending.get(pending.size() - 1).id());
                    return BotAgent.WAIT_CONFIRM_PREFIX + turn.approvalTool + '|'
                            + turn.approvalArgs + '|' + turn.approvalReason;
                }
                return turn.reply;
            } finally {
                running = false;
            }
        }

        @Override
        public List<ApprovalService.Request> pendingApprovals() {
            return approvals.pending(sessionKey);
        }

        @Override
        public String confirmTool(String toolName, String argsJson, ApprovalService.Resolution resolution) {
            resolutionsSeen.add(resolution);
            ApprovalService.Resolved resolved = approvals.resolveNext(sessionKey, resolution);
            ApprovalService.Request head = resolved == null ? null : resolved.request();
            String effectiveTool = head == null ? toolName : head.toolName();
            String effectiveArgs = head == null ? argsJson : head.argsJson();
            confirmArgsSeen.add(effectiveTool + "|" + effectiveArgs + "|" + resolution.name());
            if (resolution == ApprovalService.Resolution.DENY) {
                return "已拒绝（deny）：" + effectiveArgs + " —— 命令未执行";
            }
            return "已执行 " + effectiveTool + " " + effectiveArgs;
        }

        @Override
        public void stop() {
            stopCalls++;
        }

        @Override
        public String zbotSessionId() {
            return zbotSessionId;
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }

    /** 脚本化工厂：每个 demand 造一个替身，可查已知会话。 */
    static final class Factory implements AcpTurnTarget.Factory {
        final List<Scripted> opened = new ArrayList<Scripted>();
        final List<String> known;
        final Deque<String> freshSessionIds = new ArrayDeque<String>(Arrays.asList(
                "zb-100", "zb-200", "zb-300", "zb-400", "zb-500", "zb-600"));
        int openCalls;

        Factory(String... knownZbotSessionIds) {
            this.known = new ArrayList<String>(Arrays.asList(knownZbotSessionIds));
        }

        @Override
        public AcpTurnTarget open(AcpTurnTarget.Demand demand) {
            openCalls++;
            String zbotId = demand.zbotSessionId;
            if (zbotId == null || zbotId.isEmpty()) {
                zbotId = freshSessionIds.isEmpty() ? "zb-x" + openCalls : freshSessionIds.removeFirst();
                known.add(zbotId);
            }
            Scripted scripted = new Scripted(zbotId);
            if (demand.model != null && !demand.model.isEmpty()) {
                scripted.model = demand.model;
            }
            opened.add(scripted);
            return scripted;
        }

        @Override
        public List<String> knownZbotSessionIds() {
            return new ArrayList<String>(known);
        }

        @Override
        public void dispose(AcpTurnTarget target) {
            target.shutdown();
        }

        Scripted last() {
            return opened.get(opened.size() - 1);
        }
    }

    static StreamEvent.FinalDelta delta(String text) {
        return new StreamEvent.FinalDelta(text);
    }

    static StreamEvent.ThoughtDelta thought(String text) {
        return new StreamEvent.ThoughtDelta(text);
    }

    static StreamEvent.ToolCallRequest toolRequest(String name, String argsJson) {
        return new StreamEvent.ToolCallRequest(name, null, argsJson);
    }

    static StreamEvent.ToolResult toolResult(String name, String output, boolean ok) {
        return new StreamEvent.ToolResult(name, output, ok ? null : output, ok);
    }

    static AcpConnection connection(Recorder recorder) {
        return new AcpConnection(recorder);
    }

    static AcpAgentServer server(AcpConnection conn, AcpSessionRegistry registry,
                                 AcpApprovalBridge bridge) {
        return new AcpAgentServer(conn, registry, bridge,
                Arrays.asList("z-bot-config", "minimax"), Arrays.asList("default"),
                Arrays.asList("test-model", "other-model"));
    }

    static String req(int id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + paramsJson + "}";
    }

    static String note(String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
    }
}
