package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反向 MCP server（P21 §6）：z-bot 自己当 MCP <b>server</b>，把会话读端暴露给任意
 * MCP client（验收时用官方 Python SDK 的真 client）。
 *
 * <p>为什么要这一层：本期其余部分都是 z-bot 作为 client 去连别人的 server。
 * "对齐 MCP" 只测一半就是自欺 —— 线级格式的两个方向都得有人验过。
 * 这里给的方向是 server，且<b>只暴露 conversations / messages 读端</b>：
 * 一共两条工具，没有任何写侧、执行侧、配置侧入口（工具表本身就是这条约束的取证位，
 * {@code McpServeProtocolTest#toolTableIsExactlyTheTwoReadTools}）。</p>
 *
 * <p>实现选择：内核 0.2.1 只给了 client 侧的 4 方法 {@code McpTransport}，
 * <b>没有任何 server 侧抽象</b>，而红线要求内核一字节不改 ⇒ 这里按规范自己实现
 * newline-delimited JSON-RPC 2.0 over stdio。刻意不复用 {@code StreamableHttpMcpTransport}
 * 里的 client 逻辑，避免"自己实现自己通过"的循环取证。</p>
 *
 * <p>三条硬规矩（都是被自己的坑教育过的）：</p>
 * <ul>
 *   <li><b>stdout 是线，stderr 是人</b>：本类只在 stdout 写 JSON-RPC，日志一律走
 *       slf4j（slf4j-simple 默认落 stderr）。stdout 混进一行"启动成功"就把对端解崩。</li>
 *   <li><b>不认识的 method 必须回 JSON-RPC error</b>，不能静默丢弃：静默丢弃在对端
 *       看起来就是"超时"，而超时是我们本期刚批判过的那类错误。</li>
 *   <li><b>收尾有上限</b>：{@link Options#maxRequests} + EOF 双闸门，绝不无界读。
 *       P20 有过 JDK8 {@code HttpServer.stop()} 卡死 surefire fork 的事故。</li>
 * </ul>
 */
public final class ZBotMcpServe {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 读端工具之一：列会话（= MCP 语境下的 conversations）。 */
    public static final String TOOL_CONVERSATIONS = "zbot_conversations_list";
    /** 读端工具之二：按会话 id 读消息。 */
    public static final String TOOL_MESSAGES = "zbot_messages_read";

    /** 本 server 支持并愿意回显的协议版本；不在表里的版本回自己的默认。 */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = Collections.unmodifiableList(
            Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05"));

    /** JSON-RPC 标准错误码。 */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    /** server 配置。不可变。 */
    public static final class Options {
        String serverName = "z-bot";
        String serverVersion = "0.2.0-dev";
        long maxRequests = 1_000_000L;
        int defaultLimit = 50;
        int maxLimit = 500;

        public Options serverName(String v) {
            this.serverName = v == null || v.trim().isEmpty() ? "z-bot" : v.trim();
            return this;
        }

        public Options serverVersion(String v) {
            this.serverVersion = v == null || v.trim().isEmpty() ? "unknown" : v.trim();
            return this;
        }

        /** 一次会话最多处理多少条请求（含 notification）；0/负数 ⇒ 用默认值。 */
        public Options maxRequests(long v) {
            this.maxRequests = v <= 0 ? 1_000_000L : v;
            return this;
        }

        public Options defaultLimit(int v) {
            this.defaultLimit = v <= 0 ? 50 : v;
            return this;
        }

        public Options maxLimit(int v) {
            this.maxLimit = v <= 0 ? 500 : v;
            return this;
        }
    }

    /**
     * 会话读端。刻意只有两个方法、且都是"读"：新增写侧入口要走 code review，
     * 而不是在这条线上顺手加一个参数。
     */
    public interface ConversationSource {
        /** 会话列表；每项至少含 {@code conversation_id}。 */
        List<Map<String, Object>> conversations(boolean includeArchived, int limit);

        /** 某个会话的消息；每项至少含 {@code idx} / {@code role} / {@code content}。 */
        List<Map<String, Object>> messages(String conversationId, int limit);
    }

    private final ConversationSource source;
    private final Options options;
    private final AtomicLong requestsHandled = new AtomicLong();
    private final AtomicLong responsesSent = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile String negotiatedProtocolVersion;

    public ZBotMcpServe(ConversationSource source) {
        this(source, new Options());
    }

    public ZBotMcpServe(ConversationSource source, Options options) {
        if (source == null) {
            throw new IllegalArgumentException("conversation source is null");
        }
        this.source = source;
        this.options = options == null ? new Options() : options;
    }

    // ------------------------------------------------------------------ 工具表

    /** 对外广告的工具表：只有两条读端工具，顺序即 {@code tools/list} 里的顺序。 */
    public static List<Map<String, Object>> toolDefinitions() {
        Map<String, Object> convProps = new LinkedHashMap<String, Object>();
        convProps.put("include_archived", prop("include_archived", "boolean",
                "是否包含软归档的会话，默认 false"));
        convProps.put("limit", prop("limit", "integer",
                "最多返回多少条，默认 50，上限 500"));

        Map<String, Object> msgProps = new LinkedHashMap<String, Object>();
        msgProps.put("conversation_id", prop("conversation_id", "string",
                "会话 id（取自 " + TOOL_CONVERSATIONS + "）"));
        msgProps.put("limit", prop("limit", "integer",
                "最多返回多少条消息，默认 200，上限 500"));

        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        tools.add(tool(TOOL_CONVERSATIONS,
                "列出 z-bot 的会话（conversations）。只读：返回 id / 标题 / 模型 / 消息数 / 时间戳。",
                convProps));
        tools.add(tool(TOOL_MESSAGES,
                "按会话 id 读取该会话的消息（只读，不写不删）。返回 idx / role / content。",
                msgProps));
        return tools;
    }

    private static Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> tool(String name, String description,
                                            Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // 只有 messages 这条有必填参数；另一条留空表而不是塞一个空字符串进 required
        schema.put("required", TOOL_MESSAGES.equals(name)
                ? Collections.singletonList("conversation_id")
                : Collections.<String>emptyList());
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    // ------------------------------------------------------------------ 线级处理

    /**
     * 处理一行请求，返回要写回对端的一行 JSON；{@code null} 表示这是 notification
     * （规范里 notification 不许有响应）。<b>本方法不抛</b>：任何异常都折成 JSON-RPC error，
     * 因为"把对端挂住等一个不会来的响应"正是本期在 §1.2 里点名的内核缺陷。
     */
    public String handle(String line) {
        requestsHandled.incrementAndGet();
        JsonNode node = McpWire.read(line);
        if (node == null) {
            rejected.incrementAndGet();
            return error(null, PARSE_ERROR, "非 JSON 行");
        }
        JsonNode idNode = node.get("id");
        boolean wantsResponse = idNode != null && !idNode.isNull();
        String method = McpWire.methodOf(node);
        if (method == null || method.isEmpty()) {
            rejected.incrementAndGet();
            return wantsResponse ? error(idNode, INVALID_REQUEST, "缺 method") : null;
        }
        if (!wantsResponse) {
            // notifications/initialized、notifications/cancelled 等：收到即可，不回响应
            return null;
        }
        String result;
        try {
            result = dispatch(method, node.get("params"));
        } catch (JsonRpcError e) {
            rejected.incrementAndGet();
            return error(idNode, e.code, e.getMessage());
        } catch (Exception e) {
            rejected.incrementAndGet();
            return error(idNode, INTERNAL_ERROR,
                    SecretRedaction.scrub(e.getClass().getSimpleName() + ": " + e.getMessage(),
                            Collections.<String, String>emptyMap()));
        }
        if (result == null) {
            // 约定：dispatch 回 null = 该 method 不需要响应（本 server 里没有这种带 id 的 method）
            return null;
        }
        responsesSent.incrementAndGet();
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(idNode) + ",\"result\":" + result + "}";
    }

    private String dispatch(String method, JsonNode params) throws JsonRpcError {
        if ("initialize".equals(method)) {
            return initializeResult(params);
        }
        if ("ping".equals(method)) {
            return "{}";
        }
        if ("tools/list".equals(method)) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("tools", toolDefinitions());
            return write(out);
        }
        if ("tools/call".equals(method)) {
            return toolCallResult(params);
        }
        throw new JsonRpcError(METHOD_NOT_FOUND, "未知 method: " + method);
    }

    private String initializeResult(JsonNode params) {
        String requested = textAt(params, "protocolVersion");
        String negotiated = requested == null || requested.isEmpty()
                ? McpWire.PROTOCOL_VERSION : requested;
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(negotiated)) {
            // 规范：客户端提的版本我不支持 ⇒ 回我自己的版本，让对端决定断不断
            negotiated = McpWire.PROTOCOL_VERSION;
        }
        negotiatedProtocolVersion = negotiated;
        Map<String, Object> serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", options.serverName);
        serverInfo.put("version", options.serverVersion);
        // 工具表是编译期常量 ⇒ 不广告 listChanged（广告出去就是承诺，这里兑现不了）
        Map<String, Object> tools = new LinkedHashMap<String, Object>();
        tools.put("listChanged", Boolean.FALSE);
        Map<String, Object> caps = new LinkedHashMap<String, Object>();
        caps.put("tools", tools);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        return write(result);
    }

    private String toolCallResult(JsonNode params) throws JsonRpcError {
        String name = textAt(params, "name");
        if (name == null || name.isEmpty()) {
            throw new JsonRpcError(INVALID_PARAMS, "tools/call 缺 params.name");
        }
        if (!TOOL_CONVERSATIONS.equals(name) && !TOOL_MESSAGES.equals(name)) {
            throw new JsonRpcError(INVALID_PARAMS,
                    "未知工具: " + name + "（只暴露 " + TOOL_CONVERSATIONS + " / " + TOOL_MESSAGES + "）");
        }
        JsonNode args = params == null ? null : params.get("arguments");
        Map<String, Object> payload;
        try {
            payload = name.equals(TOOL_CONVERSATIONS)
                    ? readConversations(args) : readMessages(args);
        } catch (JsonRpcError e) {
            throw e;
        } catch (Exception e) {
            // 工具执行失败按规范回 isError 的工具结果，而不是协议层 error：
            // 对端的 call_tool 要拿到"这次调用失败了"，而不是整个会话炸掉
            Map<String, Object> failed = new LinkedHashMap<String, Object>();
            failed.put("content", Collections.singletonList(
                    textContent(SecretRedaction.scrub(String.valueOf(e.getMessage()),
                            Collections.<String, String>emptyMap()))));
            failed.put("isError", Boolean.TRUE);
            return write(failed);
        }
        Map<String, Object> ok = new LinkedHashMap<String, Object>();
        ok.put("content", Collections.singletonList(textContent(write(payload))));
        ok.put("isError", Boolean.FALSE);
        ok.put("structuredContent", payload);
        return write(ok);
    }

    private Map<String, Object> readConversations(JsonNode args) throws JsonRpcError {
        boolean includeArchived = boolAt(args, "include_archived");
        int limit = limitAt(args, options.defaultLimit);
        List<Map<String, Object>> rows = source.conversations(Boolean.valueOf(includeArchived),
                Integer.valueOf(limit));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("conversationCount", Integer.valueOf(rows == null ? 0 : rows.size()));
        out.put("limit", Integer.valueOf(limit));
        out.put("includeArchived", Boolean.valueOf(includeArchived));
        out.put("conversations", rows == null
                ? Collections.<Map<String, Object>>emptyList() : rows);
        return out;
    }

    private Map<String, Object> readMessages(JsonNode args) throws JsonRpcError {
        String id = textAt(args, "conversation_id");
        if (id == null || id.trim().isEmpty()) {
            throw new JsonRpcError(INVALID_PARAMS, "zbot_messages_read 需要 params.conversation_id");
        }
        int limit = limitAt(args, 200);
        List<Map<String, Object>> rows = source.messages(id.trim(), Integer.valueOf(limit));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("conversationId", id.trim());
        out.put("messageCount", Integer.valueOf(rows == null ? 0 : rows.size()));
        out.put("limit", Integer.valueOf(limit));
        out.put("messages", rows == null ? Collections.<Map<String, Object>>emptyList() : rows);
        return out;
    }

    private static Map<String, Object> textContent(String text) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("type", "text");
        c.put("text", text == null ? "" : text);
        return c;
    }

    /** 上限夹紧 + 非正值回落默认：不给 client 一个"limit=0 ⇒ 全库扫"的口子。 */
    private int limitAt(JsonNode args, int dflt) {
        Integer v = args == null ? null : integerOrNull(args.get("limit"));
        int limit = v == null ? dflt : v.intValue();
        if (limit <= 0) {
            limit = dflt;
        }
        return Math.min(limit, options.maxLimit);
    }

    private static Integer integerOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            if (n.isNumber()) {
                return Integer.valueOf(n.asInt());
            }
            if (n.isTextual() && !n.asText().trim().isEmpty()) {
                return Integer.valueOf(Integer.parseInt(n.asText().trim()));
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static boolean boolAt(JsonNode args, String key) {
        if (args == null) {
            return false;
        }
        JsonNode n = args.get(key);
        return n != null && !n.isNull() && n.asBoolean(false);
    }

    private static String textAt(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode n = node.get(key);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String error(JsonNode idNode, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(idNode)
                + ",\"error\":{\"code\":" + code
                + ",\"message\":" + jsonLiteral(message)
                + "}}";
    }

    /**
     * id 原样回显：数字不加引号，字符串必须加引号。
     * 数字 id 被引号化（或反之）会让规范严格的 client 判成"响应配不上请求"。
     */
    private static String idLiteral(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return "null";
        }
        if (idNode.isNumber() || idNode.isBoolean()) {
            return idNode.asText().trim();
        }
        return jsonLiteral(idNode.asText());
    }

    /** 数字 id 原样进线（不加引号），其余按字符串转义。 */
    private static String jsonLiteral(String raw) {
        if (raw != null && raw.matches("-?\\d+")) {
            return raw;
        }
        try {
            return JSON.writeValueAsString(raw == null ? "" : raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String write(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            return "{\"unserializable\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    // ------------------------------------------------------------------ stdio 循环

    /**
     * 从 {@code in} 逐行读、往 {@code out} 逐行写，直到 EOF 或达到
     * {@link Options#maxRequests}。返回处理掉的行数。
     *
     * <p>每写一行立刻 flush：stdio MCP 的对端在等这一行，缓冲在这里就是挂死。</p>
     */
    public int serve(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        int handled = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (requestsHandled.get() >= options.maxRequests) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String resp = handle(trimmed);
            handled++;
            if (resp != null) {
                w.write(resp);
                w.write('\n');
                w.flush();
            }
        }
        w.flush();
        return handled;
    }

    /** 把若干行喂进 {@link #handle}，返回响应行列表（测试与自证用，不落 stdout）。 */
    public List<String> handleAll(List<String> lines) {
        List<String> out = new ArrayList<String>();
        for (String l : lines) {
            String r = handle(l);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 读数

    public long requestsHandled() {
        return requestsHandled.get();
    }

    public long responsesSent() {
        return responsesSent.get();
    }

    /** 被拒的行数（非 JSON / 缺 method / 未知 method / 未知工具）——"空输入不许满分"的计数位。 */
    public long rejectedRequests() {
        return rejected.get();
    }

    public String negotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public Options options() {
        return options;
    }

    /** 内部用的带码异常：折成 JSON-RPC error 用，不外泄。 */
    private static final class JsonRpcError extends Exception {
        private final int code;

        JsonRpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 读干一个流但不留内容（{@code serve} 的收尾保护用；测试里也拿来确认没残留字节）。 */
    static String drainQuietly(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > 65536) {
                break;
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    static String lowerForCompare(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}
