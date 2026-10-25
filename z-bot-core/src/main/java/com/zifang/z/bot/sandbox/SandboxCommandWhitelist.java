package com.zifang.z.bot.sandbox;

import java.util.Set;

/**
 * 沙箱命令白名单接口。
 * <p>
 * 复刻自 z-team commit 36b84904 的 {@code SandboxCommandWhitelist}：
 * 定义 Agent 沙箱（{@code NativeAgentSandbox}）允许执行的 OS 命令集合。
 * <p>
 * 实现类通常按 agent 维度（agent code）缓存白名单，避免每次都查库。
 */
public interface SandboxCommandWhitelist {

    /**
     * 该白名单是否启用。
     * <p>false 时 {@link SandboxCommandFilter} 跳过校验（兼容未配置场景）。
     */
    boolean isEnabled();

    /**
     * 该白名单对应 agent 标识（{@code agentCode} 或全局 {@code "*"}）。
     */
    String agentCode();

    /**
     * 允许的可执行文件 basename 集合（如 {@code ["grep", "cat", "ls"]}）。
     * <p>大小写敏感。
     */
    Set<String> allowedCommands();
}
