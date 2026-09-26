package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.bot.slash.CommandCatalog;
import com.zifang.z.bot.slash.SlashRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P19 · 命令表单源的 <b>ACP 消费端</b>：{@code session/update} 的
 * {@code available_commands_update} 帧必须吐 {@link CommandCatalog.Endpoint#ACP} 那一段。
 *
 * <p>放在 {@code acp} 包而不是并进 {@code slash/CommandSurfaceConsistencyTest}，是因为这一面要复用
 * {@code AcpFakes}（包私有）里的真连接替身；那张守卫测试的类注释里点了这个名，两面合起来才是四面齐。</p>
 *
 * <h2>帧形状不是凭记忆编的</h2>
 * 逐字段回权威 schema 取证（{@value #SCHEMA_PATH}）：
 * <ul>
 *   <li>{@code :2443} {@code AvailableCommandsUpdate} ⇒ 判别字线上键 {@code sessionUpdate}，
 *       值字面量 {@code "available_commands_update"}；</li>
 *   <li>{@code :2179—2181} 数组字段线上键 {@code availableCommands}；</li>
 *   <li>{@code :2138} {@code AvailableCommand{name,description,input}}（三字段无 alias）；</li>
 *   <li>{@code :1321} + {@code :1104} {@code input} 形如 {@code {"hint": "…"}}。</li>
 * </ul>
 * 调用时机对齐 hermes {@code acp_adapter/server.py:1714—1741}（广告排在 session/new 的
 * response <b>之后</b>），下面 {@link #advertisementIsQueuedAfterTheSessionNewResponse} 钉的就是这一条。
 */
public class AcpCommandAdvertisementTest {

    private static final String SCHEMA_PATH =
            "~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/schema.py";

    private static final ObjectMapper M = new ObjectMapper();

    /** 起一条真连接（内存通道），走完 session/new，返回收到的全部帧。 */
    private static List<JsonNode> sessionNewFrames() throws Exception {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(new AcpFakes.Factory());
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        assertTrue("session/new 必须被处理",
                conn.handleLine(AcpFakes.req(1, AcpMethods.SESSION_NEW, "{\"cwd\":\"/tmp\"}")));
        List<JsonNode> out = new ArrayList<JsonNode>();
        for (String line : recorder.lines()) {
            out.add(M.readTree(line));
        }
        return out;
    }

    private static JsonNode findCommandsUpdate(List<JsonNode> frames) {
        JsonNode found = null;
        for (JsonNode f : frames) {
            if (!AcpMethods.SESSION_UPDATE.equals(f.path("method").asText())) {
                continue;
            }
            if (!AcpMethods.UPDATE_AVAILABLE_COMMANDS.equals(
                    f.path("params").path("update").path("sessionUpdate").asText())) {
                continue;
            }
            assertFalse("一帧会话建立只该广告一次，实到多帧", found != null);
            found = f;
        }
        return found;
    }

    // ================= 判据 =================

    @Test
    public void sessionNewEmitsExactlyTheAcpSegmentOfTheCatalog() throws Exception {
        List<JsonNode> frames = sessionNewFrames();
        JsonNode note = findCommandsUpdate(frames);
        assertTrue("没有 available_commands_update 帧 ⇒ ACP 端根本没消费命令表。全部帧=" + frames,
                note != null);

        JsonNode update = note.path("params").path("update");
        JsonNode commands = update.path("availableCommands");
        assertTrue("availableCommands 必须是数组，实到: " + update, commands.isArray());

        List<String> names = new ArrayList<String>();
        for (JsonNode c : commands) {
            names.add(c.path("name").asText());
        }
        // 技能段是运行时扫出来的（名字不在静态表里），与 /api/commands 同一个口径分开对。
        List<String> skillNames = liveSkillNames();
        int staticPart = names.size() - skillNames.size();
        assertTrue("帧里静态段为空 ⇒ 解析没量到东西，判 FATAL：" + names, staticPart > 0);

        List<String> derived = new ArrayList<String>(
                CommandCatalog.namesFor(CommandCatalog.Endpoint.ACP));
        assertEquals("静态段与技能段在帧里必须先是静态段（尾巴才是技能）：" + names,
                derived, names.subList(0, staticPart));
        assertEquals("技能段尾巴必须逐名字等于注册表现有技能名",
                skillNames, names.subList(staticPart, names.size()));

        // 判别字与包裹：sessionUpdate 这个键名本身也是判据（写成 session_update 就红了）。
        assertEquals("available_commands_update", update.path("sessionUpdate").asText());
        assertEquals("帧只有 sessionUpdate + availableCommands 两个业务键",
                new LinkedHashSet<String>(Arrays.asList("sessionUpdate", "availableCommands")),
                setOfFieldNames(update));
        assertEquals("外层 method", AcpMethods.SESSION_UPDATE, note.path("method").asText());
        System.out.println("[p19] ACP 广告帧命令数=" + names.size()
                + "（静态 " + staticPart + " + 技能 " + skillNames.size() + "）");
    }

    @Test
    public void eachAdvertisedCommandCarriesSchemaShapedFields() throws Exception {
        JsonNode update = findCommandsUpdate(sessionNewFrames()).path("params").path("update");
        for (JsonNode c : update.path("availableCommands")) {
            String name = c.path("name").asText();
            CommandCatalog.Def def = CommandCatalog.find(name);
            if (def == null) {
                assertTrue("帧里出现了表里没有、也不是技能行的 " + name,
                        liveSkillNames().contains(name));
                continue;
            }
            assertEquals(name + " 的说明与表不同源", def.description(), c.path("description").asText());
            if (def.argsHint().isEmpty()) {
                assertTrue(name + "：无参数提示时不该出现 input（hermes 同形：input=None ⇒ 省略）",
                        c.path("input").isMissingNode() || c.path("input").isNull());
            } else {
                assertEquals(name + " 的 input.hint 必须来自表的 argsHint",
                        def.argsHint(), c.path("input").path("hint").asText());
                // AvailableCommand 只有三个字段，input 只有 hint：多塞键就是自造协议。
                assertEquals(name + " 的 input 只该有 hint",
                        new LinkedHashSet<String>(Arrays.asList("hint")),
                        setOfFieldNames(c.path("input")));
            }
            assertEquals(name + " 只有 name/description/input 三个字段",
                    c.path("input").isMissingNode()
                            ? new LinkedHashSet<String>(Arrays.asList("name", "description"))
                            : new LinkedHashSet<String>(Arrays.asList("name", "description", "input")),
                    setOfFieldNames(c));
        }
        // 反向钉：WEB 独占的那些（/exit /theme /status）不许混进 ACP 帧。
        List<String> advertised = new ArrayList<String>();
        for (JsonNode c : update.path("availableCommands")) {
            advertised.add(c.path("name").asText());
        }
        for (String onlyWeb : Arrays.asList("/exit", "/theme", "/status", "/feedback")) {
            assertFalse("不该出现在 ACP 帧里: " + onlyWeb, advertised.contains(onlyWeb));
        }
    }

    /** hermes 的注释口径："send the command advertisement after the session response is queued"。 */
    @Test
    public void advertisementIsQueuedAfterTheSessionNewResponse() throws Exception {
        List<JsonNode> frames = sessionNewFrames();
        int responseAt = -1;
        int advertiseAt = -1;
        for (int i = 0; i < frames.size(); i++) {
            JsonNode f = frames.get(i);
            if (f.has("id") && !f.has("method") && responseAt < 0) {
                responseAt = i;
            }
            if (f.path("params").path("update").path("sessionUpdate")
                    .asText().equals(AcpMethods.UPDATE_AVAILABLE_COMMANDS)) {
                advertiseAt = i;
            }
        }
        assertTrue("没有 session/new 的回包: " + frames, responseAt >= 0);
        assertTrue("没有广告帧: " + frames, advertiseAt >= 0);
        assertTrue("广告必须排在 session/new 的 response 之后（客户端先拿到 sessionId 的确认）："
                        + "responseAt=" + responseAt + " advertiseAt=" + advertiseAt,
                advertiseAt > responseAt);
        assertEquals("广告帧的 sessionId 必须是本次新建的那个",
                frames.get(responseAt).path("result").path("sessionId").asText(),
                frames.get(advertiseAt).path("params").path("sessionId").asText());
        assertTrue("广告是通知，不许带 id（带 id 就成了要客户端回包请求）",
                !frames.get(advertiseAt).has("id"));
    }

    @Test
    public void sessionLoadAlsoAdvertises() throws Exception {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.Factory factory = new AcpFakes.Factory("zb-777");
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        // 先 new 一个在线 ACP 句柄，再按该句柄 load（load/resume 的在线复用路径）。
        conn.handleLine(AcpFakes.req(1, AcpMethods.SESSION_NEW, "{\"cwd\":\"/tmp\"}"));
        int newAdvertisements = countAdvertisements(recorder.lines());
        assertEquals("session/new 应广告恰好一次", 1, newAdvertisements);
        String acpSessionId = sessionIdOf(recorder.lines());
        conn.handleLine(AcpFakes.req(2, AcpMethods.SESSION_LOAD,
                "{\"sessionId\":\"" + acpSessionId + "\",\"cwd\":\"/tmp\"}"));
        assertEquals("session/load 也要广告（hermes server.py:1170/1205 同源）",
                2, countAdvertisements(recorder.lines()));
    }

    // ================= 注入：证明这一面的卫兵有牙 =================

    @Test
    public void injectedPhantomCommandInFrameIsCaught() throws Exception {
        JsonNode update = findCommandsUpdate(sessionNewFrames()).path("params").path("update");
        Set<String> derived = new LinkedHashSet<String>(
                CommandCatalog.namesFor(CommandCatalog.Endpoint.ACP));
        Set<String> actual = namesOf(update);
        assertTrue("基线自己就该同源（静态段）：" + actual,
                diffNames(derived, withoutSkills(actual)).isEmpty());

        // 注入：帧里多吐一条表里没有的命令 ⇒ 必须红（往原数组尾巴加，不替换整段）。
        ((com.fasterxml.jackson.databind.node.ArrayNode) update.path("availableCommands"))
                .add(M.createObjectNode().put("name", "/injected-acp-only")
                        .put("description", "注入的幽灵命令").put("input", ""));
        List<String> drift = diffNames(derived, withoutSkills(namesOf(update)));
        assertTrue("注入没被抓到（ACP 面是死的）: " + drift,
                drift.contains("+ ACP 帧吐出但表里没有: /injected-acp-only"));
    }

    @Test
    public void droppedCommandInFrameIsCaught() throws Exception {
        JsonNode update = findCommandsUpdate(sessionNewFrames()).path("params").path("update");
        Set<String> derived = new LinkedHashSet<String>(
                CommandCatalog.namesFor(CommandCatalog.Endpoint.ACP));
        Set<String> actual = withoutSkills(namesOf(update));
        // 注入：帧里少一条表里承诺了的命令 ⇒ 必须红。
        assertTrue("摘掉一条没被抓到", actual.remove("/sessions"));
        List<String> drift = diffNames(derived, actual);
        assertTrue("注入没被抓到（ACP 面是死的）: " + drift,
                drift.contains("- 表里登记了但 ACP 帧没吐: /sessions"));
    }

    // ================= 小工具 =================

    /** 注册表当前的技能派生命令（与 publisher 读的是同一个 live()，所以两边不可能各抄一份）。 */
    private static List<String> liveSkillNames() {
        SlashRegistry live = SlashRegistry.live();
        if (live == null) {
            return new ArrayList<String>();
        }
        Set<CommandCatalog.Endpoint> skillEndpoints = CommandCatalog.skillEndpoints();
        List<String> out = new ArrayList<String>();
        for (String key : live.skillCommandKeys()) {
            if (skillEndpoints.contains(CommandCatalog.Endpoint.ACP)) {
                out.add(key);
            }
        }
        return out;
    }

    private static Set<String> withoutSkills(Set<String> names) {
        Set<String> out = new LinkedHashSet<String>(names);
        out.removeAll(liveSkillNames());
        return out;
    }

    private static Set<String> namesOf(JsonNode update) {
        Set<String> out = new LinkedHashSet<String>();
        for (JsonNode c : update.path("availableCommands")) {
            out.add(c.path("name").asText());
        }
        return out;
    }

    /** 空输入一律 FATAL：两边都空不算通过。 */
    static List<String> diffNames(Set<String> derived, Set<String> actual) {
        List<String> problems = new ArrayList<String>();
        if (derived.isEmpty()) {
            problems.add("[FATAL] 派生集合为空");
            return problems;
        }
        if (actual.isEmpty()) {
            problems.add("[FATAL] ACP 帧解析结果为空 —— 判 FATAL 不判相等");
            return problems;
        }
        for (String n : derived) {
            if (!actual.contains(n)) {
                problems.add("- 表里登记了但 ACP 帧没吐: " + n);
            }
        }
        for (String n : actual) {
            if (!derived.contains(n)) {
                problems.add("+ ACP 帧吐出但表里没有: " + n);
            }
        }
        return problems;
    }

    private static Set<String> setOfFieldNames(JsonNode object) {
        Set<String> out = new LinkedHashSet<String>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    private static int countAdvertisements(List<String> lines) throws Exception {
        int n = 0;
        for (String line : lines) {
            JsonNode f = M.readTree(line);
            if (AcpMethods.UPDATE_AVAILABLE_COMMANDS.equals(
                    f.path("params").path("update").path("sessionUpdate").asText())) {
                n++;
            }
        }
        return n;
    }

    private static String sessionIdOf(List<String> lines) throws Exception {
        for (String line : lines) {
            JsonNode f = M.readTree(line);
            if (f.path("id").asInt(0) == 1 && f.path("result").path("sessionId").isTextual()) {
                return f.path("result").path("sessionId").asText();
            }
        }
        throw new AssertionError("[FATAL] 帧里没有 session/new 的 sessionId: " + lines);
    }
}
