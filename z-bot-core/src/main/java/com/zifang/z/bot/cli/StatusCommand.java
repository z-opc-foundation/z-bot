package com.zifang.z.bot.cli;

import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code z-bot status} — 打印最终生效的配置（不联网、不注册 center）。
 *
 * <p>API key 只报有无，绝不回显明文。</p>
 */
@Command(name = "status", description = "打印生效配置：provider / 模型 / 工具 / 沙箱 / 会话")
public class StatusCommand implements Callable<Integer> {

    @Mixin
    AgentOptions options = new AgentOptions();

    @Override
    public Integer call() {
        BotConfig config = options.loadConfig();
        BotConfig.Provider provider = config.activeProvider();
        File sandboxDir = options.sandboxDir();
        Toolkit toolkit = BuiltinTools.registerAll(new Toolkit(),
                new Sandbox(sandboxDir.getAbsolutePath()), config.getExecConfirmMode());
        SessionManager sessions = new SessionManager();
        List<SessionManager.SessionSummary> existing = sessions.listSessions();

        row("config", new File(config.getConfigDir(), "config.properties").getPath()
                + (config.isLoaded() ? "" : "  (不存在，用默认值)"));
        row("provider", provider.getCode() + "  type=" + provider.getType()
                + "  baseUrl=" + orDash(provider.getBaseUrl())
                + "  key=" + (isBlank(provider.getApiKey()) ? "none" : "set"));
        row("model", orDash(config.getModel()));
        row("agent", "steps=" + config.getMaxSteps() + " tokens=" + config.getMaxTokens()
                + " temperature=" + config.getTemperature() + " tool_choice=" + config.getToolChoice()
                + " exec_confirm=" + config.getExecConfirmMode());
        row("tools", toolkit.size() + "  " + String.join(", ", toolkit.getToolNames()));
        row("sandbox", sandboxDir.getPath());
        row("sessions", sessions.getSessionDir().getPath() + "  (" + existing.size() + " 个)");
        row("center", isBlank(config.getCenterUrl()) ? "(未配置，纯本地)" : config.getCenterUrl()
                + "  appCode=" + config.getAppCode());
        return 0;
    }

    private static void row(String key, String value) {
        System.out.println(String.format("  %-9s %s", key, value));
    }

    private static String orDash(String v) {
        return isBlank(v) ? "-" : v;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
