package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.bot.tool.ApprovalService;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 工单 §1.2 的正身：审批外送接的是 {@code tool/ApprovalService}，不是另起一套。
 *
 * <p>最关键的一条是 {@link #removingTheBridgeLeavesTheQueueStuckAndLeaksRawProtocol()}：
 * 桥摘掉 ⇒ 队列没人消费、{@code WAIT_CONFIRM} 原文无处可去 ⇒ 必然红。
 * 这一条也是 {@code p25_mutation.py} 里"摘桥"那支变异的红点。</p>
 */
public class AcpApprovalBridgeTest {

    /** 起一条已 new 好的会话，返回 (连接, 收帧器, 会话, 替身)。 */
    private Fixture fixture(AcpApprovalBridge bridge) {
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpFakes.Factory factory = new AcpFakes.Factory();
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpAgentServer server = AcpFakes.server(conn, registry, bridge);
        server.register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        return new Fixture(conn, recorder, registry, acpId, factory.last());
    }

    private static final class Fixture {
        final AcpConnection conn;
        final AcpFakes.Recorder recorder;
        final AcpSessionRegistry registry;
        final String acpId;
        final AcpFakes.Scripted target;

        Fixture(AcpConnection conn, AcpFakes.Recorder recorder, AcpSessionRegistry registry,
                String acpId, AcpFakes.Scripted target) {
            this.conn = conn;
            this.recorder = recorder;
            this.registry = registry;
            this.acpId = acpId;
            this.target = target;
        }

        AcpSessionRegistry.AcpSession session() {
            return registry.resolve(acpId);
        }
    }

    /** 让 IDE 线程外先挂上 prompt，再由测试扮演客户端回权限应答。 */
    private void awaitingTurn(Fixture f) {
        f.target.enqueue(new AcpFakes.Turn()
                .awaitingApproval("exec", "{\"command\":\"rm -rf ./build\"}", "删构建目录要人确认")
                .say("不会走到这里"));
    }

    /** 找到 z-bot 主动发出的那条 session/request_permission 请求帧。 */
    private static String permissionRequest(List<String> frames) {
        for (String line : frames) {
            if (line.contains("\"" + AcpMethods.SESSION_REQUEST_PERMISSION + "\"")) {
                return line;
            }
        }
        return null;
    }

    private static String optionIdOf(String permissionFrame, String optionId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(permissionFrame)
                + ",\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"" + optionId + "\"}}}";
    }

    /** 出向请求的 id 是字符串（{@code z-bot-N}），回帧必须原样带引号回去。 */
    private static String idLiteral(String permissionFrame) {
        JsonNode id = child(permissionFrame, "id");
        return id.isTextual() ? "\"" + id.asText() + "\"" : id.toString();
    }

    // ---- 外送链路 ----

    @Test
    public void pendingApprovalIsAskedOutOfBandWithApprovalServiceIdentity() {
        Fixture f = fixture(new AcpApprovalBridge());
        awaitingTurn(f);
        f.recorder.clear();

        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"清一下\"}]}}");

        List<String> frames = f.recorder.awaitFrames(1, 3000L);
        String permission = permissionRequest(frames);
        assertNotNull("必须有 session/request_permission 帧（桥没接上就不会有）", permission);
        JsonNode params = child(permission, "params");
        assertEquals(f.acpId, params.path("sessionId").asText());
        JsonNode call = params.path("toolCall");
        assertTrue("toolCallId 要钉在 ApprovalService 的 requestId 上: " + call.path("toolCallId").asText(),
                call.path("toolCallId").asText().startsWith("perm-check-"));
        String requestId = call.path("_meta").path("approvalRequestId").asText();
        assertEquals("requestId 必须就是队列里那条的 id（不是编的）",
                f.target.pendingApprovals().get(0).id(), requestId);
        assertEquals("exec", call.path("title").asText().split(":")[0]);
        assertTrue(call.path("rawInput").path("argsJson").asText().contains("rm -rf ./build"));
        assertEquals("pending", call.path("status").asText());

        // 选项集 = z-bot 的四档，optionId 沿用她的稳定命名。
        JsonNode options = params.path("options");
        assertEquals(Arrays.asList("allow_once", "allow_session", "allow_always", "deny"),
                optionIds(options));
        assertEquals("allow_once", options.get(0).path("optionId").asText());
        assertEquals("allow_once", options.get(0).path("kind").asText());
        assertEquals("reject_once", options.get(3).path("kind").asText());
    }

    @Test
    public void allowOnceResolutionDrainsQueueAndRunsTheTool() {
        Fixture f = fixture(new AcpApprovalBridge());
        awaitingTurn(f);
        f.recorder.clear();
        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"清一下\"}]}}");
        String permission = permissionRequest(f.recorder.awaitFrames(1, 3000L));
        assertNotNull(permission);

        f.conn.handleLine(optionIdOf(permission, AcpMethods.OPTION_ALLOW_ONCE));

        JsonNode result = child(awaitPromptReply(f, 43), "result");
        awaitQueueDrained(f.target, 3000L);
        assertEquals("队列必须被消费掉", 0, f.target.pendingApprovals().size());
        assertEquals(Arrays.asList(ApprovalService.Resolution.ONCE), f.target.resolutionsSeen);
        assertEquals(AcpMethods.STOP_END_TURN, result.path("stopReason").asText());
        assertEquals("ONCE", result.path("_meta").path("approvalResolution").asText());
        assertEquals(permissionRequestMeta(permission),
                result.path("_meta").path("approvalRequestId").asText());
        assertTrue("z-bot 的待批语义是中断当前回合，必须告诉客户端怎么继续",
                result.path("_meta").path("turnInterruptedByApproval").asBoolean());
        assertTrue(result.path("_meta").path("continueHint").asText().contains("session/prompt"));
        assertFalse("WAIT_CONFIRM 原文不许泄漏给客户端",
                f.recorder.lines().toString().contains("WAIT_CONFIRM"));
    }

    private static String permissionRequestMeta(String permissionFrame) {
        return child(permissionFrame, "params").path("toolCall").path("_meta")
                .path("approvalRequestId").asText();
    }

    /**
     * prompt 是在工作线程上跑的（{@code AcpAgentServer:137—143} 返回 {@code ACP_ASYNC}），
     * 所以回完权限帧之后必须等那条 prompt 的响应帧落地，才能断言队列/决议 —— 否则测的是竞态。
     */
    private String awaitPromptReply(Fixture f, int id) {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            for (String line : f.recorder.lines()) {
                if (line.contains("\"id\":" + id)) {
                    return line;
                }
            }
            sleep(10L);
        }
        throw new AssertionError("没等到 id=" + id + " 的 prompt 响应，已收到: " + f.recorder.lines());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 等到 target 的待批队列被消费掉为止（带上限）。 */
    private static void awaitQueueDrained(AcpFakes.Scripted target, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && !target.pendingApprovals().isEmpty()) {
            sleep(10L);
        }
    }

    @Test
    public void eachOptionIdMapsToItsOwnApprovalServiceResolution() {
        String[][] cases = {
                {AcpMethods.OPTION_ALLOW_ONCE, "ONCE"},
                {AcpMethods.OPTION_ALLOW_SESSION, "SESSION"},
                {AcpMethods.OPTION_ALLOW_ALWAYS, "ALWAYS"},
                {AcpMethods.OPTION_DENY, "DENY"},
        };
        int id = 50;
        for (String[] c : cases) {
            Fixture f = fixture(new AcpApprovalBridge());
            awaitingTurn(f);
            f.recorder.clear();
            f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":" + (id++) + ",\"method\":\"session/prompt\",\"params\":{"
                    + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"x\"}]}}");
            String permission = permissionRequest(f.recorder.awaitFrames(1, 3000L));
            assertNotNull(permission);
            f.conn.handleLine(optionIdOf(permission, c[0]));
            awaitPromptReply(f, id - 1);
            assertEquals(c[0] + " 应落到 " + c[1],
                    ApprovalService.Resolution.valueOf(c[1]), f.target.resolutionsSeen.get(0));
            assertEquals("不论哪一档都要出队", 0, f.target.pendingApprovals().size());
        }
    }

    @Test
    public void unknownOrCancelledOutcomeIsDeny() {
        Fixture f = fixture(new AcpApprovalBridge());
        awaitingTurn(f);
        f.recorder.clear();
        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"x\"}]}}");
        String permission = permissionRequest(f.recorder.awaitFrames(1, 3000L));
        // 客户端编了一个没给过它的 optionId ⇒ 一律 deny，不能当 once。
        f.conn.handleLine(optionIdOf(permission, "allow_everything_forever"));
        awaitPromptReply(f, 51);
        assertEquals(ApprovalService.Resolution.DENY, f.target.resolutionsSeen.get(0));
    }

    @Test
    public void cancelledOutcomeIsDenyAndNothingRuns() {
        Fixture f = fixture(new AcpApprovalBridge());
        awaitingTurn(f);
        f.recorder.clear();
        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"x\"}]}}");
        String permission = permissionRequest(f.recorder.awaitFrames(1, 3000L));
        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(permission)
                + ",\"result\":{\"outcome\":{\"outcome\":\"cancelled\"}}}");
        awaitPromptReply(f, 52);
        assertEquals(ApprovalService.Resolution.DENY, f.target.resolutionsSeen.get(0));
        assertTrue("deny 那一路不许执行工具", f.target.confirmArgsSeen.get(0).contains("DENY"));
    }

    /** 客户端一直不答 ⇒ 有上限地放弃并按 deny 处理（不许无界 await 把连接挂死）。 */
    @Test(timeout = 30_000L)
    public void silentClientTimesOutIntoDenyNotIntoHang() {
        final AcpFakes.Scripted target = new AcpFakes.Scripted("zb-silent");
        target.enqueue(new AcpFakes.Turn()
                .awaitingApproval("exec", "{\"command\":\"rm -rf ./build\"}", "删构建目录要人确认"));
        // 先跑一轮把真审批队列挂上（桥只认 ApprovalService 的队头，不认调用方嘴里的串）。
        final String wait = target.prompt("清一下", com.zifang.z.bot.agent.StreamListener.NOOP);
        assertTrue(AcpApprovalBridge.isWaitConfirm(wait));
        final AcpSessionRegistry.AcpSession session = newSessionHandle(target);
        final AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        final AcpConnection conn = AcpFakes.connection(recorder);
        // 只发请求帧，永不回权限应答：requestClient 的超时上限必须自己收场。
        final long startedAt = System.currentTimeMillis();
        final AtomicReference<AcpApprovalBridge.Outcome> done =
                new AtomicReference<AcpApprovalBridge.Outcome>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                done.set(new AcpApprovalBridge().requestAndResolve(conn, session,
                        wait, true, 400L));
            }
        });
        worker.setDaemon(true);
        worker.start();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (worker.isAlive() && System.currentTimeMillis() < deadline) {
            sleep(20L);
        }
        assertFalse("等客户端应答不许无界挂住", worker.isAlive());
        long elapsed = System.currentTimeMillis() - startedAt;
        assertTrue("超时上限没生效（耗时 " + elapsed + "ms）: " + recorder.lines(), elapsed < 5_000L);
        AcpApprovalBridge.Outcome outcome = done.get();
        assertNotNull("超时也必须给出一个决议", outcome);
        assertEquals("超时按 deny", ApprovalService.Resolution.DENY, outcome.resolution());
        assertTrue("要能区分'客户端答了 deny'和'客户端装死'", outcome.timedOut());
        assertEquals(Arrays.asList(ApprovalService.Resolution.DENY), target.resolutionsSeen);
        assertEquals("装死后队列同样要出清", 0, target.pendingApprovals().size());
        assertEquals(-1, updateFrames(recorder.lines()).toString().indexOf("WAIT_CONFIRM"));
    }

    /** 只有 session/update 通知帧才是"推给 IDE 的内容"；错误帧里引用原文是有意的大声失败。 */
    private static List<String> updateFrames(List<String> frames) {
        List<String> updates = new java.util.ArrayList<String>();
        for (String line : frames) {
            if (line.contains("\"" + AcpMethods.SESSION_UPDATE + "\"")) {
                updates.add(line);
            }
        }
        return updates;
    }

    // ---- §1.2 要求的那支"摘掉 bridge ⇒ 红" ----

    @Test
    public void removingTheBridgeLeavesRawProtocolStringUnusableAndQueueStuck() {
        Fixture f = fixture(AcpApprovalBridge.disabled());
        awaitingTurn(f);
        f.recorder.clear();

        f.conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":70,\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + f.acpId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"清一下\"}]}}");

        String promptReply = awaitPromptReply(f, 70);
        List<String> frames = f.recorder.lines();
        assertEquals("桥摘掉后不许再有权责外的外送帧", -1, frames.toString().indexOf("request_permission"));
        JsonNode error = child(promptReply, "error");
        assertFalse("桥不在了绝不能当成功回包", error.isMissingNode());
        assertEquals(-32603, error.path("code").asInt());
        assertTrue(error.path("message").asText().contains("AcpApprovalBridge"));
        assertEquals("队列必须还堵着 —— 这正是'别另起一套审批'的可测形式",
                1, f.target.pendingApprovals().size());
        assertFalse("WAIT_CONFIRM 原文绝不许当答案推给 IDE",
                updateFrames(frames).toString().contains("WAIT_CONFIRM"));
        assertTrue("没有桥就没有任何决议发生", f.target.resolutionsSeen.isEmpty());
    }

    @Test
    public void bridgeRefusesWhenChatAsksButQueueIsEmpty() {
        // 账实不符（chat 说要人审、队列里啥也没有）⇒ 大声失败，绝不猜着放行。
        AcpFakes.Scripted target = new AcpFakes.Scripted("zb-empty-queue");
        target.enqueue(new AcpFakes.Turn().say(BotAgentWaitConfirm.waitString()));
        AcpSessionRegistry.AcpSession session =
                newSessionHandle(target);
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        try {
            new AcpApprovalBridge().requestAndResolve(conn, session,
                    BotAgentWaitConfirm.waitString(), true);
            throw new AssertionError("队列为空时必须抛，不许自行放行");
        } catch (AcpProtocolException expected) {
            assertEquals(-32603, expected.code());
            assertTrue(expected.getMessage().contains("账实不符"));
        }
    }

    /** 直接单测 optionId → 四档这张表（不经过连接，方便日后加档时逐格核）。 */
    @Test
    public void optionMappingTableIsTheFiveAdvertisedIds() {
        List<String> allowed = Arrays.asList("allow_once", "allow_session", "allow_always", "deny");
        assertEquals(ApprovalService.Resolution.ONCE,
                AcpApprovalBridge.mapOutcome(selected("allow_once"), allowed));
        assertEquals(ApprovalService.Resolution.SESSION,
                AcpApprovalBridge.mapOutcome(selected("allow_session"), allowed));
        assertEquals(ApprovalService.Resolution.ALWAYS,
                AcpApprovalBridge.mapOutcome(selected("allow_always"), allowed));
        assertEquals(ApprovalService.Resolution.DENY,
                AcpApprovalBridge.mapOutcome(selected("deny"), allowed));
        assertEquals(ApprovalService.Resolution.DENY,
                AcpApprovalBridge.mapOutcome(selected("allow_always_4ever"), allowed));
        assertEquals(ApprovalService.Resolution.DENY, AcpApprovalBridge.mapOutcome(null, allowed));
        assertEquals(ApprovalService.Resolution.DENY,
                AcpApprovalBridge.mapOutcome(JsonRpc.object().put("outcome", JsonRpc.object()), allowed));
    }

    private static JsonNode selected(String optionId) {
        ObjectNode node = JsonRpc.object();
        ObjectNode outcome = node.putObject("outcome");
        outcome.put("outcome", "selected");
        outcome.put("optionId", optionId);
        return node;
    }

    private static AcpSessionRegistry.AcpSession newSessionHandle(final AcpTurnTarget target) {
        AcpTurnTarget.Factory factory = new AcpTurnTarget.Factory() {
            @Override
            public AcpTurnTarget open(AcpTurnTarget.Demand demand) {
                return target;
            }

            @Override
            public List<String> knownZbotSessionIds() {
                return Arrays.asList(target.zbotSessionId());
            }

            @Override
            public void dispose(AcpTurnTarget t) {
            }
        };
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        return registry.create("/w");
    }

    /** 只用来造一条"要人审"的返回串，形状与 {@code BotAgent.chat} 一致。 */
    private static final class BotAgentWaitConfirm {
        static String waitString() {
            return com.zifang.z.bot.agent.BotAgent.WAIT_CONFIRM_PREFIX
                    + "exec|{\"command\":\"ls\"}|要看一眼";
        }
    }

    // ---- 小料 ----

    private static List<String> optionIds(JsonNode options) {
        List<String> ids = new java.util.ArrayList<String>();
        for (JsonNode o : options) {
            ids.add(o.path("optionId").asText());
        }
        return ids;
    }

    private static JsonNode child(String json, String... path) {
        try {
            JsonNode node = JsonRpc.mapper().readTree(json);
            for (String p : path) {
                node = node.path(p);
            }
            return node;
        } catch (java.io.IOException e) {
            throw new AssertionError("帧不是合法 JSON: " + json, e);
        }
    }

    @Test
    public void parseWaitConfirmKeepsPipeCharactersInsideArgs() {
        // args 里带 '|' 时不能被切坏 —— reason 才是最后一段。
        AcpApprovalBridge.ParsedWait parsed =
                AcpApprovalBridge.parseWaitConfirm("WAIT_CONFIRM:exec|{\"command\":\"a|b\"}|要确认");
        assertEquals("exec", parsed.tool);
        assertEquals("{\"command\":\"a|b\"}", parsed.args);
        assertEquals("要确认", parsed.reason);
        assertTrue(AcpApprovalBridge.isWaitConfirm("WAIT_CONFIRM:exec|{}|r"));
        assertFalse(AcpApprovalBridge.isWaitConfirm("普通回答"));
        assertFalse(AcpApprovalBridge.isWaitConfirm(null));
        assertNotNull(parseWaitFailMessage());
    }

    private static String parseWaitFailMessage() {
        try {
            AcpApprovalBridge.parseWaitConfirm("不是 WAIT_CONFIRM");
            return "没抛异常";
        } catch (AcpProtocolException e) {
            assertEquals(-32603, e.code());
            return e.getMessage();
        }
    }
}
