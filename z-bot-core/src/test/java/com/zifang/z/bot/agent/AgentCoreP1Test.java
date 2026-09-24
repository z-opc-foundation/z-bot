package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P1 agent 核心能力单测：预算/grace、协作式中断、steer 两档、并行工具批次。
 * LLM 用脚本替身，沙箱与会话都落在 TemporaryFolder，不碰 {@code ~/.zbot}。
 */
public class AgentCoreP1Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedLlm llm;
    private Toolkit toolkit;
    private List<Map<String, Object>> executedArgs;
    private File sandboxDir;
    private File sessionDir;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedLlm();
        executedArgs = new ArrayList<Map<String, Object>>();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        toolkit = new Toolkit();
        toolkit.register(Toolkit.of("echo", "回显", schema("message"), args ->
                result("echoed:" + str(args, "message"))));
        toolkit.register(Toolkit.of("slow_echo", "慢速回显", schema("message"), args -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result("slow:" + str(args, "message"));
        }), true);
        toolkit.register(Toolkit.of("serial_echo", "串行回显", schema("message"), args -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result("serial:" + str(args, "message"));
        }));
        toolkit.register(Toolkit.of("stopper", "请求停止后回显", schema("message"), args -> {
            executedArgs.add(args);
            agent.stop();
            return result("stopping");
        }));
    }

    // ===== 预算与 grace =====

    @Test
    public void graceCallGivesModelAFinalChance() {
        llm.script(toolReply(call("c1", "echo", "{\"message\":\"x\"}")))
                .script(textReply("收尾答案"));

        String reply = newAgent(1).chat("loop", StreamListener.NOOP);

        assertEquals("收尾答案", reply);
        assertEquals(2, llm.callCount);
        assertTrue("grace 收尾指令应进消息流", containsUserText(llm.requests.get(1), "[system] 预算即将耗尽"));
        assertEquals(2, agent.context().budget().apiCalls());
    }

    @Test
    public void budgetExhaustedAfterGraceAlsoReturnsToolCalls() {
        for (int i = 0; i < 3; i++) {
            llm.script(toolReply(call("c" + i, "echo", "{}")));
        }

        String reply = newAgent(2).chat("loop forever", StreamListener.NOOP);

        assertTrue(reply, reply.contains("已达到预算上限(steps=3/2"));
        assertEquals(3, llm.callCount);
    }

    @Test
    public void tokenUsageFromProviderIsRecordedIntoBudget() {
        llm.script(textReply("hello"));

        newAgent(5).chat("hi", StreamListener.NOOP);

        assertEquals(18L, agent.context().budget().tokensUsed());
    }

    // ===== 协作式中断 =====

    @Test
    public void stopRequestedInsideToolAbortsBeforeNextTool() {
        llm.script(toolReply(
                call("c1", "stopper", "{\"message\":\"first\"}"),
                call("c2", "echo", "{\"message\":\"second\"}")))
                .script(textReply("never reached"));

        String reply = newAgent(5).chat("batch", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith("已中止"));
        // 串行批次里第 2 个工具前的 checkpoint 生效，未执行
        assertEquals(1, executedArgs.size());
        assertFalse(agent.isRunning());
    }

    @Test
    public void stopRequestedByListenerAbortsBeforeNextLlmCall() {
        llm.script(toolReply(call("c1", "echo", "{}")));

        final BotAgent running = newAgent(5);
        String reply = running.chat("loop", new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                if (event.kind() == StreamEvent.Kind.TOOL_RESULT) {
                    running.stop();
                }
            }
        });

        assertTrue(reply, reply.startsWith("已中止"));
        assertEquals(1, llm.callCount);
        assertTrue(running.isStopRequested());
        // 中断后下一轮 chat 会被 reset
        llm.script(textReply("fresh"));
        assertEquals("fresh", running.chat("again", StreamListener.NOOP));
    }

    // ===== steer / queue =====

    @Test
    public void steerDuringRunIsInjectedAtToolGap() {
        llm.script(toolReply(call("c1", "echo", "{\"message\":\"work\"}")))
                .script(textReply("收到"));

        List<StreamEvent> events = new ArrayList<StreamEvent>();
        String reply = newAgent(5).chat("干活", new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                events.add(event);
                if (event.kind() == StreamEvent.Kind.TOOL_RESULT) {
                    agent.steer("改用中文");
                }
            }
        });

        assertEquals("收到", reply);
        assertTrue(containsUserText(llm.requests.get(1), "[User steer]: 改用中文"));
        boolean steerEvent = false;
        for (StreamEvent e : events) {
            steerEvent |= e.kind() == StreamEvent.Kind.STEER;
        }
        assertTrue("应回抛 SteerInjected 事件", steerEvent);
    }

    @Test
    public void queuedWhileIdleMergesIntoNextUserMessage() {
        agent = newAgent(5);
        agent.steer("排队的事");

        llm.script(textReply("ok"));
        agent.chat("正事", StreamListener.NOOP);

        List<Msg> msgs = llm.requests.get(0).getMessages();
        // system + user
        assertEquals(MessageRole.USER, msgs.get(1).getRole());
        assertTrue(msgs.get(1).getContent(), msgs.get(1).getContent().contains("正事"));
        assertTrue(msgs.get(1).getContent(), msgs.get(1).getContent().contains("[User queued]: 排队的事"));
    }

    // ===== 并行工具批次 =====

    @Test
    public void parallelSafeBatchRunsConcurrently() {
        long start = System.currentTimeMillis();
        llm.script(toolReply(
                call("p1", "slow_echo", "{\"message\":\"a\"}"),
                call("p2", "slow_echo", "{\"message\":\"b\"}")))
                .script(textReply("both done"));

        String reply = newAgent(5).chat("parallel", StreamListener.NOOP);

        assertEquals("both done", reply);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("并行批次应显著快于串行(2x300ms)，实测 " + elapsed + "ms", elapsed < 450);
        // 两个结果都按原顺序回灌
        List<Msg> msgs = agent.getMemory().getMessages();
        int found = 0;
        for (Msg m : msgs) {
            if (m.getRole() == MessageRole.TOOL) {
                found++;
            }
        }
        assertEquals(2, found);
    }

    @Test
    public void nonParallelSafeBatchRunsSerially() {
        long start = System.currentTimeMillis();
        llm.script(toolReply(
                call("s1", "serial_echo", "{\"message\":\"a\"}"),
                call("s2", "serial_echo", "{\"message\":\"b\"}")))
                .script(textReply("done"));

        newAgent(5).chat("serial", StreamListener.NOOP);

        long elapsed = System.currentTimeMillis() - start;
        assertTrue("无 parallel-safe 标记必须串行(≥2x300ms)，实测 " + elapsed + "ms", elapsed >= 550);
    }

    @Test
    public void parallelBatchEmitsEventsInOrder() {
        llm.script(toolReply(
                call("p1", "slow_echo", "{\"message\":\"a\"}"),
                call("p2", "slow_echo", "{\"message\":\"b\"}")))
                .script(textReply("done"));

        List<StreamEvent> events = new ArrayList<StreamEvent>();
        newAgent(5).chat("parallel", collect(events));

        List<String> toolEvents = new ArrayList<String>();
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.ToolCallRequest) {
                toolEvents.add("req:" + ((StreamEvent.ToolCallRequest) e).name);
            } else if (e instanceof StreamEvent.ToolResult) {
                toolEvents.add("res:" + ((StreamEvent.ToolResult) e).name);
            }
        }
        // listener 单线程视角：请求按序、结果按序
        assertEquals(Arrays.asList("req:slow_echo", "req:slow_echo", "res:slow_echo", "res:slow_echo"),
                toolEvents);
    }

    // ===== helpers =====

    private BotAgent newAgent(int maxSteps) {
        agent = BotAgent.builder((BotConfig) null)
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

    private StreamListener collect(final List<StreamEvent> sink) {
        return new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                sink.add(event);
            }
        };
    }

    private static boolean containsUserText(ChatCompletionsRequest request, String needle) {
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.USER && m.getContent() != null
                    && m.getContent().contains(needle)) {
                return true;
            }
        }
        return false;
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

    /** 脚本化 LLM 替身，按序吐响应并记录请求。 */
    private static final class ScriptedLlm implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        int callCount;

        ScriptedLlm script(ChatCompletionsResponse response) {
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
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
