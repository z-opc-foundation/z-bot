package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式回推：z-bot 的 {@link StreamEvent} → ACP {@code session/update} 通知。
 *
 * <h2>不退化成整包（工单 §1.4）</h2>
 * 一个 {@link StreamEvent.FinalDelta} ⇔ 一条 {@code agent_message_chunk} 通知，
 * <b>逐个增量立刻发</b>，绝不在收尾时合并成一条。这既是协议要求（客户端据此边收边画），
 * 也是本期最容易偷工的地方 —— 所以 {@code AcpStreamPublisherTest} 里直接数帧数、
 * 逐帧比对文本顺序。
 *
 * <h2>映射表（z-bot 九种 Kind → ACP sessionUpdate）</h2>
 * <table border="1">
 *   <tr><th>StreamEvent.Kind</th><th>ACP 帧</th><th>依据</th></tr>
 *   <tr><td>{@code FINAL_DELTA}</td><td>{@code agent_message_chunk}</td>
 *       <td>hermes {@code events.py:266 make_message_cb} → 同一 sessionUpdate</td></tr>
 *   <tr><td>{@code THOUGHT_DELTA}</td><td>{@code agent_thought_chunk}</td>
 *       <td>hermes {@code events.py:189 make_thinking_cb}</td></tr>
 *   <tr><td>{@code TOOL_CALL_REQUEST}</td><td>{@code tool_call} (status=pending)</td>
 *       <td>hermes {@code events.py:114 make_tool_progress_cb} 发 ToolCallStart</td></tr>
 *   <tr><td>{@code TOOL_RESULT}</td><td>{@code tool_call_update} (completed/failed)</td>
 *       <td>同上：start 之后跟 update</td></tr>
 *   <tr><td>{@code STEER} / {@code COMPACTED}</td><td>{@code agent_thought_chunk}</td>
 *       <td>都是"给用户看一眼、不该进答案"的过程信息</td></tr>
 *   <tr><td>{@code STEP_START}</td><td>不发帧，只计数</td>
 *       <td>ACP 无对应 sessionUpdate；凭空造会违反 §0.4 的字段口径</td></tr>
 *   <tr><td>{@code DONE}</td><td>不发帧</td><td>由 {@code session/prompt} 的响应 stopReason 表达</td></tr>
 *   <tr><td>{@code ERROR}</td><td>不发帧，记 {@link #lastError()}</td>
 *       <td>错误必须变成 JSON-RPC 侧的可见失败，不能伪装成一条正常 chunk</td></tr>
 * </table>
 *
 * <p>{@code tool_call} 与 {@code tool_call_update} 靠 {@code toolCallId} 配对。
 * z-bot 的 {@code TOOL_RESULT} 事件里<b>没有</b> call id（只有 name），故这里按
 * "同名工具先进先出"配对 —— 与 {@code BotAgent.executeBatch} 保证的"结果按原顺序回灌"
 * （{@code agent/BotAgent.java:655—676}）同源，不另造标识。</p>
 */
public final class AcpStreamPublisher implements StreamListener {

    private final AcpConnection conn;
    private final String acpSessionId;
    private final Map<String, Deque<String>> openToolCalls =
            new HashMap<String, Deque<String>>();
    private final List<String> emittedSessionUpdates = new ArrayList<String>();
    private final List<String> chunkTexts = new ArrayList<String>();

    private long sequence;
    private volatile StreamEvent lastError;
    private volatile StreamEvent lastDone;
    private volatile int stepStarts;

    public AcpStreamPublisher(AcpConnection conn, String acpSessionId) {
        if (conn == null) {
            throw new IllegalArgumentException("conn 不能为 null");
        }
        this.conn = conn;
        this.acpSessionId = acpSessionId;
    }

    @Override
    public void onEvent(StreamEvent event) {
        if (event == null) {
            return;
        }
        switch (event.kind()) {
            case FINAL_DELTA:
                emitChunk(AcpMethods.UPDATE_AGENT_MESSAGE_CHUNK,
                        ((StreamEvent.FinalDelta) event).text, true);
                break;
            case THOUGHT_DELTA:
                emitChunk(AcpMethods.UPDATE_AGENT_THOUGHT_CHUNK,
                        ((StreamEvent.ThoughtDelta) event).text, true);
                break;
            case STEER:
                emitChunk(AcpMethods.UPDATE_AGENT_THOUGHT_CHUNK,
                        "[steer] " + ((StreamEvent.SteerInjected) event).text, true);
                break;
            case COMPACTED:
                StreamEvent.Compacted c = (StreamEvent.Compacted) event;
                emitChunk(AcpMethods.UPDATE_AGENT_THOUGHT_CHUNK,
                        "[compact] " + c.fromCount + "->" + c.toCount, true);
                break;
            case TOOL_CALL_REQUEST:
                openToolCall((StreamEvent.ToolCallRequest) event);
                break;
            case TOOL_RESULT:
                closeToolCall((StreamEvent.ToolResult) event);
                break;
            case STEP_START:
                stepStarts++;
                break;
            case DONE:
                lastDone = event;
                break;
            case ERROR:
                lastError = event;
                break;
            default:
                break;
        }
    }

    /** 已发出的 {@code sessionUpdate} 判别字序列（到达序）—— 测试数帧就靠它。 */
    public List<String> emittedSessionUpdates() {
        synchronized (emittedSessionUpdates) {
            return new ArrayList<String>(emittedSessionUpdates);
        }
    }

    /** 已推给客户端的文本增量（含 thought），按到达序。 */
    public List<String> chunkTexts() {
        synchronized (chunkTexts) {
            return new ArrayList<String>(chunkTexts);
        }
    }

    public int messageChunks() {
        int n = 0;
        for (String s : emittedSessionUpdates()) {
            if (AcpMethods.UPDATE_AGENT_MESSAGE_CHUNK.equals(s)) {
                n++;
            }
        }
        return n;
    }

    public int toolCallFrames() {
        int n = 0;
        for (String s : emittedSessionUpdates()) {
            if (AcpMethods.UPDATE_TOOL_CALL.equals(s) || AcpMethods.UPDATE_TOOL_CALL_UPDATE.equals(s)) {
                n++;
            }
        }
        return n;
    }

    public Throwable lastError() {
        StreamEvent e = lastError;
        if (e == null) {
            return null;
        }
        return ((StreamEvent.ErrorEvent) e).cause;
    }

    public StreamEvent lastDone() {
        return lastDone;
    }

    public int stepStarts() {
        return stepStarts;
    }

    /** 还有几笔工具调用没收到结果（cancel 打断时用于说明"停在哪儿"）。 */
    public synchronized int openToolCallCount() {
        int n = 0;
        for (Deque<String> q : openToolCalls.values()) {
            n += q.size();
        }
        return n;
    }

    // ---- 内部 ----

    private void emitChunk(String sessionUpdate, String text, boolean record) {
        ObjectNode update = JsonRpc.object();
        update.put("sessionUpdate", sessionUpdate);
        ObjectNode content = JsonRpc.object();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        update.set("content", content);
        send(update);
        if (record) {
            synchronized (chunkTexts) {
                chunkTexts.add(text == null ? "" : text);
            }
        }
    }

    private void openToolCall(StreamEvent.ToolCallRequest req) {
        String name = req.name == null ? "?" : req.name;
        String id = "tc-" + (++sequence);
        synchronized (openToolCalls) {
            Deque<String> q = openToolCalls.get(name);
            if (q == null) {
                q = new ArrayDeque<String>();
                openToolCalls.put(name, q);
            }
            q.addLast(id);
        }
        ObjectNode update = baseToolCall(id, name, "pending");
        update.put("status", "pending");
        update.set("rawInput", parseJsonOrWrap(req.argumentsJson));
        send(update);
    }

    private void closeToolCall(StreamEvent.ToolResult res) {
        String name = res.name == null ? "?" : res.name;
        String id;
        synchronized (openToolCalls) {
            Deque<String> q = openToolCalls.get(name);
            id = q == null || q.isEmpty() ? null : q.removeFirst();
        }
        if (id == null) {
            // 没配到对：不猜是哪笔调用的结果，但也要把结果推出去（id 用序号），
            // 否则客户端会永远等一个不存在的 tool_call_update。
            id = "tc-orphan-" + (++sequence);
        }
        ObjectNode update = JsonRpc.object();
        update.put("sessionUpdate", AcpMethods.UPDATE_TOOL_CALL_UPDATE);
        update.put("toolCallId", id);
        update.put("status", res.success ? "completed" : "failed");
        if (res.error != null) {
            ArrayNode content = update.putArray("content");
            ObjectNode text = JsonRpc.object();
            text.put("type", "text");
            text.put("text", res.error);
            content.add(text);
        }
        update.set("rawOutput", res.result == null ? JsonRpc.object()
                : parseJsonOrWrap(String.valueOf(res.result)));
        send(update);
    }

    private ObjectNode baseToolCall(String id, String name, String status) {
        ObjectNode update = JsonRpc.object();
        update.put("sessionUpdate", AcpMethods.UPDATE_TOOL_CALL);
        update.put("toolCallId", id);
        update.put("title", name);
        update.put("kind", "other");
        return update;
    }

    /**
     * 工具入参/输出 → wire 上的 JSON 形状。
     *
     * <p>包内共享：审批帧的 {@code toolCall.rawInput}（{@link AcpApprovalBridge}）与这里的
     * {@code session/update} 必须走同一个函数 —— 同一个 toolCall 在两条帧里给出两种形状，
     * IDE 端就只能挑一条渲染（她 SDK 的 {@code acp/helpers.py:226,247} 就是"rawInput 即真入参对象"）。</p>
     */
    static JsonNode parseJsonOrWrap(String raw) {
        if (raw == null || raw.isEmpty()) {
            return JsonRpc.object();
        }
        try {
            JsonNode node = JsonRpc.mapper().readTree(raw);
            if (node.isObject() || node.isArray()) {
                return node;
            }
        } catch (java.io.IOException ignored) {
            // 工具参数/输出不是 JSON 是常态（exec 的 stdout），下面包成文本。
        }
        ObjectNode wrapper = JsonRpc.object();
        wrapper.put("text", raw);
        return wrapper;
    }

    private void send(ObjectNode update) {
        String sessionUpdate = update.path("sessionUpdate").asText("");
        ObjectNode params = JsonRpc.object();
        params.put("sessionId", acpSessionId);
        params.set("update", update);
        conn.notify(AcpMethods.SESSION_UPDATE, params);
        synchronized (emittedSessionUpdates) {
            emittedSessionUpdates.add(sessionUpdate);
        }
    }
}
