package com.zifang.z.bot;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * z-bot CLI 入口 — 对标 cc/Hermes.
 *
 * <p>子命令:
 * <ul>
 *   <li>{@code z-bot chat "你好"} — 单轮对话, 走 LLM 直连</li>
 *   <li>{@code z-bot serve --port 9099} — 启 HTTP server, 暴露 /v1/chat/completions</li>
 *   <li>{@code z-bot --help} — 帮助</li>
 * </ul>
 *
 * <p>对应的 z-agent-kernel runtime:
 * <ul>
 *   <li>chat: 调 LlmProvider.chat()</li>
 *   <li>serve: 启 JDK 内置 HttpServer, 接收 OpenAI 协议请求, 转发给 LlmProvider</li>
 * </ul>
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
            System.err.println("用法: z-bot chat \"你的消息\"  或  z-bot serve --port 9099");
            System.err.println("提示: 完整 CLI 用 picocli 子命令模式, 这里简化为两种模式: chat / serve");
            return 1;
        }
        return new ChatCommand(model, apiKey, baseUrl, prompt).execute();
    }
}