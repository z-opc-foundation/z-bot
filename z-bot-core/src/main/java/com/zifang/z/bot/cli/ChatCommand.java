package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.cliwrap.CliEngineBridge;
import com.zifang.z.bot.config.BotConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code z-bot chat <消息>} — 单轮 agent 对话。
 *
 * <p>stdout 只输出最终回复（方便脚本管道），过程信息在 {@code --verbose} 时打到 stderr。</p>
 */
@Command(name = "chat", description = "单轮 agent 对话（带工具 / 沙箱 / 会话）")
public class ChatCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Option(names = {"-v", "--verbose"}, description = "把 step / 工具调用打到 stderr")
    boolean verbose;

    @Option(names = {"-e", "--engine"}, paramLabel = "ENGINE",
            description = "对话引擎：bot（缺省，内置 ReAct）或 claude-code / hermes / opencode（CLI wrapper）")
    String engine;

    @Parameters(index = "0", paramLabel = "PROMPT", description = "要问的内容")
    public String prompt;

    @Override
    public Integer call() {
        BotConfig config = options.loadConfig();
        String type = engine != null && !engine.trim().isEmpty() ? engine.trim() : config.getCliEngine();
        if (type != null && !type.trim().isEmpty() && !CliEngineBridge.ENGINE_BOT.equals(type.trim())) {
            return runCliEngine(config, type.trim(), prompt);
        }
        return run(options, prompt, verbose);
    }

    static int runCliEngine(BotConfig config, String engine, String prompt) {
        try {
            String reply = CliEngineBridge.chatSync(config, engine, prompt);
            System.out.println(reply);
            return 0;
        } catch (IOException e) {
            System.err.println("✗ " + e.getMessage());
            return 4;
        }
    }

    static int run(AgentOptions options, String prompt, boolean verbose) {
        BotAgent agent = options.newAgent();
        try {
            String reply = agent.chat(prompt, verbose ? printer() : StreamListener.NOOP);
            System.out.println(reply);
            if (reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX)) {
                System.err.println("危险命令待确认：" + reply.substring(BotAgent.WAIT_CONFIRM_PREFIX.length()));
                System.err.println("放行：z-bot repl 里输入 /confirm，或 POST /bot/confirm");
                return 5;
            }
            return reply.startsWith("Error:") ? 4 : 0;
        } finally {
            agent.shutdown();
        }
    }

    private static StreamListener printer() {
        return new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                if (event instanceof StreamEvent.StepStart) {
                    System.err.println("· step " + ((StreamEvent.StepStart) event).step);
                } else if (event instanceof StreamEvent.ToolCallRequest) {
                    StreamEvent.ToolCallRequest tc = (StreamEvent.ToolCallRequest) event;
                    System.err.println("→ " + tc.name + " " + tc.argumentsJson);
                } else if (event instanceof StreamEvent.ToolResult) {
                    StreamEvent.ToolResult tr = (StreamEvent.ToolResult) event;
                    System.err.println("← " + tr.name + (tr.success ? " ok" : " 失败: " + tr.error));
                } else if (event instanceof StreamEvent.ErrorEvent) {
                    System.err.println("✗ " + ((StreamEvent.ErrorEvent) event).cause.getMessage());
                } else if (event instanceof StreamEvent.Compacted) {
                    StreamEvent.Compacted cp = (StreamEvent.Compacted) event;
                    System.err.println("▤ compact " + cp.fromCount + " -> " + cp.toCount);
                }
            }
        };
    }
}
