package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.channel.Channel;
import com.zifang.z.bot.channel.ChannelConfigException;
import com.zifang.z.bot.channel.ChannelRegistry;
import com.zifang.z.bot.channel.Gateway;
import com.zifang.z.bot.channel.PairingService;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.cron.ChannelCronDelivery;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code z-bot gateway} — 常驻多通道入口。
 *
 * <p><b>P18 起通道不再手工 {@code new}：全部由 {@link ChannelRegistry} 按声明产出。</b>
 * 声明来自三层（后写覆盖）：随 jar 走的缺省档 → {@code <configDir>/channels.properties}
 * → 下面这三个命令行选项。缺省档里 {@code http} 与 {@code webhook} 是 enabled 的，
 * 所以<b>不写 manifest 的命令行语义与 P18 之前逐字一致</b>；飞书/钉钉要用户在 profile 里
 * 显式 {@code channel.feishu.enabled=true} 才产出（工单第 0 步实测它们在 main 上是
 * "0 生产构造点的抽象"，本期就是给它们接上这条线）。</p>
 *
 * <p>{@code --pairing} 会启用配对码授权（{@code <configDir>/pairing.json}）；
 * webhook / IM 通道先用配对码绑会话，本地用户通过 {@code z-bot pair <code>} 放行。</p>
 */
@Command(name = "gateway", description = "常驻多通道 gateway（注册表按 manifest 产出通道）")
public class GatewayCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Option(names = {"--port"}, paramLabel = "PORT", defaultValue = Option.NULL_VALUE,
            description = "HTTP / SSE 控制台端口（不给则用 manifest 的 channel.http.default-port）")
    Integer httpPort;

    @Option(names = {"--webhook-port"}, paramLabel = "PORT", defaultValue = Option.NULL_VALUE,
            description = "Webhook 通道端口（0 = 关闭；不给则用 channel.webhook.default-port）")
    Integer webhookPort;

    @Option(names = {"--host"}, defaultValue = "",
            description = "HTTP 与 Webhook 两个通道共用的监听地址：缺省只绑 127.0.0.1，"
                    + "要同一网络的其他机器能连才显式写 0.0.0.0")
    String host;

    @Option(names = {"--channel-manifest"}, paramLabel = "FILE", defaultValue = Option.NULL_VALUE,
            description = "额外的第四层声明来源（覆盖 profile 的 channels.properties）")
    File extraManifest;

    @Option(names = {"--pairing"}, description = "启用配对码授权（<configDir>/pairing.json）")
    boolean pairing;

    @Override
    public Integer call() throws Exception {
        BotAgent agent = options.newAgent();
        BotConfig cfg = agent.getConfig();
        File configDir = cfg == null ? null : cfg.getConfigDir();
        PairingService pairingService = pairing && configDir != null
                ? new PairingService(new File(configDir, "pairing.json"))
                : null;

        Gateway gw = new Gateway(agent, pairingService);
        ChannelRegistry registry = ChannelRegistry.load(configDir, extraManifest);
        ChannelRegistry.Context ctx = new ChannelRegistry.Context(agent, gw.bus(), configDir);
        if (host != null && !host.trim().isEmpty()) {
            ctx.override("http", "host", host.trim());
            ctx.override("webhook", "host", host.trim());
        }
        if (httpPort != null) {
            ctx.override("http", "port", String.valueOf(httpPort.intValue()));
        }
        // 0 是命令行里的"关闭 webhook"，与 P18 之前同义；不给 = 让 manifest 的 default-port 说话
        boolean webhookRequested = webhookPort == null || webhookPort.intValue() > 0;
        if (webhookRequested) {
            ctx.override("webhook", "port", String.valueOf(
                    webhookPort == null ? registry.defaultPortOf("webhook") : webhookPort.intValue()));
        }

        // 1) 控制台（拉模式）：产不出来就是起不了网关，直接失败退出
        Channel console;
        try {
            console = registry.create("http", ctx);
        } catch (ChannelConfigException e) {
            System.err.println("  gateway 起不来：" + e.getMessage());
            agent.shutdown();
            return 1;
        }
        gw.register(console);

        // 2) webhook（推送型投递目标）
        if (webhookRequested) {
            try {
                gw.register(registry.create("webhook", ctx));
            } catch (ChannelConfigException e) {
                System.err.println("  webhook 通道没起来 —— " + e.getMessage());
            }
        }

        // 3) manifest 里其它 enabled 的声明（本期真正的新线路：feishu / dingtalk）
        ChannelRegistry.Batch imBatch = registry.createAll(ctx, "http", "webhook");
        for (Channel c : imBatch.channels()) {
            gw.register(c);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(gw::stop, "z-bot-gateway-shutdown"));

        // cron 的通道投递口（P17 红线 2：投递接口不能只有单测消费者）。
        // 通道表按"当时活着的那些"现取（gw.channels() 是活视图），但**剔掉拉模式控制台**：
        // HttpConsoleChannel.send() 是刻意的空转（浏览器自己来拉），留着它等于允许
        // 记一条"投递成功"而没有任何人收到 —— 比报 unknown channel 坏得多。
        // P18 起这个筛子读的是声明里的 outbound 标志，不再是 instanceof（标志有兑现路径：
        // ChannelRegistry.isOutbound，缺省档把 http 写成 false）。
        if (agent.getCronScheduler() != null) {
            agent.getCronScheduler().deliverViaChannels(
                    ChannelCronDelivery.forChannels(new java.util.function.Supplier<List<Channel>>() {
                        @Override
                        public List<Channel> get() {
                            List<Channel> pushable = new ArrayList<Channel>();
                            for (Channel c : gw.channels()) {
                                if (registry.isOutbound(c.name())) {
                                    pushable.add(c);
                                }
                            }
                            return pushable;
                        }
                    }));
        }

        gw.start();
        System.out.println("  gateway 已启动");
        System.out.println("  通道: " + gw.channels().stream().map(Channel::name)
                .reduce((a, b) -> a + ", " + b).orElse("(无)"));
        System.out.println("  通道声明来源: " + registry.sources());
        if (!imBatch.skipped().isEmpty()) {
            System.out.println("  按声明跳过（enabled=false）: " + imBatch.skipped());
        }
        for (String failure : imBatch.failures()) {
            System.out.println("  ✗ 通道装配失败（不是静默降级）: " + failure);
        }
        if (agent.getCronScheduler() != null) {
            System.out.println("  cron 投递口: 已接通道表（到点任务的结果按 job 的 deliver 路由投，"
                    + "outbound=false 的拉模式通道不作为投递目标）");
        }
        if (pairingService != null) {
            System.out.println("  配对码 TTL " + (pairingService.getTtlMs() / 60000) + " 分钟（z-bot pair <code> 绑定）");
        }
        System.out.println("  Ctrl-C 退出");

        gw.awaitTermination();
        gw.stop();
        return 0;
    }
}
