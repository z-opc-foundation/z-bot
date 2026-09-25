package com.zifang.z.bot.store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code state.db} 的<b>坏库自检与自愈</b>（P15）。
 *
 * <p>三级处置，从轻到重；只有最后一级才允许出现"新空库"，且必须先留下完整原件：</p>
 * <ol>
 *   <li><b>体检</b> {@link #probe}：{@code PRAGMA integrity_check} + FTS 读探针 +
 *       一条回滚掉的 {@code messages} 写探针。写探针不是多余的——FTS5 影子表烂掉时
 *       读表和 {@code integrity_check} 都是绿的，只有 INSERT 经触发器才暴露
 *       （hermes 在 {@code _db_opens_cleanly} 的注释里为这个坑专门写了 #50502）；</li>
 *   <li><b>就地修 FTS</b> {@link #dropFtsSchema}：只有 FTS 索引烂、 canonical 表完好时走这条，
 *       删掉 {@code messages_fts*} 与相关触发器，由 {@code StateStore} 重建并从 {@code messages} 回填。
 *       <b>零消息丢失</b>；</li>
 *   <li><b>备份 + 重建</b> {@link #backupAside}：文件真打不开/integrity 不过，才把原文件
 *       {@code rename} 成 {@code state.db.corrupt-<时间戳>}（连带 {@code -wal}/{@code -shm}），
 *       在原路径建全新空库。<b>备份失败就直接抛，绝不往下走</b>——"删掉再建" 的前提是原件还在。</li>
 * </ol>
 */
public final class DbRecovery {

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 体检结论。 */
    public static final class Probe {
        public boolean openable;
        public String integrityProblem;
        public boolean ftsBroken;
        public boolean ftsModuleMissing;
        public String reason;

        public boolean healthy() {
            return openable && integrityProblem == null && !ftsBroken;
        }

        /** 只有"FTS 烂但 canonical 表好"这一类能就地修，不必动原文件。 */
        public boolean repairableInPlace() {
            return openable && integrityProblem == null && ftsBroken;
        }

        @Override
        public String toString() {
            return healthy() ? "ok" : String.valueOf(reason);
        }
    }

    /**
     * 打开并体检。任何情况下都不抛异常（体检本身失败就是结论，不是错误）。
     */
    public static Probe probe(File dbFile, SqliteTx.Config cfg) {
        Probe p = new Probe();
        if (dbFile == null || !dbFile.exists() || dbFile.length() == 0L) {
            p.openable = true; // 新库/空库：没有可坏的东西
            return p;
        }
        Connection c = null;
        try {
            c = SqliteTx.open(dbFile, cfg);
            p.openable = true;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                List<String> bad = new ArrayList<String>();
                while (rs.next() && bad.size() < 3) {
                    String row = rs.getString(1);
                    if (row != null && !"ok".equalsIgnoreCase(row.trim())) {
                        bad.add(row);
                    }
                }
                if (!bad.isEmpty()) {
                    p.integrityProblem = join(bad);
                }
            }
            if (p.integrityProblem != null) {
                p.reason = "integrity_check: " + p.integrityProblem;
                return p;
            }
            // canonical 表读探针（表还不存在不算病：迁移阶梯会建）
            try (Statement st = c.createStatement()) {
                st.executeQuery("SELECT COUNT(*) FROM sessions").close();
            } catch (SQLException e) {
                if (!isMissingObject(e)) {
                    p.reason = "sessions 读失败: " + e.getMessage();
                    p.integrityProblem = p.reason;
                    return p;
                }
            }
            ftsProbe(c, p);
            if (!p.ftsBroken) {
                writeProbe(c, p);
            }
            return p;
        } catch (SQLException e) {
            p.openable = false;
            p.reason = "打不开: " + e.getMessage();
            return p;
        } finally {
            SqliteTx.close(c);
        }
    }

    /** FTS 读探针：跑一次真实搜索路径会走的 {@code MATCH}。 */
    private static void ftsProbe(Connection c, Probe p) {
        if (!tableExists(c, "messages_fts")) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.executeQuery("SELECT 1 FROM messages_fts WHERE messages_fts MATCH '\"\"' LIMIT 1").close();
        } catch (SQLException e) {
            if (isFtsModuleMissing(e)) {
                p.ftsModuleMissing = true; // 运行时没编 FTS5：降级能力，不是坏库
                return;
            }
            p.ftsBroken = true;
            p.reason = "fts 读: " + e.getMessage();
        }
    }

    /**
     * 写探针：插一条哨兵消息后回滚。触发器把 INSERT 同步进 FTS，所以影子表烂掉时这里必炸，
     * 而 {@code integrity_check} 和纯读都是绿的。
     */
    private static void writeProbe(Connection c, Probe p) {
        if (!tableExists(c, "messages")) {
            return;
        }
        boolean hasFts = tableExists(c, "messages_fts");
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            try {
                st.execute("INSERT INTO messages(session_id,idx,payload,timestamp,role)"
                        + " VALUES('__zbot_probe__',-1,'{}','1970-01-01 00:00:00','probe')");
            } finally {
                try {
                    st.execute("ROLLBACK");
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            if (hasFts && isCorruptionLike(e)) {
                p.ftsBroken = true;
                p.reason = "写探针(FTS 触发器): " + e.getMessage();
                return;
            }
            if (isBusy(e)) {
                return; // 别的进程在写：不是坏库
            }
            if (isMissingObject(e)) {
                return;
            }
            p.reason = "写探针: " + e.getMessage();
            p.integrityProblem = p.reason;
        }
    }

    /** 删掉 FTS 虚拟表与相关触发器（就地修的第一步；{@code messages} 的行一动不动）。 */
    public static int dropFtsSchema(File dbFile, SqliteTx.Config cfg) throws SQLException {
        Connection c = SqliteTx.open(dbFile, cfg);
        int dropped = 0;
        try {
            List<String> objects = new ArrayList<String>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT type, name FROM sqlite_master WHERE name LIKE 'messages_fts%'")) {
                while (rs.next()) {
                    objects.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                try {
                    for (String obj : objects) {
                        int bar = obj.indexOf('|');
                        String type = obj.substring(0, bar);
                        String name = obj.substring(bar + 1);
                        if ("trigger".equals(type)) {
                            st.execute("DROP TRIGGER IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
                            dropped++;
                        } else if ("table".equals(type)) {
                            st.execute("DROP TABLE IF EXISTS \"" + name.replace("\"", "\"\"") + "\"");
                            dropped++;
                        }
                    }
                    // 触发器可能不叫 messages_fts*（老库里叫 messages_ai）：一并清掉指向 FTS 的
                    for (String t : Arrays.asList("messages_ai", "messages_ad", "messages_au")) {
                        st.execute("DROP TRIGGER IF EXISTS " + t);
                    }
                    st.execute("COMMIT");
                } catch (SQLException e) {
                    try (Statement rb = c.createStatement()) {
                        rb.execute("ROLLBACK");
                    } catch (SQLException ignored) {
                    }
                    throw e;
                }
            }
            return dropped;
        } finally {
            SqliteTx.close(c);
        }
    }

    /**
     * 把可疑文件挪去 {@code <name>.corrupt-<yyyyMMdd_HHmmss>}（连带 {@code -wal}/{@code -shm}），
     * 返回备份路径。用 move 而不是 copy：一次 rename 同时完成"留原件"和"腾出原路径"，
     * 中途不会有"备份写了一半、原件又被覆盖"的窗口。
     *
     * @throws IOException 备份失败——调用方必须停手，不许重建
     */
    public static File backupAside(File dbFile, String reason) throws IOException {
        if (dbFile == null || !dbFile.exists()) {
            throw new IOException("没有可备份的文件: " + dbFile);
        }
        String stamp = LocalDateTime.now().format(STAMP);
        File backup = new File(dbFile.getParentFile(), dbFile.getName() + ".corrupt-" + stamp);
        int n = 0;
        while (backup.exists()) {
            backup = new File(dbFile.getParentFile(),
                    dbFile.getName() + ".corrupt-" + stamp + "-" + (++n));
        }
        try {
            moveAsideIfPresent(dbFile, "-wal", backup);
            moveAsideIfPresent(dbFile, "-shm", backup);
            Files.move(dbFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("拒绝重建 state.db：备份失败（" + reason + "）: " + e.getMessage(), e);
        }
        return backup;
    }

    private static void moveAsideIfPresent(File dbFile, String suffix, File backup) throws IOException {
        File sidecar = new File(dbFile.getParentFile(), dbFile.getName() + suffix);
        if (!sidecar.exists()) {
            return;
        }
        File target = new File(backup.getParentFile(), backup.getName() + suffix);
        Files.move(sidecar.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean isCorruptionLike(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) {
                continue;
            }
            String l = m.toLowerCase();
            if (l.contains("malformed") || l.contains("corrupt") || (l.contains("fts5") && l.contains("no such"))) {
                return true;
            }
        }
        return false;
    }

    static boolean isBusy(SQLException e) {
        return SqliteTx.isBusy(e);
    }

    /** "no such table/column/module"：缺对象，交给迁移阶梯补，不算坏库。 */
    static boolean isMissingObject(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("no such")) {
                return true;
            }
        }
        return false;
    }

    static boolean isFtsModuleMissing(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.toLowerCase().contains("no such module: fts5")) {
                return true;
            }
        }
        return false;
    }

    private static boolean tableExists(Connection c, String name) {
        try {
            return SchemaMigrations.hasTable(c, name);
        } catch (SQLException e) {
            return false;
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private DbRecovery() {
    }
}
