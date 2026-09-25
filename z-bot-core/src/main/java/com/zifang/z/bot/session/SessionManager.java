package com.zifang.z.bot.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.store.StateStore;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话持久化。两种后端：
 * <ul>
 *   <li>默认 JSON 模式：{@code <profile>/sessions/<id>.json} + {@code _index.json}，
 *       格式与 z-opc 老 bot 兼容；</li>
 *   <li>store 模式（P2 起）：传入 {@link StateStore} 后 SQLite {@code state.db} 是唯一事实来源，
 *       启动时把目录里遗留的 JSON 会话一次性收编进库（原文件保留）。</li>
 * </ul>
 */
public class SessionManager {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File sessionDir;
    private final StateStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<String, SessionData>();
    /** 实例级随机短标识，拼进新会话 id 防跨进程/同进程不同实例同毫秒撞 id。 */
    private final String instanceTag =
            java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    private String currentSessionId;

    /** 无 profile 上下文时的兜底档：跟着 {@code ZBOT_HOME}/{@code -Dzbot.home} 走，不再写死 {@code ~/.zbot}。 */
    public SessionManager() {
        this(new File(com.zifang.z.bot.config.BotConfig.defaultConfigDir(), "sessions"));
    }

    public SessionManager(File sessionDir) {
        this(sessionDir, null);
    }

    public SessionManager(File sessionDir, StateStore store) {
        this.sessionDir = sessionDir;
        this.store = store;
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        if (store != null) {
            migrateJsonSessionsIntoStore();
            for (StateStore.SessionRow row : store.listSessions()) {
                SessionData sd = new SessionData();
                sd.id = row.id;
                sd.title = row.title;
                sd.createdAt = row.createdAt;
                sd.messageCount = row.messageCount;
                sessions.put(row.id, sd);
            }
        } else {
            loadIndex();
        }
    }

    public StateStore getStore() {
        return store;
    }

    public synchronized String getCurrentSessionId() {
        if (currentSessionId == null) {
            currentSessionId = createSession();
        }
        return currentSessionId;
    }

    public synchronized String createSession() {
        long now = System.currentTimeMillis();
        // 时间戳 + 实例级随机段：防止两个进程或同进程不同实例在同一毫秒各 /new 撞 id 互相吞会话；
        // 同实例内连续新建（TUI 连按两次 /new）由去重循环兜底
        String id = "session_" + now + "-" + instanceTag;
        for (int n = 1; sessions.containsKey(id); n++) {
            id = "session_" + now + "-" + instanceTag + "-" + n;
        }
        SessionData sd = new SessionData();
        sd.id = id;
        sd.createdAt = LocalDateTime.now().format(DTF);
        sd.title = "新会话";
        sessions.put(id, sd);
        currentSessionId = id;
        if (store != null) {
            store.upsertSession(id, sd.title, null, null, 0, 0, 0);
        } else {
            saveIndex();
        }
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
        if (store != null) {
            store.deleteSession(id);
        }
        File f = new File(sessionDir, id + ".json");
        if (f.exists()) {
            f.delete();
        }
        if (id.equals(currentSessionId)) {
            currentSessionId = null;
        }
        if (store == null) {
            saveIndex();
        }
    }

    public synchronized void saveMessages(String sessionId, List<Msg> messages) {
        SessionData sd = sessions.get(sessionId);
        if (sd == null || messages == null) {
            return;
        }
        if ("新会话".equals(sd.title)) {
            String title = titleFrom(messages);
            if (title != null) {
                sd.title = title;
            }
        }
        sd.messageCount = messages.size();
        if (store != null) {
            store.saveMessages(sessionId, messages);
            store.upsertSession(sessionId, sd.title, null, null, sd.messageCount, 0, 0);
            return;
        }
        try {
            List<Map<String, Object>> plain = new ArrayList<Map<String, Object>>();
            for (Msg m : messages) {
                plain.add(MsgCodec.toPlain(m));
            }
            mapper.writeValue(new File(sessionDir, sessionId + ".json"), plain);
            saveIndex();
        } catch (IOException e) {
            System.err.println("[SessionManager] saveMessages 失败: " + e.getMessage());
        }
    }

    public List<Msg> loadMessages(String sessionId) {
        if (store != null) {
            return store.loadMessages(sessionId);
        }
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
                Msg m = MsgCodec.fromPlain(map);
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

    /** 把目录里遗留的 JSON 会话一次性收编进 state.db（只在库为空时执行，原文件保留）。 */
    private void migrateJsonSessionsIntoStore() {
        if (!store.listSessions().isEmpty()) {
            return;
        }
        File[] files = sessionDir.listFiles((dir, name) ->
                name.startsWith("session_") && name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - ".json".length());
            List<Msg> messages = loadMessagesFromJson(f);
            String createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(f.lastModified()),
                    ZoneId.systemDefault()).format(DTF);
            String title = titleFrom(messages);
            store.upsertSession(id, title == null ? "新会话" : title, null, null,
                    messages.size(), 0, 0);
            if (!messages.isEmpty()) {
                store.saveMessages(id, messages);
            }
            SessionData sd = new SessionData();
            sd.id = id;
            sd.title = title == null ? "新会话" : title;
            sd.createdAt = createdAt;
            sd.messageCount = messages.size();
            sessions.put(id, sd);
        }
    }

    private List<Msg> loadMessagesFromJson(File f) {
        if (!f.exists()) {
            return new ArrayList<Msg>();
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(f,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<Msg> out = new ArrayList<Msg>();
            for (Map<String, Object> map : raw) {
                Msg m = MsgCodec.fromPlain(map);
                if (m != null) {
                    out.add(m);
                }
            }
            return out;
        } catch (IOException e) {
            return new ArrayList<Msg>();
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
     * {@code serve} 与命令行 {@code chat} 共用同一个 profile 的 sessions 目录，谁后写 {@code _index.json}
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
                List<Msg> messages = loadMessagesFromJson(f);
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
