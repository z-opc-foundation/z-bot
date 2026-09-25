package com.zifang.z.bot.config;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * z-bot 运行配置 — 来自 ~/.zbot/config.properties + 环境变量 + 命令行覆盖.
 *
 * <p>provider 段的键式统一为 {@code <code>.api.key} / {@code <code>.base.url} /
 * {@code <code>.model} / {@code <code>.type}；minimax 与星火 MaaS 是历史遗留 code，
 * 保留在 {@link #LEGACY_PROVIDERS} 里以兼容既有配置文件。</p>
 */
public final class BotConfig {

    /** 已在 properties 里出现过的 code，即使没有 providers 声明也会被识别。 */
    private static final Map<String, String> LEGACY_PROVIDERS = new LinkedHashMap<String, String>();

    static {
        LEGACY_PROVIDERS.put("minimax", "https://api.minimax.chat/v1");
        LEGACY_PROVIDERS.put("spark", "https://maas-coding-api.cn-huabei-1.xf-yun.com/v2");
    }

    private static final String DEFAULT_CONFIG_DIR = System.getProperty("user.home") + "/.zbot";

    private final File configDir;
    private final Map<String, Provider> providers = new LinkedHashMap<String, Provider>();
    private String activeProviderCode;
    private String model;
    private int maxSteps = 50;
    private int maxTokens = 8192;
    /** 整个会话轮的累计 token 预算（kernel IterationBudget 口径），与单次请求的 maxTokens 区分。 */
    private long tokenBudget = 400_000L;
    private double temperature = 0.7;
    private String toolChoice = "required";
    private String execConfirmMode = "dangerous";
    /** exec 免确认命令前缀白名单（用户逐条授权的持久化产物，来自 agent.exec.confirm.whitelist）。 */
    private List<String> execConfirmWhitelist = new ArrayList<String>();
    /** LLM 调用可重试错误（429/5xx/超时）的额外重试次数。 */
    private int retryMaxAttempts = 2;
    /** 重试退避基数（毫秒），第 n 次重试等待 backoff * n。 */
    private long retryBackoffMs = 1000L;
    /** 主模型重试耗尽后的降级模型 id 列表（llm.fallback.models，逗号分隔）。 */
    private List<String> fallbackModels = new ArrayList<String>();
    /** 会话持久化库路径（agent.state.db）；空 = 只用 JSON 会话文件。 */
    private String stateDbPath;
    /** delegate_task 子代理最大委托深度（agent.delegate.max.depth）：0=关闭，默认 2。 */
    private int delegateMaxDepth = 2;
    /** 异步委托并发宽度（agent.delegate.max.children）：同时在跑的子代理上限，默认 3。 */
    private int delegateMaxChildren = 3;
    /** MCP server 列表（mcp.servers），每一项 name + stdio 命令行。空 = 不接 MCP。 */
    private List<McpServerEntry> mcpServers = new ArrayList<McpServerEntry>();
    private String centerUrl;
    private String appCode = "default-chat";
    private boolean loaded;

    private BotConfig(File configDir) {
        this.configDir = configDir;
    }

    /**
     * 读 {@value #DEFAULT_CONFIG_DIR}/config.properties；文件不存在时返回仅含环境变量的默认配置。
     */
    public static BotConfig load() {
        return load(new File(DEFAULT_CONFIG_DIR));
    }

    public static BotConfig load(File dir) {
        BotConfig cfg = new BotConfig(dir);
        File f = new File(dir, "config.properties");
        Properties props = new Properties();
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                props.load(fis);
                cfg.loaded = true;
            } catch (Exception e) {
                System.err.println("[BotConfig] 读取 " + f + " 失败: " + e.getMessage());
            }
        }
        cfg.fromProperties(props);
        cfg.fromEnvironment();
        if (cfg.activeProviderCode == null) {
            cfg.activeProviderCode = "openai";
        }
        return cfg;
    }

    private void fromProperties(Properties props) {
        for (Map.Entry<String, String> e : LEGACY_PROVIDERS.entrySet()) {
            String code = e.getKey();
            String key = trim(props.getProperty(code + ".api.key"));
            String url = trim(props.getProperty(code + ".base.url"));
            String mdl = trim(props.getProperty(code + ".model"));
            String type = trim(props.getProperty(code + ".type"));
            if (key.isEmpty() && url.isEmpty() && mdl.isEmpty()) {
                continue;
            }
            addProvider(code, type.isEmpty() ? "openai" : type,
                    key, url.isEmpty() ? e.getValue() : url, mdl);
        }

        // providers=minimax,spark,glm —— 显式声明的 provider 列表
        for (String code : splitCodes(props.getProperty("providers"))) {
            addProvider(code, orDefault(trim(props.getProperty(code + ".type")), "openai"),
                    trim(props.getProperty(code + ".api.key")),
                    trim(props.getProperty(code + ".base.url")),
                    trim(props.getProperty(code + ".model")));
        }

        String maxSteps = trim(props.getProperty("agent.max.steps"));
        if (!maxSteps.isEmpty()) {
            this.maxSteps = parseInt(maxSteps, this.maxSteps);
        }
        String maxTokens = trim(props.getProperty("agent.max.tokens"));
        if (!maxTokens.isEmpty()) {
            this.maxTokens = parseInt(maxTokens, this.maxTokens);
        }
        String tokenBudget = trim(props.getProperty("agent.token.budget"));
        if (!tokenBudget.isEmpty()) {
            this.tokenBudget = parseLong(tokenBudget, this.tokenBudget);
        }
        String temperature = trim(props.getProperty("agent.temperature"));
        if (!temperature.isEmpty()) {
            this.temperature = parseDouble(temperature, this.temperature);
        }
        String toolChoice = trim(props.getProperty("agent.tool.choice"));
        if (!toolChoice.isEmpty()) {
            this.toolChoice = toolChoice;
        }
        String execConfirm = trim(props.getProperty("agent.exec.confirm"));
        if (!execConfirm.isEmpty()) {
            this.execConfirmMode = execConfirm;
        }
        this.execConfirmWhitelist = splitList(props.getProperty("agent.exec.confirm.whitelist"));
        String retryMax = trim(props.getProperty("llm.retry.max"));
        if (!retryMax.isEmpty()) {
            this.retryMaxAttempts = parseInt(retryMax, this.retryMaxAttempts);
        }
        String retryBackoff = trim(props.getProperty("llm.retry.backoff.ms"));
        if (!retryBackoff.isEmpty()) {
            this.retryBackoffMs = parseLong(retryBackoff, this.retryBackoffMs);
        }
        this.fallbackModels = splitList(props.getProperty("llm.fallback.models"));
        String stateDb = trim(props.getProperty("agent.state.db"));
        if (stateDb.isEmpty()) {
            stateDb = System.getProperty("zbot.state.db",
                    System.getProperty("user.home") + "/.zbot/state.db");
        }
        this.stateDbPath = stateDb;
        String delegateDepth = trim(props.getProperty("agent.delegate.max.depth"));
        if (!delegateDepth.isEmpty()) {
            this.delegateMaxDepth = parseInt(delegateDepth, this.delegateMaxDepth);
        }
        String delegateChildren = trim(props.getProperty("agent.delegate.max.children"));
        if (!delegateChildren.isEmpty()) {
            this.delegateMaxChildren = parseInt(delegateChildren, this.delegateMaxChildren);
        }
        this.centerUrl = trim(props.getProperty("center.url"));
        String appCode = trim(props.getProperty("center.app.code"));
        if (!appCode.isEmpty()) {
            this.appCode = appCode;
        }

        // mcp.servers = "fs=node /usr/local/bin/mcp-fs.js,git=uvx mcp-git"
        // 逗号分隔每个 server, "=" 前面是 server name, 后面是完整命令行 (空格分隔)
        String mcpRaw = trim(props.getProperty("mcp.servers"));
        if (!mcpRaw.isEmpty()) {
            this.mcpServers = new ArrayList<McpServerEntry>();
            for (String entry : mcpRaw.split(",")) {
                String e = entry.trim();
                if (e.isEmpty()) {
                    continue;
                }
                int eq = e.indexOf('=');
                if (eq <= 0 || eq == e.length() - 1) {
                    continue;
                }
                String name = e.substring(0, eq).trim();
                String cmdline = e.substring(eq + 1).trim();
                List<String> cmd = new ArrayList<String>();
                for (String token : cmdline.split("\\s+")) {
                    if (!token.isEmpty()) {
                        cmd.add(token);
                    }
                }
                if (name.isEmpty() || cmd.isEmpty()) {
                    continue;
                }
                this.mcpServers.add(new McpServerEntry(name, cmd));
            }
        }

        String active = trim(props.getProperty("llm.provider"));
        String llmModel = trim(props.getProperty("llm.model"));
        if (!active.isEmpty()) {
            this.activeProviderCode = active;
        } else if (providers.containsKey("spark")) {
            // 历史行为：配了星火就用星火
            this.activeProviderCode = "spark";
        } else if (providers.containsKey("minimax")) {
            this.activeProviderCode = "minimax";
        }
        if (!llmModel.isEmpty()) {
            this.model = llmModel;
        } else if (activeProviderCode != null && providers.containsKey(activeProviderCode)) {
            this.model = providers.get(activeProviderCode).getModel();
        }
    }

    private void fromEnvironment() {
        String apiKey = trim(System.getenv("Z_BOT_API_KEY"));
        String baseUrl = trim(System.getenv("Z_BOT_BASE_URL"));
        String envModel = trim(System.getenv("Z_BOT_MODEL"));
        String envProvider = trim(System.getenv("Z_BOT_PROVIDER"));
        if (!apiKey.isEmpty() || !baseUrl.isEmpty() || !envModel.isEmpty()) {
            String code = !envProvider.isEmpty() ? envProvider : "openai";
            Provider p = providers.get(code);
            addProvider(code, p == null ? "openai" : p.getType(),
                    apiKey.isEmpty() && p != null ? p.getApiKey() : apiKey,
                    baseUrl.isEmpty() && p != null ? p.getBaseUrl() : baseUrl,
                    envModel.isEmpty() && p != null ? p.getModel() : envModel);
            this.activeProviderCode = code;
            if (!envModel.isEmpty()) {
                this.model = envModel;
            }
        }
        if (this.model == null || this.model.isEmpty()) {
            this.model = "gpt-4o-mini";
        }
    }

    private void addProvider(String code, String type, String apiKey, String baseUrl, String mdl) {
        providers.put(code, new Provider(code, type, apiKey, baseUrl, mdl));
    }

    /**
     * 命令行覆盖：把 {@code --provider/--api-key/--base-url/--model} 写进配置。
     *
     * <p>没给出的字段沿用同名 provider 的原值，所以 {@code --api-key} 单独出现时
     * 不会把 {@code ~/.zbot/config.properties} 里的 baseUrl / model 抹掉。</p>
     */
    public void useProvider(String code, String type, String apiKey, String baseUrl, String model) {
        String target = code == null || code.trim().isEmpty() ? activeProviderCode : code.trim();
        Provider old = providers.get(target);
        addProvider(target,
                orFallback(type, old == null ? "openai" : old.getType()),
                orFallback(apiKey, old == null ? null : old.getApiKey()),
                orFallback(baseUrl, old == null ? null : old.getBaseUrl()),
                orFallback(model, old == null ? this.model : old.getModel()));
        this.activeProviderCode = target;
        this.model = orFallback(model, this.model);
    }

    private static String orFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public void setExecConfirmMode(String execConfirmMode) {
        this.execConfirmMode = execConfirmMode;
    }

    public Provider activeProvider() {
        Provider p = providers.get(activeProviderCode);
        if (p != null) {
            return p;
        }
        if (!providers.isEmpty()) {
            return providers.values().iterator().next();
        }
        return new Provider(activeProviderCode, "openai", null, null, model);
    }

    public String getActiveProviderCode() {
        return activeProviderCode;
    }

    public void setActiveProviderCode(String code) {
        this.activeProviderCode = code;
    }

    public Map<String, Provider> getProviders() {
        return Collections.unmodifiableMap(providers);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public long getTokenBudget() {
        return tokenBudget;
    }

    public double getTemperature() {
        return temperature;
    }

    public String getToolChoice() {
        return toolChoice;
    }

    /** {@code agent.exec.confirm}：off / dangerous / all，决定 exec 工具何时要求人工确认。 */
    public String getExecConfirmMode() {
        return execConfirmMode;
    }

    public List<String> getExecConfirmWhitelist() {
        return Collections.unmodifiableList(execConfirmWhitelist);
    }

    public void setExecConfirmWhitelist(List<String> whitelist) {
        this.execConfirmWhitelist = whitelist == null
                ? new ArrayList<String>() : new ArrayList<String>(whitelist);
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public List<String> getFallbackModels() {
        return Collections.unmodifiableList(fallbackModels);
    }

    /** {@code agent.state.db}：SQLite 会话库路径；new Agent() 默认在此路径建库。 */
    public String getStateDbPath() {
        return stateDbPath;
    }

    public void setStateDbPath(String stateDbPath) {
        this.stateDbPath = stateDbPath;
    }

    /** {@code agent.delegate.max.depth}：delegate_task 最大委托深度，0 = 关闭委托。 */
    public int getDelegateMaxDepth() {
        return delegateMaxDepth;
    }

    /** {@code agent.delegate.max.children}：异步委托并发宽度。 */
    public int getDelegateMaxChildren() {
        return delegateMaxChildren;
    }

    /** MCP server 配置列表。{@code mcp.servers} 解析结果；空 = 不接 MCP。 */
    public List<McpServerEntry> getMcpServers() {
        return Collections.unmodifiableList(mcpServers);
    }

    public void setMcpServers(List<McpServerEntry> mcpServers) {
        this.mcpServers = mcpServers == null
                ? new ArrayList<McpServerEntry>() : new ArrayList<McpServerEntry>(mcpServers);
    }

    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }

    public String getCenterUrl() {
        return centerUrl;
    }

    public void setCenterUrl(String centerUrl) {
        this.centerUrl = centerUrl;
    }

    public String getAppCode() {
        return appCode;
    }

    public File getConfigDir() {
        return configDir;
    }

    /** properties 文件是否真的被读到（用于区分"配置缺失"与"配置为空"）。 */
    public boolean isLoaded() {
        return loaded;
    }

    private static String orDefault(String v, String d) {
        return v.isEmpty() ? d : v;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static java.util.List<String> splitCodes(String v) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String s : trim(v).split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static int parseInt(String v, int d) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static long parseLong(String v, long d) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    /** 逗号分隔列表 → List<String>（trim、去空）。 */
    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<String>();
        for (String s : trim(v).split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static double parseDouble(String v, double d) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    /**
     * 单个 LLM provider 的连接信息。type 决定走哪个 kernel provider。
     */
    public static final class Provider {

        private final String code;
        private final String type;
        private final String apiKey;
        private final String baseUrl;
        private final String model;

        public Provider(String code, String type, String apiKey, String baseUrl, String model) {
            this.code = code;
            this.type = type;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getCode() {
            return code;
        }

        /** openai / anthropic / deepseek / qwen / dashscope / gemini；openai 兼容网关都算 openai。 */
        public String getType() {
            return type;
        }

        public String getApiKey() {
            return apiKey;
        }

        /**
         * 多 key 凭据池：{@code <code>.api.key} 允许逗号分隔多个 key（Hermes credential pool 语义）。
         * 单 key 时返回单元素列表。
         */
        public List<String> getApiKeys() {
            List<String> out = new ArrayList<String>();
            if (apiKey == null) {
                return out;
            }
            for (String s : apiKey.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getModel() {
            return model;
        }

        /** 换 key 的副本（凭据池按 key 构造单 key provider 实例时用）。 */
        public Provider withApiKey(String apiKey) {
            return new Provider(code, type, apiKey, baseUrl, model);
        }

        @Override
        public String toString() {
            return "Provider{code='" + code + "', type='" + type + "', baseUrl='" + baseUrl
                    + "', model='" + model + "', apiKey=" + (apiKey == null || apiKey.isEmpty() ? "none" : "set") + '}';
        }
    }

    /**
     * 一个 MCP server 配置 — 仅支持 stdio transport（命令行拉起子进程），
     * name 作为工具前缀（{@code mcp-<name>-<tool>})。
     */
    public static final class McpServerEntry {
        private final String name;
        private final List<String> command;

        public McpServerEntry(String name, List<String> command) {
            this.name = name;
            this.command = command == null
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(command);
        }

        public String getName() {
            return name;
        }

        public List<String> getCommand() {
            return command;
        }

        @Override
        public String toString() {
            return "McpServer{name='" + name + "', command=" + command + "}";
        }
    }
}
