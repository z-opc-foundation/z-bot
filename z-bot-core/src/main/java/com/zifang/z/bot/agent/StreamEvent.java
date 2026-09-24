package com.zifang.z.bot.agent;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 流式事件 — 与 z-opc 老 engine 的 StreamEvent 七种事件一一对应。
 *
 * <p>消费方（终端 TUI / SSE）按 kind 过滤自己关心的切片，
 * 例如只要最终答案的订阅者可以只处理 {@link FinalDelta}。</p>
 */
public abstract class StreamEvent {

    private final LocalDateTime timestamp = LocalDateTime.now();

    public abstract Kind kind();

    public LocalDateTime timestamp() {
        return timestamp;
    }

    public enum Kind {
        /** 一次 ReAct 迭代开始 */
        STEP_START,
        /** LLM 原始文本增量（思考 / 工具调用旁白） */
        THOUGHT_DELTA,
        /** LLM 决定调用工具 */
        TOOL_CALL_REQUEST,
        /** 工具执行结束 */
        TOOL_RESULT,
        /** 最终答案文本增量 */
        FINAL_DELTA,
        /** agent 结束（终止标记） */
        DONE,
        /** 用户 steer 插话被注入消息流 */
        STEER,
        /** 上下文中段被压缩成摘要 */
        COMPACTED,
        /** 不可恢复错误 */
        ERROR
    }

    public static final class StepStart extends StreamEvent {
        public final int step;

        public StepStart(int step) {
            this.step = step;
        }

        @Override
        public Kind kind() {
            return Kind.STEP_START;
        }

        @Override
        public String toString() {
            return "StepStart(" + step + ")";
        }
    }

    public static final class ThoughtDelta extends StreamEvent {
        public final String text;

        public ThoughtDelta(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        public Kind kind() {
            return Kind.THOUGHT_DELTA;
        }

        @Override
        public String toString() {
            return "ThoughtDelta(" + text + ")";
        }
    }

    public static final class ToolCallRequest extends StreamEvent {
        public final String name;
        public final Map<String, Object> arguments;
        public final String argumentsJson;

        public ToolCallRequest(String name, Map<String, Object> arguments, String argumentsJson) {
            this.name = name;
            this.arguments = arguments;
            this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        }

        @Override
        public Kind kind() {
            return Kind.TOOL_CALL_REQUEST;
        }

        @Override
        public String toString() {
            return "ToolCallRequest(" + name + ", args=" + argumentsJson + ")";
        }
    }

    public static final class ToolResult extends StreamEvent {
        public final String name;
        public final Object result;
        public final String error;
        public final boolean success;

        public ToolResult(String name, Object result, String error, boolean success) {
            this.name = name;
            this.result = result;
            this.error = error;
            this.success = success;
        }

        @Override
        public Kind kind() {
            return Kind.TOOL_RESULT;
        }

        @Override
        public String toString() {
            return "ToolResult(" + name + ", success=" + success
                    + ", result=" + (result == null ? "null" : result.toString())
                    + (error == null ? "" : ", error=" + error) + ")";
        }
    }

    public static final class FinalDelta extends StreamEvent {
        public final String text;

        public FinalDelta(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        public Kind kind() {
            return Kind.FINAL_DELTA;
        }

        @Override
        public String toString() {
            return "FinalDelta(" + text + ")";
        }
    }

    public static final class Done extends StreamEvent {
        public final String reply;
        public final int totalSteps;
        public final Integer promptTokens;
        public final Integer completionTokens;

        public Done(String reply, int totalSteps, Integer promptTokens, Integer completionTokens) {
            this.reply = reply == null ? "" : reply;
            this.totalSteps = totalSteps;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public Kind kind() {
            return Kind.DONE;
        }

        @Override
        public String toString() {
            return "Done(steps=" + totalSteps + ", replyLen=" + reply.length() + ")";
        }
    }

    public static final class SteerInjected extends StreamEvent {
        public final String text;

        public SteerInjected(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        public Kind kind() {
            return Kind.STEER;
        }

        @Override
        public String toString() {
            return "SteerInjected(" + text + ")";
        }
    }

    public static final class Compacted extends StreamEvent {
        public final String summary;
        public final int fromCount;
        public final int toCount;

        public Compacted(String summary, int fromCount, int toCount) {
            this.summary = summary == null ? "" : summary;
            this.fromCount = fromCount;
            this.toCount = toCount;
        }

        @Override
        public Kind kind() {
            return Kind.COMPACTED;
        }

        @Override
        public String toString() {
            return "Compacted(" + fromCount + "->" + toCount + ", summaryLen=" + summary.length() + ")";
        }
    }

    public static final class ErrorEvent extends StreamEvent {
        public final Throwable cause;

        public ErrorEvent(Throwable cause) {
            this.cause = cause;
        }

        @Override
        public Kind kind() {
            return Kind.ERROR;
        }

        @Override
        public String toString() {
            return "Error(" + (cause == null ? "null" : cause.getMessage()) + ")";
        }
    }
}
