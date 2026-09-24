package com.zifang.z.bot.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话持久化：{@code ~/.zbot/sessions/<id>.json} + {@code _index.json}。
 *
 * <p>文件格式与 z-opc 老 bot 兼容（role/content/contentType/toolName/toolCallId/isFinal/timestamp），
 * 老会话文件能被新 bot 直接读回来；tool_call 消息额外带 {@code toolCalls} 数组以承载
 * kernel {@link Msg} 的原生结构。</p>
 */
public class SessionManager {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File sessionDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<String, SessionData>();
    private String currentSessionId;

    public SessionManager() {
        this(new File(System.getProperty("user.home") + "/.zbot/sessions"));
    }

    public SessionManager(File sessionDir) {
        this.sessionDir = sessionDir;
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        loadIndex();
    }

    public synchronized String getCurrentSessionId() {
        if (currentSessionId == null) {
            currentSessionId = createSession();
        }
        return currentSessionId;
    }

    public synchronized String createSession() {
        long now = System.currentTimeMillis();
        // 同一毫秒内连续新建（TUI 里连按两次 /new）会撞 id，撞了就静默复用同一份 json
        String id = "session_" + now;
        for (int n = 1; sessions.containsKey(id); n++) {
            id = "session_" + now + "-" + n;
        }
        SessionData sd = new SessionData();
        sd.id = id;
        sd.createdAt = LocalDateTime.now().format(DTF);
        sd.title = "新会话";
        sessions.put(id, sd);
        currentSessionId = id;
        saveIndex();
        return id;
    }

    public synchronized void switchSession(String id) {
        if (sessions.containsKey(id)) {
            currentSessionId = id;
        }
    }

    public synchronized List<SessionSummary> listSessions() {
        List<SessionSummary> list = new ArrayList<SessionSummary>();
        for (SessionData sd : sessions.values()) {
            SessionSummary s = new SessionSummary();
            s.id = sd.id;
            s.title = sd.title;
            s.createdAt = sd.createdAt;
            s.messageCount = sd.messageCount;
            list.add(s);
        }
        Collections.sort(list, (a, b) -> nullSafeCompare(b.createdAt, a.createdAt));
        return list;
    }

    private static int nullSafeCompare(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    public synchronized void deleteSession(String id) {
        sessions.remove(id);
        File f = new File(sessionDir, id + ".json");
        if (f.exists()) {
            f.delete();
        }
        if (id.equals(currentSessionId)) {
            currentSessionId = null;
        }
        saveIndex();
    }

    public synchronized void saveMessages(String sessionId, List<Msg> messages) {
        SessionData sd = sessions.get(sessionId);
        if (sd == null || messages == null) {
            return;
        }
        try {
            List<Map<String, Object>> msgs = new ArrayList<Map<String, Object>>();
            for (Msg m : messages) {
                msgs.add(toPlain(m));
            }
            mapper.writeValue(new File(sessionDir, sessionId + ".json"), msgs);
            sd.messageCount = msgs.size();
            if ("新会话".equals(sd.title)) {
                String title = titleFrom(messages);
                if (title != null) {
                    sd.title = title;
                }
            }
            saveIndex();
        } catch (IOException e) {
            System.err.println("[SessionManager] saveMessages 失败: " + e.getMessage());
        }
    }

    public List<Msg> loadMessages(String sessionId) {
        File f = new File(sessionDir, sessionId + ".json");
        if (!f.exists()) {
            return new ArrayList<Msg>();
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(f,
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
        } catch (IOException e) {
            return new ArrayList<Msg>();
        }
    }

    public File getSessionDir() {
        return sessionDir;
    }

    // ===== 序列化 =====

    private Map<String, Object> toPlain(Msg m) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("role", m.getRole() == null ? "user" : m.getRole().name().toLowerCase());
        map.put("content", m.getContent());
        map.put("contentType", contentTypeOf(m));
        map.put("toolName", toolNameOf(m));
        map.put("toolCallId", m.getToolCallId());
        map.put("isFinal", Boolean.TRUE.equals(meta(m, "isFinal")));
        map.put("timestamp", LocalDateTime.now().toString());
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

    @SuppressWarnings("unchecked")
    private Msg fromPlain(Map<String, Object> map) {
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

    // ===== index =====

    private void loadIndex() {
        File idx = new File(sessionDir, "_index.json");
        if (idx.exists()) {
            try {
                List<SessionData> list = mapper.readValue(idx, new TypeReference<List<SessionData>>() {
                });
                for (SessionData sd : list) {
                    if (sd != null && sd.id != null) {
                        sessions.put(sd.id, sd);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        adoptOrphanSessionFiles();
    }

    /**
     * {@code serve} 与命令行 {@code chat} 共用 {@code ~/.zbot/sessions}，谁后写 {@code _index.json}
     * 就把对方的会话从索引里挤掉（消息文件还在，列表里却再也看不到）。启动时按消息文件收编这类会话。
     */
    private void adoptOrphanSessionFiles() {
        File[] files = sessionDir.listFiles((dir, name) ->
                name.startsWith("session_") && name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            final String id = f.getName().substring(0, f.getName().length() - ".json".length());
            if (sessions.containsKey(id)) {
                continue;
            }
            try {
                List<Msg> messages = loadMessages(id);
                SessionData sd = new SessionData();
                sd.id = id;
                sd.createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()),
                        ZoneId.systemDefault()).format(DTF);
                sd.messageCount = messages.size();
                String title = titleFrom(messages);
                sd.title = title == null ? "新会话" : title;
                sessions.put(id, sd);
            } catch (RuntimeException e) {
                System.err.println("[SessionManager] 收编会话 " + id + " 失败: " + e.getMessage());
            }
        }
    }

    /** 首条用户消息截 30 字当标题；没有用户消息时返回 {@code null}。 */
    private static String titleFrom(List<Msg> messages) {
        for (Msg m : messages) {
            if (m.getRole() == MessageRole.USER && m.getContent() != null && !m.getContent().isEmpty()) {
                return m.getContent().length() > 30 ? m.getContent().substring(0, 30) + "..." : m.getContent();
            }
        }
        return null;
    }

    private void saveIndex() {
        try {
            mapper.writeValue(new File(sessionDir, "_index.json"), new ArrayList<SessionData>(sessions.values()));
        } catch (IOException ignored) {
        }
    }

    // ===== data =====

    public static class SessionData {
        public String id;
        public String title;
        public String createdAt;
        public int messageCount;
    }

    public static class SessionSummary {
        public String id;
        public String title;
        public String createdAt;
        public int messageCount;
    }
}
