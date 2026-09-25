package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.MessageType;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BotAgent} ReAct 循环单测：LLM 用脚本替身，工具用内存桩，
 * 沙箱与会话目录都落在 {@link TemporaryFolder} 里，不碰 {@code ~/.zbot}。
 */
public class BotAgentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private Toolkit toolkit;
    private List<Map<String, Object>> executedArgs;
    private File sandboxDir;
    private File sessionDir;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        executedArgs = new ArrayList<Map<String, Object>>();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        toolkit = new Toolkit();
        toolkit.register(Toolkit.of("echo", "回显", schema("message"), args ->
                result("echoed:" + str(args, "message"))));
        toolkit.register(Toolkit.of("probe", "记录入参", schema("i"), args -> {
            executedArgs.add(args);
            return result("probed");
        }));
        toolkit.register(Toolkit.of("risky", "需要确认", schema("cmd"), args -> {
            executedArgs.add(args);
            return Confirmations.alreadyConfirmed(args)
                    ? result("risk-accepted") : Confirmations.needsConfirmation("高危操作");
        }));
    }

    // ===== 单轮文本答案 =====

    @Test
    public void textReplyBecomesFinalAnswerAndEmitsEvents() {
        llm.script(textReply("你好，我是 z-bot"));
        List<StreamEvent> events = new ArrayList<StreamEvent>();

        String reply = newAgent(10).chat("hi", collect(events));

        assertEquals("你好，我是 z-bot", reply);
        assertEquals(Arrays.asList(StreamEvent.Kind.STEP_START, StreamEvent.Kind.FINAL_DELTA,
                StreamEvent.Kind.DONE), kinds(events));
        StreamEvent.Done done = (StreamEvent.Done) events.get(2);
        assertEquals("你好，我是 z-bot", done.reply);
        assertEquals(1, done.totalSteps);
        assertEquals(Integer.valueOf(11), done.promptTokens);
        assertEquals(Integer.valueOf(7), done.completionTokens);
        assertEquals(2, agent.getMemory().size());
    }

    @Test
    public void emptyReplyOnFirstStepIsReportedAsError() {
        llm.script(textReply(""));

        String reply = newAgent(10).chat("hi", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith("Error:"));
        assertEquals(1, llm.callCount);
    }

    // ===== 工具调用 =====

    @Test
    public void toolCallIsExecutedAndResultFedBackToNextRequest() {
        llm.script(toolReply(call("call_1", "echo", "{\"message\":\"ping\"}")))
                .script(textReply("done"));
        List<StreamEvent> events = new ArrayList<StreamEvent>();

        String reply = newAgent(10).chat("echo ping", collect(events));

        assertEquals("done", reply);
        List<Msg> second = llm.requests.get(1).getMessages();
        assertEquals(MessageType.TOOL_CALL, second.get(2).getType());
        assertEquals("call_1", second.get(2).getToolCalls().get(0).getId());
        assertEquals(MessageRole.TOOL, second.get(3).getRole());
        assertEquals("call_1", second.get(3).getToolCallId());
        assertEquals("echoed:ping", second.get(3).getContent());
        assertEquals(Arrays.asList(StreamEvent.Kind.STEP_START, StreamEvent.Kind.TOOL_CALL_REQUEST,
                StreamEvent.Kind.TOOL_RESULT, StreamEvent.Kind.STEP_START, StreamEvent.Kind.FINAL_DELTA,
                StreamEvent.Kind.DONE), kinds(events));
        StreamEvent.ToolCallRequest request = (StreamEvent.ToolCallRequest) events.get(1);
        assertEquals("echo", request.name);
        assertEquals("ping", request.arguments.get("message"));
    }

    @Test
    public void systemPromptToolsAndToolChoiceAreSentToProvider() throws Exception {
        llm.script(textReply("ok"));

        newAgent(10, config("agent.tool.choice=required")).chat("hi", StreamListener.NOOP);

        ChatCompletionsRequest sent = llm.requests.get(0);
        // echo/probe/risky + delegate_task + memory + cronjob
        assertEquals(6, sent.getTools().size());
        assertEquals("required", sent.getProviderParams().get("tool_choice"));
        assertEquals("test-model", sent.getModel());
        assertEquals(0.7d, sent.getTemperature(), 0.001d);
        assertEquals(MessageRole.SYSTEM, sent.getMessages().get(0).getRole());
        assertTrue(sent.getMessages().get(0).getContent().contains("echo"));
    }

    @Test
    public void allToolCallsInOneStepAreExecutedInOrder() {
        llm.script(toolReply(call("c1", "probe", "{\"i\":1}"), call("c2", "probe", "{\"i\":2}")))
                .script(textReply("both done"));

        String reply = newAgent(10).chat("run twice", StreamListener.NOOP);

        assertEquals("both done", reply);
        assertEquals(2, executedArgs.size());
        assertEquals(1, ((Number) executedArgs.get(0).get("i")).intValue());
        assertEquals(2, ((Number) executedArgs.get(1).get("i")).intValue());
        // user + assistant(tool_calls) + 2×tool + 最终答案
        assertEquals(5, agent.getMemory().size());
    }

    @Test
    public void finalAnswerToolEndsLoopWithoutExecuting() {
        llm.script(toolReply(call("c1", "final_answer", "{\"answer\":\"任务完成\"}")));

        String reply = newAgent(10).chat("finish", StreamListener.NOOP);

        assertEquals("任务完成", reply);
        assertEquals(1, llm.callCount);
        assertTrue(executedArgs.isEmpty());
        assertEquals("任务完成", lastAssistantText(agent.getMemory().getMessages()));
    }

    @Test
    public void unknownToolBecomesErrorResultAndLoopContinues() {
        llm.script(toolReply(call("c1", "nope", "{}")))
                .script(textReply("recovered"));

        String reply = newAgent(10).chat("bad tool", StreamListener.NOOP);

        assertEquals("recovered", reply);
        Msg toolMsg = agent.getMemory().getMessages().get(2);
        assertEquals(MessageRole.TOOL, toolMsg.getRole());
        assertTrue(toolMsg.getContent(), toolMsg.getContent().contains("未找到工具"));
    }

    // ===== 文本描述的工具调用兜底 =====

    @Test
    public void functionsStyleTextToolCallIsExecuted() {
        llm.script(textReply("functions.echo({\"message\":\"from text\"})"))
                .script(textReply("ok"));

        String reply = newAgent(10).chat("echo", StreamListener.NOOP);

        assertEquals("ok", reply);
        List<Msg> second = llm.requests.get(1).getMessages();
        assertEquals(MessageType.TOOL_CALL, second.get(2).getType());
        assertEquals(MessageRole.TOOL, second.get(3).getRole());
        assertEquals("echoed:from text", second.get(3).getContent());
    }

    @Test
    public void quotedArgsWithoutJsonAreMappedToSchemaParamNames() {
        llm.script(textReply("我来调用 echo 工具，参数 \"deep\""))
                .script(textReply("ok"));

        String reply = newAgent(10).chat("echo", StreamListener.NOOP);

        assertEquals("ok", reply);
        assertEquals(2, llm.callCount);
        assertEquals("echoed:deep", llm.requests.get(1).getMessages().get(3).getContent());
    }

    @Test
    public void textMentioningUnknownToolIsTreatedAsAnswer() {
        llm.script(textReply("我调用了 not_registered(\"x\")"));

        String reply = newAgent(10).chat("hi", StreamListener.NOOP);

        assertEquals("我调用了 not_registered(\"x\")", reply);
        assertEquals(1, llm.callCount);
    }

    // ===== 人工确认 =====

    @Test
    public void dangerousToolSuspendsChatUntilConfirm() {
        llm.script(toolReply(call("c1", "risky", "{\"cmd\":\"rm\"}")));

        String reply = newAgent(10).chat("do risky", StreamListener.NOOP);

        assertEquals("WAIT_CONFIRM:risky|{\"cmd\":\"rm\"}|高危操作", reply);
        assertEquals(1, llm.callCount);

        String after = agent.confirmTool("risky", "{\"cmd\":\"rm\"}");

        assertEquals("risk-accepted", after);
        assertTrue(Confirmations.alreadyConfirmed(executedArgs.get(executedArgs.size() - 1)));
        assertEquals("risk-accepted", lastToolMsg(agent.getMemory().getMessages()).getContent());
    }

    @Test
    public void confirmationEventIsFailedToolResult() {
        llm.script(toolReply(call("c1", "risky", "{}")));
        List<StreamEvent> events = new ArrayList<StreamEvent>();

        newAgent(10).chat("do risky", collect(events));

        StreamEvent.ToolResult result = (StreamEvent.ToolResult) events.get(2);
        assertFalse(result.success);
        assertEquals("高危操作", result.error);
    }

    // ===== 步数与中止 =====

    @Test
    public void maxStepsExhaustedReturnsHint() {
        // 预算 2 步 + kernel grace 收尾 1 次：3 次调用都还吐 tool_calls 才真正耗尽
        for (int i = 0; i < 5; i++) {
            llm.script(toolReply(call("c" + i, "probe", "{}")));
        }

        String reply = newAgent(2).chat("loop forever", StreamListener.NOOP);

        assertTrue(reply, reply.contains("已达到预算上限(steps=3/2"));
        assertEquals(3, llm.callCount);
    }

    @Test
    public void stopBreaksLoopAtNextStep() {
        for (int i = 0; i < 5; i++) {
            llm.script(toolReply(call("c" + i, "probe", "{}")));
        }
        final BotAgent running = newAgent(5);

        String reply = running.chat("loop", new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                if (event.kind() == StreamEvent.Kind.STEP_START
                        && ((StreamEvent.StepStart) event).step == 2) {
                    running.stop();
                }
            }
        });

        assertTrue(reply, reply.startsWith("已中止"));
        assertEquals(1, llm.callCount);
        assertFalse(running.isRunning());
    }

    // ===== LLM 异常 =====

    @Test
    public void llmFailureYieldsErrorReplyAndEvent() {
        llm.failing = new RuntimeException("connect refused");
        List<StreamEvent> events = new ArrayList<StreamEvent>();

        String reply = newAgent(10).chat("hi", collect(events));

        assertEquals("Error: connect refused", reply);
        assertEquals(StreamEvent.Kind.ERROR, events.get(1).kind());
        assertEquals(1, agent.getMemory().size());
    }

    // ===== 会话持久化 =====

    @Test
    public void chatPersistsConversationToSessionFile() {
        llm.script(toolReply(call("c1", "echo", "{\"message\":\"x\"}")))
                .script(textReply("saved"));
        BotAgent agent = newAgent(10);
        String sid = agent.currentSessionId();

        agent.chat("hello", StreamListener.NOOP);

        List<Msg> reloaded = new SessionManager(sessionDir).loadMessages(sid);
        assertEquals(4, reloaded.size());
        assertEquals("saved", reloaded.get(3).getContent());
    }

    @Test
    public void newSessionAndSwitchRestoreHistory() {
        llm.script(textReply("first"));
        BotAgent agent = newAgent(10);
        String oldSession = agent.currentSessionId();
        agent.chat("hello", StreamListener.NOOP);

        String fresh = agent.newSession();

        assertFalse(fresh.equals(oldSession));
        assertEquals(0, agent.getMemory().size());
        agent.switchSession(oldSession);
        assertEquals(2, agent.getMemory().size());
        assertEquals("hello", agent.getMemory().getMessages().get(0).getContent());
        assertEquals(oldSession, agent.currentSessionId());
    }

    @Test
    public void clearMemoryKeepsCurrentSession() {
        llm.script(textReply("x"));
        BotAgent agent = newAgent(10);
        String sid = agent.currentSessionId();
        agent.chat("hello", StreamListener.NOOP);

        agent.clearMemory();

        assertEquals(0, agent.getMemory().size());
        assertEquals(sid, agent.currentSessionId());
    }

    // ===== 装配 =====

    @Test
    public void builderRegistersBuiltinToolsAgainstSandbox() throws Exception {
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(textReply("ready"));

        String reply = agent.chat("hi", StreamListener.NOOP);

        assertEquals("ready", reply);
        List<String> names = agent.getToolkit().getToolNames();
        assertTrue(names.toString(), names.containsAll(Arrays.asList(
                "read_file", "write_file", "exec", "search", "sysinfo", "mvn_build", "curl_test",
                "echo", "time", "counter", "health")));
        assertEquals(sandboxDir.getCanonicalFile(), agent.getSandbox().root().getCanonicalFile());
    }

    @Test
    public void sandboxedWriteAndReadFileRoundTrip() {
        BotAgent agent = newAgentWithBuiltins();
        llm.script(textReply("written"));
        agent.chat("warm up", StreamListener.NOOP);

        ToolResult written = agent.getToolkit().execute("write_file",
                Collections.<String, Object>singletonMap("path", "notes.txt"));
        assertTrue(written.getContent(), !written.isError());
        assertTrue(new File(sandboxDir, "notes.txt").exists());
        ToolResult escaped = agent.getToolkit().execute("read_file",
                Collections.<String, Object>singletonMap("path", "../../../../etc/passwd"));
        assertTrue(escaped.isError());
        assertTrue(escaped.getContent(), escaped.getContent().contains("沙箱"));
    }

    @Test
    public void dangerousExecSuspendsUntilConfirmed() throws Exception {
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf p11-absent-dir\"}")));

        String reply = agent.chat("run it", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith("WAIT_CONFIRM:exec|"));
        String after = agent.confirmTool("exec", "{\"command\":\"rm -rf p11-absent-dir\"}");
        assertTrue(after, after.contains("exit=0"));
    }

    /**
     * 审批绑命令（P11 真进程 E2E 实测到的替换洞）：人放行的是<b>队列里那一条</b>，
     * 调用方在 confirm 时换一份参数，不能拿这次放行去跑另一条命令。
     */
    @Test
    public void confirmCannotBeRepointedToADifferentCommand() throws Exception {
        File approved = new File(sandboxDir, "approved.txt");
        File substituted = new File(sandboxDir, "substituted.txt");
        assertTrue(approved.createNewFile() && substituted.createNewFile());
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf approved.txt\"}")));

        String reply = agent.chat("delete approved", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith("WAIT_CONFIRM:exec|"));
        // 人点的是"放行刚才那条"，客户端把参数换成另一条命令
        String after = agent.confirmTool("exec", "{\"command\":\"rm -rf substituted.txt\"}");

        assertTrue(after, after.contains("exit=0"));
        assertFalse("人批准的命令没跑", approved.exists());
        assertTrue("放行被挪用去跑了另一条命令", substituted.exists());
    }

    /**
     * 待批取代（红线 8 账实一致）：z-bot 的确认是中断回合，同一会话里新待批到来时旧的那条
     * 永远不会再被执行 —— 必须离开队列，否则 {@code /confirm} 消费的是与真实动作无关的旧账。
     */
    @Test
    public void newerPendingSupersedesTheAbandonedOne() throws Exception {
        File older = new File(sandboxDir, "older.txt");
        File newer = new File(sandboxDir, "newer.txt");
        assertTrue(older.createNewFile() && newer.createNewFile());
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf older.txt\"}")));
        llm.script(toolReply(call("c2", "exec", "{\"command\":\"rm -rf newer.txt\"}")));

        agent.chat("drop older", StreamListener.NOOP);
        agent.chat("drop newer", StreamListener.NOOP);

        assertEquals("活待批只可能有一条", 1, agent.pendingApprovals().size());
        assertTrue(agent.pendingApprovals().get(0).command(),
                agent.pendingApprovals().get(0).command().contains("newer.txt"));

        String after = agent.confirmTool("exec", "{\"command\":\"rm -rf newer.txt\"}");

        assertTrue(after, after.contains("exit=0"));
        assertTrue("被取代的旧待批竟被执行了", older.exists());
        assertFalse("活待批没执行", newer.exists());
        assertEquals(0, agent.pendingApprovals().size());
    }

    /**
     * 审批绕过回归：模型在自己的工具参数里塞 {@code "__confirmed__":true} 不能当作人的放行章。
     *
     * <p>修复前 {@code exec} 直接读到该标记 → {@code ExecGuard} 放行 → 命令无审批执行。</p>
     */
    @Test
    public void modelCannotSelfConfirmDangerousExec() throws Exception {
        File victim = new File(sandboxDir, "victim.txt");
        assertTrue(victim.createNewFile());
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(toolReply(call("c1", "exec",
                "{\"command\":\"rm -rf victim.txt\",\"__confirmed__\":true}")));

        String reply = agent.chat("delete it", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith("WAIT_CONFIRM:exec|"));
        assertTrue("自带 __confirmed__ 竟绕过了审批", victim.exists());
    }

    /** 人放行时盖的章仍然有效（confirmTool 在 parseArgs 之后盖章）。 */
    @Test
    public void humanConfirmStillRunsThePendingCommand() throws Exception {
        File victim = new File(sandboxDir, "victim.txt");
        assertTrue(victim.createNewFile());
        BotAgent agent = BotAgent.builder(config("agent.exec.confirm=dangerous"))
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .build();
        llm.script(toolReply(call("c1", "exec",
                "{\"command\":\"rm -rf victim.txt\",\"__confirmed__\":true}")));
        agent.chat("delete it", StreamListener.NOOP);

        String after = agent.confirmTool("exec", "{\"command\":\"rm -rf victim.txt\"}");

        assertTrue(after, !after.contains("需要确认"));
        assertTrue("人确认后命令没执行", !victim.exists());
    }

    @Test
    public void parseArgsStripsModelSuppliedConfirmationStamp() {
        Map<String, Object> args = BotAgent.parseArgs("{\"command\":\"ls\",\"__confirmed__\":true}");
        assertEquals("ls", args.get("command"));
        assertFalse("模型自带的确认标记没被剥掉", args.containsKey(Confirmations.CONFIRMED_ARG));
    }

    // ===== helpers =====

    private BotAgent newAgent(int maxSteps) {
        return newAgent(maxSteps, null);
    }

    private BotAgent newAgent(int maxSteps, BotConfig config) {
        agent = BotAgent.builder(config)
                .provider(llm)
                .toolkit(toolkit)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .maxSteps(maxSteps)
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        return agent;
    }

    private BotAgent newAgentWithBuiltins() {
        agent = BotAgent.builder(null)
                .provider(llm)
                .toolkit(new Toolkit())
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .build();
        return agent;
    }

    private BotConfig config(String... extraLines) throws Exception {
        File dir = tmp.newFolder("cfg");
        try (FileWriter w = new FileWriter(new File(dir, "config.properties"))) {
            w.write("llm.provider=glm\n");
            w.write("glm.type=openai\n");
            w.write("glm.api.key=test-key\n");
            w.write("glm.base.url=http://127.0.0.1:1/v1\n");
            w.write("glm.model=glm-4\n");
            for (String line : extraLines) {
                w.write(line + "\n");
            }
        }
        return BotConfig.load(dir);
    }

    private StreamListener collect(final List<StreamEvent> sink) {
        return new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                sink.add(event);
            }
        };
    }

    private static List<StreamEvent.Kind> kinds(List<StreamEvent> events) {
        List<StreamEvent.Kind> out = new ArrayList<StreamEvent.Kind>();
        for (StreamEvent e : events) {
            out.add(e.kind());
        }
        return out;
    }

    private static Map<String, Object> schema(String... propertyNames) {
        Map<String, Object> properties = new java.util.LinkedHashMap<String, Object>();
        for (String name : propertyNames) {
            properties.put(name, Collections.<String, Object>singletonMap("type", "string"));
        }
        Map<String, Object> schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static ToolResult result(String content) {
        return new ToolResult(null, null, content, false, Collections.<String, Object>emptyMap());
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }

    private static ChatCompletionsResponse textReply(String content) {
        return response(content, Collections.<ToolCall>emptyList());
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return response("", Arrays.asList(calls));
    }

    private static ChatCompletionsResponse response(String content, List<ToolCall> toolCalls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, toolCalls, "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    private static String lastAssistantText(List<Msg> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MessageRole.ASSISTANT && msgs.get(i).getToolCalls().isEmpty()) {
                return msgs.get(i).getContent();
            }
        }
        return null;
    }

    private static Msg lastToolMsg(List<Msg> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MessageRole.TOOL) {
                return msgs.get(i);
            }
        }
        return null;
    }

    /** 脚本化 LLM 替身：按序吐出预设响应，并记录每次请求。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        private final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        private int callCount;
        private RuntimeException failing;

        ScriptedProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

        @Override
        public String name() {
            return "scripted";
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
            callCount++;
            if (failing != null) {
                throw failing;
            }
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
