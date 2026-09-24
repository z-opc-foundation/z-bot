package com.zifang.z.bot.store;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.session.MsgCodec;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code ~/.zbot/state.db} 会话持久化 — SQLite + WAL。
 *
 * <p>三张表：{@code sessions}（会话元信息）、{@code messages}（消息行，(session_id, idx) 唯一）、
 * {@code session_model_usage}（模型×任务记账）。FTS5 可用时建 {@code messages_fts} 虚拟表做全文
 * 检索，检测不到（老 sqlite / 驱动裁剪）就静默降级 LIKE 查询。</p>
 */
public class StateStore {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final File dbFile;
    private final boolean ftsEnabled;

    public StateStore(File dbFile) {
        this.dbFile = dbFile;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc 不在 classpath", e);
        }
        try (Connection c = connect()) {
            initSchema(c);
            this.ftsEnabled = initFts(c);
        } catch (SQLException e) {
            throw new IllegalStateException("state.db 初始化失败: " + e.getMessage(), e);
        }
    }

    public File getDbFile() {
        return dbFile;
    }

    /** FTS5 可用（全文检索走 MATCH）；false 表示 LIKE 降级。 */
    public boolean isFtsEnabled() {
        return ftsEnabled;
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    private void initSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS sessions ("
                    + " id TEXT PRIMARY KEY,"
                    + " title TEXT NOT NULL DEFAULT '新会话',"
                    + " source TEXT NOT NULL DEFAULT 'cli',"
                    + " model TEXT, provider TEXT,"
                    + " message_count INTEGER NOT NULL DEFAULT 0,"
                    + " tokens INTEGER NOT NULL DEFAULT 0,"
                    + " api_calls INTEGER NOT NULL DEFAULT 0,"
                    + " created_at TEXT NOT NULL,"
                    + " updated_at TEXT NOT NULL,"
                    + " metadata TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS messages ("
                    + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " idx INTEGER NOT NULL,"
                    + " role TEXT, content TEXT, content_type TEXT,"
                    + " tool_name TEXT, tool_call_id TEXT,"
                    + " payload TEXT NOT NULL,"
                    + " timestamp TEXT NOT NULL,"
                    + " UNIQUE(session_id, idx))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, idx)");
            st.execute("CREATE TABLE IF NOT EXISTS session_model_usage ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
                    + " api_calls INTEGER, ts TEXT NOT NULL)");
        }
    }

    private boolean initFts(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5("
                    + "session_id UNINDEXED, content)");
            st.execute("CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN"
                    + " INSERT INTO messages_fts(session_id, content) VALUES (new.session_id, new.content);"
                    + " END");
            return true;
        } catch (SQLException e) {
            System.err.println("[StateStore] FTS5 不可用，全文检索降级 LIKE: " + e.getMessage());
            return false;
        }
    }

    // ===== sessions =====

    public synchronized void upsertSession(String id, String title, String model, String provider,
                                           int messageCount, long tokens, long apiCalls) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (Connection c = connect()) {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sessions(id,title,model,provider,message_count,tokens,api_calls,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)"
                            + " ON CONFLICT(id) DO UPDATE SET title=excluded.title, model=excluded.model,"
                            + " provider=excluded.provider, message_count=excluded.message_count,"
                            + " tokens=excluded.tokens, api_calls=excluded.api_calls, updated_at=excluded.updated_at");
            ps.setString(1, id);
            ps.setString(2, title == null ? "新会话" : title);
            ps.setString(3, model);
            ps.setString(4, provider);
            ps.setInt(5, messageCount);
            ps.setLong(6, tokens);
            ps.setLong(7, apiCalls);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StateStore] upsertSession 失败: " + e.getMessage());
        }
    }

    public synchronized void deleteSession(String id) {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE session_id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            deleteFts(c, id);
        } catch (SQLException e) {
            System.err.println("[StateStore] deleteSession 失败: " + e.getMessage());
        }
    }

    private void deleteFts(Connection c, String sessionId) {
        if (!ftsEnabled) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages_fts WHERE session_id=?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized boolean sessionExists(String id) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sessions WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized List<SessionRow> listSessions() {
        List<SessionRow> out = new ArrayList<SessionRow>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,title,model,provider,message_count,tokens,api_calls,created_at,updated_at"
                             + " FROM sessions ORDER BY updated_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9)));
            }
        } catch (SQLException e) {
            System.err.println("[StateStore] listSessions 失败: " + e.getMessage());
        }
        return out;
    }

    // ===== messages =====

    /** 全量替换某会话的消息（与内存里 {@code ConversationMemory} 保持一一对应）。 */
    public synchronized void saveMessages(String sessionId, List<Msg> messages) {
        String now = java.time.LocalDateTime.now().toString();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM messages WHERE session_id=?")) {
                del.setString(1, sessionId);
                del.executeUpdate();
            }
            deleteFts(c, sessionId);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO messages(session_id,idx,role,content,content_type,tool_name,tool_call_id,payload,timestamp)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < messages.size(); i++) {
                    java.util.Map<String, Object> plain = MsgCodec.toPlain(messages.get(i));
                    ins.setString(1, sessionId);
                    ins.setInt(2, i);
                    ins.setString(3, str(plain.get("role")));
                    ins.setString(4, str(plain.get("content")));
                    ins.setString(5, str(plain.get("contentType")));
                    ins.setString(6, str(plain.get("toolName")));
                    ins.setString(7, str(plain.get("toolCallId")));
                    ins.setString(8, MAPPER.writeValueAsString(plain));
                    ins.setString(9, now);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            System.err.println("[StateStore] saveMessages 失败: " + e.getMessage());
        }
    }

    public synchronized List<Msg> loadMessages(String sessionId) {
        List<Msg> out = new ArrayList<Msg>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payload FROM messages WHERE session_id=? ORDER BY idx")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> plain = MAPPER.readValue(rs.getString(1),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                            });
                    Msg m = MsgCodec.fromPlain(plain);
                    if (m != null) {
                        out.add(m);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[StateStore] loadMessages 失败: " + e.getMessage());
        }
        return out;
    }

    // ===== 全文检索 =====

    /** 按关键词搜消息，返回命中 (session_id, 片段) 列表；FTS5 不可用时走 LIKE。 */
    public synchronized List<SearchHit> search(String keyword, int limit) {
        List<SearchHit> out = new ArrayList<SearchHit>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return out;
        }
        String kw = keyword.trim();
        try (Connection c = connect()) {
            if (ftsEnabled) {
                // FTS5 MATCH 语法对引号/运算符敏感，包成短语字面量
                String phrase = "\"" + kw.replace("\"", "\"\"") + "\"";
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT session_id, content FROM messages_fts WHERE messages_fts MATCH ? LIMIT ?")) {
                    ps.setString(1, phrase);
                    ps.setInt(2, limit);
                    collect(ps, out, kw);
                }
            }
            if (out.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT session_id, content FROM messages"
                                + " WHERE content LIKE ? ORDER BY seq DESC LIMIT ?")) {
                    ps.setString(1, "%" + kw + "%");
                    ps.setInt(2, limit);
                    collect(ps, out, kw);
                }
            }
        } catch (SQLException e) {
            System.err.println("[StateStore] search 失败: " + e.getMessage());
        }
        return out;
    }

    private static void collect(PreparedStatement ps, List<SearchHit> out, String kw) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String content = rs.getString(2);
                int at = content == null ? -1 : content.indexOf(kw);
                String snippet = at < 0 ? content
                        : content.substring(Math.max(0, at - 20),
                        Math.min(content.length(), at + kw.length() + 40));
                out.add(new SearchHit(rs.getString(1), snippet));
            }
        }
    }

    // ===== 统计 / 清理 =====

    public synchronized Stats stats() {
        Stats s = new Stats();
        try (Connection c = connect()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*), COALESCE(SUM(message_count),0), COALESCE(SUM(tokens),0),"
                            + " COALESCE(SUM(api_calls),0) FROM sessions")) {
                if (rs.next()) {
                    s.sessions = rs.getInt(1);
                    s.messages = rs.getLong(2);
                    s.tokens = rs.getLong(3);
                    s.apiCalls = rs.getLong(4);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT model, COUNT(*) FROM session_model_usage GROUP BY model")) {
                while (rs.next()) {
                    s.usageByModel.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("[StateStore] stats 失败: " + e.getMessage());
        }
        return s;
    }

    /** 删除 updated_at 早于 {@code beforeDays} 天前、消息数为 0 的空会话，返回删除条数。 */
    public synchronized int prune(int beforeDays) {
        String cutoff = java.time.LocalDateTime.now().minusDays(beforeDays)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM sessions WHERE updated_at < ? AND message_count = 0")) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StateStore] prune 失败: " + e.getMessage());
            return 0;
        }
    }

    /** 导出某会话全部消息为 JSONL（每行一条消息的 payload）；sessionId 为 null 时导全部。 */
    public synchronized List<String> exportJsonl(String sessionId) {
        List<String> out = new ArrayList<String>();
        String sql = sessionId == null
                ? "SELECT payload FROM messages ORDER BY session_id, idx"
                : "SELECT payload FROM messages WHERE session_id=? ORDER BY idx";
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (sessionId != null) {
                ps.setString(1, sessionId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("[StateStore] export 失败: " + e.getMessage());
        }
        return out;
    }

    // ===== 记账 =====

    public synchronized void recordUsage(String sessionId, String model,
                                         Integer promptTokens, Integer completionTokens, int apiCalls) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO session_model_usage(session_id,model,prompt_tokens,completion_tokens,api_calls,ts)"
                             + " VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, model);
            ps.setObject(3, promptTokens);
            ps.setObject(4, completionTokens);
            ps.setInt(5, apiCalls);
            ps.setString(6, java.time.LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[StateStore] recordUsage 失败: " + e.getMessage());
        }
    }

    // ===== 行类型 =====

    public static class SessionRow {
        public final String id;
        public final String title;
        public final String model;
        public final String provider;
        public final int messageCount;
        public final long tokens;
        public final long apiCalls;
        public final String createdAt;
        public final String updatedAt;

        public SessionRow(String id, String title, String model, String provider, int messageCount,
                          long tokens, long apiCalls, String createdAt, String updatedAt) {
            this.id = id;
            this.title = title;
            this.model = model;
            this.provider = provider;
            this.messageCount = messageCount;
            this.tokens = tokens;
            this.apiCalls = apiCalls;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static class SearchHit {
        public final String sessionId;
        public final String snippet;

        public SearchHit(String sessionId, String snippet) {
            this.sessionId = sessionId;
            this.snippet = snippet;
        }
    }

    public static class Stats {
        public int sessions;
        public long messages;
        public long tokens;
        public long apiCalls;
        public final java.util.Map<String, Long> usageByModel = new java.util.LinkedHashMap<String, Long>();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
