package com.zifang.z.bot;

import com.zifang.z.bot.cli.AgentOptions;
import com.zifang.z.bot.cli.ChatCommand;
import com.zifang.z.bot.cli.ReplCommand;
import com.zifang.z.bot.cli.ServeCommand;
import com.zifang.z.bot.cli.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * z-bot CLI — 本地 ReAct agent（z-agent-kernel provider + 内置工具 + 沙箱）。
 *
 * <pre>
 *   z-bot "你好"                    单轮对话（等价于 chat 子命令）
 *   z-bot                           进入终端 TUI
 *   z-bot chat -v "读一下 README"    单轮 + 工具过程打到 stderr
 *   z-bot repl                       终端 TUI（/status /sessions /confirm …）
 *   z-bot serve --port 8080          HTTP + SSE + web 控制台
 *   z-bot status                     打印生效配置
 * </pre>
 *
 * <p>模型 / key 默认取 {@code ~/.zbot/config.properties}，命令行选项只做覆盖。</p>
 */
@Command(name = "z-bot", mixinStandardHelpOptions = true, version = "z-bot 0.2.0",
        subcommands = {ChatCommand.class, ReplCommand.class, ServeCommand.class, StatusCommand.class},
        description = "本地 agent 应用（ReAct + 工具 + 沙箱 + 会话）")
public class ZBot implements Callable<Integer> {

    @Mixin
    AgentOptions options = new AgentOptions();

    @Parameters(arity = "0..1", paramLabel = "PROMPT", description = "直接提问（省略则进入 repl）")
    String prompt;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ZBot()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) {
            ReplCommand repl = new ReplCommand();
            repl.options = options;
            return repl.call();
        }
        ChatCommand chat = new ChatCommand();
        chat.options = options;
        chat.prompt = prompt;
        return chat.call();
    }
}
