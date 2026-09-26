package com.zifang.z.bot.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.bot.agent.BotAgent;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * p28a §1.1 —— 台账里<b>每一条路由</b>的真进程断言：真起 JDK HttpServer，真发一次请求，
 * 判完状态码再判 body 形状（形状由台账自己声明的原子串驱动，不在测试里另抄一份清单）。
 *
 * <p>另有三组"实数据对拍"：库里有 N 行 ⇒ 接口给 N 条。工单点名的 {@code IPage}/{@code total}
 * 那类恒 0 字段由 {@link #countOrTotalFieldsNeverLie()} 做全表普查，遇到就点名。</p>
 */
public class HttpRouteShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONSOLE_RESOURCE = "/web/index.html";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private P28HttpFixture fx;

    @Before
    public void setUp() throws Exception {
        fx = P28HttpFixture.start(tmp);
    }

    @After
    public void tearDown() {
        if (fx != null) {
            fx.close();
        }
    }

    // ===== 全表：每条路由一支真请求 + 形状对账 =====

    @Test
    public void everyLedgerRouteAnswersItsDeclaredShape() throws Exception {
        seedState();
        List<String> checked = new ArrayList<String>();
        for (HttpChannel.Route r : HttpChannel.routes()) {
            RequestSpec spec = requestFor(r);
            P28HttpFixture.Response resp = fx.call(r.method(), spec.pathAndQuery, spec.body);
            assertEquals("台账行 " + r + " 的状态码不是声明的那个", spec.expectedStatus, resp.code);
            assertShape(r, resp, spec);
            checked.add(r.method() + " " + r.path());
        }
        assertEquals("台账有 " + HttpChannel.routes().size() + " 行，真请求只打了 " + checked.size()
                + " 行 —— 分母不许缺", HttpChannel.routes().size(), checked.size());
    }

    // ===== 实数据对拍：库里有 N 行 ⇒ 接口给 N 条 =====

    @Test
    public void sessionsRowsEqualTheStoreRows() throws Exception {
        assertEquals("空库就该回空数组（不是 {sessions:[]} 那种包一层的假形状）",
                "[]", fx.call("GET", "/api/sessions", null).body);

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            P28HttpFixture.Response created = fx.call("POST", "/api/sessions", null);
            assertEquals(200, created.code);
            ids.add(onlyText(created.body, "id"));
        }

        int onDisk = readJsonArray(new File(fx.sessionDir, "_index.json")).size();
        JsonNode served = JSON.readTree(fx.call("GET", "/api/sessions", null).body);
        assertTrue("/api/sessions 顶层必须是数组，不是被包了一层: " + served.getNodeType(), served.isArray());
        assertEquals("库里有 " + onDisk + " 行会话 ⇒ 接口必须给 " + onDisk + " 条", onDisk, served.size());
        assertEquals("库里 3 行新建 + 夹具自带 = 接口条数", Integer.valueOf(3), Integer.valueOf(ids.size()));
        List<String> servedIds = new ArrayList<String>();
        for (JsonNode row : served) {
            servedIds.add(row.get("id").asText());
        }
        assertTrue("接口回的行 id 必须是库里那几条: served=" + servedIds + " ids=" + ids,
                servedIds.containsAll(ids));
        // 每行的列名与列序都在台账里
        assertRowFields("/api/sessions", served, "id", "title", "createdAt", "messageCount");
        List<String> order = new ArrayList<String>();
        Iterator<String> fn = served.get(0).fieldNames();
        while (fn.hasNext()) {
            order.add(fn.next());
        }
        assertEquals("列序也是契约（前端按它渲染）",
                "id,title,createdAt,messageCount", String.join(",", order));
        assertEquals("新会话的 messageCount 该是 0（库里没消息）", 0,
                served.get(served.size() - 1).get("messageCount").asInt());
    }

    @Test
    public void sessionMessagesRowsEqualTheSessionFileRows() throws Exception {
        String id = onlyText(fx.call("POST", "/api/sessions", null).body, "id");
        // 先走一次带工具调用的对话，好让 tool_call / tool_result 两种行都真落到库里
        fx.llm.scriptToolCall("echo", "{\"message\":\"ping\"}");
        fx.llm.script("P28-MEMORY-KEEP");
        fx.call("POST", "/bot/chat", "{\"message\":\"p28 对拍\"}");

        File sessionFile = new File(fx.sessionDir, id + ".json");
        assertTrue("聊天没把消息落到 " + sessionFile, sessionFile.isFile());
        int onDisk = readJsonArray(sessionFile).size();

        JsonNode served = JSON.readTree(fx.call("GET", "/api/session/messages?id=" + id, null).body);
        assertTrue(served.isArray());
        assertEquals("库里 " + onDisk +  " 行消息 ⇒ 接口必须给 " + onDisk + " 条", onDisk, served.size());
        assertRowFields("/api/session/messages", served,
                "role", "content", "toolCalls", "toolCallId", "contentType", "toolName", "isFinal");
        // 顺序也是契约：接口第 0 条的角色必须等于库文件第 0 行的角色
        assertEquals("行序必须跟库里一致",
                served.get(0).get("role").asText(),
                normalizeRole(readJsonArray(sessionFile).get(0).get("role").asText()));
    }

    @Test
    public void cronCountEqualsJobsOnDisk() throws Exception {
        JsonNode empty = JSON.readTree(fx.call("GET", "/api/cron", null).body);
        assertEquals("空 cron 目录 ⇒ count 必须是 0（不是缺字段）", 0, empty.get("count").asInt());
        assertEquals("空 cron 目录 ⇒ jobs 必须是空数组", 0, empty.get("jobs").size());

        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            P28HttpFixture.Response add = fx.call("POST", "/api/cron",
                    "{\"action\":\"add\",\"name\":\"p28-" + i + "\",\"prompt\":\"干活\","
                            + "\"schedule\":\"every 30m\"}");
            assertEquals(add.toString(), 200, add.code);
            JsonNode job = JSON.readTree(add.body).get("job");
            assertNotNull("add 必须把整行 job 回出来: " + add.body, job);
            ids.add(job.get("id").asText());
        }

        File jobsFile = new File(fx.cronDir, "jobs.json");
        assertTrue("cron 没落盘到 " + jobsFile, jobsFile.isFile());
        int onDisk = readJsonArray(jobsFile).size();

        JsonNode list = JSON.readTree(fx.call("GET", "/api/cron", null).body);
        assertEquals("库里有 " + onDisk + " 条任务 ⇒ count 必须是 " + onDisk, onDisk, list.get("count").asInt());
        assertEquals("count 与 jobs 数组长度必须自洽（恒 0 那类病就在这）",
                list.get("count").asInt(), list.get("jobs").size());
        List<String> servedIds = new ArrayList<String>();
        for (JsonNode row : list.get("jobs")) {
            servedIds.add(row.get("id").asText());
        }
        assertTrue("接口给的 id 必须是库里那几条: " + servedIds, servedIds.containsAll(ids));
        assertRowFields("/api/cron.jobs", list.get("jobs"),
                "id", "name", "prompt", "schedule", "enabled", "lastRun", "lastResult");
    }

    @Test
    public void toolsRowsEqualTheToolkit() throws Exception {
        JsonNode served = JSON.readTree(fx.call("GET", "/bot/tools", null).body);
        int inToolkit = fx.agent.getToolkit().getAllTools().size();
        assertEquals("工具表有 " + inToolkit + " 个 ⇒ /bot/tools 必须给 " + inToolkit + " 行",
                inToolkit, served.size());
        List<String> names = new ArrayList<String>();
        for (JsonNode row : served) {
            names.add(row.get("name").asText());
        }
        assertTrue("工具名必须真来自 Toolkit: " + names,
                names.containsAll(java.util.Arrays.asList(P28HttpFixture.TOOL_NAMES)));
        assertRowFields("/bot/tools", served, "name", "description");
    }

    @Test
    public void skillListCountEqualsSkillsOnDisk() throws Exception {
        writeSkill("p28-alpha");
        writeSkill("p28-beta");

        JsonNode served = JSON.readTree(fx.call("GET", "/api/skill/list", null).body);
        int dirs = 0;
        File[] children = fx.skillsDir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory() && new File(c, "SKILL.md").isFile()) {
                    dirs++;
                }
            }
        }
        assertEquals("磁盘上有 " + dirs + " 个技能 ⇒ count 必须是 " + dirs, dirs, served.get("count").asInt());
        assertEquals("count 与 skillCodes 必须自洽",
                served.get("count").asInt(), served.get("skillCodes").size());
        List<String> codes = new ArrayList<String>();
        for (JsonNode n : served.get("skillCodes")) {
            codes.add(n.asText());
        }
        assertTrue("技能 code 必须真来自磁盘: " + codes, codes.contains("p28-alpha"));
        assertTrue("技能 code 要按字典序（台账消费方按它做前缀匹配）",
                codes.equals(sortedCopy(codes)));
    }

    @Test
    public void modelsCountEqualsProviderCatalog() throws Exception {
        JsonNode served = JSON.readTree(fx.call("GET", "/api/models", null).body);
        assertEquals("替身供应商有 " + P28HttpFixture.STUB_MODEL_COUNT + " 个模型 ⇒ count 必须同数",
                P28HttpFixture.STUB_MODEL_COUNT, served.get("count").asInt());
        assertEquals("count 与 models 数组必须自洽",
                served.get("count").asInt(), served.get("models").size());
        List<String> ids = new ArrayList<String>();
        for (JsonNode row : served.get("models")) {
            ids.add(row.get("id").asText());
        }
        assertTrue("模型 id 必须真来自供应商目录: " + ids,
                ids.containsAll(java.util.Arrays.asList(P28HttpFixture.STUB_MODEL_IDS)));
        assertRowFields("/api/models.models", served.get("models"),
                "id", "displayName", "provider", "contextWindow", "maxOutputTokens", "capabilities");
        assertTrue("fetchedAt 不许恒 0（0 = 从没真拉过）", served.get("fetchedAt").asLong() > 0L);
        assertFalse("第一次拉完不该标 stale", served.get("stale").asBoolean());
    }

    @Test
    public void countOrTotalFieldsNeverLie() throws Exception {
        seedState();
        Map<String, JsonNode> bodies = new LinkedHashMap<String, JsonNode>();
        for (HttpChannel.Route r : HttpChannel.routes()) {
            if (r.shape() != HttpChannel.BodyShape.JSON_ARRAY && r.shape() != HttpChannel.BodyShape.JSON_OBJECT) {
                continue;
            }
            RequestSpec spec = requestFor(r);
            P28HttpFixture.Response resp = fx.call(r.method(), spec.pathAndQuery, spec.body);
            if (resp.code == 200) {
                bodies.put(r.method() + " " + r.path(), JSON.readTree(resp.body));
            }
        }
        List<String> lies = new ArrayList<String>();
        for (Map.Entry<String, JsonNode> e : bodies.entrySet()) {
            collectCountTotalLies(e.getKey(), e.getValue(), e.getValue(), lies);
        }
        assertTrue("有接口的 count/total 与它自己带的数组长度不一致（恒 0 那类病）:\n"
                + String.join("\n", lies), lies.isEmpty());
        assertTrue("普查一条都没扫到 = 尺空转（台账里至少有 3 个带 count 的接口）",
                lies.size() >= 0 && countBearingEndpoints(bodies) >= 3);
    }

    // ===== 会话写口的判词（本期修的 D-P28-4：switch/delete 原先恒 ok:true） =====

    @Test
    public void switchAndDeleteTellTheTruthAboutUnknownIds() throws Exception {
        String id = onlyText(fx.call("POST", "/api/sessions", null).body, "id");

        P28HttpFixture.Response ghost = fx.call("POST", "/api/session/switch",
                "{\"id\":\"session_19700101_not_there\"}");
        assertEquals("切到库里没有的 id 必须 404，不能再一口 ok:true: " + ghost.body, 404, ghost.code);
        assertFalse(JSON.readTree(ghost.body).get("ok").asBoolean());

        P28HttpFixture.Response del = fx.call("POST", "/api/session/delete",
                "{\"id\":\"session_19700101_not_there\"}");
        assertEquals("删库里没有的 id 必须 404: " + del.body, 404, del.code);

        P28HttpFixture.Response twice = fx.call("POST", "/api/session/delete", "{\"id\":\"" + id + "\"}");
        assertEquals("真存在的会话要能删掉: " + twice, 200, twice.code);
        assertEquals("同一个 id 删第二次必须说没有，而不是继续说成功",
                404, fx.call("POST", "/api/session/delete", "{\"id\":\"" + id + "\"}").code);
        assertFalse(fx.call("GET", "/api/sessions", null).body.contains(id));
    }

    @Test
    public void missingIdOnWriteEndpointsStaysFourHundred() throws Exception {
        assertEquals(400, fx.call("POST", "/api/session/switch", "{}").code);
        assertEquals(400, fx.call("POST", "/api/session/delete", "{}").code);
    }

    // ===== 缺省绑回环：整表的门 =====

    @Test
    public void defaultBindIsLoopbackAndOptInHostIsHonoured() throws Exception {
        assertTrue("缺省必须绑回环: " + fx.channel.getBindAddress(),
                fx.channel.getBindAddress().isLoopbackAddress());
        // 一个测试里起两个服务：TemporaryFolder 同名目录会给第二次直接拒，所以 ns 必须给
        P28HttpFixture wide = P28HttpFixture.start(tmp, "127.0.0.1", "-explicit-loopback");
        try {
            assertTrue(wide.channel.getBindAddress().isLoopbackAddress());
        } finally {
            wide.close();
        }
        // 显式 opt-in 才允许通配 —— 这里只算地址，不真开监听（红线：只连 127.0.0.1）
        assertTrue("显式 --host 0.0.0.0 必须被 honour 成通配，否则 opt-in 是假的",
                ChannelBind.resolve("0.0.0.0").getHostAddress().equals("0.0.0.0"));
        assertTrue("null host 必须落回环", ChannelBind.resolve(null).isLoopbackAddress());
    }

    // ===== 形状契约求值（原子串来自台账，不在测试里重抄） =====

    private static void assertShape(HttpChannel.Route r, P28HttpFixture.Response resp, RequestSpec spec)
            throws IOException {
        String body = resp.body;
        String ct = resp.contentType == null ? "" : resp.contentType;
        JsonNode json = null;
        if (r.shape() == HttpChannel.BodyShape.JSON_ARRAY || r.shape() == HttpChannel.BodyShape.JSON_OBJECT) {
            assertTrue(r + " 的 Content-Type 必须是 JSON，实到 " + ct, ct.startsWith("application/json"));
            json = JSON.readTree(body);
        }
        for (String atom : r.fields().split(";")) {
            if (atom.isEmpty()) {
                continue;
            }
            if (atom.startsWith("sse-events:")) {
                assertEquals(r + " 的形状是 SSE_STREAM 却走了 JSON 通道",
                        HttpChannel.BodyShape.SSE_STREAM, r.shape());
                assertTrue(r + " 的 Content-Type 必须是 text/event-stream，实到 " + ct,
                        ct.startsWith("text/event-stream"));
                continue;
            }
            switch (atom) {
                case "nonempty":
                    assertTrue(r + " 响应体是空的", body.length() > 0);
                    break;
                case "array":
                    assertTrue(r + " 顶层不是数组（被包了一层就算形状不符）: " + json.getNodeType(),
                            json.isArray());
                    break;
                case "object":
                    assertTrue(r + " 顶层不是对象: " + json.getNodeType(), json.isObject());
                    break;
                default:
                    applyFieldAtom(r, atom, body, json, resp);
            }
        }
        if (spec.mustContain != null) {
            assertTrue(r + " 少了这一截: " + spec.mustContain, body.contains(spec.mustContain));
        }
    }

    private static void applyFieldAtom(HttpChannel.Route r, String atom, String body, JsonNode json,
                                       P28HttpFixture.Response resp) {
        if (atom.startsWith("literal:")) {
            for (String needle : atom.substring("literal:".length()).split(",")) {
                assertTrue(r + " 响应里没有 " + needle + " —— " + body, body.contains(needle));
            }
        } else if (atom.startsWith("key:")) {
            for (String key : atom.substring("key:".length()).split(",")) {
                assertTrue(r + " 的文本里没有 " + key + "= —— " + body, body.contains(key + "="));
            }
        } else if (atom.startsWith("resource:")) {
            String res = atom.substring("resource:".length());
            int bytes = ctLengthOf(res);
            assertTrue(r + " 指向的资源读不到: " + res, bytes > 0);
            assertTrue(r + " 回的不是 HTML（逐字节等值由 HttpRouteLedgerTest 负责）: "
                    + body.substring(0, Math.min(60, body.length())), body.startsWith("<!DOCTYPE html>"));
            // 中文按 UTF-8 是多字节，字符数只能对上"不超过字节数"这条下界
            assertTrue(r + " 响应字符数(" + body.length() + ") 大于资源字节数(" + bytes + ")",
                    body.length() <= bytes);
            assertEquals(r + " 的资源路径与产品常量不一致", CONSOLE_RESOURCE, res);
        } else if (atom.startsWith("fields:")) {
            for (String field : atom.substring("fields:".length()).split(",")) {
                assertTrue(r + " 缺顶层字段 " + field + " —— " + body, json.get(field) != null);
            }
        } else if (atom.startsWith("count-of:")) {
            String key = atom.substring("count-of:".length());
            JsonNode count = json.get("count");
            JsonNode arr = json.get(key);
            assertNotNull(r + " 要断言 count 与 " + key + " 自洽，可 " + key + " 不存在 —— " + body, arr);
            assertNotNull(r + " 没有 count 字段 —— " + body, count);
            assertEquals(r + " 的 count(" + count.asInt() + ") 与数组 " + key
                    + " 的长度(" + arr.size() + ") 不一致 —— " + body, count.asInt(), arr.size());
        } else if (atom.startsWith("rowfields:")) {
            String[] parts = atom.substring("rowfields:".length()).split(":", 2);
            JsonNode rows = "-".equals(parts[0]) ? json : json.get(parts[0]);
            assertNotNull(r + " 没有数组 " + parts[0] + " —— " + body, rows);
            assertTrue(r + " 的数组 " + parts[0] + " 是空的，列名对拍不出来", rows.size() > 0);
            assertRowFields(r + " 的 " + parts[0], rows, parts[1].split(","));
        } else {
            fail(r + " 的形状契约里有不认识的原子: " + atom);
        }
    }

    private static int ctLengthOf(String resource) {
        try {
            return P28HttpFixture.readAll(HttpChannel.class.getResourceAsStream(resource)).length;
        } catch (IOException e) {
            throw new AssertionError("读不到资源 " + resource, e);
        }
    }

    /**
     * 列名全集对账：台账声明的列 == 实际出现过的列（集合，不看顺序 —— 不同行的列子集不一样，
     * 例如 {@code /api/session/messages} 的 toolName 只在 tool_call 行里出现）。
     * 三条都要成立：声明的列真出现过（不空头支票）、实际出现的列都在声明里（不夹带）、
     * 至少一列（不是空对象数组）。
     */
    private static void assertRowFields(String who, JsonNode rows, String... expected) {
        assertTrue(who + " 必须是数组", rows.isArray());
        assertTrue(who + " 空数组测不出列名", rows.size() > 0);
        java.util.Set<String> declared = new java.util.LinkedHashSet<String>(
                java.util.Arrays.asList(expected));
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (JsonNode row : rows) {
            Iterator<String> it = row.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                assertTrue(who + " 出现了台账没声明的列 " + name + " —— " + row, declared.contains(name));
                seen.add(name);
            }
        }
        for (String name : declared) {
            assertTrue(who + " 声明了列 " + name + " 但没有一行真的带它（空头支票）", seen.contains(name));
        }
        assertEquals(who + " 的列名全集与台账声明不一致", declared, seen);
    }

    // ===== 每行的请求规格（分母仍来自台账：没写规格的行直接红） =====

    private static final class RequestSpec {
        final String pathAndQuery;
        final String body;
        final int expectedStatus;
        final String mustContain;

        RequestSpec(String pathAndQuery, String body, int expectedStatus, String mustContain) {
            this.pathAndQuery = pathAndQuery;
            this.body = body;
            this.expectedStatus = expectedStatus;
            this.mustContain = mustContain;
        }
    }

    private String seededSessionId;

    private RequestSpec requestFor(HttpChannel.Route r) {
        String path = r.path();
        if ("GET".equals(r.method())) {
            if ("/api/session/messages".equals(path)) {
                assertNotNull("台账循环没先建会话就读消息，数组必空、列名对不出来", seededSessionId);
                return new RequestSpec(path + "?id=" + seededSessionId, null, 200, null);
            }
            return new RequestSpec(path, null, 200, null);
        }
        if ("/bot/chat".equals(path) || "/bot/chat/stream".equals(path)) {
            return new RequestSpec(path, "{\"message\":\"p28 形状对账\"}", 200, null);
        }
        if ("/bot/confirm".equals(path)) {
            return new RequestSpec(path, "{\"toolName\":\"echo\",\"argsJson\":\"{}\"}", 200, null);
        }
        if ("/bot/steer".equals(path)) {
            return new RequestSpec(path, "p28 steer", 200, null);
        }
        if ("/bot/stop".equals(path)) {
            return new RequestSpec(path, null, 200, null);
        }
        if ("/api/session/switch".equals(path) || "/api/session/delete".equals(path)) {
            // 写口的判词现在会回读库，所以台账循环必须打一个真存在的 id
            assertNotNull("台账循环没先建会话就打通口", seededSessionId);
            return new RequestSpec(path, "{\"id\":\"" + seededSessionId + "\"}", 200, null);
        }
        if ("/api/sessions".equals(path)) {
            return new RequestSpec(path, null, 200, "\"ok\":true");
        }
        if ("/api/cron".equals(path)) {
            return new RequestSpec(path, "{\"action\":\"add\",\"name\":\"p28 形状\",\"prompt\":\"x\","
                    + "\"schedule\":\"every 45m\"}", 200, null);
        }
        if ("/api/agent/register".equals(path)) {
            return new RequestSpec(path, "{}", 200, null);
        }
        if ("/api/skill/push".equals(path) || "/api/skill/sync".equals(path)) {
            return new RequestSpec(path, null, 200, null);
        }
        throw new AssertionError("台账新增了一行 " + r + "，但这里没给它请求规格 —— 分母不许缺");
    }

    private void seedState() throws Exception {
        seededSessionId = onlyText(fx.call("POST", "/api/sessions", null).body, "id");
        // 种子会话里必须有一次真工具调用：台账给 /api/session/messages 声明了 toolCalls/toolName
        // 这些列，只发一条纯文本的话这些列根本不会落地，形状断言就成了空跑。
        fx.llm.scriptToolCall("echo", "{\"message\":\"p28 seed\"}");
        fx.llm.script("P28-SEED-REPLY");
        fx.call("POST", "/bot/chat", "{\"message\":\"p28 seed\"}");
        fx.llm.script("P28-SEED-REPLY-2");
        fx.call("POST", "/bot/chat", "{\"message\":\"p28 seed 2\"}");
        // 台账里 GET /api/cron 排在 POST /api/cron 之前：不先塞一条任务，GET 那行的 jobs[] 就是空的，
        // "列名对拍"会退化成空跑（正是工单警告的"200 不算证据"那一类）。
        assertEquals("种子 cron 没建起来", 200, fx.call("POST", "/api/cron",
                "{\"action\":\"add\",\"name\":\"p28 seed\",\"prompt\":\"x\","
                        + "\"schedule\":\"every 45m\"}").code);
    }

    // ===== 小工具 =====

    private static String normalizeRole(Object raw) {
        if (raw == null) {
            return "user";
        }
        return raw.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> sortedCopy(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        java.util.Collections.sort(out);
        return out;
    }

    private static JsonNode readJsonArray(File f) throws IOException {
        assertTrue("文件不存在: " + f, f.isFile());
        JsonNode node = JSON.readTree(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertTrue(f + " 不是 JSON 数组", node.isArray());
        return node;
    }

    private void writeSkill(String name) throws IOException {
        File dir = new File(fx.skillsDir, name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("建不了 " + dir);
        }
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: p28 的技能\nversion: 1.0.0\n---\n正文。\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String onlyText(String json, String key) throws IOException {
        JsonNode node = JSON.readTree(json).get(key);
        assertNotNull("响应里没有 " + key + " —— " + json, node);
        return node.asText();
    }

    private static int countBearingEndpoints(Map<String, JsonNode> bodies) {
        int n = 0;
        for (JsonNode node : bodies.values()) {
            if (node != null && node.isObject() && node.get("count") != null) {
                n++;
            }
        }
        return n;
    }

    /** 找"恒 0 / 与自身数组不一致"的计数口：对象里凡是 count|total|size，都要与同层某个数组等长。 */
    private static void collectCountTotalLies(String who, JsonNode root, JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (("count".equals(e.getKey()) || "total".equals(e.getKey()) || "size".equals(e.getKey()))
                        && e.getValue().isInt()) {
                    JsonNode mate = siblingArray(node);
                    if (mate != null && mate.size() != e.getValue().asInt()) {
                        out.add(who + "." + e.getKey() + "=" + e.getValue().asInt()
                                + " 而同层数组长度=" + mate.size());
                    }
                }
            }
            it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                collectCountTotalLies(who + "." + e.getKey(), root, e.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectCountTotalLies(who + "[" + i + "]", root, node.get(i), out);
            }
        }
    }

    private static JsonNode siblingArray(JsonNode obj) {
        JsonNode best = null;
        Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isArray() && (best == null || e.getValue().size() >= best.size())) {
                best = e.getValue();
            }
        }
        return best;
    }

    /** 用来确认夹具真在用替身，而不是偷偷打了外网（getProvider() 带 Resilient 装饰层）。 */
    @Test
    public void fixtureNeverTouchesARealProvider() throws Exception {
        BotAgent agent = fx.agent;
        assertEquals("providerCode 必须是替身档: ", "p28stub", agent.getProviderCode());
        assertEquals("供应商名必须一路透到装饰层外面: ", "p28stub", agent.getProvider().name());
        int before = fx.llm.calls();
        fx.llm.script("P28-CALL-PROOF");
        fx.call("POST", "/bot/chat", "{\"message\":\"p28 计数\"}");
        assertTrue("打了真进程却没经过替身 provider（before=" + before + " after=" + fx.llm.calls()
                + "）⇒ 这一路可能出网", fx.llm.calls() > before);
    }
}
