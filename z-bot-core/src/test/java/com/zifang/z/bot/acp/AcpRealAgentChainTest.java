package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.ApprovalService;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真链路：真 {@link BotAgent} + 真 {@link ApprovalService} + 真 {@link AcpConnection}
 * 走完 {@code initialize → session/new → session/prompt}（含流式与审批外送）。
 *
 * <p>这里<b>没有</b>脚本化的 target：只有 LLM 那一腿是脚本（工单 §4 红线 4 不许打厂商 API）。
 * 待批队列由 {@code BotAgent} 自己入队（{@code agent/BotAgent.java:565—578}），
 * 放行由 {@code BotAgent.confirmTool} 真的重跑工具 —— 桥接的是不是真审批，这一层说了才算。</p>
 */
public class AcpRealAgentChainTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 只回答"调这个工具"、然后"给最终答案"的两腿脚本 LLM。 */
    private static final class TwoStepLlm implements LlmProvider {
        final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();

        @Override
        public String name() {
            return "two-step";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            requests.add(request);
            return scripted.isEmpty() ? reply("收尾", null) : scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request,
                               java.util.function.Consumer<ChatCompletionsResponse> onChunk,
                               java.util.function.Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }

        TwoStepLlm script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }
    }

    private static ChatCompletionsResponse reply(String content, List<ToolCall> calls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, calls, "stop")),
                null, "stop", Collections.emptyMap());
    }

    private static ChatCompletionsResponse toolReply(String callId, String tool, String argsJson) {
        return reply("", Collections.singletonList(new ToolCall(callId, tool, argsJson)));
    }

    /** 真 BotAgent 工厂：脚本 LLM + 一个"要人审"的工具 + 真会话目录。 */
    private static final class RealFactory implements AcpTurnTarget.Factory {
        final TwoStepLlm llm;
        final File sessionDir;
        final File sandboxDir;
        final List<BotAgent> built = new ArrayList<BotAgent>();
        final AtomicInteger executions = new AtomicInteger();

        RealFactory(TwoStepLlm llm, File sessionDir, File sandboxDir) {
            this.llm = llm;
            this.sessionDir = sessionDir;
            this.sandboxDir = sandboxDir;
        }

        @Override
        public AcpTurnTarget open(AcpTurnTarget.Demand demand) {
            BotAgent agent = buildAgent(demand);
            built.add(agent);
            return new BotAgentAcpTarget(agent);
        }

        private BotAgent buildAgent(AcpTurnTarget.Demand demand) {
            Toolkit toolkit = new Toolkit();
            toolkit.register(Toolkit.of("dangerous", "需要人审的工具", null, args -> {
                if (Confirmations.alreadyConfirmed(args)) {
                    executions.incrementAndGet();
                    return ToolResult.text("已执行:" + str(args, Confirmations.COMMAND_ARG));
                }
                return Confirmations.needsConfirmation("该命令要人确认");
            }));
            toolkit.register(Toolkit.of("plain", "不需要人审的工具", null,
                    args -> ToolResult.text("普通工具的输出")));
            BotAgent agent = BotAgent.builder((BotConfig) null)
                    .provider(llm)
                    .toolkit(toolkit)
                    .sandbox(new com.zifang.z.bot.tool.Sandbox(sandboxDir.getAbsolutePath()))
                    .sessionManager(new SessionManager(sessionDir))
                    .model(demand.model == null || demand.model.isEmpty() ? "test-model" : demand.model)
                    .withoutBuiltinTools()
                    .withoutCompressor()
                    .build();
            if (demand.zbotSessionId == null || demand.zbotSessionId.isEmpty()) {
                agent.newSession();
            } else {
                agent.switchSession(demand.zbotSessionId);
            }
            return agent;
        }

        @Override
        public List<String> knownZbotSessionIds() {
            BotAgent probe = buildAgent(AcpTurnTarget.Demand.restore(sessionDir.getPath(), null));
            try {
                List<String> ids = new ArrayList<String>();
                for (SessionManager.SessionSummary s : probe.listSessions()) {
                    ids.add(s.id);
                }
                return ids;
            } finally {
                probe.shutdown();
            }
        }

        @Override
        public void dispose(AcpTurnTarget target) {
            target.shutdown();
        }

        BotAgent lastAgent() {
            return built.get(built.size() - 1);
        }
    }

    private static String str(java.util.Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    // ---- 用例 ----

    @Test(timeout = 120_000L)
    public void promptStreamsEveryAgentEventAsItsOwnFrame() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        llm.script(toolReply("c1", "plain", "{\"why\":\"看一眼\"}"))
                .script(reply("读完了，共 3 个文件", null));
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s1"), tmp.newFolder("w1"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        String zbotId = child(recorder.last(), "result", "_meta", "zbotSessionId").asText();
        assertFalse("真 BotAgent 的会话 id 要能映射上", zbotId.isEmpty());

        recorder.clear();
        conn.handleLine(prompt(2, acpId, "列一下目录"));
        // 等的是"响应帧到了"这个因果，不是凑帧数：awaitFrames(2) 会在 tool_call +
        // tool_call_update 两帧刚到就返回，那时末帧还是通知，:202 那条"末帧是响应"就成了假红
        // （实测：同一条树单独跑绿、四个 ACP 类合跑红一次 —— 典型等 A 断言 B）。
        String response = awaitFrame(recorder, "\"id\":2,\"result\"", 20_000L);
        assertNotNull("prompt 必须有响应帧", response);
        List<String> frames = recorder.lines();

        // 事件面：tool_call → tool_call_update(failed: 要人审) → agent_message_chunk → 响应
        List<String> kinds = sessionUpdates(frames);
        assertTrue("必须有 tool_call 帧，工具过程不许被吞: " + kinds, kinds.contains("tool_call"));
        assertTrue("必须有 tool_call_update: " + kinds, kinds.contains("tool_call_update"));
        assertTrue("最终答案必须是一帧 agent_message_chunk: " + kinds,
                kinds.contains("agent_message_chunk"));
        assertTrue("帧数必须多于 1（整包实现会在这里红）: " + kinds.size(), kinds.size() > 1);
        String last = frames.get(frames.size() - 1);
        assertEquals("末帧才是 prompt 响应", 2, child(last, "id").asInt());
        assertEquals(AcpMethods.STOP_END_TURN, child(last, "result", "stopReason").asText());
        assertTrue(textOf(frames, "agent_message_chunk").contains("读完了"));
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void realApprovalQueueIsDrainedThroughTheBridgeAndToolActuallyRuns() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        llm.script(toolReply("c1", "dangerous", "{\"command\":\"rm -rf ./out\"}"));
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s2"), tmp.newFolder("w2"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        BotAgent agent = factory.lastAgent();
        recorder.clear();

        conn.handleLine(prompt(3, acpId, "删掉 out 目录"));
        // 同一条理由：awaitFrames(1) 可能只等到 tool_call 通知，待批帧还在路上 ⇒ 假红。
        String permission = awaitFrame(recorder, AcpMethods.SESSION_REQUEST_PERMISSION, 20_000L);
        assertNotNull("真 BotAgent 的待批必须外送成 session/request_permission", permission);
        assertEquals("队列真身就是 BotAgent 的那个 ApprovalService", 1, agent.pendingApprovals().size());
        String queuedId = agent.pendingApprovals().get(0).id();
        assertEquals(queuedId, child(permission, "params", "toolCall", "_meta", "approvalRequestId").asText());
        // rawInput 必须是"工具的真入参对象"（她 SDK acp/helpers.py 同形），不是 z-bot 的记账壳：
        // command 键取模型给的那个 command 参数，整串 argsJson 挪进 _meta 由人审/执行同源取用。
        assertTrue("rawInput 得是对象，IDE 才渲染得出来: "
                        + child(permission, "params", "toolCall", "rawInput").toString(),
                child(permission, "params", "toolCall", "rawInput").isObject());
        assertEquals("rm -rf ./out",
                child(permission, "params", "toolCall", "rawInput", "command").asText());
        assertEquals("{\"command\":\"rm -rf ./out\"}",
                child(permission, "params", "toolCall", "_meta", "approvalArgsJson").asText());
        assertEquals(acpId, child(permission, "params", "sessionId").asText());

        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(permission)
                + ",\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"allow_once\"}}}");
        String promptResult = awaitFrame(recorder, "\"id\":3", 20_000L);
        assertNotNull("放行后必须有 prompt 响应", promptResult);
        assertEquals(AcpMethods.STOP_END_TURN, child(promptResult, "result", "stopReason").asText());
        assertEquals("ONCE", child(promptResult, "result", "_meta", "approvalResolution").asText());
        assertEquals("队列必须被真消费", 0, agent.pendingApprovals().size());
        assertEquals("工具必须真的被执行了一次", 1, factory.executions.get());
        assertTrue("工具输出要作为一帧推给 IDE",
                textOf(recorder.lines(), "agent_message_chunk").contains("已执行:rm -rf ./out"));
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void denyPathDoesNotRunTheToolButStillAnswersThePrompt() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        llm.script(toolReply("c1", "dangerous", "{\"command\":\"shutdown now\"}"));
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s3"), tmp.newFolder("w3"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        BotAgent agent = factory.lastAgent();
        recorder.clear();

        conn.handleLine(prompt(4, acpId, "关机"));
        String permission = awaitFrame(recorder, AcpMethods.SESSION_REQUEST_PERMISSION, 20_000L);
        assertNotNull(permission);
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral(permission)
                + ",\"result\":{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"deny\"}}}");

        String promptResult = awaitFrame(recorder, "\"id\":4", 20_000L);
        assertEquals("DENY", child(promptResult, "result", "_meta", "approvalResolution").asText());
        assertEquals("拒绝后一条都不许执行", 0, factory.executions.get());
        assertEquals("拒绝也要出队，否则下一条会话被堵", 0, agent.pendingApprovals().size());
        assertTrue(textOf(recorder.lines(), "agent_message_chunk").contains("已拒绝"));
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void sessionCancelNotificationStopsTheRealAgent() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        llm.script(toolReply("c1", "dangerous", "{\"command\":\"sleep 1\"}"));
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s4"), tmp.newFolder("w4"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        BotAgent agent = factory.lastAgent();

        assertFalse(agent.isStopRequested());
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\""
                + acpId + "\"}}");
        assertTrue("session/cancel 必须落到 BotAgent.stop() 的中断旗子上", agent.isStopRequested());
        assertEquals("通知不回包", 1, recorder.lines().size());
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void setModelReopensTheRealAgentWithTheNewModel() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s5"), tmp.newFolder("w5"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        BotAgent before = factory.lastAgent();

        recorder.clear();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/set_model\",\"params\":{"
                + "\"sessionId\":\"" + acpId + "\",\"modelId\":\"other-model\"}}");
        String frame = recorder.last();
        assertFalse("set_model 不许静默失败: " + frame, child(frame, "error").isMissingNode() ? false : true);
        assertEquals("other-model", child(frame, "result", "models", "currentModelId").asText());
        BotAgent after = factory.lastAgent();
        assertFalse("必须是真的换了一个 agent 实例", before == after);
        assertEquals("other-model", after.getModel());
        assertEquals("ACP 句柄不变", acpId, registry.resolve(acpId).acpSessionId());
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void setModelRejectsModelOutsideTheProfileCatalog() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s6"), tmp.newFolder("w6"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        new AcpAgentServer(conn, registry, new AcpApprovalBridge(), Arrays.asList("z-bot-config"),
                Arrays.asList("default"), Arrays.asList("only-this")).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        recorder.clear();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/set_model\",\"params\":{"
                + "\"sessionId\":\"" + acpId + "\",\"modelId\":\"not-in-catalog\"}}");
        assertEquals(-32602, child(recorder.last(), "error").path("code").asInt());
        assertTrue(child(recorder.last(), "error", "message").asText().contains("not-in-catalog"));
        assertEquals("白名单外不许真的换掉 agent", 1, factory.built.size());
        registry.closeAll();
    }

    @Test(timeout = 120_000L)
    public void loadByZbotSessionIdRestoresAfterProcessRestart() throws Exception {
        TwoStepLlm llm = new TwoStepLlm();
        RealFactory factory = new RealFactory(llm, tmp.newFolder("s7"), tmp.newFolder("w7"));
        AcpFakes.Recorder recorder = new AcpFakes.Recorder();
        AcpConnection conn = AcpFakes.connection(recorder);
        AcpSessionRegistry registry = new AcpSessionRegistry(factory);
        AcpFakes.server(conn, registry, new AcpApprovalBridge()).register();
        conn.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\"}}");
        String acpId = child(recorder.last(), "result", "sessionId").asText();
        String zbotId = child(recorder.last(), "result", "_meta", "zbotSessionId").asText();
        // 先跑一轮，让落盘历史里真有消息
        conn.handleLine(prompt(2, acpId, "你好"));
        awaitFrame(recorder, "\"id\":2", 20_000L);

        // 模拟重启：新 registry、新 server，同一个落盘目录
        AcpFakes.Recorder recorder2 = new AcpFakes.Recorder();
        AcpConnection conn2 = AcpFakes.connection(recorder2);
        AcpSessionRegistry registry2 = new AcpSessionRegistry(factory);
        AcpFakes.server(conn2, registry2, new AcpApprovalBridge()).register();

        conn2.handleLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/load\",\"params\":{"
                + "\"sessionId\":\"" + acpId + "\",\"cwd\":\"/w\"}}");
        JsonNode error = child(recorder2.last(), "error");
        assertFalse("旧 ACP 句柄跨进程必须大声拒绝", error.isMissingNode());
        assertEquals(-32602, error.path("code").asInt());
        assertTrue("要给出可恢复办法: " + error.path("message").asText(),
                error.path("message").asText().contains("session/list"));

        recorder2.clear();
        conn2.handleLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/load\",\"params\":{"
                + "\"sessionId\":\"" + zbotId + "\",\"cwd\":\"/w\"}}");
        String restored = awaitFrame(recorder2, "\"id\":2,\"result\"", 20_000L);
        assertNotNull("按 z-bot 会话 id 必须显式恢复（等响应帧本身，不凑帧数）", restored);
        assertEquals(zbotId, child(restored, "result", "_meta", "zbotSessionId").asText());
        assertFalse("恢复出来的 ACP 句柄是新的", zbotId.equals(child(restored, "result", "sessionId").asText()));
        registry.closeAll();
        registry2.closeAll();
    }

    // ---- 小料 ----

    private static String prompt(int id, String sessionId, String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"session/prompt\",\"params\":{"
                + "\"sessionId\":\"" + sessionId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\""
                + text + "\"}]}}";
    }

    private static String awaitFrame(AcpFakes.Recorder recorder, String token, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String hit = null;
            for (String line : recorder.lines()) {
                if (line.contains(token)) {
                    hit = line;
                }
            }
            if (hit != null) {
                return hit;
            }
            Thread.sleep(10L);
        }
        return null;
    }

    private static List<String> sessionUpdates(List<String> frames) {
        List<String> out = new ArrayList<String>();
        for (String f : frames) {
            JsonNode update = child(f, "params", "update");
            if (!update.isMissingNode()) {
                out.add(update.path("sessionUpdate").asText());
            }
        }
        return out;
    }

    private static String textOf(List<String> frames, String sessionUpdate) {
        StringBuilder sb = new StringBuilder();
        for (String f : frames) {
            JsonNode update = child(f, "params", "update");
            if (update.isMissingNode() || !sessionUpdate.equals(update.path("sessionUpdate").asText())) {
                continue;
            }
            JsonNode content = update.path("content");
            if (content.isArray()) {
                for (JsonNode c : content) {
                    sb.append(c.path("text").asText(""));
                }
            } else {
                sb.append(content.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private static String idLiteral(String permissionFrame) {
        JsonNode id = child(permissionFrame, "id");
        return id.isTextual() ? "\"" + id.asText() + "\"" : id.toString();
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
}
