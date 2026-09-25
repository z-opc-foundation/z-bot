package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.checkpoint.CheckpointManager;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.slash.SlashRegistry;
import com.zifang.z.bot.slash.SlashCommand;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * checkpoint 接线测试：脚本 LLM 经 write_file 写好 → 写坏 → /rollback 恢复，
 * 沙箱与影子仓库都在 {@link TemporaryFolder}，不碰 {@code ~/.zbot}。
 */
public class BotAgentCheckpointTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private File sandboxDir;
    private File sessionDir;
    private File storeDir;
    private BotAgent agent;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("本机无 git，跳过 checkpoint 测试", CheckpointManager.gitAvailable());
        llm = new ScriptedProvider();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        storeDir = tmp.newFolder("ckpt-store");
        agent = BotAgent.builder(null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .checkpointManager(new CheckpointManager(storeDir, sandboxDir))
                .build();
    }

    @Test
    public void writeThenCorruptThenRollbackRestoresGoodVersion() throws Exception {
        // 第一轮：写入 good —— 打快照 S1（空沙箱）后落盘
        llm.script(toolReply(call("c1", "write_file",
                        "{\"path\":\"notes.txt\",\"content\":\"good\"}")))
                .script(textReply("done1"));
        agent.chat("write good", StreamListener.NOOP);
        assertEquals("good", read("notes.txt"));

        // 第二轮：覆盖成 bad —— 打快照 S2（此刻文件还是 good）后落盘
        llm.script(toolReply(call("c2", "write_file",
                        "{\"path\":\"notes.txt\",\"content\":\"bad\"}")))
                .script(textReply("done2"));
        agent.chat("overwrite bad", StreamListener.NOOP);
        assertEquals("bad", read("notes.txt"));

        String reply = agent.rollbackCheckpoint("");

        assertTrue(reply, reply.contains("已回滚"));
        assertEquals("good", read("notes.txt"));

        // 列表里应有两次快照，* 标在刚回滚到的那次上
        String list = agent.checkpointManage("");
        assertTrue(list, list.contains("checkpoints (2)"));
        assertTrue(list, list.contains("write_file"));
    }

    @Test
    public void readOnlyToolDoesNotCreateSnapshot() throws Exception {
        llm.script(toolReply(call("c1", "echo", "{\"message\":\"hi\"}")))
                .script(textReply("ok"));
        agent.chat("echo", StreamListener.NOOP);

        String list = agent.checkpointManage("");

        assertTrue(list, list.contains("暂无 checkpoint"));
    }

    @Test
    public void slashRegistryExposesRollbackAndCheckpoints() throws Exception {
        SlashRegistry reg = SlashRegistry.withBuiltinCommands();
        SlashCommand rollback = reg.find("/rollback");
        SlashCommand checkpoints = reg.find("/checkpoints");
        assertNotNull("/rollback 未注册", rollback);
        assertNotNull("/checkpoints 未注册", checkpoints);

        llm.script(toolReply(call("c1", "write_file",
                        "{\"path\":\"a.txt\",\"content\":\"v1\"}")))
                .script(textReply("ok"));
        agent.chat("write", StreamListener.NOOP);
        // 第二轮写坏 —— 此刻的最新快照保存的是 v1 状态
        llm.script(toolReply(call("c2", "write_file",
                        "{\"path\":\"a.txt\",\"content\":\"broken\"}")))
                .script(textReply("ok"));
        agent.chat("corrupt", StreamListener.NOOP);
        assertEquals("broken", read("a.txt"));

        String list = checkpoints.execute(agent, "");
        assertTrue(list, list.contains("checkpoints (2)"));
        String back = rollback.execute(agent, "");
        assertTrue(back, back.contains("已回滚"));
        assertEquals("v1", read("a.txt"));
    }

    // ===== helpers =====

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File(sandboxDir, name).toPath()),
                StandardCharsets.UTF_8);
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

    /** 脚本化 LLM 替身：按序吐出预设响应。 */
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
