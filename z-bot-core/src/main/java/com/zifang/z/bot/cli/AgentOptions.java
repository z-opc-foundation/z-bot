package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Sandbox;
import picocli.CommandLine.Option;

import java.io.File;

/**
 * chat / repl / serve / status 共用的命令行选项 — 覆盖 {@code ~/.zbot/config.properties}。
 *
 * <p>选项是"缺省即不覆盖"：没给的字段沿用配置文件里同名 provider 的值，
 * 所以 {@code --api-key} 单用不会把已配好的 baseUrl 抹掉。</p>
 */
public class AgentOptions {

    @Option(names = {"-m", "--model"}, paramLabel = "MODEL",
            description = "模型：<model> 或 <provider>/<model>，例如 glm-4 / openai/gpt-4o-mini")
    String model;

    @Option(names = {"-p", "--provider"}, paramLabel = "CODE",
            description = "provider code（默认用配置里的 llm.provider）")
    String provider;

    @Option(names = {"--api-key"}, paramLabel = "KEY", description = "LLM API key")
    String apiKey;

    @Option(names = {"--base-url"}, paramLabel = "URL",
            description = "LLM base URL（OpenAI 兼容网关 / 本地代理）")
    String baseUrl;

    @Option(names = {"--exec-confirm"}, paramLabel = "MODE",
            description = "exec 确认模式: off | dangerous | all")
    String execConfirm;

    @Option(names = {"--yolo"},
            description = "跳过全部工具人工确认（等价 --exec-confirm off，启动时冻结，运行期不可改）")
    boolean yolo;

    @Option(names = {"--max-steps"}, paramLabel = "N", description = "ReAct 最大步数")
    Integer maxSteps;

    @Option(names = {"--sandbox"}, paramLabel = "DIR",
            description = "文件 / 命令沙箱根目录（默认 ~/.zbot/workspace）")
    String sandbox;

    @Option(names = {"--session"}, paramLabel = "ID", description = "启动时切到指定会话")
    String session;

    @Option(names = {"--new-session"}, description = "启动时新建会话")
    boolean newSession;

    @Option(names = {"--config-dir"}, paramLabel = "DIR", description = "配置目录（默认 ~/.zbot）")
    File configDir;

    /** 读配置并套用命令行覆盖。 */
    public BotConfig loadConfig() {
        BotConfig config = configDir == null ? BotConfig.load() : BotConfig.load(configDir);
        String code = provider;
        String modelId = model;
        if (modelId != null && modelId.indexOf('/') > 0) {
            int slash = modelId.indexOf('/');
            if (code == null || code.isEmpty()) {
                code = modelId.substring(0, slash);
            }
            modelId = modelId.substring(slash + 1);
        }
        config.useProvider(code, code == null ? null : typeOf(code), apiKey, baseUrl, modelId);
        if (maxSteps != null && maxSteps > 0) {
            config.setMaxSteps(maxSteps);
        }
        if (execConfirm != null && !execConfirm.trim().isEmpty()) {
            config.setExecConfirmMode(execConfirm.trim());
        }
        if (yolo) {
            config.setExecConfirmMode("off");
        }
        return config;
    }

    /** 按当前选项装配一个可直接对话的 agent。 */
    public BotAgent newAgent() {
        BotConfig config = loadConfig();
        warnIfUnusable(config);
        BotAgent.Builder builder = BotAgent.builder(config);
        builder.sandbox(new Sandbox(sandboxDir().getAbsolutePath()));
        BotAgent agent = builder.build();
        if (newSession) {
            agent.newSession();
        } else if (session != null && !session.trim().isEmpty()) {
            agent.switchSession(session.trim());
        }
        return agent;
    }

    /** {@code --sandbox} 优先，其次 {@code -Dzbot.sandbox}，最后 {@code ~/.zbot/workspace}。 */
    public File sandboxDir() {
        String defaultDir = System.getProperty("zbot.sandbox",
                System.getProperty("user.home") + "/.zbot/workspace");
        return new File(sandbox == null || sandbox.trim().isEmpty() ? defaultDir : sandbox.trim());
    }

    /** 只在真正缺 key 时提醒，避免本地占位 key 场景误报。 */
    public void warnIfUnusable(BotConfig config) {
        BotConfig.Provider provider = config.activeProvider();
        if (provider.getApiKey() == null || provider.getApiKey().trim().isEmpty()) {
            System.err.println("[z-bot] provider=" + provider.getCode() + " 没有 API key："
                    + "用 --api-key 或写进 ~/.zbot/config.properties 的 "
                    + provider.getCode() + ".api.key（本地网关可填占位值）");
        }
    }

    /** provider code → kernel provider type；OpenAI 兼容网关（minimax/spark/glm/…）都归 openai。 */
    static String typeOf(String code) {
        String c = code.toLowerCase();
        if ("anthropic".equals(c) || "claude".equals(c)) {
            return "anthropic";
        }
        if ("gemini".equals(c)) {
            return "gemini";
        }
        if ("deepseek".equals(c)) {
            return "deepseek";
        }
        if ("qwen".equals(c)) {
            return "qwen";
        }
        if ("dashscope".equals(c)) {
            return "dashscope";
        }
        return "openai";
    }
}
