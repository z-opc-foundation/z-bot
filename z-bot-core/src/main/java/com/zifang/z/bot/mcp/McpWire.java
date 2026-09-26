package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * MCP 线级小工具：JSON-RPC id 提取、请求拼装、shell 引号。
 *
 * <p>刻意把"id 怎么取"收在一处：内核 {@code StdioMcpTransport} 的三个已知缺陷里最要命的一个
 * 就是用<b>字符串包含</b>（{@code line.contains("\"id\":" + id)}）来配对端响应，
 * 于是任何非紧凑 JSON（官方 SDK / 多数真 server 都是 {@code "id": 1} 带空格）永远配不上 ⇒ 永久挂死。
 * 这里改成解析后再取 —— 这是 P21 旁路内核 transport 的理由之一。</p>
 */
final class McpWire {

    static final ObjectMapper JSON = new ObjectMapper();

    /** 我们自己发出去的请求用的协议版本（对齐官方 SDK 支持的下界）。 */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private McpWire() {
    }

    /** 解析后取 {@code id}；数字返回 Long，字符串返回其值，没有 id（notification）返回 null。 */
    static Long idOf(String json) {
        JsonNode node = read(json);
        if (node == null) {
            return null;
        }
        return idOf(node);
    }

    static Long idOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode id = node.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        return Long.valueOf(asLong(id));
    }

    /** id 允许是字符串（规范允许），统一成 long 便于做 key；非数字 id 走 hashCode 兜底。 */
    private static long asLong(JsonNode id) {
        if (id.isNumber()) {
            return id.asLong();
        }
        try {
            return Long.parseLong(id.asText().trim());
        } catch (NumberFormatException e) {
            // 规范允许字符串 id；本仓 client 只发数字 id，出现字符串 id 说明对端在回显别的请求，
            // 用一个稳定但不会和数字 id 撞的负数空间。
            return -Math.abs((long) id.asText().hashCode()) - 1L;
        }
    }

    static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    static String methodOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode m = node.get("method");
        return m == null || m.isNull() ? null : m.asText();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> paramsOf(JsonNode node) {
        if (node == null) {
            return McpNotificationListener.emptyParams();
        }
        JsonNode p = node.get("params");
        if (p == null || p.isNull() || !p.isObject()) {
            return McpNotificationListener.emptyParams();
        }
        try {
            return JSON.convertValue(p, Map.class);
        } catch (Exception e) {
            return McpNotificationListener.emptyParams();
        }
    }

    /** 紧凑 JSON 请求（自己发的东西保持紧凑，别给对端找麻烦）。 */
    static String request(long id, String method, Object params) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"").append(method).append("\",\"params\":");
        appendValue(sb, params);
        return sb.append('}').toString();
    }

    static String notification(String method, Object params) {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"method\":\"")
                .append(method).append("\",\"params\":");
        appendValue(sb, params);
        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object params) {
        if (params == null) {
            sb.append("{}");
            return;
        }
        try {
            sb.append(JSON.writeValueAsString(params));
        } catch (Exception e) {
            throw new IllegalArgumentException("params 无法序列化: " + e.getMessage(), e);
        }
    }

    /**
     * POSIX 单引号包裹（内部单引号用 {@code '\''} 惯用法）。
     * 命令行参数里出现空格/分号是常态（路径、脚本参数），不引号化就是注入面。
     */
    static String shellQuote(String token) {
        if (token == null) {
            return "''";
        }
        return "'" + token.replace("'", "'\\''") + "'";
    }

    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
