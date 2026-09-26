package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P14 三位点的「它确实被调到了」：不看读数口径、不看代码，就在真 BotAgent 的循环里
 * 数 {@link CompressorEngine#siteHits(int)}。
 *
 * <p>每个位点一支具名测试；把循环里对应那一行摘掉，对应这支测试必须红（杠② 的 M4/M5/M6 就是注入这个）。</p>
 */
public class CompressionSiteInvocationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class ScriptedLlm implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        private final List<ChatCompletionsResponse> script = new ArrayList<ChatCompletionsResponse>();

        void addToolCall() {
            script.add(response("先看一眼", Collections.singletonList(new com.zifang.z.agent.kernel.message.ToolCall(
                    "call_1", "noop_tool", "{}"))));
        }

        void addText(String text) {
            script.add(response(text, Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList()));
        }

        /** 不回 usage 的一轮（让真实口径保持 0，好把「越线」这件事单独归因给某个位点）。 */
        void addTextNoUsage(String text) {
            script.add(new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(
                            0, text, Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                    TokenUsage.empty(), "stop", null));
        }

        private static ChatCompletionsResponse response(String content,
                                                        List<com.zifang.z.agent.kernel.message.ToolCall> calls) {
            return new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, calls, "stop")),
                    new TokenUsage(9000L, 5L, 9005L), calls.isEmpty() ? "stop" : "tool_calls", null);
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
            ChatCompletionsResponse r = script.isEmpty()
                    ? null : script.remove(0);
            return r == null ? text("兜底回复") : r;
        }

        private static ChatCompletionsResponse text(String t) {
            return new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(
                            0, t, Collections.emptyList(), "stop")),
                    new TokenUsage(10, 1, 11), "stop", null);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request,
                               java.util.function.Consumer<ChatCompletionsResponse> onChunk,
                               java.util.function.Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }

    private static final class NoopTool implements Tool {
        @Override
        public String getName() {
            return "noop_tool";
        }

        @Override
        public String getDescription() {
            return "空转工具，往上下文里灌一大段结果";
        }

        @Override
        public Map<String, Object> getSchema() {
            return Collections.emptyMap();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                sb.append("工具输出填充段落 ").append(i).append("。\n");
            }
            return ToolResult.text(sb.toString());
        }
    }

    private static final class Summarizer implements ContextEngine.Summarizer {
        @Override
        public String summarize(List<Msg> messages) {
            return "摘要（保留了全部事实）";
        }
    }

    private CompressorEngine engine(long window, int maxOutput, double pct) {
        return engine(window, maxOutput, pct, 4);
    }

    /** keepRecent=1：让「只有 system + user + assistant 三条」的桩历史也够压（否则先被 short-history 挡住）。 */
    private CompressorEngine engine(long window, int maxOutput, double pct, int keepRecent) {
        return new CompressorEngine(window, maxOutput, pct, keepRecent,
                CompressorEngine.DEFAULT_COOLDOWN_MILLIS, 2, 60_000L);
    }

    private BotAgent agent(LlmProvider llm, CompressorEngine engine, Toolkit toolkit,
                           SessionManager sm) throws Exception {
        return BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(tmp.newFolder("sandbox" + System.nanoTime()).getAbsolutePath()))
                .sessionManager(sm)
                .toolkit(toolkit)
                .contextEngine(engine, new Summarizer())
                .model("test-model")
                .maxSteps(4)
                .build();
    }

    private static void seed(BotAgent a, int n) {
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder("垫底历史 " + i + "：");
            while (sb.length() < 200) {
                sb.append("填充填充填充填充。");
            }
            a.getMemory().add(Msg.user(sb.toString()));
        }
    }

    /** 位点 1（轮首）：进入 ReAct 循环第一步必须评估一次。 */
    @Test
    public void turnStartSiteIsCalledEveryStep() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.addText("好");
        CompressorEngine e = engine(400_000L, 0, 0.50D);
        BotAgent a = agent(llm, e, new Toolkit(), new SessionManager(tmp.newFolder("s1")));
        try {
            assertEquals("好", a.chat("今天天气怎么样"));
            assertTrue("轮首位点一次都没被调到（siteHits[turn-start]="
                    + e.siteHits(CompressorEngine.SITE_TURN_START) + "）",
                    e.siteHits(CompressorEngine.SITE_TURN_START) >= 1);
        } finally {
            a.shutdown();
        }
    }

    /** 位点 2（API 调用前粗估）：真实 usage 还没回来之前，粗估越线就得当场拦一道。 */
    @Test
    public void preApiCallSiteGuardsWithCoarseEstimate() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.addTextNoUsage("第一轮收到");     // 不回 usage ⇒ 真实口径一直是 0
        llm.addText("好");
        // 窗口 1000、pct=0.5 ⇒ 触发线 500。同一 step 里位点 1 先看（0，不触发），
        // 位点 2 拿请求体粗估（≥500）判成该压 ⇒ 压这一次只能记在位点 2 头上。
        CompressorEngine e = engine(1000L, 0, 0.50D, 1);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append("上下文填充段落。");
        }
        BotAgent a = agent(llm, e, new Toolkit(), new SessionManager(tmp.newFolder("s2")));
        try {
            // 垫三条历史：位点 2 压的时候记忆里已经有 system + 3 垫的 + 本轮 user = 5 条
            seed(a, 3);
            assertEquals("位点 1 在真实口径为 0 时不该触发", 0, e.getCompressCount());
            a.chat(big.toString());
            assertTrue("API 前粗估位点没被调到",
                    e.siteHits(CompressorEngine.SITE_BEFORE_API_CALL) >= 1);
            assertTrue("粗估没进引擎（observationAtSite="
                            + e.observationAtSite(CompressorEngine.SITE_BEFORE_API_CALL) + "）",
                    e.observationAtSite(CompressorEngine.SITE_BEFORE_API_CALL) >= 500L);
            assertTrue("位点 2 拦下后必须真压一次（lastSkip=" + e.getLastSkipReason() + "）",
                    e.getCompressCount() >= 1);
        } finally {
            a.shutdown();
        }
    }

    /** 位点 3（工具批后）：工具输出灌进上下文之后必须再判一次，而不是等下一轮的首位点。 */
    @Test
    public void postToolBatchSiteSeesToolOutput() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.addToolCall();
        llm.addText("工具跑完了");
        Toolkit tk = new Toolkit();
        tk.register(new NoopTool());
        // usage.promptTokens=9000 ⇒ 轮首/粗估两位都不越线（线在 10000）；
        // 工具批灌进来的 ~3000 tokens 只有位点 3 看得见 ⇒ 这一支压的就是位点 3 拦下的那次。
        CompressorEngine e = engine(20_000L, 0, 0.50D, 1);
        BotAgent a = agent(llm, e, tk, new SessionManager(tmp.newFolder("s3")));
        try {
            seed(a, 3);
            assertEquals(0, e.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH));
            a.chat("用一下工具");
            assertTrue("工具批后位点没被调到（siteHits[post-tool]="
                            + e.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH) + "）",
                    e.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH) >= 1);
            assertTrue("工具批后观测没算进工具输出: "
                            + e.observationAtSite(CompressorEngine.SITE_AFTER_TOOL_BATCH),
                    e.observationAtSite(CompressorEngine.SITE_AFTER_TOOL_BATCH) >= 10_000L);
            assertTrue("位点 3 越线之后必须真压一次（lastSkip=" + e.getLastSkipReason() + "）",
                    e.getCompressCount() >= 1);
        } finally {
            a.shutdown();
        }
    }

    /** 关掉开关 ⇒ 三个位点照样被调到，但没有一个会触发压缩（配置位真的接在闸门上）。 */
    @Test
    public void disabledEngineStillEvaluatesButNeverCompresses() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.addText("好");
        CompressorEngine e = engine(1000L, 0, 0.01D).withEnabled(false);
        BotAgent a = agent(llm, e, new Toolkit(), new SessionManager(tmp.newFolder("s4")));
        try {
            a.chat("随便说点什么");
            assertFalse(e.shouldCompress());
            assertEquals(0, e.getCompressCount());
            assertEquals(CompressorEngine.SKIP_DISABLED, e.getLastSkipReason());
        } finally {
            a.shutdown();
        }
    }

}
