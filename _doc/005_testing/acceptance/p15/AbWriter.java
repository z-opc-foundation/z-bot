import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.bot.store.StateStore;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * P15 验收③的量具之一：<b>两个真 JVM</b> 同时往<b>同一个 state.db 的同一个 session</b>追加消息。
 *
 * <p>args: dbFile tag n beginImmediate busyTimeoutMs writeRetries startEpochMs</p>
 *
 * <p>读数和自述分开：appendMessage 的返回值只算"调用方看到的"，行数以另一个独立 JDBC 连接为准
 * （被测对象说没丢不算证据）。idx 空洞 = MAX(idx)+1 - COUNT(*)，非 0 就说明有行没落进去。</p>
 */
public class AbWriter {

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text, MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new HashMap<String, Object>());
    }

    public static void main(String[] args) throws Exception {
        File db = new File(args[0]);
        String tag = args[1];
        int n = Integer.parseInt(args[2]);
        boolean imm = Boolean.parseBoolean(args[3]);
        int busy = Integer.parseInt(args[4]);
        int retries = Integer.parseInt(args[5]);
        long startAt = Long.parseLong(args[6]);

        StateStore.Options o = StateStore.Options.defaults();
        o.beginImmediate(imm);
        o.busyTimeoutMillis(busy);
        o.writeRetries(retries);
        StateStore store = new StateStore(db, o);

        // 两个写进程刻意写同一个 session：逼出 MAX(idx)+1 的跨进程竞态（UNIQUE(session_id,idx) 是唯一防线）
        String sid = "ab-shared";
        store.upsertSession(sid, "shared A/B session", "stub-model", "stub", 0, 0L, 0L, "abtest");

        while (System.currentTimeMillis() < startAt) {
            Thread.sleep(1);
        }
        long t0 = System.currentTimeMillis();
        int ok = 0, lost = 0;
        for (int i = 0; i < n; i++) {
            int idx = store.appendMessage(sid, user(tag + "#" + i + " " + UUID.randomUUID()));
            if (idx >= 0) {
                ok++;
            } else {
                lost++;
            }
        }
        long ms = System.currentTimeMillis() - t0;

        // 独立连接读数：不信被测对象的自述
        Class.forName("org.sqlite.JDBC");
        long rows = -1, distinct = -1, idxSpan = -1, countCol = -1;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COUNT(DISTINCT content), COALESCE(MAX(idx)+1,0), "
                            + "(SELECT message_count FROM sessions WHERE id=?) FROM messages WHERE session_id=?")) {
                ps.setString(1, sid);
                ps.setString(2, sid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rows = rs.getLong(1);
                        distinct = rs.getLong(2);
                        idxSpan = rs.getLong(3);
                        countCol = rs.getLong(4);
                    }
                }
            }
        }
        System.out.println("RESULT tag=" + tag + " immediate=" + imm + " busy=" + busy + " retries=" + retries
                + " n=" + n + " ok=" + ok + " lost=" + lost
                + " rows=" + rows + " distinct=" + distinct + " idxSpan=" + idxSpan
                + " gaps=" + (idxSpan - rows) + " message_count=" + countCol
                + " lastError=" + store.lastError() + " ms=" + ms);
    }
}
