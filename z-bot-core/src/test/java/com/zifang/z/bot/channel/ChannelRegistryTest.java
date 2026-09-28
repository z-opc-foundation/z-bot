package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ChannelRegistry}：三层后写覆盖、惰性构造、{@code requires} 的显式缺键报错、
 * 以及"广告出去的每个字段都有兑现路径"（{@code default-port} / {@code outbound} / {@code requires}）。
 *
 * <p>所有 manifest 都写在 {@link TemporaryFolder} 里，绝不碰 {@code ~/.zbot}（红线 1）。</p>
 */
public class ChannelRegistryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File configDir;
    private BotAgent agent;
    private ChannelBus bus;

    @Before
    public void setUp() throws Exception {
        configDir = tmp.newFolder("profile");
        agent = stubAgent();
        bus = new ChannelBus(agent);
        bus.start();
    }

    @After
    public void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
        if (agent != null) {
            agent.shutdown();
        }
    }

    // ===== 缺省档的形状 =====

    @Test
    public void builtinManifestDeclaresFourKindsWithExpectedFlags() throws Exception {
        ChannelRegistry r = ChannelRegistry.load(null);
        assertEquals("缺省档应当只有四种通道声明", 4, r.names().size());
        assertTrue(r.names().containsAll(java.util.Arrays.asList("http", "webhook", "feishu", "dingtalk")));

        assertFalse("IM 通道不随包发凭据，缺省必须 enabled=false", r.spec("feishu").enabled());
        assertFalse(r.spec("dingtalk").enabled());
        assertTrue("控制台缺省要起（P18 之前的行为）", r.spec("http").enabled());
        assertTrue(r.spec("webhook").enabled());

        assertEquals(8080, r.spec("http").defaultPort());
        assertEquals(8090, r.spec("webhook").defaultPort());
        assertEquals(9101, r.spec("feishu").defaultPort());
        assertEquals(9102, r.spec("dingtalk").defaultPort());

        assertEquals("拉模式控制台不许当投递目标", false, r.spec("http").outbound());
        assertTrue(r.spec("webhook").outbound());
        assertEquals(java.util.Arrays.asList("app-id", "app-secret"), r.spec("feishu").requires());
        assertEquals(Collections.singletonList("webhook-url"), r.spec("dingtalk").requires());
        assertEquals("https://open.feishu.cn/open-apis", r.spec("feishu").config().get("api-base"));
        assertTrue("工厂表覆盖缺省档的四种 kind",
                r.registeredKinds().containsAll(java.util.Arrays.asList("http", "webhook", "feishu", "dingtalk")));
    }

    // ===== 惰性 =====

    @Test
    public void loadingSpecsConstructsNothing() throws Exception {
        final int[] built = new int[]{0};
        Properties declared = new Properties();
        declared.setProperty("channel.a.kind", "probe");
        declared.setProperty("channel.b.kind", "probe");
        declared.setProperty("channel.c.kind", "probe");
        declared.setProperty("channel.c.enabled", "false");
        ChannelRegistry r = ChannelRegistry.fromProperties(declared)
                .registerFactory("probe", new ChannelRegistry.ChannelFactory() {
                    @Override
                    public Channel create(ChannelRegistry.Spec spec, ChannelRegistry.Context ctx) {
                        built[0]++;
                        return new NamedChannel(spec.name());
                    }
                });

        assertEquals(3, r.names().size());
        assertNotNull(r.spec("a"));
        assertEquals("probe", r.spec("a").kind());
        assertFalse(r.spec("c").enabled());
        for (ChannelRegistry.Spec s : r.specs()) {
            assertNotNull(s.name());
        }
        assertEquals("读声明阶段一个通道都不该构造出来", 0, built[0]);
        assertEquals(0, r.createCount());
        assertTrue(r.materializedNames().isEmpty());

        ChannelRegistry.Batch b = r.createAll(new ChannelRegistry.Context(agent, bus, configDir));
        assertEquals("enabled=false 的那条被跳过而不是构造", 2, b.created());
        assertEquals(Collections.singletonList("c"), b.skipped());
        assertEquals(2, built[0]);
        assertEquals(2, r.createCount());
        assertTrue(r.isMaterialized("a"));
        assertFalse(r.isMaterialized("c"));
    }

    // ===== 后写覆盖 =====

    @Test
    public void profileManifestOverridesBuiltinAndCliBeatsProfile() throws Exception {
        int fromManifest = freePort();
        int fromCli = freePort();
        writeManifest("channel.webhook.default-port=" + fromManifest + "\n"
                + "channel.feishu.enabled=true\n"
                + "channel.feishu.config.app-id=cli_stub_app\n"
                + "channel.feishu.config.app-secret=stub_secret_not_real\n"
                + "channel.feishu.config.api-base=http://127.0.0.1:9/ignored\n");

        ChannelRegistry r = ChannelRegistry.load(configDir);
        assertEquals("profile 覆盖缺省档的 default-port", fromManifest, r.spec("webhook").defaultPort());
        assertTrue("profile 把缺省 disabled 的 feishu 打开", r.spec("feishu").enabled());
        assertEquals(2, r.spec("feishu").declaredBy().size());
        assertTrue("来源顺序：先缺省档后 profile",
                r.spec("feishu").declaredBy().get(0).startsWith("builtin:")
                        && r.spec("feishu").declaredBy().get(1).startsWith("file:"));

        // 第三层：CLI 覆盖压过 profile 的 default-port
        ChannelRegistry.Context ctx = new ChannelRegistry.Context(agent, bus, configDir)
                .override("webhook", "port", String.valueOf(fromCli));
        Channel webhook = r.create("webhook", ctx);
        assertEquals(fromCli, ((WebhookChannel) webhook).getPort());

        FeishuChannel feishu = (FeishuChannel) r.create("feishu", ctx);
        assertEquals("http://127.0.0.1:9/ignored", feishu.apiBase());
        assertEquals("http://127.0.0.1:9/ignored/im/v1/messages?receive_id_type=open_id",
                feishu.outgoingUrl());
    }

    @Test
    public void explicitManifestLayerBeatsProfileAndMissingFileFailsLoudly() throws Exception {
        int fromProfile = freePort();
        int fromExtra = freePort();
        writeManifest("channel.webhook.default-port=" + fromProfile + "\n");
        File extra = new File(configDir, "override.properties");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(extra),
                StandardCharsets.UTF_8)) {
            w.write("channel.webhook.default-port=" + fromExtra + "\n");
        }
        ChannelRegistry r = ChannelRegistry.load(configDir, extra);
        assertEquals("第四层（--channel-manifest）压过 profile", fromExtra, r.defaultPortOf("webhook"));
        assertEquals(3, r.sources().size());
        assertTrue(r.sources().get(2).startsWith("file:"));
        assertEquals("file:" + extra.getPath(), r.sources().get(2));
        assertEquals("没有 extra 层时 profile 就是最高层声明",
                fromProfile, ChannelRegistry.load(configDir).defaultPortOf("webhook"));

        try {
            ChannelRegistry.load(configDir, new File(configDir, "nope.properties"));
            fail("给了 --channel-manifest 却读不到文件必须炸，不许静默当成没写");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nope.properties"));
        }
    }

    @Test
    public void laterPropertiesLayerWinsFieldByField() {
        Properties first = new Properties();
        first.setProperty("channel.x.kind", "probe");
        first.setProperty("channel.x.default-port", "1111");
        first.setProperty("channel.x.config.token", "from-first");
        Properties second = new Properties();
        second.setProperty("channel.x.default-port", "2222");
        second.setProperty("channel.x.config.token", "from-second");
        ChannelRegistry r = ChannelRegistry.fromProperties(first, second);
        assertEquals(2222, r.spec("x").defaultPort());
        assertEquals("from-second", r.spec("x").config().get("token"));
        assertEquals("没被后一层提到的字段留着", "probe", r.spec("x").kind());
    }

    // ===== requires / enabled / kind 的显式失败 =====

    @Test
    public void requiresMissingKeysAreNamedExplicitly() throws Exception {
        writeManifest("channel.feishu.enabled=true\n"
                + "channel.feishu.config.app-id=cli_stub_app\n");   // 故意不给 app-secret
        ChannelRegistry r = ChannelRegistry.load(configDir);
        try {
            r.create("feishu", new ChannelRegistry.Context(agent, bus, configDir));
            fail("缺键必须抛，不许产出一个发不出去的通道");
        } catch (ChannelConfigException e) {
            assertEquals(Collections.singletonList("app-secret"), e.missingKeys());
            assertTrue(e.getMessage(), e.getMessage().contains("app-secret"));
            assertTrue("要写出实例名", e.getMessage().contains("channel.feishu"));
            assertTrue("要写出声明来源，便于人找到是哪层没配", e.getMessage().contains("file:"));
        }
        assertEquals("失败不该计数成已构造", 0, r.createCount());
    }

    @Test
    public void requiresIsOverridableSoStaticTokenPathStaysReachable() throws Exception {
        int p = freePort();
        writeManifest("channel.feishu.enabled=true\n"
                + "channel.feishu.requires=static-token\n"
                + "channel.feishu.config.static-token=stub-static-token\n"
                + "channel.feishu.config.port=" + p + "\n");
        ChannelRegistry r = ChannelRegistry.load(configDir);
        FeishuChannel ch = (FeishuChannel) r.create("feishu",
                new ChannelRegistry.Context(agent, bus, configDir));
        assertEquals("stub-static-token", ch.currentToken());
        assertEquals(p, ch.getPort());
    }

    @Test
    public void unknownKindIsExplicitNotSilent() throws Exception {
        writeManifest("channel.mystery.enabled=true\n");   // kind 缺省 = name = mystery，没注册工厂
        ChannelRegistry r = ChannelRegistry.load(configDir);
        try {
            r.create("mystery", new ChannelRegistry.Context(agent, bus, configDir));
            fail("kind 没有工厂 ⇒ 必须点名报出来");
        } catch (ChannelConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("kind=mystery"));
            assertTrue(e.getMessage(), e.getMessage().contains("没有注册工厂"));
        }
    }

    @Test
    public void undeclaredInstanceIsNamedExplicitly() throws Exception {
        ChannelRegistry r = ChannelRegistry.load(configDir);
        assertNull(r.spec("slack"));
        try {
            r.create("slack", new ChannelRegistry.Context(agent, bus, configDir));
            fail("没声明过的实例不能凭空造");
        } catch (ChannelConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("没有声明"));
        }
    }

    @Test
    public void createOnDisabledSpecIsAnExplicitRefusal() throws Exception {
        ChannelRegistry r = ChannelRegistry.load(null);
        try {
            r.create("feishu", new ChannelRegistry.Context(agent, bus, null));
            fail("enabled=false 的声明不能被 create() 悄悄产出");
        } catch (ChannelConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("enabled=false"));
        }
    }

    // ===== default-port / outbound 的兑现 =====

    @Test
    public void defaultPortIsHonoredWhenNobodyGivesPort() throws Exception {
        int p = freePort();
        writeManifest("channel.dingtalk.enabled=true\n"
                + "channel.dingtalk.default-port=" + p + "\n"
                + "channel.dingtalk.config.webhook-url=http://127.0.0.1:1/robot/send?access_token=stub\n");
        ChannelRegistry r = ChannelRegistry.load(configDir);
        Channel ch = r.create("dingtalk", new ChannelRegistry.Context(agent, bus, configDir));
        assertEquals("没有 CLI、没有 config.port 时用 default-port", p, ((DingTalkChannel) ch).getPort());
    }

    @Test
    public void outboundFlagIsRecordedForCreatedChannels() throws Exception {
        ChannelRegistry r = ChannelRegistry.load(null);
        assertTrue("http 还没产出时没有读数，按缺省可推送处理（筛子的判据来自产出）",
                r.isOutbound("http"));
        Channel console = r.create("http", new ChannelRegistry.Context(agent, bus, null)
                .override("http", "port", String.valueOf(freePort())));
        assertEquals("http", console.name());
        assertFalse("产出后 outbound=false 生效 —— GatewayCommand 的 cron 投递口读的就是它",
                r.isOutbound(console.name()));

        ChannelRegistry.Context ctx = new ChannelRegistry.Context(agent, bus, null)
                .override("webhook", "port", String.valueOf(freePort()));
        Channel hook = r.create("webhook", ctx);
        assertTrue(r.isOutbound(hook.name()));
    }

    // ===== manifest 本身写坏了不许放过 =====

    @Test
    public void unknownAttributeKeyFailsLoudly() throws Exception {
        writeManifest("channel.webhook.colour=pink\n");
        try {
            ChannelRegistry.load(configDir);
            fail("拼错字段要当场说，否则用户以为配上了");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("channel.webhook.colour"));
        }
    }

    @Test
    public void badBooleanAndBadPortFailLoudly() throws Exception {
        writeManifest("channel.webhook.enabled=maybe\n");
        try {
            ChannelRegistry.load(configDir);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("true/false"));
        }

        writeManifest("channel.webhook.default-port=http8080\n");
        try {
            ChannelRegistry.load(configDir);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("整数端口"));
        }
    }

    @Test
    public void createAllCollectsFailuresInsteadOfSilentlyDegrading() throws Exception {
        writeManifest("channel.feishu.enabled=true\n"
                + "channel.dingtalk.enabled=true\n");   // 两个都缺凭据
        ChannelRegistry r = ChannelRegistry.load(configDir);
        ChannelRegistry.Batch b = r.createAll(new ChannelRegistry.Context(agent, bus, configDir),
                "http", "webhook");
        assertEquals("created=" + b.created() + " skipped=" + b.skipped() + " failures=" + b.failures(),
                2, b.failures().size());
        assertTrue("失败条目按实例名开头，人能一眼看出是哪条声明坏了：" + b.failures(),
                b.failures().get(1).startsWith("feishu: "));
        assertTrue(b.failures().get(1), b.failures().get(1).contains("app-id, app-secret"));
        assertTrue(b.failures().get(0), b.failures().get(0).contains("webhook-url"));
        assertTrue(b.failures().get(1), b.failures().get(1).contains("app-id"));
        assertEquals("缺键报错要点名到实例名", 0, b.created());
    }

    @Test
    public void namesAreSortedNotHashtableOrder() throws Exception {
        // Properties 的 stringPropertyNames() 是 Hashtable 序（同一份文件两次 JVM 可能不同序）；
        // 注册表在同一层内按键名排序，保证通道注册顺序、status/cron 目标列表都是确定的。
        for (int i = 0; i < 3; i++) {
            ChannelRegistry r = ChannelRegistry.load(null);
            assertEquals(java.util.Arrays.asList("dingtalk", "feishu", "http", "webhook"), r.names());
        }
    }

    // ===== helpers =====

    private void writeManifest(String body) throws IOException {
        File f = new File(configDir, ChannelRegistry.MANIFEST_FILE_NAME);
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f),
                StandardCharsets.UTF_8)) {
            w.write(body);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /** 只用来数"被构造了几次"的最小通道。 */
    private static final class NamedChannel implements Channel {
        private final String name;

        NamedChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void awaitTermination() {
        }

        @Override
        public void send(OutboundMessage message) {
        }
    }

    private BotAgent stubAgent() throws Exception {
        return BotAgent.builder(null)
                .provider(new LlmProvider() {
                    @Override
                    public String name() {
                        return "stub";
                    }

                    @Override
                    public List<Model> listModels() {
                        return Collections.emptyList();
                    }

                    @Override
                    public boolean supportsModel(String m) {
                        return true;
                    }

                    @Override
                    public ChatCompletionsResponse chat(ChatCompletionsRequest r) {
                        String last = r.getMessages().isEmpty() ? ""
                                : r.getMessages().get(r.getMessages().size() - 1).getContent();
                        return new ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                                        "echo: " + last,
                                        Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new TokenUsage(5L, 3L, 8L), "stop", null);
                    }

                    @Override
                    public void streamChat(ChatCompletionsRequest r,
                                           java.util.function.Consumer<ChatCompletionsResponse> on,
                                           java.util.function.Consumer<Throwable> err) {
                        on.accept(chat(r));
                    }
                })
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutCenter()
                .build();
    }
}
