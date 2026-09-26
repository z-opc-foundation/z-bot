package com.zifang.z.bot.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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

    /**
     * 红线 1 的单一解析点：{@code -Dzbot.home} &gt; {@code ZBOT_HOME} &gt; {@code ~/.zbot}。
     *
     * <p>{@code ZBOT_HOME} 这个名字在 {@code PairCommand} 的报错文案里已经写了很久，但此前
     * 全仓没有任何一处真的读它 —— 多 profile 隔离实际只有 {@code --config-dir} 一条路。
     * 系统属性那条分支同时是单测注入 profile 的口子（进程内改不了环境变量）。</p>
     */
    public static File defaultConfigDir() {
        String home = trim(System.getProperty("zbot.home"));
        if (home.isEmpty()) {
            home = trim(System.getenv("ZBOT_HOME"));
        }
        return new File(home.isEmpty()
                ? System.getProperty("user.home") + "/.zbot" : home);
    }

    /**
     * 沙箱根的单一解析点：{@code --sandbox} &gt; {@code -Dzbot.sandbox} &gt;
     * {@code <configDir>/workspace}。此前这条优先级链在 {@code AgentOptions}、
     * {@code BotAgent.Builder}、{@code Sandbox} 各抄了一份，每份的缺省值都写死 {@code ~/.zbot}。
     */
    public static File resolveWorkspaceDir(File configDir, String cliOverride) {
        String v = trim(cliOverride);
        if (v.isEmpty()) {
            v = trim(System.getProperty("zbot.sandbox"));
        }
        if (!v.isEmpty()) {
            return new File(v);
        }
        return new File(configDir == null ? defaultConfigDir() : configDir, "workspace");
    }

    /** 会话 JSON 目录（一次性迁移入口）跟着 profile 走。 */
    public File sessionsDir() {
        return new File(configDir == null ? defaultConfigDir() : configDir, "sessions");
    }

    /** 沙箱根（{@code --sandbox}/{@code -Dzbot.sandbox} 都不给时的缺省档）。 */
    public File workspaceDir() {
        return resolveWorkspaceDir(configDir, null);
    }

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
    /**
     * /confirm 选 "always" 落盘的<b>精确命令</b>名单（agent.exec.approval.always）。
     * 与前缀白名单分开存：白名单按词前缀放行，这里只放行整条一模一样的命令。
     */
    private List<String> execAlwaysApprovals = new ArrayList<String>();
    /** always 名单的键名（唯一会被 z-bot 回写的键）。 */
    public static final String ALWAYS_APPROVALS_KEY = "agent.exec.approval.always";
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

    private Properties rawProps = new Properties();

    private BotConfig(File configDir) {
        this.configDir = configDir;
    }

    /**
     * 读 {@code <profile>/config.properties}（profile 见 {@link #defaultConfigDir()}）；
     * 文件不存在时返回仅含环境变量的默认配置。
     */
    public static BotConfig load() {
        return load(defaultConfigDir());
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
        this.rawProps = new Properties();
        this.rawProps.putAll(props);
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
        this.execAlwaysApprovals = splitEscapedList(props.getProperty(ALWAYS_APPROVALS_KEY));
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
            stateDb = System.getProperty("zbot.state.db", "");
        }
        if (stateDb.isEmpty()) {
            // 缺省跟着 configDir 走 — 写死 ~/.zbot 会让 --config-dir 多 profile 共享同一个会话库
            stateDb = new File(configDir, "state.db").getAbsolutePath();
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
        //
        // P21 起多两种形态，但**老写法的一个字节都不改**：
        //   ① 命令行值直接写 URL（http:// 或 https:// 前缀）⇒ 判成 http；
        //   ② 逐 server 覆盖键 mcp.server.<name>.{transport,url,headers,timeout}
        //      headers 用分号分隔（逗号是 server 分隔符，不能再用）：
        //      mcp.server.corp.headers = "Authorization=Bearer xxx;X-API-Key=yyy"
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
                if (name.isEmpty()) {
                    continue;
                }
                McpServerEntry built = buildMcpEntry(props, name, cmdline);
                if (built != null) {
                    this.mcpServers.add(built);
                }
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

    /**
     * {@code agent.exec.confirm.whitelist}：人手工写的<b>前缀</b>白名单（token 边界匹配）。
     *
     * <p>与 {@link #getExecAlwaysApprovals()} 的区别：白名单条目按"前 N 个词"放行，
     * always 名单是 /confirm 选"always"时落盘的<b>整条命令精确匹配</b>，两者不能混用。</p>
     */
    public List<String> getExecWhitelist() {
        return getExecConfirmWhitelist();
    }

    /** {@code agent.exec.approval.always}：/confirm 的 ALWAYS 决议落盘下来的精确命令。 */
    public List<String> getExecAlwaysApprovals() {
        return Collections.unmodifiableList(execAlwaysApprovals);
    }

    public void setExecAlwaysApprovals(List<String> entries) {
        this.execAlwaysApprovals = entries == null
                ? new ArrayList<String>() : new ArrayList<String>(entries);
    }

    /**
     * /confirm 的 ALWAYS 档落盘：加进 {@code execAlwaysApprovals} 并写回
     * {@code <configDir>/config.properties}。
     *
     * <p>红线 1：只写 configDir，绝不写代码目录。写失败要冒出来（调用方据此告知用户
     * "本次生效但没落盘"），不许静默。</p>
     *
     * @return 是否真的写进了文件（内存名单无论如何都会更新）
     */
    public synchronized boolean appendAlwaysApproval(String approvalKey) {
        if (approvalKey == null || approvalKey.trim().isEmpty()) {
            return false;
        }
        String entry = approvalKey.trim();
        if (execAlwaysApprovals.contains(entry)) {
            return true;
        }
        execAlwaysApprovals.add(entry);
        return persistKey(ALWAYS_APPROVALS_KEY, joinEscaped(execAlwaysApprovals));
    }

    /** 原地改一行 properties（保留其它键与原有顺序），写临时文件后原子替换。 */
    private boolean persistKey(String key, String value) {
        if (configDir == null) {
            return false;
        }
        File target = new File(configDir, "config.properties");
        try {
            if (configDir.getParentFile() != null) {
                configDir.mkdirs();
            }
            List<String> lines = new ArrayList<String>();
            if (target.exists()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(target), "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } finally {
                    reader.close();
                }
            }
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String name = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
                if (key.equals(name)) {
                    lines.set(i, key + "=" + value);
                    replaced = true;
                }
            }
            if (!replaced) {
                lines.add(key + "=" + value);
            }
            File tmp = new File(configDir, "config.properties.zbot-tmp");
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            try {
                for (int i = 0; i < lines.size(); i++) {
                    writer.write(lines.get(i));
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
            if (target.exists() && !target.delete()) {
                System.err.println("[BotConfig] 无法覆盖 " + target + "（请检查文件权限）");
                return false;
            }
            if (!tmp.renameTo(target)) {
                System.err.println("[BotConfig] 无法写入 " + target);
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[BotConfig] 持久化 " + key + " 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 拼成写进 properties 的一行值。
     *
     * <p>两层转义，缺一不可：① 我们自己吃 {@code \,} 这层（{@link #splitEscapedList}），
     * 命令里带逗号不会被切碎；② 回读走 {@link java.util.Properties#load}，它会先吃掉一层反斜杠，
     * 所以写盘前要把整串反斜杠再翻倍。实测：少第 ② 层时
     * {@code docker run -e A=1,B=2 alpine} 会被读成两条
     * {@code [docker run -e A=1, B=2 alpine]}（名单被劈开 ⇒ 免确认范围被悄悄放大）。</p>
     */
    private static String joinEscaped(List<String> entries) {
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.replace("\\", "\\\\").replace(",", "\\,")   // 我们的转义形
                    .replace("\\", "\\\\")                              // 给 Properties.load 翻倍
                    .replace("\r", "\\r").replace("\n", "\\n"));        // 别让换行劈成两行
        }
        return sb.toString();
    }

    /** 支持 {@code \,} 转义的逗号分隔列表（always 名单里存的是命令，命令可以带逗号）。 */
    private static List<String> splitEscapedList(String v) {
        List<String> out = new ArrayList<String>();
        if (v == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == ',') {
                addIfNotBlank(out, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(out, current.toString());
        return out;
    }

    private static void addIfNotBlank(List<String> out, String value) {
        if (value != null && !value.trim().isEmpty()) {
            out.add(value.trim());
        }
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

    /**
     * P15：把 {@code StateStore.Options.configKeys()} 那 10 个键映成打开库的参数。
     *
     * <p>默认值只有一份 —— 先取 {@link StateStore.Options#defaults()}，配置显式写了才覆盖。
     * 这里再抄一份数字就是等着和 store 里分叉的（红线 2）。键有没有真被读到由
     * {@code BotConfigStateStoreOptionsTest} 逐条钉住：列进 {@code configKeys()} 但没接的键，
     * 那个测试会红。</p>
     */
    public com.zifang.z.bot.store.StateStore.Options stateStoreOptions() {
        com.zifang.z.bot.store.StateStore.Options o =
                com.zifang.z.bot.store.StateStore.Options.defaults();
        o.busyTimeoutMillis(intOf("agent.state.db.busy.timeout.ms", o.busyTimeoutMillis()));
        o.writeRetries(intOf("agent.state.db.write.retries", o.writeRetries()));
        o.retryWindowMillis(longOf("agent.state.db.write.retry.min.ms", o.retryMinMillis()),
                longOf("agent.state.db.write.retry.max.ms", o.retryMaxMillis()));
        o.beginImmediate(boolOf("agent.state.db.write.begin.immediate", o.beginImmediate()));
        o.checkpointEveryNWrites(intOf("agent.state.db.checkpoint.every.n", o.checkpointEveryNWrites()));
        o.autoRecoverCorrupt(boolOf("agent.state.db.auto.recover.corrupt", o.autoRecoverCorrupt()));
        o.verifyOnOpen(boolOf("agent.state.db.verify.on.open", o.verifyOnOpen()));
        o.autoPrune(boolOf("agent.state.prune.auto", o.autoPrune()));
        o.retentionDays(intOf("agent.state.prune.retention.days", o.retentionDays()));
        return o;
    }

    private int intOf(String key, int current) {
        String raw = trim(rawProps.getProperty(key));
        if (raw.isEmpty()) {
            return current;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println("[BotConfig] " + key + "=" + raw + " 不是整数，沿用 " + current);
            return current;
        }
    }

    private long longOf(String key, long current) {
        String raw = trim(rawProps.getProperty(key));
        if (raw.isEmpty()) {
            return current;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            System.err.println("[BotConfig] " + key + "=" + raw + " 不是整数，沿用 " + current);
            return current;
        }
    }

    private boolean boolOf(String key, boolean current) {
        String raw = trim(rawProps.getProperty(key));
        if (raw.isEmpty()) {
            return current;
        }
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        System.err.println("[BotConfig] " + key + "=" + raw + " 不是布尔值，沿用 " + current);
        return current;
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

    // ─── P23 技能体系（只增键，不重排既有代码） ───────────────────────────────

    /** {@code skills.bundled.dir} —— {@code /skills sync} 的源目录（自带技能包）。 */
    public String getSkillsBundledDir() {
        return trim(rawProps.getProperty("skills.bundled.dir"));
    }

    /** {@code skills.guard.source} —— 安装期扫描的信任级：bundled(默认) | community。 */
    public String getSkillsGuardSource() {
        String v = trim(rawProps.getProperty("skills.guard.source"));
        return v.isEmpty() ? "bundled" : v;
    }

    /** {@code skills.commands.enabled} —— 技能是否注册成斜杠命令（缺省开）。 */
    public boolean isSkillCommandsEnabled() {
        String v = trim(rawProps.getProperty("skills.commands.enabled"));
        return v.isEmpty() || Boolean.parseBoolean(v);
    }

    /**
     * {@code skills.platform.override} —— 覆盖 OS 探测（给测试/验收用；留空 = 自动探测）。
     * 同时写回系统属性 {@code zbot.skills.platform}，让静态的 {@code SkillLoader} 判定看到它。
     */
    public String getSkillsPlatformOverride() {
        return trim(rawProps.getProperty("skills.platform.override"));
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
    /**
     * 把一个 {@code mcp.servers} 的 value 折成 {@link McpServerEntry}，再套上
     * {@code mcp.server.<name>.*} 的逐 server 覆盖。
     *
     * @return null 表示这条应该被跳过（沿用老行为：命令行 token 数为 0 就丢弃）
     */
    private static McpServerEntry buildMcpEntry(Properties props, String name, String cmdline) {
        String prefix = "mcp.server." + name + ".";
        String url = trim(props.getProperty(prefix + "url"));
        String transport = trim(props.getProperty(prefix + "transport"));
        String timeoutRaw = trim(props.getProperty(prefix + "timeout"));
        Map<String, String> headers =
                parseMcpHeaders(trim(props.getProperty(prefix + "headers")));

        boolean looksLikeUrl = cmdline.regionMatches(true, 0, "http://", 0, 7)
                || cmdline.regionMatches(true, 0, "https://", 0, 8);
        List<String> cmd = new ArrayList<String>();
        if (!looksLikeUrl) {
            for (String token : cmdline.split("\\s+")) {
                if (!token.isEmpty()) {
                    cmd.add(token);
                }
            }
            if (cmd.isEmpty() && url.isEmpty() && transport.isEmpty()) {
                return null; // 老行为：没有命令行的 stdio 条目不成立
            }
        } else if (url.isEmpty()) {
            url = cmdline;
        }

        if (transport.isEmpty()) {
            transport = url.isEmpty() ? McpServerEntry.TRANSPORT_STDIO : McpServerEntry.TRANSPORT_HTTP;
        }
        long timeout = 0L;
        if (!timeoutRaw.isEmpty()) {
            timeout = parseInt(timeoutRaw, 0);
        }
        return new McpServerEntry(name, cmd, transport, url, headers, timeout);
    }

    /**
     * {@code headers} 值：{@code "K=V;K2=V2"}。分号分隔是因为逗号已被
     * {@code mcp.servers} 当 server 分隔符占用。value 里可以再含 {@code =}
     * （{@code "X-Blob=a=b=c"} ⇒ value 取第一个 {@code =} 之后的全部）。
     */
    private static Map<String, String> parseMcpHeaders(String raw) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split(";")) {
            String p = pair.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    /**
     * 一个 MCP server 的配置。
     *
     * <p>P21 前只有 {@code name + command}（stdio 一种形态）。现在多一个
     * {@link #TRANSPORT_STDIO}/{@link #TRANSPORT_HTTP} 判别位与 http 侧的
     * {@code url + headers}，但<b>老写法的解析结果一位都没动</b>：
     * {@code mcp.servers = "fs=node /usr/local/bin/mcp-fs.js,git=uvx mcp-git"}
     * 仍然解成两条 stdio 条目（{@link BotConfig} 的 mcp 解析块 + 回归测试
     * {@code McpServerEntryCompatTest}）。</p>
     *
     * <p>{@code headers} 可能带真凭证（{@code Authorization: Bearer ...}），所以：
     * {@link #getHeaders()} 是给建 transport 用的<b>原文</b>，
     * {@link #maskedHeaders()}/{@link #toSafeMap()}/{@link #toString()} 是<b>给日志与
     * /status 类产物看的</b>脱敏口径 —— value 一律打码，只暴露 key 名。</p>
     */
    public static final class McpServerEntry {

        /** 子进程 + newline-delimited JSON-RPC（老写法就是这个）。 */
        public static final String TRANSPORT_STDIO = "stdio";
        /** StreamableHTTP（POST JSON-RPC + mcp-session-id + 可选 text/event-stream）。 */
        public static final String TRANSPORT_HTTP = "http";

        private final String name;
        private final List<String> command;
        private final String transport;
        private final String url;
        private final Map<String, String> headers;
        private final long timeoutMillis;

        /** 老的两个字段构造器：保留 ⇒ 任何既有调用点/测试不必改。 */
        public McpServerEntry(String name, List<String> command) {
            this(name, command, TRANSPORT_STDIO, "", null, 0L);
        }

        public McpServerEntry(String name, List<String> command, String transport, String url,
                              Map<String, String> headers, long timeoutMillis) {
            this.name = name;
            this.command = command == null
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(command);
            this.transport = normalizeTransport(transport);
            this.url = url == null ? "" : url.trim();
            this.headers = headers == null || headers.isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new java.util.LinkedHashMap<String, String>(headers));
            this.timeoutMillis = timeoutMillis;
        }

        private static String normalizeTransport(String v) {
            if (v == null) {
                return TRANSPORT_STDIO;
            }
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.isEmpty() || TRANSPORT_STDIO.equals(t) || "stdio+".equals(t)) {
                return TRANSPORT_STDIO;
            }
            // 规范里这层绑定的名字叫 streamable http；http / https / streamable-http 都收
            if (TRANSPORT_HTTP.equals(t) || "https".equals(t) || "http+sse".equals(t)
                    || "streamablehttp".equals(t) || "streamable-http".equals(t)
                    || "streamable_http".equals(t) || "sse".equals(t)) {
                return TRANSPORT_HTTP;
            }
            return t;
        }

        public String getName() {
            return name;
        }

        public List<String> getCommand() {
            return command;
        }

        /** {@link #TRANSPORT_STDIO} 或 {@link #TRANSPORT_HTTP}；未知值原样回（建 transport 时报错）。 */
        public String getTransport() {
            return transport;
        }

        public String getUrl() {
            return url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        /** 打码后的 headers：唯一允许进日志/产物的口径。 */
        public Map<String, String> maskedHeaders() {
            return com.zifang.z.bot.mcp.SecretRedaction.maskAll(headers);
        }

        /** {@code <= 0} 表示"用 transport 实现的默认值"，不在配置层编一个第二默认值。 */
        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public boolean isHttp() {
            return TRANSPORT_HTTP.equals(transport);
        }

        /** 给状态/前端看的脱敏视图 —— 里面没有任何 header 原文，也没有 url 的 query。 */
        public Map<String, Object> toSafeMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("transport", transport);
            if (TRANSPORT_HTTP.equals(transport)) {
                m.put("url", com.zifang.z.bot.mcp.SecretRedaction.maskUrl(url));
                m.put("headerNames", new ArrayList<String>(headers.keySet()));
            } else {
                m.put("command", command);
            }
            m.put("timeoutMillis", Long.valueOf(timeoutMillis));
            return m;
        }

        @Override
        public String toString() {
            if (TRANSPORT_HTTP.equals(transport)) {
                return "McpServer{name='" + name + "', transport=http, url="
                        + com.zifang.z.bot.mcp.SecretRedaction.maskUrl(url)
                        + ", headers=" + maskedHeaders() + "}";
            }
            return "McpServer{name='" + name + "', command=" + command + "}";
        }
    }
}
