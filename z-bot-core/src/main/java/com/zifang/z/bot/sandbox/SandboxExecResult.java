package com.zifang.z.bot.sandbox;

/**
 * 沙箱命令执行结果。
 */
public final class SandboxExecResult {

    private final int exitCode;
    private final String output;
    private final boolean timedOut;

    public SandboxExecResult(int exitCode, String output, boolean timedOut) {
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.timedOut = timedOut;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }

    @Override
    public String toString() {
        if (timedOut) {
            return output + "\n[sandbox: timeout]";
        }
        return output;
    }
}
