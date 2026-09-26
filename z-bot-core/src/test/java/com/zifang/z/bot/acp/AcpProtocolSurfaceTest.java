package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 协议面骨架：帧格式、派发表、未知方法的大声失败、initialize 的能力声明。
 *
 * <p>方法集的分母不是工单那几个字面量计数，而是 SDK 派发表
 * {@code ~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/meta.py:3—17}
 * 的 13 条 agent 面方法（复算见 {@code _doc/acceptance/p25/EVIDENCE.md} §0.2）。
 * 本类第一条用例就是把这张表钉在测试里：少一条或多一条都会红。</p>
 */
public class AcpProtocolSurfaceTest {

    // ---- §1.1 派发表 = 协议面 ----

    @Test
    public void agentMethodSetIsTheThirteenFromTheSdkDispatchTable() {
        assertEquals(Arrays.asList("authenticate", "initialize", "session/cancel", "session/close",
                        "session/fork", "session/list", "session/load", "session/new",
                        "session/prompt", "session/resume", "session/set_config_option",
                        "session/set_mode", "session/set_model"),
                new java.util.ArrayList<String>(AcpMethods.AGENT_METHODS));
        assertEquals("agent 面 13 条（acp/meta.py:3—17）", 13, AcpMethods.AGENT_METHODS.size());
        assertEquals("client 面 9 条（acp/meta.py:18—28）", 9, AcpMethods.CLIENT_METHODS.size());
        assertEquals("协议版本钉在 meta.py:29", 1, AcpMethods.PROTOCOL_VERSION);
    }

    @Test
    public void everyAdvertisedAgentMethodIsActuallyDispatchable() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(new AcpFakes.Factory());
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();

        for (String method : AcpMethods.AGENT_METHODS) {
            assertTrue("方法表里有 " + method + "，派发表里没有 ⇒ 会被当未知方法：",
                    conn.registeredMethods().contains(method));
        }
        // session/cancel 在 ACP 里是通知，必须挂在通知面而不是请求面（否则客户端等一个不来的回包）。
        assertTrue(conn.registeredMethods().contains(AcpMethods.SESSION_CANCEL));
    }

    @Test
    public void unknownMethodFailsLoudlyWithMethodNotFoundAndKnownSet() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/promt\",\"params\":{}}");

        String frame = recorder.last();
        assertNotNull("未知方法必须有回帧", frame);
        JsonNode node = parse(frame);
        assertEquals("要带原 id", 7, node.path("id").asInt());
        JsonNode error = node.path("error");
        assertFalse("不许静默忽略", error.isMissingNode());
        assertEquals(-32601, error.path("code").asInt());
        assertTrue("文案要点名方法名: " + error.path("message").asText(),
                error.path("message").asText().contains("session/promt"));
        List<String> known = new java.util.ArrayList<String>();
        for (JsonNode m : error.path("data").path("knownMethods")) {
            known.add(m.asText());
        }
        assertEquals("data.knownMethods 必须是协议全集，帮客户端一眼看出拼错", 13, known.size());
        assertTrue(known.contains("session/prompt"));
    }

    @Test
    public void knownButUnimplementedMethodReportsItsOwnNameAndReason() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/fork\",\"params\":"
                + "{\"sessionId\":\"acp-x\",\"cwd\":\"\"}}");
        JsonNode error = parse(recorder.last()).path("error");
        assertEquals(-32601, error.path("code").asInt());
        assertEquals("not-implemented-in-p25", error.path("data").path("status").asText());
        assertTrue("未实现要说清是哪条方法: " + error.path("message").asText(),
                error.path("message").asText().contains("session/fork"));
    }

    @Test
    public void unknownNotificationIsRecordedAndNotSilentlyDropped() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"session/teleport\",\"params\":{}}");

        assertEquals("通知不能回包（JSON-RPC 2.0），所以一条帧都不许多发", 0, recorder.lines().size());
        assertEquals(Arrays.asList("session/teleport"), conn.unknownNotifications());
    }

    @Test
    public void malformedFrameGetsParseErrorInsteadOfSilence() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{not json at all");
        JsonNode error = parse(recorder.last()).path("error");
        assertEquals(-32700, error.path("code").asInt());

        recorder.clear();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":3}");
        error = parse(recorder.last()).path("error");
        assertEquals(-32600, error.path("code").asInt());
        assertEquals(3, parse(recorder.last()).path("id").asInt());
    }

    // ---- initialize ----

    @Test
    public void initializeAdvertisesOnlyRealCapabilities() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpAgentServer server = AcpFakes.server(conn,
                new AcpSessionRegistry(new AcpFakes.Factory()), new AcpApprovalBridge());
        server.register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":1,\"clientInfo\":{\"name\":\"zed\"}}}");
        JsonNode result = parse(recorder.last()).path("result");
        assertTrue(server.isInitialized());
        assertEquals(1, result.path("protocolVersion").asInt());
        assertEquals("z-bot", result.path("agentInfo").path("name").asText());
        assertTrue(result.path("agentInfo").path("version").asText().startsWith("z-bot/"));
        assertTrue("loadSession 真做了 ⇒ 才敢声明",
                result.path("agentCapabilities").path("loadSession").asBoolean());
        assertFalse("session/fork 未实现 ⇒ 不许在能力表里撒谎",
                result.path("agentCapabilities").path("sessionCapabilities").has("fork"));
        assertTrue(result.path("agentCapabilities").path("sessionCapabilities").has("list"));
        assertFalse("prompt 不收图（未声明 image 能力）",
                result.path("agentCapabilities").path("promptCapabilities").path("image").asBoolean());
        assertEquals("z-bot-config", result.path("authMethods").get(0).path("id").asText());
    }

    @Test
    public void initializeRejectsForeignProtocolVersionInsteadOfPretending() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":99}}");
        JsonNode error = parse(recorder.last()).path("error");
        assertEquals(-32602, error.path("code").asInt());
        assertTrue(error.path("message").asText().contains("99"));
    }

    @Test
    public void authenticateOnlyAcceptsAdvertisedMethodIds() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"authenticate\","
                + "\"params\":{\"methodId\":\"minimax\"}}");
        assertTrue(parse(recorder.last()).has("result"));

        recorder.clear();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"authenticate\","
                + "\"params\":{\"methodId\":\"somebody-elses-key\"}}");
        JsonNode error = parse(recorder.last()).path("error");
        assertEquals(-32602, error.path("code").asInt());
        assertTrue("要点名是哪个 methodId 不认: " + error.path("message").asText(),
                error.path("message").asText().contains("somebody-elses-key"));
    }

    // ---- 会话生命周期（不经 prompt 的那半）----

    @Test
    public void newSessionReturnsAcpHandleAndMapsToZbotSession() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.Factory factory = new AcpFakes.Factory();
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/new\","
                + "\"params\":{\"cwd\":\"/work/a\"}}");
        JsonNode result = parse(recorder.last()).path("result");
        String acpId = result.path("sessionId").asText();
        assertTrue("ACP 句柄要有自己的前缀，别和 z-bot 会话 id 混成一个: " + acpId,
                acpId.startsWith("acp-"));
        assertEquals("zb-100", result.path("_meta").path("zbotSessionId").asText());
        assertEquals("桥接状态要自报，IDE 侧才看得见没接审批", "attached",
                result.path("_meta").path("approvalBridge").asText());
        assertEquals(acpId, registry.resolve(acpId).acpSessionId());
        assertEquals("反向也要查得到", acpId, registry.byZbotSessionId("zb-100").acpSessionId());
    }

    @Test
    public void promptWithoutSessionIdIsInvalidParams() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session/prompt\",\"params\":{}}");
        assertEquals(-32602, parse(recorder.last()).path("error").path("code").asInt());
    }

    private static JsonNode parse(String line) {
        try {
            return JsonRpc.mapper().readTree(line);
        } catch (java.io.IOException e) {
            throw new AssertionError("回帧不是合法 JSON: " + line, e);
        }
    }

    /** 单帧里不许混两条报文（换行分帧的前提）。 */
    @Test
    public void framesAreSingleLineJson() {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.server(conn, new AcpSessionRegistry(new AcpFakes.Factory()),
                new AcpApprovalBridge()).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        String frame = recorder.last();
        assertNull("一帧之内不许有换行", frame.indexOf('\n') >= 0 ? "has newline" : null);
        assertFalse("不许把两帧拼一行", frame.contains("}{"));
    }
}
