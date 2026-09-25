package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.memory.MemoryStore;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * memory 工具接线测试：append 直写、rewrite 走 WAIT_CONFIRM 审批、
 * SOUL/记忆注入 system prompt。全部落 TemporaryFolder。
 */
public class BotAgentMemoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private File sandboxDir;
    private File sessionDir;
    private MemoryStore store;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        store = new MemoryStore(tmp.newFolder("memories"));
    }

    /** agent 在测试体里构建 — 先写好记忆再装配，system prompt 才能带上注入内容。 */
    private BotAgent buildAgent() {
        return BotAgent.builder(null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .memoryStore(store)
                .build();
    }

    @Test
    public void soulAndMemoryAreInjectedIntoSystemPrompt() throws Exception {
        store.appendMemory("记住：部署走 250 机器");
        store.appendUser("用户偏好简短回复");
        agent = buildAgent();
        llm.script(textReply("ok"));

        agent.chat("hi", StreamListener.NOOP);

        ChatCompletionsRequest sent = llm.requests.get(0);
        String system = sent.getMessages().get(0).getContent();
        assertTrue(system, system.contains("z-bot"));          // 默认 SOUL 人格
        assertTrue(system, system.contains("部署走 250 机器"));  // MEMORY 注入
        assertTrue(system, system.contains("用户偏好简短回复"));  // USER 注入
        // 工具列表里有 memory
        assertTrue(sent.getTools().toString(), sent.getTools().toString().contains("memory"));
    }

    @Test
    public void memoryAppendWritesFileDirectly() throws Exception {
        agent = buildAgent();
        llm.script(toolReply(call("c1", "memory",
                        "{\"action\":\"append\",\"section\":\"memory\",\"content\":\"项目用 Java 8\"}")))
                .script(textReply("记住了"));

        String reply = agent.chat("记住这个", StreamListener.NOOP);

        assertEquals("记住了", reply);
        assertTrue(store.readMemory(), store.readMemory().contains("项目用 Java 8"));
    }

    @Test
    public void memoryRewriteSuspendsUntilConfirmed() throws Exception {
        agent = buildAgent();
        store.appendMemory("old-entry");
        llm.script(toolReply(call("c1", "memory",
                        "{\"action\":\"rewrite\",\"section\":\"memory\",\"content\":\"new-page\"}")));

        String reply = agent.chat("重写记忆", StreamListener.NOOP);

        assertTrue(reply, reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX + "memory|"));
        assertTrue("未确认前不得落盘", store.readMemory().contains("old-entry"));
        assertTrue(agent.memoryManage("pending"), agent.memoryManage("pending").contains("rewrite"));

        String after = agent.confirmTool("memory",
                "{\"action\":\"rewrite\",\"section\":\"memory\",\"content\":\"new-page\"}");
        assertTrue(after, after.contains("已重写 MEMORY.md"));
        assertEquals("new-page", store.readMemory());
        assertFalse(store.readMemory(), store.readMemory().contains("old-entry"));
        assertTrue(agent.memoryManage("pending"), agent.memoryManage("pending").contains("没有待审批"));
    }

    // ===== helpers =====

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

    /** 脚本化 LLM 替身：按序吐出预设响应。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();

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
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
