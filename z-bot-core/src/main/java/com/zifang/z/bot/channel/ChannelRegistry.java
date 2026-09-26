package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 通道注册表：把"通道"从<b>手工 {@code new}</b> 变成<b>声明式装配 + 惰性构造</b>。
 *
 * <p><b>为什么落在 z-bot 这一侧而不是内核</b>：工单假设 {@code z-agent-kernel-app} 提供 SPI，
 * 实测（EVIDENCE §0-6）那个 0.2.1 jar 里只有 {@code package-info.class} —— 空模块，
 * 没有任何可实现的接口。所以注册表必须是 z-bot 自己的东西，内核仓一个字节不动。</p>
 *
 * <p><b>为什么是自建 manifest 而不是 {@code java.util.ServiceLoader}</b>：ServiceLoader 需要
 * {@code META-INF/services/<接口>} 这个外部注册文件，而"它真的被加载过"这件事必须在测试里放一个
 * provider 才能证；provider 文件只能写进 {@code src/test/resources}，那不在本期写域内
 * （写域只到 {@code src/test/**\/channel/**}）。选一个"文件在数据根下、内容可读可断言"的
 * manifest，验收面反而更硬：杠③ 能在真进程里看见它被读到。两种做法都是"name → 声明"的表，
 * 惰性语义完全一致。</p>
 *
 * <p><b>惰性</b>（工单第 0 步证伪 ⑤ 的硬约束）：注册表里只有 {@link Spec}（名字、kind、
 * requires、default-port、outbound、配置键值）和 {@code kind → 工厂} 两张表。
 * {@link #load load()} 与 {@link #specs()}/{@link #spec(String)} 全程不实例化任何通道、
 * 不做任何反射扫描（本类里没有 {@code Class.forName}、没有 {@code ServiceLoader}、
 * 没有 {@code getDeclaredConstructors}），只有 {@link #create create()} 才会调工厂。
 * {@link #createCount()} 就是这件事的观测面。</p>
 *
 * <p><b>后写覆盖</b>：声明按"来源顺序"逐字段合并，后一个来源覆盖前一个来源同名实例的同名字段。
 * 来源固定三层，从低到高：</p>
 * <ol>
 *   <li>随 jar 走的缺省档
 *       {@code src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties}
 *       —— 声明四种 kind 的形状，IM 通道 {@code enabled=false}（不随包发凭据）。</li>
 *       <li>{@code <configDir>/channels.properties} —— <b>用户这一份 profile 的声明</b>。
 *       这是本期唯一的通道配置入口：{@link com.zifang.z.bot.config.BotConfig BotConfig}
 *       归 P21（它要给 {@code McpServerEntry} 加 transport 字段），里面没有任何 feishu/dingtalk 键，
 *       {@code rawProps} 也没有公开 getter，所以本期<b>不</b>往那儿挤。</li>
 *   <li>{@link Context#override CLI 覆盖} —— {@code --port / --webhook-port / --host}
 *       仍然是最高优先级，保证现有命令行语义不回归。</li>
 * </ol>
 *
 * <p>{@code <configDir>} 由 {@code --config-dir} / {@code ZBOT_HOME} 决定（红线 1）；
 * 拿不到目录时注册表只带缺省档，<b>绝不</b>回落到 {@code ~/.zbot}。</p>
 */
public final class ChannelRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelRegistry.class);

    /** profile 侧 manifest 的文件名（放在 {@code <configDir>} 下）。 */
    public static final String MANIFEST_FILE_NAME = "channels.properties";

    /** 随 jar 走的缺省档（同包资源）。 */
    public static final String BUILTIN_RESOURCE = "channels.builtin.properties";

    /** 所有声明键的公共前缀。 */
    public static final String PREFIX = "channel.";

    /** 单个实例的声明。字段级"后写覆盖"，未声明的字段留白（{@code null}）。 */
    public static final class Spec {
        private final String name;
        private String kind;
        private Boolean enabled;
        private Boolean outbound;
        private Integer defaultPort;
        private List<String> requires = new ArrayList<String>();
        private final Map<String, String> config = new LinkedHashMap<String, String>();
        private final List<String> declaredBy = new ArrayList<String>();

        Spec(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        /** 缺省 {@code kind = name}（manifest 只写 {@code channel.feishu.config.*} 时不用重复声明 kind）。 */
        public String kind() {
            return kind != null ? kind : name;
        }

        /** 缺省 {@code true}：声明了却没写 enabled 的实例就是要起。 */
        public boolean enabled() {
            return enabled == null || enabled;
        }

        /** 缺省 {@code true}（真出站通道）；拉模式通道必须显式写 {@code outbound=false}。 */
        public boolean outbound() {
            return outbound == null || outbound;
        }

        /** 缺省端口：{@code config.port} 没给时用它，{@code -1} = 没声明（调用方必须给端口）。 */
        public int defaultPort() {
            return defaultPort == null ? -1 : defaultPort;
        }

        public List<String> requires() {
            return Collections.unmodifiableList(requires);
        }

        public Map<String, String> config() {
            return Collections.unmodifiableMap(config);
        }

        /** 这个实例被哪几层来源写过（按顺序）—— 后写覆盖的可审计证据。 */
        public List<String> declaredBy() {
            return Collections.unmodifiableList(declaredBy);
        }

        @Override
        public String toString() {
            return "Spec[" + name + " kind=" + kind() + " enabled=" + enabled()
                    + " outbound=" + outbound() + " default-port=" + defaultPort()
                    + " requires=" + requires + " keys=" + config.keySet() + "]";
        }
    }

    /** {@code kind → 怎么造}。工厂只在 {@link #create} 时被调一次。 */
    public interface ChannelFactory {
        Channel create(Spec spec, Context ctx) throws Exception;
    }

    /** 构造一个通道所需的一切外部条件（agent / bus / 数据根 / CLI 覆盖）。 */
    public static final class Context {
        private final BotAgent agent;
        private final ChannelBus bus;
        private final File configDir;
        private final Map<String, Map<String, String>> overrides =
                new LinkedHashMap<String, Map<String, String>>();

        public Context(BotAgent agent, ChannelBus bus, File configDir) {
            this.agent = agent;
            this.bus = bus;
            this.configDir = configDir;
        }

        public BotAgent agent() {
            return agent;
        }

        public ChannelBus bus() {
            return bus;
        }

        public File configDir() {
            return configDir;
        }

        /** CLI 覆盖（最高优先级）；返回自身便于链式。 */
        public Context override(String name, String key, String value) {
            if (value == null) {
                return this;
            }
            Map<String, String> per = overrides.get(name);
            if (per == null) {
                per = new LinkedHashMap<String, String>();
                overrides.put(name, per);
            }
            per.put(key, value);
            return this;
        }

        /** 生效值：CLI 覆盖 &gt; manifest。空白串按"没给"处理。 */
        public String value(Spec spec, String key) {
            Map<String, String> per = overrides.get(spec.name());
            String v = per == null ? null : per.get(key);
            if (isBlank(v)) {
                v = spec.config().get(key);
            }
            return isBlank(v) ? null : v.trim();
        }

        /** 生效端口：CLI/manifest 的 {@code port} &gt; {@code default-port}；都没有 ⇒ {@code -1}。 */
        public int port(Spec spec) {
            String p = value(spec, "port");
            if (p != null) {
                try {
                    return Integer.parseInt(p.trim());
                } catch (NumberFormatException e) {
                    LOG.warn("[channel-registry] {} 的 port=\"{}\" 不是数字，改用 default-port",
                            spec.name(), p);
                }
            }
            return spec.defaultPort();
        }

        /** 生效监听地址：CLI/manifest 的 {@code host}；没给 ⇒ null（只绑回环）。 */
        public String host(Spec spec) {
            return value(spec, "host");
        }
    }

    /** 一次 {@link #createAll} 的结果：造出来的 + 显式列出的失败与跳过，一个都不吞。 */
    public static final class Batch {
        private final List<Channel> channels = new ArrayList<Channel>();
        private final List<String> failures = new ArrayList<String>();
        private final List<String> skipped = new ArrayList<String>();

        public List<Channel> channels() {
            return Collections.unmodifiableList(channels);
        }

        /** {@code "实例名: 原因"} 形式；原因里带着缺的键名。 */
        public List<String> failures() {
            return Collections.unmodifiableList(failures);
        }

        /** manifest 里 {@code enabled=false} 而被跳过的实例名。 */
        public List<String> skipped() {
            return Collections.unmodifiableList(skipped);
        }

        public int created() {
            return channels.size();
        }
    }

    private final Map<String, Spec> specs = new LinkedHashMap<String, Spec>();
    private final Map<String, ChannelFactory> factories = new LinkedHashMap<String, ChannelFactory>();
    private final List<String> sources = new ArrayList<String>();
    private final Map<String, Boolean> outboundByChannelName = new LinkedHashMap<String, Boolean>();
    private final Set<String> materialized = new LinkedHashSet<String>();
    private int createCount;

    private ChannelRegistry() {
    }

    // ===== 装配 =====

    /** 缺省档 + {@code <configDir>/channels.properties}，并挂上仓内四种 kind 的工厂。 */
    public static ChannelRegistry load(File configDir) {
        return load(configDir, null);
    }

    /**
     * 同上，多一层显式 manifest（{@code --channel-manifest}）：它的优先级高于
     * {@code <configDir>/channels.properties}，仍然是"后写覆盖"。
     *
     * @throws IllegalArgumentException 给了 {@code explicit} 却读不到（不许静默当成"没写"）
     */
    public static ChannelRegistry load(File configDir, File explicit) {
        ChannelRegistry r = loadWithoutBuiltinFactories(configDir, explicit);
        r.registerBuiltInFactories();
        return r;
    }

    /** 同上，但不挂内置工厂（给测试留的口子：自己 registerFactory 才能证"惰性"和"未知 kind"）。 */
    public static ChannelRegistry loadWithoutBuiltinFactories(File configDir) {
        return loadWithoutBuiltinFactories(configDir, null);
    }

    static ChannelRegistry loadWithoutBuiltinFactories(File configDir, File explicit) {
        ChannelRegistry r = new ChannelRegistry();
        r.readBuiltin();
        if (configDir != null) {
            File manifest = new File(configDir, MANIFEST_FILE_NAME);
            if (manifest.isFile()) {
                r.read(manifest);
            } else {
                LOG.info("[channel-registry] profile 里没有 {}（只按缺省档装配）", manifest.getPath());
            }
        }
        if (explicit != null) {
            if (!explicit.isFile()) {
                throw new IllegalArgumentException("--channel-manifest 指向的文件不存在: " + explicit);
            }
            r.read(explicit);
        }
        return r;
    }

    /** 某实例声明的缺省端口；没有这个实例时返回 {@code -1}（调用方自己决定怎么报）。 */
    public int defaultPortOf(String name) {
        Spec s = specs.get(name);
        return s == null ? -1 : s.defaultPort();
    }

    /** 只给一个 properties（进程内测试用，完全不碰磁盘上的 profile）。 */
    public static ChannelRegistry fromProperties(Properties... ordered) {
        ChannelRegistry r = new ChannelRegistry();
        for (int i = 0; i < ordered.length; i++) {
            r.merge(ordered[i], "inline#" + i);
        }
        return r;
    }

    private void readBuiltin() {
        InputStream is = ChannelRegistry.class.getResourceAsStream(BUILTIN_RESOURCE);
        if (is == null) {
            LOG.warn("[channel-registry] 缺省档 {} 不在 classpath 上 —— 只认 profile manifest",
                    BUILTIN_RESOURCE);
            return;
        }
        try {
            Properties p = new Properties();
            p.load(is);
            merge(p, "builtin:" + BUILTIN_RESOURCE);
        } catch (IOException e) {
            LOG.warn("[channel-registry] 缺省档读失败: {}", e.getMessage());
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // 资源流关不掉不值得抛
            }
        }
    }

    private void read(File file) {
        try (InputStream is = new FileInputStream(file)) {
            Properties p = new Properties();
            p.load(is);
            merge(p, "file:" + file.getPath());
        } catch (IOException e) {
            // 静默降级 = 用户以为通道配上了。这里必须把来源和原因一起冒出去。
            throw new IllegalStateException("manifest 读不起来: " + file + " —— " + e.getMessage(), e);
        }
    }

    /** 逐字段合并：后一个来源同名实例的同名字段覆盖前一个（{@code declaredBy} 留下审计痕迹）。 */
    void merge(Properties p, String source) {
        sources.add(source);
        // Properties 的 stringPropertyNames() 是 Hashtable 序（不稳定）。装配顺序影响通道注册顺序、
        // 进而影响 status/cron 目标列表的可预期性 ⇒ 同一层内按键名排序，让声明式装配是确定的。
        List<String> keys = new ArrayList<String>(p.stringPropertyNames());
        Collections.sort(keys);
        for (String raw : keys) {
            if (!raw.startsWith(PREFIX)) {
                continue;
            }
            String rest = raw.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                LOG.warn("[channel-registry] {} 里 {} 不是 channel.<name>.<attr> 形状，忽略", source, raw);
                continue;
            }
            String name = rest.substring(0, dot);
            String tail = rest.substring(dot + 1);
            String value = p.getProperty(raw);
            Spec s = specs.get(name);
            if (s == null) {
                s = new Spec(name);
                specs.put(name, s);
            }
            if (!s.declaredBy.contains(source)) {
                s.declaredBy.add(source);
            }
            applyField(s, tail, value == null ? "" : value.trim(), raw);
        }
    }

    private void applyField(Spec s, String tail, String value, String rawKey) {
        if (tail.startsWith("config.")) {
            s.config.put(tail.substring("config.".length()), value);
            return;
        }
        if ("kind".equals(tail)) {
            s.kind = value;
        } else if ("enabled".equals(tail)) {
            s.enabled = parseBool(value, rawKey);
        } else if ("outbound".equals(tail)) {
            s.outbound = parseBool(value, rawKey);
        } else if ("default-port".equals(tail)) {
            try {
                s.defaultPort = Integer.valueOf(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(rawKey + " 必须是整数端口，实际=\"" + value + "\"");
            }
        } else if ("requires".equals(tail)) {
            s.requires = splitList(value);
        } else {
            throw new IllegalArgumentException("不认识 manifest 键 " + rawKey
                    + "（可用：kind / enabled / outbound / default-port / requires / config.<key>）");
        }
    }

    private static Boolean parseBool(String v, String key) {
        if ("true".equalsIgnoreCase(v)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(v)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(key + " 只能是 true/false，实际=\"" + v + "\"");
    }

    private static List<String> splitList(String v) {
        List<String> out = new ArrayList<String>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ===== 工厂表（kind → 怎么造） =====

    /** 追加/覆盖一个 kind 的工厂；返回自身便于链式。 */
    public ChannelRegistry registerFactory(String kind, ChannelFactory factory) {
        factories.put(kind, factory);
        return this;
    }

    public Set<String> registeredKinds() {
        return new LinkedHashSet<String>(factories.keySet());
    }

    /** 仓内四种通道：{@code http}（拉模式控制台）/ {@code webhook} / {@code feishu} / {@code dingtalk}。 */
    public ChannelRegistry registerBuiltInFactories() {
        registerFactory("http", new ChannelFactory() {
            @Override
            public Channel create(Spec spec, Context ctx) throws ChannelConfigException {
                int port = ctx.port(spec);
                if (port < 0) {
                    throw missing(spec, "port");
                }
                return new HttpConsoleChannel(new com.zifang.z.bot.channel.HttpChannel(
                        ctx.agent(), port, ctx.host(spec)));
            }
        });
        registerFactory("webhook", new ChannelFactory() {
            @Override
            public Channel create(Spec spec, Context ctx) throws ChannelConfigException {
                int port = ctx.port(spec);
                if (port < 0) {
                    throw missing(spec, "port");
                }
                return new WebhookChannel(ctx.bus(), port, spec.name(), ctx.host(spec));
            }
        });
        registerFactory("feishu", new ChannelFactory() {
            @Override
            public Channel create(Spec spec, Context ctx) {
                return new FeishuChannel(ctx.bus(), ctx.port(spec),
                        ctx.value(spec, FeishuChannel.KEY_APP_ID),
                        ctx.value(spec, FeishuChannel.KEY_APP_SECRET),
                        ctx.value(spec, FeishuChannel.KEY_VERIFICATION_TOKEN),
                        ctx.value(spec, FeishuChannel.KEY_ENCRYPT_KEY),
                        ctx.value(spec, FeishuChannel.KEY_STATIC_TOKEN),
                        ctx.host(spec),
                        ctx.value(spec, FeishuChannel.KEY_API_BASE),
                        ctx.value(spec, FeishuChannel.KEY_RECEIVE_ID_TYPE));
            }
        });
        registerFactory("dingtalk", new ChannelFactory() {
            @Override
            public Channel create(Spec spec, Context ctx) {
                return new DingTalkChannel(ctx.bus(), ctx.port(spec),
                        ctx.value(spec, DingTalkChannel.KEY_WEBHOOK_URL),
                        ctx.value(spec, DingTalkChannel.KEY_SECRET),
                        ctx.host(spec));
            }
        });
        return this;
    }

    private static ChannelConfigException missing(Spec spec, String... keys) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, keys);
        return new ChannelConfigException("channel." + spec.name() + " 缺配置键: "
                + String.join(", ", list), list);
    }

    // ===== 读声明（不构造任何东西） =====

    public List<String> names() {
        return new ArrayList<String>(specs.keySet());
    }

    public List<Spec> specs() {
        return new ArrayList<Spec>(specs.values());
    }

    public Spec spec(String name) {
        return specs.get(name);
    }

    /** 声明里读到的来源，按加载顺序（后写覆盖的审计面）。 */
    public List<String> sources() {
        return Collections.unmodifiableList(sources);
    }

    public boolean isMaterialized(String name) {
        return materialized.contains(name);
    }

    /** {@link #create} 被调了几次 —— 惰性取证就量这个。 */
    public int createCount() {
        return createCount;
    }

    /**
     * 某通道名是否被允许作为主动推送目标（{@code outbound} 的兑现路径）。
     *
     * <p>消费者是 {@code cli/GatewayCommand} 的 cron 投递口：拉模式的 {@code http} 控制台
     * {@code outbound=false}，绝不能当投递目标 —— 记一条"投递成功"而没有任何人收到，
     * 比报 unknown channel 坏得多。</p>
     */
    public boolean isOutbound(String channelName) {
        Boolean v = outboundByChannelName.get(channelName);
        return v == null ? Boolean.TRUE : v;
    }

    public Set<String> materializedNames() {
        return new LinkedHashSet<String>(materialized);
    }

    // ===== 构造 =====

    /**
     * 按声明产出一个通道实例。
     *
     * @throws ChannelConfigException 声明的 {@code requires} 有缺键，或 kind 没注册工厂
     *                                （消息原文带着缺哪个键 / 哪个 kind）
     */
    public Channel create(String name, Context ctx) throws ChannelConfigException {
        Spec s = specs.get(name);
        if (s == null) {
            throw new ChannelConfigException("channel." + name
                    + " 没有声明（manifest 里没有 channel." + name + ".* 任何键）");
        }
        if (!s.enabled()) {
            throw new ChannelConfigException("channel." + name + " 被声明为 enabled=false"
                    + "（来源 " + s.declaredBy() + "）—— 要起它请在 manifest 里改写这一项");
        }
        List<String> lacking = new ArrayList<String>();
        for (String key : s.requires()) {
            if (isBlank(ctx.value(s, key))) {
                lacking.add(key);
            }
        }
        if (!lacking.isEmpty()) {
            throw new ChannelConfigException("channel." + name + "（kind=" + s.kind()
                    + "）缺配置键: " + String.join(", ", lacking)
                    + " —— 来源 " + s.declaredBy(), lacking);
        }
        ChannelFactory f = factories.get(s.kind());
        if (f == null) {
            throw new ChannelConfigException("channel." + name + " 的 kind=" + s.kind()
                    + " 没有注册工厂（已注册: " + factories.keySet() + "）");
        }
        Channel ch;
        try {
            ch = f.create(s, ctx);
        } catch (ChannelConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelConfigException("channel." + name + "（kind=" + s.kind()
                    + "）工厂抛了: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (ch == null) {
            throw new ChannelConfigException("channel." + name + " 的工厂返回 null");
        }
        createCount++;
        materialized.add(name);
        outboundByChannelName.put(ch.name(), Boolean.valueOf(s.outbound()));
        LOG.info("[channel-registry] 产出 {}（kind={}, name={}, outbound={}）",
                name, s.kind(), ch.name(), s.outbound());
        return ch;
    }

    /** 产出所有 {@code enabled} 的声明；失败逐条列出来，不静默跳过。 */
    public Batch createAll(Context ctx) {
        return createAll(ctx, new String[0]);
    }

    /**
     * 同上，但跳过指定实例名 —— CLI 已经按老语义单独装配的那两路（{@code http} 控制台、
     * {@code webhook}）不重复产出，它们的端口由 {@code --port}/{@code --webhook-port} 说了算。
     */
    public Batch createAll(Context ctx, String... skipNames) {
        List<String> skip = new ArrayList<String>();
        Collections.addAll(skip, skipNames == null ? new String[0] : skipNames);
        Batch b = new Batch();
        for (String name : new ArrayList<String>(specs.keySet())) {
            Spec s = specs.get(name);
            if (skip.contains(name)) {
                continue;
            }
            if (!s.enabled()) {
                b.skipped.add(name);
                continue;
            }
            try {
                b.channels.add(create(name, ctx));
            } catch (ChannelConfigException e) {
                b.failures.add(name + ": " + e.getMessage());
                LOG.warn("[channel-registry] {} 装配失败 —— {}", name, e.getMessage());
            }
        }
        return b;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
