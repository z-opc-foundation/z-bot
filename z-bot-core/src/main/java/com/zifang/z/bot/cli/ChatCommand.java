package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Parameters(index = "0", paramLabel = "PROMPT", description = "要问的内容")
    public String prompt;

    @Override
    public Integer call() {
        return run(options, prompt, verbose);
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
                }
            }
        };
    }
}
