package com.zifang.z.bot.cliwrap;

import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.io.IOException;
import java.io.OutputStream;

/**
 * CLI Agent 执行抽象接口。
 *
 * <p>每种 CLI（claude-code / hermes / 其他）实现一个 wrapper，
 * 由 {@link CliAgentWrapperRegistry} 按 {@link CliAgentConfig#getType()} 路由。
 *
 * <p>实现方式不限：
 * <ul>
 *   <li>{@code ClaudeCodeWrapper} — subprocess + stream-json 解析</li>
 *   <li>{@code HermesProcessWrapper} — HTTP gateway（hermes API server）</li>
 *   <li>{@code OpencodeProcessWrapper} — HTTP API（opencode server）</li>
 * </ul>
 */
public interface CliAgentWrapper {

    /**
     * 流式执行：yield 逐 token delta，包成 OpenAI SSE chunk 写入 sseOut。
     *
     * @param config  CLI Agent 配置（command/args/workingDir/type）
     * @param request 聊天请求（含 messages）
     * @param sseOut  SSE 输出流（已设置好 text/event-stream 头）
     * @return 完整回复文本（用于持久化）
     */
    String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut) throws IOException;

    /**
     * 非流式执行：聚合完整文本返回。
     */
    String syncChat(CliAgentConfig config, ChatCompletionRequest request) throws IOException;

    /**
     * 该 wrapper 支持的 CLI type（如 "claude-code" / "hermes"）。
     */
    boolean supports(String type);
}
