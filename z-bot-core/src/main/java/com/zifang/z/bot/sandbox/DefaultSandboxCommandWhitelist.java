package com.zifang.z.bot.sandbox;

import java.util.Collections;
import java.util.Set;

/**
 * 默认沙箱命令白名单：仅允许 {@code grep}。
 * <p>
 * 复刻自 z-team commit a64972fa 的 {@code DefaultSandboxCommandWhitelist}：
 * 作为兜底实现，避免沙箱在未配置时完全失控。
 * <p>
 * 实际生产环境应通过 {@code SandboxCommandWhitelistLoader} 从
 * {@code z_team_generic_config} 加载，key 为 {@code sandbox_allowed_commands}。
 */
public class DefaultSandboxCommandWhitelist implements SandboxCommandWhitelist {

    public static final String DEFAULT_AGENT_CODE = "*";
    /**
     * 全局单例：仅允许 grep 的白名单，用于 {@link z-bot exec 路径} 等默认场景。
     */
    public static final SandboxCommandWhitelist GREP_ONLY = new DefaultSandboxCommandWhitelist();
    private static final Set<String> DEFAULT_COMMANDS = Collections.singleton("grep");

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String agentCode() {
        return DEFAULT_AGENT_CODE;
    }

    @Override
    public Set<String> allowedCommands() {
        return DEFAULT_COMMANDS;
    }
}
