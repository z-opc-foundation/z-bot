package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 一条要按 JSON-RPC error 回给对端的协议异常。
 *
 * <p>它和"进程内异常"的分工很清楚：只有从 {@link AcpConnection} 分派路径抛出、
 * 且带 {@code code} 的异常才会变成 error 响应；其它异常一律收敛成
 * {@link JsonRpc#INTERNAL_ERROR} 并保留原始文案 —— 工单 §1.1 要的是"大声失败"，
 * 不是把异常吞进 stderr 让客户端以为成功。</p>
 */
public class AcpProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final JsonNode data;
    private final transient JsonNode requestId;

    public AcpProtocolException(int code, String message, JsonNode data, Throwable cause) {
        this(code, message, data, cause, null);
    }

    /** 带 requestId 的构造：分派层知道该把 error 回给谁。 */
    public AcpProtocolException(int code, String message, JsonNode data, Throwable cause,
                                JsonNode requestId) {
        super(message, cause);
        this.code = code;
        this.data = data;
        this.requestId = requestId;
    }

    public static AcpProtocolException invalidParams(String message) {
        return new AcpProtocolException(JsonRpc.INVALID_PARAMS, message, null, null);
    }

    public static AcpProtocolException internal(String message, Throwable cause) {
        return new AcpProtocolException(JsonRpc.INTERNAL_ERROR, message, null, cause);
    }

    /**
     * 未知方法：{@code -32601} + 把已知方法表带进 {@code data}，
     * 让客户端一眼看到自己拼错了哪个名字，而不是只收到一个裸码。
     */
    public static AcpProtocolException methodNotFound(String method) {
        ObjectNode data = JsonRpc.object();
        data.put("unknownMethod", method);
        ArrayNode known = data.putArray("knownMethods");
        for (String m : AcpMethods.AGENT_METHODS) {
            known.add(m);
        }
        return new AcpProtocolException(JsonRpc.METHOD_NOT_FOUND,
                "method not found: " + method, data, null);
    }

    /** 已知但本期不实现：同一个码，文案里明说原因（见 EVIDENCE §未做）。 */
    public static AcpProtocolException notImplemented(String method, String why) {
        ObjectNode data = JsonRpc.object();
        data.put("method", method);
        data.put("status", "not-implemented-in-p25");
        return new AcpProtocolException(JsonRpc.METHOD_NOT_FOUND,
                method + " not implemented in p25: " + why, data, null);
    }

    public int code() {
        return code;
    }

    public JsonNode data() {
        return data;
    }

    public JsonNode requestId() {
        return requestId;
    }
}
