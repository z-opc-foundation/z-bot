package com.zifang.z.bot.cliwrap;

import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.config.BotConfig;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI wrapper 引擎与 z-bot 内置 {@code BotAgent} 的桥。
 *
 * <p>{@code agent.engine=bot}（缺省）走内置 ReAct；配成 {@code claude-code /
 * hermes / opencode} 时，对话面（CLI 与 HTTP）把请求转交 {@link CliChatExecutor}，
 * 由外部 CLI 进程自己跑循环。命令/参数可用 {@code agent.engine.command /
 * agent.engine.args / agent.engine.working-dir} 覆盖，缺省按 type 给默认模板。</p>
 */
public final class CliEngineBridge {

    public static final String ENGINE_BOT = "bot";

    private CliEngineBridge() {
    }

    /** 配置声明的引擎；bot/空一律归一为 bot。 */
    public static String engineType(BotConfig config) {
        if (config == null) {
            return ENGINE_BOT;
        }
        String v = config.getCliEngine();
        return v == null || v.trim().isEmpty() ? ENGINE_BOT : v.trim();
    }

    public static boolean isBotEngine(BotConfig config) {
        return ENGINE_BOT.equals(engineType(config));
    }

    public static CliChatExecutor newExecutor() {
        CliChatExecutor executor = new CliChatExecutor();
        executor.setRegistry(new CliAgentWrapperRegistry(
                new ClaudeCodeWrapper(),
                new HermesProcessWrapper(),
                new OpencodeProcessWrapper()));
        return executor;
    }

    public static CliAgentConfig buildConfig(BotConfig config, String type) {
        String command = config != null && config.getCliEngineCommand() != null
                && !config.getCliEngineCommand().isEmpty()
                ? config.getCliEngineCommand()
                : CliAgentConfig.defaultCommand(type);
        List<String> args = config != null && config.getCliEngineArgs() != null
                && !config.getCliEngineArgs().isEmpty()
                ? new ArrayList<String>(config.getCliEngineArgs())
                : CliAgentConfig.defaultArgs(type);
        String workingDir = config != null && config.getCliEngineWorkingDir() != null
                && !config.getCliEngineWorkingDir().isEmpty()
                ? config.getCliEngineWorkingDir()
                : defaultWorkingDir();
        return new CliAgentConfig(type, type, type, command, args, workingDir);
    }

    /** 单轮同步：CLI 面用，stdout 只出最终回复。 */
    public static String chatSync(BotConfig config, String type, String prompt) throws IOException {
        ChatCompletionRequest request = new ChatCompletionRequest();
        ChatCompletionRequest.ChatMessage user = new ChatCompletionRequest.ChatMessage();
        user.setRole("user");
        user.setContent(prompt);
        List<ChatCompletionRequest.ChatMessage> messages = new ArrayList<ChatCompletionRequest.ChatMessage>();
        messages.add(user);
        request.setMessages(messages);
        request.setModel(type);
        return newExecutor().executeSync(buildConfig(config, type), request);
    }

    /** 单轮流式：HTTP 面用，sseOut 收 OpenAI chat.completions 形状的 SSE 帧，返回完整回复。 */
    public static String chatStream(BotConfig config, String type, String prompt, OutputStream sseOut)
            throws IOException {
        ChatCompletionRequest request = new ChatCompletionRequest();
        ChatCompletionRequest.ChatMessage user = new ChatCompletionRequest.ChatMessage();
        user.setRole("user");
        user.setContent(prompt);
        List<ChatCompletionRequest.ChatMessage> messages = new ArrayList<ChatCompletionRequest.ChatMessage>();
        messages.add(user);
        request.setMessages(messages);
        request.setModel(type);
        request.setStream(true);
        return newExecutor().executeStream(buildConfig(config, type), request, sseOut);
    }

    private static String defaultWorkingDir() {
        try {
            File dir = BotConfig.resolveWorkspaceDir(BotConfig.defaultConfigDir(), null);
            return dir == null ? new File(BotConfig.defaultConfigDir(), "workspace").getPath()
                    : dir.getPath();
        } catch (RuntimeException e) {
            return new File(BotConfig.defaultConfigDir(), "workspace").getPath();
        }
    }
}
