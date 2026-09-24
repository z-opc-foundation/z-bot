package com.zifang.z.bot.agent;

/**
 * 工具需要人工确认时抛出，中断 ReAct 循环。
 *
 * <p>调用方（{@link BotAgent#chat}）把它翻译成 {@code WAIT_CONFIRM:tool|args|reason}
 * 协议串，交给终端 /confirm 或 POST /bot/confirm 恢复执行。</p>
 */
public class ToolConfirmationNeeded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String toolName;
    private final String toolArgs;
    private final String reason;

    public ToolConfirmationNeeded(String toolName, String toolArgs, String reason) {
        super("工具 " + toolName + " 需要确认: " + reason);
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.reason = reason;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    public String getReason() {
        return reason;
    }
}
