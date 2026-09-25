package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.InterruptFlag;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P12 prompt 缓存不变量：system prompt 构建一次即逐字节重放，
 * 记忆 / 技能 / center 召回 / 时钟这些易变内容一律改道进 user 消息。
 *
 * <p>这里比的是<b>实际发给 provider 的字节</b>（替身把每一轮请求都记下来），
 * 不是被测对象的自述：从 {@code memory.getSystemPrompt()} 取字符串来比是自己查自己，
 * 拼请求时再动一次手就量不出来了。</p>
 */
public class SystemPromptCacheFreezeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;
    private File sessionDir;
    private File memoryDir;
    private File skillsDir;
    private TestMemoryStoreHolder memories;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        memoryDir = tmp.newFolder("memories");
        skillsDir = tmp.newFolder("skills");
        memories = new TestMemoryStoreHolder(memoryDir);
    }

    @Test
    public void twoChatsInOneSessionSendByteIdenticalSystemPrompt() throws Exception {
        memories.write("MEMORY.md", "旧记忆：部署走 250 机器");
        ScriptedLlm llm = new ScriptedLlm();
        BotAgent agent = newAgent(llm);

        llm.script(textReply("第一轮"));
        assertEquals("第一轮", agent.chat("第一句", StreamListener.NOOP));

        // 中途改盘：这正是缓存最容易破的时刻
        memories.write("MEMORY.md", "新记忆：中途写入的一行");
        memories.write("SOUL.md", "# SOUL\n\n中途改过的人格");
        writeSkill("deploy", "部署技能正文");

        llm.script(textReply("第二轮"));
        assertEquals("第二轮", agent.chat("第二句", StreamListener.NOOP));

        byte[] first = systemPromptBytesOf(llm.requests.get(0));
        byte[] second = systemPromptBytesOf(llm.requests.get(1));
        assertArrayEquals("两次 chat 的 system prompt 必须逐字节相同（缓存不变量）", first, second);
    }

    @Test
    public void everyStepOfAMultiStepTurnReplaysTheSameSystemBytes() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        Toolkit toolkit = new Toolkit();
        toolkit.register(Toolkit.of("echo", "回显", stringSchema("message"),
                args -> result("echoed")));
        BotAgent agent = BotAgent.builder(null)
                .provider(llm)
                .toolkit(toolkit)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .memoryStore(memories.store)
                .skillsRoot(skillsDir)
                .maxSteps(5)
                .withoutBuiltinTools()
                .build();

        llm.script(toolReply(call("c1", "echo", "{\"message\":\"x\"}")))
                .script(textReply("完成"));
        agent.chat("干活", StreamListener.NOOP);

        assertEquals(2, llm.requests.size());
        assertArrayEquals("同一轮里的每一步也必须重放同一份 system prompt",
                systemPromptBytesOf(llm.requests.get(0)), systemPromptBytesOf(llm.requests.get(1)));
    }

    @Test
    public void volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt()
            throws Exception {
        memories.write("MEMORY.md", "记忆行-A");
        memories.write("USER.md", "画像行-B");
        writeSkill("deploy", "技能正文-C");
        ScriptedLlm llm = new ScriptedLlm();
        BotAgent agent = newAgent(llm);

        llm.script(textReply("ok"));
        agent.chat("用户原话-D", StreamListener.NOOP);

        ChatCompletionsRequest sent = llm.requests.get(0);
        String system = sent.getMessages().get(0).getContent();
        assertFalse("记忆不许进 system prompt", system.contains("记忆行-A"));
        assertFalse("用户画像不许进 system prompt", system.contains("画像行-B"));
        assertFalse("技能指引不许进 system prompt", system.contains("技能正文-C"));
        assertFalse("时钟不许进 system prompt", system.contains("当前时间"));
        assertTrue("骨架还在（工具面 + 规则）", system.contains("可用工具") && system.contains("执行规则"));

        String user = userTextOf(sent);
        assertTrue("记忆改道进 user 消息", user.contains("记忆行-A"));
        assertTrue("画像改道进 user 消息", user.contains("画像行-B"));
        assertTrue("技能改道进 user 消息", user.contains("技能正文-C"));
        assertTrue("时钟改道进 user 消息", user.contains("当前时间"));
        assertTrue("用户原话仍在最后", user.endsWith("用户原话-D"));
        assertTrue("抬头要标清楚这是模板注入，不是用户原话",
                user.startsWith(BotAgent.VOLATILE_CONTEXT_HEADER));
    }

    @Test
    public void midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt() throws Exception {
        memories.write("MEMORY.md", "第一轮的记忆");
        ScriptedLlm llm = new ScriptedLlm();
        BotAgent agent = newAgent(llm);

        llm.script(textReply("r1"));
        agent.chat("一", StreamListener.NOOP);
        memories.write("MEMORY.md", "第二轮才写入的记忆");
        llm.script(textReply("r2"));
        agent.chat("二", StreamListener.NOOP);

        assertTrue("中途写的记忆下一轮就要看得见（改道之后才算生效）",
                userTextOf(llm.requests.get(1)).contains("第二轮才写入的记忆"));
        assertFalse("上一轮的 user 消息不该被回写",
                userTextOf(llm.requests.get(0)).contains("第二轮才写入的记忆"));
    }

    // ===== helpers =====

    private BotAgent newAgent(ScriptedLlm llm) {
        return BotAgent.builder(null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .memoryStore(memories.store)
                .skillsRoot(skillsDir)
                .maxSteps(5)
                .build();
    }

    /** {@code <skillsRoot>/<name>/SKILL.md}：技能正文是易变内容，只许走 user 消息。 */
    private void writeSkill(String name, String body) throws Exception {
        File dir = new File(skillsDir, name);
        dir.mkdirs();
        java.nio.file.Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: 测试技能\n---\n" + body + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] systemPromptBytesOf(ChatCompletionsRequest request) {
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.SYSTEM) {
                return m.getContent().getBytes(StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("请求里没有 system 消息");
    }

    private static String userTextOf(ChatCompletionsRequest request) {
        String last = null;
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.USER) {
                last = m.getContent();
            }
        }
        assertTrue("请求里没有 user 消息", last != null);
        return last;
    }

    private static Map<String, Object> stringSchema(String... propertyNames) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (String name : propertyNames) {
            properties.put(name, Collections.<String, Object>singletonMap("type", "string"));
        }
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static ToolResult result(String content) {
        return new ToolResult(null, null, content, false, Collections.<String, Object>emptyMap());
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

    /** 直接往记忆目录里写文件：绕开审批，专测「中途写盘会不会污染缓存前缀」。 */
    private static final class TestMemoryStoreHolder {
        final com.zifang.z.bot.memory.MemoryStore store;
        private final File dir;

        TestMemoryStoreHolder(File dir) {
            this.dir = dir;
            this.store = new com.zifang.z.bot.memory.MemoryStore(dir);
        }

        void write(String fileName, String content) throws Exception {
            java.nio.file.Files.write(new File(dir, fileName).toPath(),
                    content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 脚本化 LLM 替身：记下每一轮实际发出的请求。 */
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
            return scripted.isEmpty() ? textReply("脚本用尽") : scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
