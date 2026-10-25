package com.zifang.z.bot.sandbox;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Agent 沙箱接口。
 */
public interface AgentSandbox extends Closeable {

    static AgentSandbox create(String workingDirectory) {
        return create(SandboxConfig.builder().workingDirectory(workingDirectory).build());
    }

    static AgentSandbox create(SandboxConfig config) {
        return new NativeAgentSandbox(config);
    }

    /**
     * 在沙箱内执行 shell 命令（{@code sh -c}）。
     */
    String exec(String command) throws IOException;

    /**
     * 在沙箱内执行 shell 命令，返回结构化结果。
     */
    SandboxExecResult execDetailed(String command) throws IOException;

    /**
     * 在沙箱内执行 argv（不经过 shell）。
     */
    SandboxExecResult execArgv(List<String> argv) throws IOException;

    /**
     * 工作目录（绝对路径）。
     */
    String getWorkingDirectory();

    /**
     * 当前沙箱配置。
     */
    SandboxConfig getConfig();

    @Override
    void close();
}
