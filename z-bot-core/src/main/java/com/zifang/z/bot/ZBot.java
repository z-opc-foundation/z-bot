package com.zifang.z.bot;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * z-bot CLI 入口 — 对标 cc/Hermes.
 *
 * <p>用法: {@code z-bot [-m vendor/model] --api-key <key> [--base-url <url>] "消息"} — 单轮对话, 走 z-agent-kernel LlmProvider。
 * <p>{@code z-bot --help} 看完整选项。HTTP serve 模式尚未实现。
 */
@Command(name = "z-bot", mixinStandardHelpOptions = true, version = "z-bot 0.1.0",
        description = "本地 agent 应用 (对标 Claude Code / Hermes)")
public class ZBot implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "LLM 模型 (默认 openai/gpt-4o-mini)", defaultValue = "openai/gpt-4o-mini")
    String model;

    @Option(names = {"--api-key"}, description = "LLM API Key (可走环境变量 Z_BOT_API_KEY)", defaultValue = "${Z_BOT_API_KEY:-}")
    String apiKey;

    @Option(names = {"--base-url"}, description = "LLM base URL (可选)", defaultValue = "${Z_BOT_BASE_URL:-}")
    String baseUrl;

    @Parameters(arity = "0..1", description = "对话内容 (chat 子命令)")
    String prompt;

    public static void main(String[] args) {
        int rc = new CommandLine(new ZBot()).execute(args);
        System.exit(rc);
    }

    @Override
    public Integer call() {
        if (prompt == null || prompt.isEmpty()) {
            System.err.println("用法: z-bot [-m vendor/model] --api-key <key> [--base-url <url>] \"你的消息\"");
            System.err.println("示例: z-bot -m openai/gpt-4o-mini --base-url http://127.0.0.1:18180/v1 --api-key k \"你好\"");
            return 1;
        }
        return new ChatCommand(model, apiKey, baseUrl, prompt).execute();
    }
}