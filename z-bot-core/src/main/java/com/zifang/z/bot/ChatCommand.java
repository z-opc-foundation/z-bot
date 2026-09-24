package com.zifang.z.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.provider.AnthropicProvider;
import com.zifang.z.agent.kernel.llm.provider.DashScopeProvider;
import com.zifang.z.agent.kernel.llm.provider.DeepSeekProvider;
import com.zifang.z.agent.kernel.llm.provider.GeminiProvider;
import com.zifang.z.agent.kernel.llm.provider.OpenAIProvider;
import com.zifang.z.agent.kernel.llm.provider.QwenProvider;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * chat 子命令 — 单轮对话.
 *
 * <p>model 形如 "openai/gpt-4o-mini" / "anthropic/claude-3-5-sonnet" / "deepseek/deepseek-chat".
 * <p>根据 vendor 前缀选 kernel 默认 provider, 调 chat().
 */
public class ChatCommand {

    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final String prompt;

    public ChatCommand(String model, String apiKey, String baseUrl, String prompt) {
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.prompt = prompt;
    }

    public int execute() {
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("错误: 必须设置 API Key (--api-key 或环境变量 Z_BOT_API_KEY)");
            return 2;
        }
        LlmProvider provider = selectProvider(model);
        if (provider == null) {
            System.err.println("错误: 不支持的 vendor: " + model + " (支持 openai/anthropic/deepseek/qwen/dashscope/gemini, 形如 vendor/model)");
            return 3;
        }
        try {
            List<Msg> messages = new ArrayList<Msg>();
            messages.add(new Msg(MessageRole.USER, prompt));
            ChatCompletionsRequest req = new ChatCompletionsRequest(
                    stripVendor(model), messages, null, 0.7, null, 1024, false, null);
            ChatCompletionsResponse resp = provider.chat(req);
            if (!resp.getChoices().isEmpty()) {
                System.out.println(resp.getChoices().get(0).getContent());
            } else {
                System.err.println("警告: LLM 返回空响应");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("LLM 调用失败: " + e.getMessage());
            return 4;
        }
    }

    /**
     * 根据 model 前缀选 provider.
     */
    private LlmProvider selectProvider(String model) {
        if (model == null) return null;
        int slash = model.indexOf('/');
        String vendor = slash < 0 ? "openai" : model.substring(0, slash);
        String apiBase = baseUrl == null || baseUrl.isEmpty() ? null : baseUrl;
        switch (vendor.toLowerCase()) {
            case "openai":
                return new OpenAIProvider(apiKey, apiBase);
            case "anthropic":
            case "claude":
                return new AnthropicProvider(apiKey, apiBase);
            case "deepseek":
                return new DeepSeekProvider(apiKey, apiBase);
            case "qwen":
                return new QwenProvider(apiKey, apiBase);
            case "dashscope":
                return new DashScopeProvider(apiKey, apiBase);
            case "gemini":
                return new GeminiProvider(apiKey, apiBase);
            default:
                return null;
        }
    }

    private static String stripVendor(String model) {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(slash + 1);
    }
}