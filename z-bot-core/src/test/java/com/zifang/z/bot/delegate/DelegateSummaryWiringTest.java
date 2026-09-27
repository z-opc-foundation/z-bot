package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P27c 第二条的<b>接线</b>层：{@code agent.delegate.max.summary.chars} 不只是解析出来摆着，
 * 它必须真的决定"子代理回复进父上下文时裁不裁、溢出文件落在哪"。
 *
 * <p>{@code SummaryBudgetTest} 量的是纯函数那一层；这里量的是 {@code DelegateManager}
 * 那两个朝向父模型的出口（同步回执 + {@code /background result}）。两边都留着，
 * 是因为"键有读点"这件事在源码里 grep 得到、在生产里只有走过这两道出口才算兑现。</p>
 */
public class DelegateSummaryWiringTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cfgDir;
    private File sandboxDir;
    private File sessionDir;

    @Before
    public void setUp() throws Exception {
        cfgDir = tmp.newFolder("cfg");
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
    }

    @Test
    public void syncExitTrimsIntoParentContextAndSpillsUnderProfileDir() throws Exception {
        String longReply = liney(20);                       // 2020 字符
        RecordingProvider llm = new RecordingProvider()
                .script(toolReply(call("p1", "delegate_task", "{\"task\":\"write a lot\"}")))
                .script(textReply(longReply))
                .script(textReply("parent-done"));
        BotAgent agent = builder(config("agent.delegate.max.summary.chars=300"), llm)
                .delegateDepth(0).build();

        agent.chat("go", StreamListener.NOOP);

        String delivered = toolContent(llm.requests.get(2));
        assertTrue("进父上下文的这份没被裁: " + head(delivered), delivered.contains("[SUMMARY TRUNCATED]"));
        assertTrue("裁完还有 " + delivered.length() + " 字符，预算是 300", delivered.length() < 300 + 600);
        assertTrue(delivered, delivered.contains(longReply.substring(0, 40)));   // 头段还在
        assertFalse("中间段漏进了父上下文: " + head(delivered), delivered.contains(line(10)));
        assertTrue(delivered, delivered.contains("原文共 " + longReply.length() + " 字符"));

        File spill = onlySpillFile();
        assertEquals("溢出文件必须是子代理全文", longReply,
                new String(Files.readAllBytes(spill.toPath()), StandardCharsets.UTF_8));
    }

    /** 反向那一支：{@code =0} 必须真的整份照回（"0 disables the ceiling"）。 */
    @Test
    public void capZeroDeliversTheWholeReplyAndWritesNothing() throws Exception {
        String longReply = liney(20);
        RecordingProvider llm = new RecordingProvider()
                .script(toolReply(call("p1", "delegate_task", "{\"task\":\"write a lot\"}")))
                .script(textReply(longReply))
                .script(textReply("parent-done"));
        BotAgent agent = builder(config("agent.delegate.max.summary.chars=0"), llm)
                .delegateDepth(0).build();

        agent.chat("go", StreamListener.NOOP);

        String delivered = toolContent(llm.requests.get(2));
        assertTrue("关掉上限却裁了: " + head(delivered), delivered.contains(longReply));
        assertFalse(delivered, delivered.contains("[SUMMARY TRUNCATED]"));
        assertEquals("关掉裁切就不该往 profile 里写文件", 0,
                summariesDir().isDirectory() ? summariesDir().listFiles().length : 0);
    }

    /** 第二个出口：{@code /background result} 取回的那份也过同一把尺。 */
    @Test
    public void asyncExitIsTrimmedByTheSameCap() throws Exception {
        RecordingProvider llm = new RecordingProvider()
                .script(textReply(liney(20)));
        BotAgent agent = builder(config("agent.delegate.max.summary.chars=300"), llm)
                .delegateDepth(0).build();

        String submitted = agent.submitBackground("write a lot");
        String id = submitted.replaceFirst(".*已提交异步委托 (bg[0-9]+-[0-9]+).*", "$1");
        String result = waitFor(agent, id, 8000);

        assertTrue(result, result.contains("DONE"));
        assertTrue("异步出口没裁: " + head(result), result.contains("[SUMMARY TRUNCATED]"));
        assertFalse("异步出口把中间段也带回来了: " + head(result), result.contains(line(10)));
        assertEquals("溢出目录按 configDir 推导（红线 1：不写宿主家目录）",
                1, summariesDir().listFiles().length);
    }

    // ===== helpers =====

    private File summariesDir() {
        return new File(cfgDir, "delegate/summaries");
    }

    private File onlySpillFile() {
        File[] files = summariesDir().listFiles();
        assertTrue("溢出目录里没有唯一文件: " + Arrays.toString(files),
                files != null && files.length == 1);
        return files[0];
    }

    private BotConfig config(String... extraLines) throws Exception {
        try (FileWriter w = new FileWriter(new File(cfgDir, "config.properties"))) {
            w.write("llm.provider=glm\n");
            w.write("glm.type=openai\n");
            w.write("glm.api.key=test-key\n");
            w.write("glm.base.url=http://127.0.0.1:1/v1\n");
            w.write("glm.model=glm-4\n");
            for (String line : extraLines) {
                w.write(line + "\n");
            }
        }
        return BotConfig.load(cfgDir);
    }

    private BotAgent.Builder builder(BotConfig config, LlmProvider llm) {
        return BotAgent.builder(config)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model");
    }

    private static String waitFor(BotAgent agent, String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String result = agent.backgroundResult(id);
        while (result.contains("QUEUED") || result.contains("RUNNING")) {
            if (System.currentTimeMillis() > deadline) {
                return result;
            }
            Thread.sleep(50);
            result = agent.backgroundResult(id);
        }
        return result;
    }

    private static String toolContent(ChatCompletionsRequest request) {
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.TOOL) {
                return m.getContent() == null ? "" : m.getContent();
            }
        }
        throw new AssertionError("请求里没有 TOOL 行：" + request.getMessages().size() + " 条消息");
    }

    private static String head(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String liney(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(line(i)).append('\n');
        }
        return sb.toString();
    }

    private static String line(int i) {
        StringBuilder sb = new StringBuilder(String.format("L%03d|", i));
        while (sb.length() < 100) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static ChatCompletionsResponse textReply(String content) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(
                        0, content, Collections.<ToolCall>emptyList(), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(
                        0, "", Arrays.asList(calls), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    private static final class RecordingProvider implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

        RecordingProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

        @Override
        public String name() {
            return "recording";
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
