package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.channel.Channel;
import com.zifang.z.bot.channel.Gateway;
import com.zifang.z.bot.channel.HttpChannel;
import com.zifang.z.bot.channel.OutboundMessage;
import com.zifang.z.bot.channel.PairingService;
import com.zifang.z.bot.channel.WebhookChannel;
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
 * <p>当前默认通道：{@link HttpChannel}（浏览器控制台 + SSE）+ {@link WebhookChannel}
 * （入站 POST + 轮询出站，给 IM/第三方系统对接）；后续 IM 通道（飞书/钉钉）通过配置启用。</p>
 *
 * <p>{@code --pairing} 会启用配对码授权（{@code <configDir>/pairing.json}）；
 * webhook / IM 通道先用配对码绑会话，本地用户通过 {@code z-bot pair <code>} 放行。</p>
 */
@Command(name = "gateway", description = "常驻多通道 gateway（HTTP + Webhook + 可选 IM）")
public class GatewayCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Option(names = {"--port"}, defaultValue = "8080", description = "HTTP / SSE 控制台端口")
    int httpPort;

    @Option(names = {"--webhook-port"}, defaultValue = "8090", description = "Webhook 通道端口（0 = 关闭）")
    int webhookPort;

    @Option(names = {"--host"}, defaultValue = "",
            description = "HTTP 与 Webhook 两个通道共用的监听地址：缺省只绑 127.0.0.1，"
                    + "要同一网络的其他机器能连才显式写 0.0.0.0")
    String host;

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
        // HttpChannel 已经实现了原 /bot/* 端点；适配为 Channel SPI（不入总线，只暴露 HTTP 控制台）
        gw.register(new HttpChannelAdapter(new HttpChannel(agent, httpPort, host)));
        if (webhookPort > 0) {
            gw.register(new WebhookChannel(gw.bus(), webhookPort, "webhook", host));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(gw::stop, "z-bot-gateway-shutdown"));

        // cron 的通道投递口（P17 红线 2：投递接口不能只有单测消费者）。
        // 通道表按"当时活着的那些"现取（gw.channels() 是活视图），但**剔掉拉模式的 HTTP 控制台**：
        // HttpChannelAdapter.send() 是刻意的空转（浏览器自己来拉），留着它等于允许
        // 记一条"投递成功"而没有任何人收到 —— 比报 unknown channel 坏得多。
        if (agent.getCronScheduler() != null) {
            agent.getCronScheduler().deliverViaChannels(
                    ChannelCronDelivery.forChannels(new java.util.function.Supplier<List<Channel>>() {
                        @Override
                        public List<Channel> get() {
                            List<Channel> pushable = new ArrayList<Channel>();
                            for (Channel c : gw.channels()) {
                                if (!(c instanceof HttpChannelAdapter)) {
                                    pushable.add(c);
                                }
                            }
                            return pushable;
                        }
                    }));
        }

        gw.start();
        System.out.println("  gateway 已启动");
        System.out.println("  通道: " + gw.channels().stream().map(Channel::name).reduce((a, b) -> a + ", " + b).orElse("(无)"));
        if (agent.getCronScheduler() != null) {
            System.out.println("  cron 投递口: 已接通道表（到点任务的结果按 job 的 deliver 路由投，"
                    + "拉模式的 HTTP 控制台不作为投递目标）");
        }
        if (pairingService != null) {
            System.out.println("  配对码 TTL " + (pairingService.getTtlMs() / 60000) + " 分钟（z-bot pair <code> 绑定）");
        }
        System.out.println("  Ctrl-C 退出");

        gw.awaitTermination();
        gw.stop();
        return 0;
    }

    /** 把现有 HttpChannel（直接吃 agent）适配为 Channel SPI（不挂总线）。 */
    private static final class HttpChannelAdapter implements Channel {
        private final HttpChannel inner;

        HttpChannelAdapter(HttpChannel inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return "http";
        }

        @Override
        public boolean isRunning() {
            return inner.getPort() > 0;
        }

        @Override
        public void start() throws Exception {
            inner.start();
        }

        @Override
        public void stop() {
            inner.stop();
        }

        @Override
        public void awaitTermination() {
            inner.awaitTermination();
        }

        @Override
        public void send(OutboundMessage message) {
            // HttpChannel 是浏览器拉模式，没有"主动推给客户端"的接口；outbound 队列没意义。
        }
    }
}