package com.zifang.z.bot;

import com.zifang.z.bot.cli.AgentOptions;
import com.zifang.z.bot.cli.ChatCommand;
import com.zifang.z.bot.cli.GatewayCommand;
import com.zifang.z.bot.cli.McpCommand;
import com.zifang.z.bot.cli.PairCommand;
import com.zifang.z.bot.cli.ReplCommand;
import com.zifang.z.bot.cli.SendCommand;
import com.zifang.z.bot.cli.ServeCommand;
import com.zifang.z.bot.cli.SessionsCommand;
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
 *   z-bot serve --port 8080          HTTP + SSE + web 控制台（单通道）
 *   z-bot gateway --webhook-port 8090 多通道常驻（HTTP + Webhook，可选 pairing）
 *   z-bot status                     打印生效配置
 *   z-bot send webhook <conv> <text> 脚本化外发到 channel
 *   z-bot pair <8位码>                消费 gateway 发出的配对码
 *   z-bot mcp serve                  反向：把 z-bot 当 MCP server（stdio，只读会话/消息）
 * </pre>
 *
 * <p>模型 / key 默认取<b>当前 profile</b>（{@code -Dzbot.home} &gt; {@code ZBOT_HOME} &gt;
 * {@code ~/.zbot}）下的 {@code config.properties}，命令行选项只做覆盖。</p>
 */
@Command(name = "z-bot", mixinStandardHelpOptions = true, version = "z-bot 0.2.0",
        subcommands = {ChatCommand.class, ReplCommand.class, ServeCommand.class, GatewayCommand.class,
                StatusCommand.class, SessionsCommand.class, SendCommand.class, PairCommand.class,
                McpCommand.class, com.zifang.z.bot.cli.AcpCommand.class},
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
