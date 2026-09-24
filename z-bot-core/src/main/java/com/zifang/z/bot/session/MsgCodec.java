package com.zifang.z.bot.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Msg} ↔ 平面 Map 双向序列化 — JSON 文件与 state.db 共用同一种行格式，
 * 老会话文件（role/content/contentType/toolName/toolCallId/isFinal/toolCalls）能原样读回。
 */
public final class MsgCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MsgCodec() {
    }

    public static String toJson(List<Msg> messages) {
        try {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (Msg m : messages) {
                out.add(toPlain(m));
            }
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败: " + e.getMessage(), e);
        }
    }

    public static List<Msg> fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<Msg>();
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<Msg> out = new ArrayList<Msg>();
            for (Map<String, Object> map : raw) {
                Msg m = fromPlain(map);
                if (m != null) {
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<Msg>();
        }
    }

    public static Map<String, Object> toPlain(Msg m) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("role", m.getRole() == null ? "user" : m.getRole().name().toLowerCase());
        map.put("content", m.getContent());
        map.put("contentType", contentTypeOf(m));
        map.put("toolName", toolNameOf(m));
        map.put("toolCallId", m.getToolCallId());
        map.put("isFinal", Boolean.TRUE.equals(meta(m, "isFinal")));
        map.put("timestamp", java.time.LocalDateTime.now().toString());
        if (!m.getToolCalls().isEmpty()) {
            List<Map<String, Object>> tcs = new ArrayList<Map<String, Object>>();
            for (ToolCall tc : m.getToolCalls()) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("id", tc.getId());
                one.put("name", tc.getName());
                one.put("argumentsJson", tc.getArgumentsJson());
                tcs.add(one);
            }
            map.put("toolCalls", tcs);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Msg fromPlain(Map<String, Object> map) {
        MessageRole role = parseRole(str(map.get("role")));
        String content = str(map.get("content"));
        String contentType = str(map.get("contentType"));
        String toolCallId = str(map.get("toolCallId"));
        String toolName = str(map.get("toolName"));
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        if (toolName != null && !toolName.isEmpty()) {
            meta.put("toolName", toolName);
        }
        if (Boolean.TRUE.equals(map.get("isFinal"))) {
            meta.put("isFinal", Boolean.TRUE);
        }

        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        Object rawTcs = map.get("toolCalls");
        if (rawTcs instanceof List) {
            for (Object o : (List<Object>) rawTcs) {
                if (o instanceof Map) {
                    Map<String, Object> tc = (Map<String, Object>) o;
                    toolCalls.add(new ToolCall(str(tc.get("id")), str(tc.get("name")),
                            str(tc.get("argumentsJson"))));
                }
            }
        }
        if (toolCalls.isEmpty() && "tool_call".equals(contentType) && toolName != null && !toolName.isEmpty()) {
            // 老 bot 的落盘格式：content 就是 args JSON
            toolCalls.add(new ToolCall(toolCallId, toolName, content));
            meta.put("toolName", toolName);
        }

        MessageType type = toolCalls.isEmpty()
                ? ("tool_result".equals(contentType) ? MessageType.TOOL_RESULT : MessageType.TEXT)
                : MessageType.TOOL_CALL;
        return new Msg(role, null, content, type, toolCallId, toolCalls, meta);
    }

    private static String contentTypeOf(Msg m) {
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            return "tool_call";
        }
        if (m.getRole() == MessageRole.TOOL) {
            return "tool_result";
        }
        return "text";
    }

    private static String toolNameOf(Msg m) {
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            return m.getToolCalls().get(0).getName();
        }
        Object n = meta(m, "toolName");
        return n == null ? null : n.toString();
    }

    private static Object meta(Msg m, String key) {
        return m.getMetadata() == null ? null : m.getMetadata().get(key);
    }

    private static MessageRole parseRole(String role) {
        if (role == null) {
            return MessageRole.USER;
        }
        switch (role.toLowerCase()) {
            case "system":
                return MessageRole.SYSTEM;
            case "assistant":
                return MessageRole.ASSISTANT;
            case "tool":
                return MessageRole.TOOL;
            case "function":
                return MessageRole.FUNCTION;
            default:
                return MessageRole.USER;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
