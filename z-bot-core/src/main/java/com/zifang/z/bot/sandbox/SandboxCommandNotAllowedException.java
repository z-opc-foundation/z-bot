package com.zifang.z.bot.sandbox;

/**
 * 沙箱命令白名单校验失败异常。
 * <p>
 * 当 Agent 沙箱尝试执行不在白名单内的命令时抛出，由 {@code SandboxCommandFilter}
 * 在 {@code NativeAgentSandbox.exec / execArgv} 入口拦截。
 */
public class SandboxCommandNotAllowedException extends SecurityException {

    private static final long serialVersionUID = 1L;

    private final String command;
    private final String agentCode;

    public SandboxCommandNotAllowedException(String message, String command, String agentCode) {
        super(message);
        this.command = command;
        this.agentCode = agentCode;
    }

    public String getCommand() {
        return command;
    }

    public String getAgentCode() {
        return agentCode;
    }
}
