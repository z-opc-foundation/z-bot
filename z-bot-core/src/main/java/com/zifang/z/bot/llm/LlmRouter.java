package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.provider.AnthropicProvider;
import com.zifang.z.agent.kernel.llm.provider.DashScopeProvider;
import com.zifang.z.agent.kernel.llm.provider.DeepSeekProvider;
import com.zifang.z.agent.kernel.llm.provider.GeminiProvider;
import com.zifang.z.agent.kernel.llm.provider.OpenAIProvider;
import com.zifang.z.agent.kernel.llm.provider.QwenProvider;
import com.zifang.z.bot.config.BotConfig;

/**
 * 把 {@link BotConfig.Provider} 映射成 kernel 的 {@link LlmProvider} 实例。
 *
 * <p>MiniMax / 星火 MaaS / 各家 OpenAI 兼容网关都归到 {@code openai} 类型，
 * 只靠 baseUrl 区分；只有 Anthropic / Gemini 这类非 OpenAI 协议才走各自的 provider。</p>
 */
public final class LlmRouter {

    private LlmRouter() {
    }

    public static LlmProvider create(BotConfig.Provider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        String apiKey = provider.getApiKey();
        String baseUrl = emptyToNull(provider.getBaseUrl());
        String type = provider.getType() == null ? "openai" : provider.getType().toLowerCase();
        switch (type) {
            case "openai":
            case "minimax":
            case "spark":
            case "ollama":
            case "openai-compatible":
                return baseUrl == null ? new OpenAIProvider(apiKey) : new OpenAIProvider(apiKey, baseUrl);
            case "anthropic":
            case "claude":
                return baseUrl == null ? new AnthropicProvider(apiKey) : new AnthropicProvider(apiKey, baseUrl);
            case "deepseek":
                return baseUrl == null ? new DeepSeekProvider(apiKey) : new DeepSeekProvider(apiKey, baseUrl);
            case "qwen":
                return baseUrl == null ? new QwenProvider(apiKey) : new QwenProvider(apiKey, baseUrl);
            case "dashscope":
                return baseUrl == null ? new DashScopeProvider(apiKey) : new DashScopeProvider(apiKey, baseUrl);
            case "gemini":
                return baseUrl == null ? new GeminiProvider(apiKey) : new GeminiProvider(apiKey, baseUrl);
            default:
                throw new IllegalArgumentException("不支持的 provider type: " + provider.getType()
                        + " (支持 openai/anthropic/deepseek/qwen/dashscope/gemini)");
        }
    }

    private static String emptyToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }
}
