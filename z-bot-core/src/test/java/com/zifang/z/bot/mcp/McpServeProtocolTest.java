package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P21 §6 的进程内那一半：反向 {@code mcp serve} 的线级协议。
 *
 * <p>与 {@code p21_e2e.py} 的分工：E2E 用<b>官方 SDK 的真 client</b>连真 {@code z-bot mcp serve}
 * 进程（那一半才算"对端认这条线"）；这一支负责把逐条线上行为钉成可变异、可回归的单测：
 * initialize 回显版本、工具表<b>只有</b>两条读端、未知 method 必须回 JSON-RPC error（不许静默
 * 让对端等）、id 原样回显、notification 不许有响应、{@code serve} 的收尾有界。</p>
 */
public class McpServeProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 假会话库：只有读，没有任何写入口 —— 类结构本身就是"只读"的围栏。 */
    private static final class FakeSource implements ZBotMcpServe.ConversationSource {
        final List<String> readCalls = new ArrayList<String>();
        int conversationsCalledWithLimit = -1;
        boolean includeArchivedSeen;

        @Override
        public List<Map<String, Object>> conversations(boolean includeArchived, int limit) {
            readCalls.add("conversations");
            includeArchivedSeen = includeArchived;
            conversationsCalledWithLimit = limit;
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            out.add(row("c-one", "第一条会话", 2));
            out.add(row("c-two", "第二条会话", 0));
            return out;
        }

        @Override
        public List<Map<String, Object>> messages(String conversationId, int limit) {
            readCalls.add("messages:" + conversationId);
            if ("c-two".equals(conversationId)) {
                return Collections.emptyList();
            }
            if ("boom".equals(conversationId)) {
                throw new IllegalStateException("库被打开了但表不在");
            }
            return Arrays.asList(msg(0, "user", "你好"), msg(1, "assistant", "在的"));
        }

        private static Map<String, Object> row(String id, String title, int count) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("conversation_id", id);
            m.put("title", title);
            m.put("message_count", Integer.valueOf(count));
            return m;
        }

        private static Map<String, Object> msg(int idx, String role, String content) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("idx", Integer.valueOf(idx));
            m.put("role", role);
            m.put("content", content);
            return m;
        }
    }

    private static String req(int id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + paramsJson + "}";
    }

    private static JsonNode parse(String line) throws IOException {
        return JSON.readTree(line);
    }

    // ------------------------------------------------------------------ 握手

    @Test
    public void initializeEchoesTheSupportedVersionAndAdvertisesNoListChanged() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode r = parse(s.handle(req(1, "initialize",
                "{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{\"name\":\"probe\",\"version\":\"9\"}}")));
        assertEquals(1L, r.get("id").asLong());
        JsonNode result = r.get("result");
        assertNotNull(result);
        assertEquals("2025-03-26", result.get("protocolVersion").asText());
        assertFalse("工具表是常量，不许广告 listChanged",
                result.get("capabilities").get("tools").get("listChanged").asBoolean(true));
        assertEquals("z-bot", result.get("serverInfo").get("name").asText());
        assertEquals("2025-03-26", s.negotiatedProtocolVersion());

        // 客户端提了个不认识的版本 ⇒ 回自己的，而不是报错（规范口径）
        JsonNode r2 = parse(s.handle(req(2, "initialize", "{\"protocolVersion\":\"1999-01-01\"}")));
        assertEquals(McpWire.PROTOCOL_VERSION, r2.get("result").get("protocolVersion").asText());
    }

    @Test
    public void notificationsGetNoResponseAtAll() {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        assertNull(s.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertEquals(1L, s.requestsHandled());
        assertEquals(0L, s.responsesSent());
        assertEquals("没有响应不等于回了一行空", 0L, s.rejectedRequests());
    }

    // ------------------------------------------------------------------ 工具表 = 只读围栏

    @Test
    public void toolTableIsExactlyTheTwoReadTools() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode r = parse(s.handle(req(3, "tools/list", "{}")));
        JsonNode tools = r.get("result").get("tools");
        assertEquals("只许暴露两条读端工具，实际: " + tools, 2, tools.size());
        assertEquals(ZBotMcpServe.TOOL_CONVERSATIONS, tools.get(0).get("name").asText());
        assertEquals(ZBotMcpServe.TOOL_MESSAGES, tools.get(1).get("name").asText());
        for (JsonNode t : tools) {
            String name = t.get("name").asText();
            assertFalse("工具名里不许出现写侧动词: " + name,
                    name.matches(".*(send|write|delete|prune|run|exec|chat|append|set).*"));
            assertEquals("object", t.get("inputSchema").get("type").asText());
            assertTrue(t.get("description").asText().length() > 0);
        }
        JsonNode required = tools.get(1).get("inputSchema").get("required");
        assertEquals("messages 这条必须有必填参数", 1, required.size());
        assertEquals("conversation_id", required.get(0).asText());
        assertEquals("另一条不该有必填参数", 0,
                tools.get(0).get("inputSchema").get("required").size());
    }

    // ------------------------------------------------------------------ 读端语义

    @Test
    public void callReturnsTextContentAndStructuredPayload() throws Exception {
        FakeSource src = new FakeSource();
        ZBotMcpServe s = new ZBotMcpServe(src);
        JsonNode r = parse(s.handle(req(4, "tools/call",
                "{\"name\":\"" + ZBotMcpServe.TOOL_CONVERSATIONS + "\",\"arguments\":{\"include_archived\":true,\"limit\":7}}")));
        JsonNode result = r.get("result");
        assertFalse(result.get("isError").asBoolean(true));
        assertEquals("text", result.get("content").get(0).get("type").asText());
        JsonNode payload = parse(result.get("content").get(0).get("text").asText());
        assertEquals(2, payload.get("conversations").size());
        assertEquals("c-one", payload.get("conversations").get(0).get("conversation_id").asText());
        assertEquals(7, payload.get("limit").asInt());
        assertTrue(src.includeArchivedSeen);
        assertEquals(7, src.conversationsCalledWithLimit);
        // structuredContent 与 text 必须是同一份事实，否则前端与 client 各看一套
        assertEquals(payload.get("conversationCount").asInt(),
                result.get("structuredContent").get("conversationCount").asInt());
    }

    @Test
    public void messagesReadForUnknownConversationIsAToolErrorNotACrash() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode r = parse(s.handle(req(5, "tools/call",
                "{\"name\":\"" + ZBotMcpServe.TOOL_MESSAGES + "\",\"arguments\":{\"conversation_id\":\"boom\"}}")));
        assertTrue("数据源炸了要折成 isError 的工具结果: " + r, r.get("result").get("isError").asBoolean(false));
        assertTrue(r.get("result").get("content").get(0).get("text").asText().contains("表不在"));
        assertEquals(0L, s.rejectedRequests());

        JsonNode empty = parse(s.handle(req(6, "tools/call",
                "{\"name\":\"" + ZBotMcpServe.TOOL_MESSAGES + "\",\"arguments\":{\"conversation_id\":\"c-two\"}}")));
        assertFalse(empty.get("result").get("isError").asBoolean(true));
        assertEquals("空表也要如实回 0 条，不能当成错误",
                0, empty.get("result").get("structuredContent").get("messageCount").asInt());
    }

    @Test
    public void missingRequiredArgumentIsAnInvalidParamsError() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode r = parse(s.handle(req(7, "tools/call",
                "{\"name\":\"" + ZBotMcpServe.TOOL_MESSAGES + "\",\"arguments\":{}}")));
        assertEquals(-32602, r.get("error").get("code").asInt());
        assertTrue(r.get("error").get("message").asText().contains("conversation_id"));
        assertEquals("错误响应不许同时带 result", true, !r.has("result"));
    }

    @Test
    public void unknownToolAndUnknownMethodBothAnswerInsteadOfHanging() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode t = parse(s.handle(req(8, "tools/call",
                "{\"name\":\"zbot_send_message\",\"arguments\":{}}")));
        assertEquals(-32602, t.get("error").get("code").asInt());
        assertTrue(t.get("error").get("message").asText().contains("zbot_send_message"));

        JsonNode m = parse(s.handle(req(9, "prompts/list", "{}")));
        assertEquals(-32601, m.get("error").get("code").asInt());
        assertEquals(2L, s.rejectedRequests());
        // 空输入 / 非 JSON 也必须回，不能静默
        JsonNode junk = parse(s.handle("this is not json"));
        assertEquals(-32700, junk.get("error").get("code").asInt());
        assertTrue("id 未知时按规范回 null", junk.get("id").isNull());
    }

    @Test
    public void stringAndNumericIdsAreEchoedInTheirOwnShape() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        JsonNode num = parse(s.handle(req(42, "ping", "{}")));
        assertTrue(num.get("id").isNumber());
        assertEquals(42L, num.get("id").asLong());
        JsonNode str = parse(s.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc-1\",\"method\":\"ping\"}"));
        assertTrue("字符串 id 必须原样回成字符串: " + str, str.get("id").isTextual());
        assertEquals("abc-1", str.get("id").asText());
        JsonNode err = parse(s.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc-2\",\"method\":\"nope\"}"));
        assertTrue(err.get("id").isTextual());
        assertEquals("abc-2", err.get("id").asText());
    }

    // ------------------------------------------------------------------ serve 循环

    @Test
    public void serveLoopWritesOneResponsePerLineAndStopsAtEof() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        String feed = req(1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}") + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + req(2, "tools/list", "{}") + "\n"
                + req(3, "tools/call", "{\"name\":\"" + ZBotMcpServe.TOOL_CONVERSATIONS
                + "\",\"arguments\":{}}") + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int handled = s.serve(new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8)), out);
        String wire = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String[] lines = wire.trim().split("\n");
        assertEquals("4 行进 3 行出（notification 不出行）: " + wire, 3, lines.length);
        assertEquals(4, handled);
        for (String l : lines) {
            assertFalse("每行都必须是一整个响应，不许粘包: " + l, l.contains("}{"));
            JsonNode n = parse(l);
            assertEquals("2.0", n.get("jsonrpc").asText());
        }
        assertEquals(2L, parse(lines[1]).get("id").asLong());
        // 空输入 ⇒ 0 行输出，且计数如实为 0（空跑不许满分，反过来也不许假红）
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        assertEquals(0, s.serve(new ByteArrayInputStream(new byte[0]), out2));
        assertEquals(0, out2.size());
    }

    @Test
    public void requestCapBoundsTheLoopInsteadOfReadingForever() throws Exception {
        ZBotMcpServe s = new ZBotMcpServe(new FakeSource(),
                new ZBotMcpServe.Options().maxRequests(2));
        StringBuilder feed = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            feed.append(req(i + 1, "ping", "{}")).append('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int handled = s.serve(new ByteArrayInputStream(feed.toString().getBytes(StandardCharsets.UTF_8)), out);
        assertEquals("到达 max-requests 必须自己收尾: " + handled, 2, handled);
        assertEquals(2, out.toString("UTF-8").trim().split("\n").length);
    }

    /** 真管道路径（不是内存字符串）：{@code z-bot mcp serve} 的 IO 形状。 */
    @Test
    public void serveOverAPipeAnswersTheRealWire() throws Exception {
        final ZBotMcpServe s = new ZBotMcpServe(new FakeSource());
        PipedInputStream serverIn = new PipedInputStream();
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedOutputStream serverOut = new PipedOutputStream();
        final PipedInputStream clientIn = new PipedInputStream(serverOut);

        final String[] failure = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.serve(serverIn, serverOut);
                } catch (IOException e) {
                    failure[0] = e.toString();
                }
            }
        }, "p21-serve-pipe");
        t.setDaemon(true);
        t.start();

        clientOut.write((req(1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        clientOut.flush();
        String line = readLine(clientIn);
        assertTrue("管道里应当回来一行: " + line, line.startsWith("{"));
        JsonNode n = parse(line);
        assertEquals(1L, n.get("id").asLong());
        assertEquals("2025-06-18", n.get("result").get("protocolVersion").asText());
        assertNull("serve 内部不该抛", failure[0]);

        clientOut.close();
        t.join(5000L);
        assertFalse("serve 线程必须在 EOF 后自己收尾，不许赖着", t.isAlive());
    }

    private static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
