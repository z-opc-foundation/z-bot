package com.zifang.z.bot.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 极简 JSON-RPC 2.0 报文编解码（ACP 传输层用）。
 *
 * <p>帧格式 = <b>每行一条 JSON</b>（newline-delimited），不是 {@code Content-Length} 头帧。
 * 依据是她依赖的那个 SDK 的实现注释：
 * {@code ~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/connection.py:62}
 * {@code """Minimal JSON-RPC 2.0 connection over newline-delimited JSON frames."""}，
 * 读侧 {@code acp/stdio.py:56} 用 {@code sys.stdin.buffer.readline()}。
 * 对拍见 {@code _doc/acceptance/p25/EVIDENCE.md} §0.4。</p>
 *
 * <p>错误码用 JSON-RPC 标准码：{@code -32700} parse、{@code -32600} invalid request、
 * {@code -32601} method not found、{@code -32602} invalid params、{@code -32603} internal。
 * 工单 §1.1 要求"未知方法大声返回 method-not-found"，落地在
 * {@link AcpConnection} 的分派缺省分支，错误码常量在此。</p>
 */
public final class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /** 协议自己定义的错误码没进本类：ACP schema 只规定了 JSON-RPC 标准码 + 业务消息文案。 */

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpc() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 一条已解析的报文（请求 / 通知 / 响应三态合一，按字段是否有值区分）。 */
    public static final class Message {

        private final JsonNode id;
        private final String method;
        private final JsonNode params;
        private final JsonNode result;
        private final JsonNode error;
        private final boolean responseShaped;

        Message(JsonNode id, String method, JsonNode params, JsonNode result, JsonNode error,
                boolean responseShaped) {
            this.id = id;
            this.method = method;
            this.params = params;
            this.result = result;
            this.error = error;
            this.responseShaped = responseShaped;
        }

        /** 有 id 且有 method ⇒ 需要回包；只有 method ⇒ 通知（不回包）。 */
        public boolean isRequest() {
            return !responseShaped && method != null && id != null && !id.isNull();
        }

        public boolean isNotification() {
            return !responseShaped && method != null && (id == null || id.isNull());
        }

        /** 对端回来的响应（对应我们发出的 {@code session/request_permission} 等）。 */
        public boolean isResponse() {
            return responseShaped;
        }

        public JsonNode id() {
            return id;
        }

        /** id 的字符串形态：数字 id 与字符串 id 都归一化成文本，用于 pending 表键。 */
        public String idText() {
            return id == null || id.isNull() ? null : (id.isTextual() ? id.textValue() : id.toString());
        }

        public String method() {
            return method;
        }

        public JsonNode params() {
            return params == null ? MAPPER.createObjectNode() : params;
        }

        public JsonNode result() {
            return result;
        }

        public JsonNode error() {
            return error;
        }

        /** 对端报错时的文案（无 error 时 null）。 */
        public String error_message() {
            if (error == null) {
                return null;
            }
            JsonNode m = error.get("message");
            return m == null ? null : m.asText();
        }
    }

    /**
     * 解析一行 JSON。
     *
     * @throws AcpProtocolException 不是合法 JSON（{@link #PARSE_ERROR}）或不是合法 JSON-RPC 报文
     *                               （{@link #INVALID_REQUEST}）
     */
    public static Message parse(String line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new AcpProtocolException(PARSE_ERROR, "Parse error: " + e.getOriginalMessage(), null, e);
        } catch (java.io.IOException e) {
            throw new AcpProtocolException(PARSE_ERROR, "Parse error: " + e.getMessage(), null, e);
        }
        if (node == null || !node.isObject()) {
            throw new AcpProtocolException(INVALID_REQUEST, "Invalid request: not a JSON object", null, null);
        }
        JsonNode id = node.get("id");
        JsonNode method = node.get("method");
        JsonNode params = node.get("params");
        JsonNode result = node.get("result");
        JsonNode error = node.get("error");
        boolean responseShaped = result != null || error != null;
        if (!responseShaped && (method == null || !method.isTextual())) {
            throw new AcpProtocolException(INVALID_REQUEST,
                    "Invalid request: missing string member \"method\"", null, null, id);
        }
        return new Message(id, method == null ? null : method.textValue(), params, result, error,
                responseShaped);
    }

    public static String request(String id, String method, JsonNode params) {
        ObjectNode node = base(method, params);
        node.put("id", id);
        return write(node);
    }

    public static String notification(String method, JsonNode params) {
        return write(base(method, params));
    }

    public static String response(JsonNode id, JsonNode result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", id == null ? MAPPER.nullNode() : id);
        node.set("result", result == null ? MAPPER.createObjectNode() : result);
        return write(node);
    }

    /**
     * 错误响应。{@code data} 可为 null —— 未知方法那类"大声失败"会把已知方法表塞进 data，
     * 让客户端一眼看到自己拼错了哪个名字，而不是只收到一个裸 {@code -32601}。
     */
    public static String error(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", id == null ? MAPPER.nullNode() : id);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        if (data != null) {
            err.set("data", data);
        }
        node.set("error", err);
        return write(node);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** 把 {@code {"k":"v",…}} 的字符串表按插入序写成 ObjectNode（少写一处 try/catch 样板）。 */
    public static ObjectNode fields(String... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        if (keyValues == null) {
            return node;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                node.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return node;
    }

    /** 响应里的 {@code result} 允许是 null（SDK 的可选响应就是这么发的）。 */
    public static String responseNullable(JsonNode id, JsonNode result) {
        return response(id, result);
    }

    private static ObjectNode base(String method, JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        if (params != null && !params.isNull()) {
            node.set("params", params);
        }
        return node;
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ACP 报文序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /** 便于测试断言：把一行 JSON 摊平成 {@code key=value} 序列（只到第一层）。 */
    public static String flatten(String json) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode node = MAPPER.readTree(json);
            walk("", node, sb);
        } catch (java.io.IOException e) {
            return "<unparseable:" + json + ">";
        }
        return sb.toString();
    }

    private static void walk(String prefix, JsonNode node, StringBuilder sb) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), sb);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(prefix + "[" + i + "]", node.get(i), sb);
            }
        } else {
            sb.append(sb.length() == 0 ? "" : " ").append(prefix).append('=').append(node.asText());
        }
    }
}
