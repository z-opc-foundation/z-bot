package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 网关不回传 usage（或回传全 0）时的记账回归：
 * kernel 会把缺失 usage 归一成 TokenUsage.empty()，BotAgent 必须走请求字符估算分支，
 * 否则压缩引擎永远收不到 update、阈值永远不触发（P3 真机 E2E 踩过的坑）。
 */
public class UsageFallbackCompressTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 全 0 usage 的脚本 LLM（模拟 bench 代理不回传 usage 的行为）。 */
    private static final class ZeroUsageLlm implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();

        @Override
        public String name() {
            return "zero-usage";
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
            return new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(
                            0, "收到", Collections.emptyList(), "stop")),
                    TokenUsage.empty(), "stop", null);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request,
                               java.util.function.Consumer<ChatCompletionsResponse> onChunk,
                               java.util.function.Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }

    @Test
    public void zeroUsage_fallsBackToCharEstimate_feedingCompressor() throws Exception {
        ZeroUsageLlm llm = new ZeroUsageLlm();
        CompressorEngine engine = new CompressorEngine(2000L);

        BotAgent agent = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .contextEngine(engine, new ContextEngineSummarizerStub())
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        try {
            // 6000 字符消息 → 估算 3000 tokens ≥ 2000×0.85 阈值
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                big.append("上下文填充段落。");
            }
            String reply = agent.chat(big.toString());
            assertEquals("收到", reply);
            assertTrue("零 usage 时必须按请求字符估算喂给压缩引擎，实际 contextTokens="
                    + engine.getContextTokens(), engine.getContextTokens() >= 1700);
        } finally {
            agent.shutdown();
        }
    }

    @Test
    public void zeroUsage_smallMessage_staysUnderThreshold() throws Exception {
        ZeroUsageLlm llm = new ZeroUsageLlm();
        CompressorEngine engine = new CompressorEngine(400_000L);

        BotAgent agent = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .contextEngine(engine, new ContextEngineSummarizerStub())
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        try {
            agent.chat("短消息");
            assertTrue("小消息估算不应越过大预算阈值", !engine.shouldCompress());
            assertTrue("估算仍应 >0（记账路径生效）", engine.getContextTokens() > 0);
        } finally {
            agent.shutdown();
        }
    }

    /** 测试桩：永不返回空摘要（本测试只验证记账，不验证压缩本身）。 */
    private static final class ContextEngineSummarizerStub
            implements com.zifang.z.agent.kernel.agent.ContextEngine.Summarizer {
        @Override
        public String summarize(List<com.zifang.z.agent.kernel.message.Msg> messages) {
            return "stub 摘要";
        }
    }
}
