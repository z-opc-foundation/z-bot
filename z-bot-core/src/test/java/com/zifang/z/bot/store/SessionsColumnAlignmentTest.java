package com.zifang.z.bot.store;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P15b：{@code _doc/005_testing/acceptance/p15b/sessions_column_alignment.md} 的逐列理由表 ⟷ 我们盘上 schema 的
 * 一致性守卫。工单要求"表能机械复算、不许手敲 46 行"，那张表的**跨仓**方向由
 * {@code p15b_check.py}（需要 hermes 参照仓在场）钉；**本单测只钉不需要参照仓的那一半**，
 * 于是 {@code mvn -o test} 在任何机器上都跑得动。
 *
 * <p>三条守卫（任何一条被破都直接红，不为跑绿放宽）：</p>
 * <ol>
 *   <li>{@code 判定=要} 的行，"我们现有对应列"里的列名必须真的在 {@link SchemaMigrations#requiredColumns()}
 *       的 head 声明里（表不许承诺一个 schema 里没有的列）；</li>
 *   <li>head 里每一列都必须被某个 {@code 判定=要} 的行认领，否则必须在"我们独有列"白名单里
 *       —— <b>这一条就是红线 2 的守门人</b>：往后谁往 sessions 加了不在这张表里的列，单测当场红；</li>
 *   <li>{@code 判定=不要} 与 {@code 判定=占位} 的行，她那 32 个列名<b>一个都不许出现在我们 head 里</b>
 *       （"不加"这件事本身要能被机械证伪，不能只写在散文里）。</li>
 * </ol>
 */
public class SessionsColumnAlignmentTest {

    /** 相对仓库根的路径。测试的工作目录是 {@code z-bot-core}，所以往上找。 */
    private static final String DOC_REL = "_doc/005_testing/acceptance/p15b/sessions_column_alignment.md";

    /**
     * 我们 head 里<b>没有 hermes 对位列</b>的两列，逐条给出去向（不是豁免，是记账）：
     * <ul>
     *   <li>{@code updated_at}：我们的排序/prune cutoff 键，她只有 started_at/ended_at；真消费者是
     *       {@code StateStore.listSessions} 的 {@code ORDER BY updated_at}；</li>
     *   <li>{@code metadata}：P2 遗留列，实测全仓 0 处读写 ⇒ 我们自己违反红线 2 的存量，
     *       已在 EVIDENCE 记为待处置项，本期不删。</li>
     * </ul>
     */
    private static final Set<String> OURS_ONLY =
            new LinkedHashSet<String>(Arrays.asList("updated_at", "metadata"));

    // ===== 机械解析：只认 6 格的表体行，转义竖线按文字处理 =====

    private static File locateDoc() throws Exception {
        File dir = new File(System.getProperty("user.dir")).getCanonicalFile();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParentFile()) {
            File f = new File(dir, DOC_REL);
            if (f.isFile()) {
                return f;
            }
        }
        throw new AssertionError("找不到理由表 " + DOC_REL + "（从 "
                + System.getProperty("user.dir") + " 往上找 5 层）");
    }

    private static List<String[]> readRows() throws Exception {
        File f = locateDoc();
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : Files.readAllLines(f.toPath(), Charset.forName("UTF-8"))) {
            String s = line.trim();
            if (!s.matches("^\\|\\s*[a-z_]+\\s*\\|.*")) {
                continue;
            }
            List<String> cells = splitRow(s);
            assertEquals("理由表 " + DOC_REL + " 出现非 6 格行: " + s, 6, cells.size());
            if ("她的列名".equals(cells.get(0))) {
                continue;
            }
            rows.add(cells.toArray(new String[6]));
        }
        assertFalse("理由表一行都没解析到（解析器失效≠通过）", rows.isEmpty());
        return rows;
    }

    /** 按未转义的 `|` 切格；{@code \|} 算格内文字。 */
    private static List<String> splitRow(String s) {
        List<String> out = new ArrayList<String>();
        String body = s.startsWith("|") ? s.substring(1) : s;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (ch == '|') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** 从"有 `xxx`"这种格子里挑出我们的列名。 */
    private static Set<String> ourColumnsOf(String cell) {
        Set<String> out = new LinkedHashSet<String>();
        int i = 0;
        while (true) {
            int a = cell.indexOf('`', i);
            if (a < 0) {
                return out;
            }
            int b = cell.indexOf('`', a + 1);
            if (b < 0) {
                return out;
            }
            String tok = cell.substring(a + 1, b);
            if (tok.matches("[a-z_]+")) {
                out.add(tok);
            }
            i = b + 1;
        }
    }

    private static Map<String, List<String>> byVerdict(List<String[]> rows) {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        for (String v : new String[]{"要", "不要", "占位"}) {
            m.put(v, new ArrayList<String>());
        }
        for (String[] r : rows) {
            List<String> bucket = m.get(r[3]);
            assertNotNull("判定格出现未知取值: " + r[3] + " @ " + r[0], bucket);
            bucket.add(r[0]);
        }
        return m;
    }

    private static List<String> headSessionsColumns() {
        return SchemaMigrations.requiredColumns().get("sessions");
    }

    // ===== ① 表的判定分布本身 =====

    @Test
    public void alignmentTableHoldsExactlyHermesFortySixSessionsColumns() throws Exception {
        List<String[]> rows = readRows();
        assertEquals("理由表必须一列不差地把她的 sessions 列判完", 46, rows.size());
        Set<String> names = new LinkedHashSet<String>();
        for (String[] r : rows) {
            assertTrue("列名重复: " + r[0], names.add(r[0]));
        }
        // 这 46 个名字与参照仓逐行比对由 p15b_check.py 做；这里只保证行数/唯一性/判定合法
        Map<String, List<String>> by = byVerdict(rows);
        assertEquals("要", 14, by.get("要").size());
        assertEquals("不要", 14, by.get("不要").size());
        assertEquals("占位", 18, by.get("占位").size());
        assertEquals(46, by.get("要").size() + by.get("不要").size() + by.get("占位").size());
    }

    @Test
    public void everyPlaceholderRowNamesThePhaseThatUnlocksIt() throws Exception {
        for (String[] r : readRows()) {
            if (!"占位".equals(r[3])) {
                continue;
            }
            String whole = r[4] + " " + r[5];
            assertTrue("占位行必须点名哪一期解锁（禁写\"以后再说\"）: " + r[0],
                    whole.matches(".*\\bP\\d+\\b.*"));
            for (String banned : new String[]{"以后再说", "待定", "TBD", "看情况"}) {
                assertFalse("占位行 " + r[0] + " 写了禁用语 " + banned, whole.contains(banned));
            }
        }
    }

    @Test
    public void everyRejectedRowCarriesAnEmpiricalMarker() throws Exception {
        for (String[] r : readRows()) {
            if ("不要".equals(r[3])) {
                assertTrue("判\"不要\"必须带实测证据串: " + r[0], r[5].contains("实测"));
            }
        }
    }

    // ===== ② 要 / head 双向闭合（红线 2 的守门人）=====

    @Test
    public void everyWantedColumnActuallyExistsInHeadDeclaration() throws Exception {
        Set<String> head = new LinkedHashSet<String>(headSessionsColumns());
        for (String[] r : readRows()) {
            if (!"要".equals(r[3])) {
                continue;
            }
            Set<String> claimed = ourColumnsOf(r[2]);
            assertTrue("判定=要 却没写出我们对应的列: " + r[0], !claimed.isEmpty());
            for (String col : claimed) {
                assertTrue("表里判\"要\"的 " + r[0] + " 声称对应我们的 " + col
                        + "，但 head 声明里没有这一列", head.contains(col));
            }
        }
    }

    /**
     * 表里"消费者"格点名的 Java 类，必须真在主源码里被写到（M4 注入的"把 StateStore 写成 StateStor"
     * 就是冲这条来的 —— 这一条原本是只存在于 p15b_check.py 里的，杠② 把它逼进了单测）。
     */
    @Test
    public void everyWantedRowConsumerClassIsGreppableInMainSource() throws Exception {
        Set<String> identifiers = mainSourceIdentifiers();
        assertFalse("扫不到任何主源码标识符（空参照集不判通过）", identifiers.isEmpty());
        int checked = 0;
        List<String> missing = new ArrayList<String>();
        for (String[] r : readRows()) {
            if (!"要".equals(r[3])) {
                continue;
            }
            for (String cls : backtickedCamelNames(r[4])) {
                checked++;
                if (!identifiers.contains(cls)) {
                    missing.add(r[0] + "->" + cls);
                }
            }
        }
        assertTrue("一条消费者类都没挑出来 ⇒ 解析器失效，不算通过", checked > 0);
        assertTrue("判定=要 的消费者格里有主源码里打不到的类名: " + missing, missing.isEmpty());
    }

    private static List<String> backtickedCamelNames(String cell) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int a = cell.indexOf('`', i);
            if (a < 0) {
                return out;
            }
            int b = cell.indexOf('`', a + 1);
            if (b < 0) {
                return out;
            }
            String tok = cell.substring(a + 1, b);
            if (tok.matches("[A-Z][A-Za-z0-9]*")) {
                out.add(tok);
            }
            i = b + 1;
        }
    }

    /** 把 z-bot-core/src/main/java 下所有 .java 的标识符汇成一个集合（等价于 git grep -w，但不依赖 shell）。 */
    private static Set<String> mainSourceIdentifiers() throws Exception {
        File root = locateDoc().getParentFile();
        while (root != null && !new File(root, "z-bot-core/src/main/java").isDirectory()) {
            root = root.getParentFile();
        }
        assertNotNull("找不到 z-bot-core/src/main/java", root);
        Set<String> out = new LinkedHashSet<String>();
        collect(new File(root, "z-bot-core/src/main/java"), out);
        return out;
    }

    private static void collect(File dir, Set<String> out) throws Exception {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collect(f, out);
                continue;
            }
            if (!f.getName().endsWith(".java")) {
                continue;
            }
            String text = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")
                    .matcher(text);
            while (mt.find()) {
                out.add(mt.group());
            }
        }
    }

    @Test
    public void headDeclaresNoSessionsColumnThatTheTableDoesNotAccountFor() throws Exception {
        Set<String> claimed = new LinkedHashSet<String>();
        for (String[] r : readRows()) {
            if ("要".equals(r[3])) {
                claimed.addAll(ourColumnsOf(r[2]));
            }
        }
        assertFalse("解析器一个列名都没挑出来（空参照集不判通过）", claimed.isEmpty());
        Set<String> orphan = new TreeSet<String>();
        for (String col : headSessionsColumns()) {
            if (!claimed.contains(col) && !OURS_ONLY.contains(col)) {
                orphan.add(col);
            }
        }
        assertTrue("红线 2：head 里出现了不在这张理由表（判定=要）里的 sessions 列 ⇒ 加了 0 消费者的列。"
                + "孤儿列=" + orphan + "；要新增列请同时在理由表里给它一行判定", orphan.isEmpty());
        // 白名单本身也要被用满：OURS_ONLY 里的列若已不在 head（比如真删了），就该从注释里清掉
        Set<String> stale = new TreeSet<String>(OURS_ONLY);
        stale.removeAll(new LinkedHashSet<String>(headSessionsColumns()));
        assertTrue("OURS_ONLY 白名单里有 head 已不存在的列（记账过期）: " + stale, stale.isEmpty());
    }

    @Test
    public void rejectedAndDeferredHermesColumnsAreAllAbsentFromOurSchema() throws Exception {
        Set<String> head = new LinkedHashSet<String>(headSessionsColumns());
        List<String> leaked = new ArrayList<String>();
        int checked = 0;
        for (String[] r : readRows()) {
            if (!"不要".equals(r[3]) && !"占位".equals(r[3])) {
                continue;
            }
            checked++;
            // 她的列名若与我们的某一列同名 ⇒ 那一列必须是"要"，否则就是偷渡
            if (head.contains(r[0])) {
                leaked.add(r[0] + "[" + r[3] + "]");
            }
        }
        assertEquals("要核的 不要/占位 行应是 32 行", 32, checked);
        assertTrue("被判 不要/占位 的 hermes 列出现在了我们的 head 里（红线 2 违例）: " + leaked,
                leaked.isEmpty());
    }

    // ===== ③ 老库升级：不丢行 + 新列真被读到（单测层；真进程层见杠③ E2E）=====

    /** P2（{@code ae5aff7}）的原样 DDL —— 手抄字面量，不从生产代码取，否则"迁移"等于自证。 */
    private static final String[] PRE_P15_DDL = {
            "CREATE TABLE sessions ("
                    + " id TEXT PRIMARY KEY,"
                    + " title TEXT NOT NULL DEFAULT '新会话',"
                    + " source TEXT NOT NULL DEFAULT 'cli',"
                    + " model TEXT, provider TEXT,"
                    + " message_count INTEGER NOT NULL DEFAULT 0,"
                    + " tokens INTEGER NOT NULL DEFAULT 0,"
                    + " api_calls INTEGER NOT NULL DEFAULT 0,"
                    + " created_at TEXT NOT NULL,"
                    + " updated_at TEXT NOT NULL,"
                    + " metadata TEXT)",
            "CREATE TABLE messages ("
                    + " seq INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " idx INTEGER NOT NULL,"
                    + " role TEXT, content TEXT, content_type TEXT,"
                    + " tool_name TEXT, tool_call_id TEXT,"
                    + " payload TEXT NOT NULL,"
                    + " timestamp TEXT NOT NULL,"
                    + " UNIQUE(session_id, idx))",
            "CREATE TABLE session_model_usage ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " session_id TEXT NOT NULL,"
                    + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
                    + " api_calls INTEGER, ts TEXT NOT NULL)",
            "CREATE TABLE async_delegations ("
                    + " id TEXT PRIMARY KEY,"
                    + " task TEXT NOT NULL,"
                    + " status TEXT NOT NULL,"
                    + " reply TEXT NOT NULL DEFAULT '',"
                    + " created_at TEXT NOT NULL,"
                    + " finished_at TEXT)",
    };

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File buildPreP15Db() throws Exception {
        Class.forName("org.sqlite.JDBC");
        File f = new File(tmp.newFolder("p15b" + System.nanoTime()), "state.db");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement st = c.createStatement()) {
            for (String ddl : PRE_P15_DDL) {
                st.execute(ddl);
            }
            st.execute("INSERT INTO sessions(id,title,source,model,provider,message_count,tokens,"
                    + "api_calls,created_at,updated_at) VALUES('keep-1','留着','cli','m','p',"
                    + "2,10,1,'2024-01-02 10:00:00','2024-01-02 11:00:00')");
            st.execute("INSERT INTO sessions(id,title,source,model,provider,message_count,tokens,"
                    + "api_calls,created_at,updated_at) VALUES('keep-2','也留着','web','m','p',"
                    + "1,5,1,'2024-01-03 10:00:00','2024-01-03 11:00:00')");
            st.execute("INSERT INTO messages(session_id,idx,role,payload,timestamp) VALUES"
                    + "('keep-1',0,'user','{\"role\":\"user\"}','2024-01-02 10:00:00')");
            st.execute("INSERT INTO messages(session_id,idx,role,payload,timestamp) VALUES"
                    + "('keep-1',1,'assistant','{\"role\":\"assistant\"}','2024-01-02 10:00:01')");
        } finally {
            c.close();
        }
        return f;
    }

    private static List<String> pragmaColumns(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(sessions)")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    @Test
    public void preP15DatabaseUpgradesWithoutLosingRowsAndItsColumnsMatchHead() throws Exception {
        File f = buildPreP15Db();
        assertEquals("升级前（P2/v1 原样）sessions 该有 11 列", 11, pragmaColumns(f).size());

        StateStore store = new StateStore(f);
        SchemaMigrations.Report r = store.lastMigration();
        assertNotNull(r);
        assertEquals("老库是未登记的 v0", SchemaMigrations.UNVERSIONED, r.fromVersion);
        assertEquals(SchemaMigrations.HEAD_VERSION, r.toVersion);
        assertEquals("P15 加列台账就该是那 4 列: " + r.addedColumns,
                Arrays.asList("sessions.parent_session_id", "sessions.ended_at",
                        "sessions.end_reason", "sessions.archived"),
                r.addedColumns);

        List<String> live = pragmaColumns(f);
        assertEquals("升级后 PRAGMA table_info(sessions) 的列集必须与 head 声明逐字相等",
                new TreeSet<String>(headSessionsColumns()), new TreeSet<String>(live));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            assertTrue(rs.next());
            assertEquals("老库的行一行不许丢", 2, rs.getInt(1));
        }
    }

    @Test
    public void upgradedColumnsAreActuallyReadBackByProductionPaths() throws Exception {
        File f = buildPreP15Db();
        StateStore store = new StateStore(f);
        // 老库升级后，prune 的"已结束/在飞"闸门要真吃到 ended_at/archived 这两列
        assertEquals("未 end 之前两行都在飞，缺省 prune 一格不许删",
                2, store.listSessions(true).size());
        store.endSession("keep-1", "user-stop");
        store.archiveSession("keep-2", true);

        List<com.zifang.z.bot.store.StateStore.SessionRow> all = store.listSessions(true);
        assertEquals(2, all.size());
        com.zifang.z.bot.store.StateStore.SessionRow one = null;
        for (com.zifang.z.bot.store.StateStore.SessionRow row : all) {
            if ("keep-1".equals(row.id)) {
                one = row;
            }
        }
        assertNotNull("血统/终态列写进去要读得回来", one);
        assertNotNull("ended_at 该被真读回", one.endedAt);
        assertEquals("end_reason 该被真读回", "user-stop", one.endReason);
        assertFalse("archived 该被真读回", store.listSessions(false).isEmpty());
        assertEquals("archived=1 的行缺省不许出现在 list 里",
                1, store.listSessions(false).size());
        assertEquals("keep-1", store.listSessions(false).get(0).id);
    }

    @Test
    public void reopeningAnUpgradedDatabaseAddsNothingFurther() throws Exception {
        File f = buildPreP15Db();
        StateStore first = new StateStore(f);
        assertEquals(4, first.lastMigration().addedColumns.size());

        StateStore second = new StateStore(f);
        assertEquals("重开不许再加列（幂等）", 0, second.lastMigration().addedColumns.size());
        assertEquals("重开不许再跑阶梯", 0, second.lastMigration().appliedSteps.size());
        assertEquals(15, pragmaColumns(f).size());
        Connection probe = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try {
            assertTrue("head 自检必须过", SchemaMigrations.checkHead(probe).isEmpty());
        } finally {
            probe.close();
        }
    }
}
