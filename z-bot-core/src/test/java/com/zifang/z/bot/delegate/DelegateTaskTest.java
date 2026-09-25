package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.agent.IterationBudget;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
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
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * delegate_task 子代理树单测：脚本 LLM 同时充当父/子 agent 的模型
 * （恰好验证"子代理共享父 provider"），沙箱/会话/委托台账全在 {@link TemporaryFolder}。
 */
public class DelegateTaskTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private File sandboxDir;
    private File sessionDir;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
    }

    @Test
    public void delegateRunsChildAndReturnsReplyWithQuarterBudget() throws Exception {
        // 父 r1: 下发 delegate_task → 子 r1: 直接文本完成 → 父 r2: 汇总
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"find the answer\",\"label\":\"probe\"}")))
                .script(textReply("child-answer-42"))
                .script(textReply("summary: 42"));
        BotAgent agent = builder(null).delegateDepth(0)
                .budget(new IterationBudget(8, 40_000L))
                .build();

        assertTrue(agent.getToolkit().getToolNames().toString(),
                agent.getToolkit().getToolNames().contains("delegate_task"));
        String reply = agent.chat("go", StreamListener.NOOP);

        assertEquals("summary: 42", reply);
        BotAgent child = agent.getDelegation().lastChild;
        assertTrue("工具结果应包含子代理回复", reply != null);
        // 子代理预算 = 父的 1/4（kernel childBudget）
        assertEquals(2, child.context().budget().maxIterations());
        assertEquals(10_000L, child.context().budget().maxTokens());
        // 子代理不接入 center（builder(null) 本来就没有，这里防 config 模式回归）
        assertEquals(null, child.getCenterClient());
    }

    @Test
    public void depthLimitStripsDelegateToolFromChild() throws Exception {
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"leaf task\"}")))
                .script(textReply("leaf-done"))
                .script(textReply("final"));
        BotAgent agent = builder(configWith("agent.delegate.max.depth=1")).build();

        assertTrue(agent.getToolkit().getToolNames().contains("delegate_task"));
        agent.chat("go", StreamListener.NOOP);

        BotAgent child = agent.getDelegation().lastChild;
        assertFalse("深度用尽后子代理不应再有 delegate_task",
                child.getToolkit().getToolNames().contains("delegate_task"));
    }

    @Test
    public void delegateDisabledWhenMaxDepthZero() throws Exception {
        BotAgent agent = builder(configWith("agent.delegate.max.depth=0")).build();

        assertFalse(agent.getToolkit().getToolNames().contains("delegate_task"));
        assertEquals("未启用子代理委托", agent.describeAgents());
    }

    @Test
    public void asyncDelegationCompletesAndResultRetrievable() throws Exception {
        // submitAsync 不经过父 LLM：子代理直接消费脚本里的文本回复
        llm.script(textReply("async-done-ok"));
        BotAgent agent = builder(null).delegateDepth(0).build();

        String submitted = agent.submitBackground("background task x");
        assertTrue(submitted, submitted.contains("已提交异步委托"));

        String id = submitted.replaceFirst(".*已提交异步委托 (bg[0-9]+-[0-9]+).*", "$1");
        String result = waitForDone(agent, id, 5000);
        assertTrue(result, result.contains("async-done-ok"));
        assertTrue(agent.describeAgents(), agent.describeAgents().contains("DONE"));
    }

    // ===== helpers =====

    private BotAgent.Builder builder(BotConfig config) {
        return BotAgent.builder(config)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model");
    }

    private BotConfig configWith(String... extraLines) throws Exception {
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

    private static String waitForDone(BotAgent agent, String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String result = agent.backgroundResult(id);
        while (result.contains("QUEUED") || result.contains("RUNNING")) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(50);
            result = agent.backgroundResult(id);
        }
        return result;
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

    /** 脚本化 LLM 替身：父/子 agent 共用同一实例，按序吐出预设响应。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

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
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
