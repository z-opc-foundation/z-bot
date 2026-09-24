package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.channel.TerminalChannel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * {@code z-bot repl} — 终端 TUI（不带子命令直接运行 {@code z-bot} 时进入同一模式）。
 */
@Command(name = "repl", aliases = "interactive", description = "交互式终端会话")
public class ReplCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Override
    public Integer call() throws Exception {
        BotAgent agent = options.newAgent();
        try {
            new TerminalChannel(agent).run();
        } finally {
            agent.shutdown();
        }
        return 0;
    }
}
