package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.channel.Channel;
import com.zifang.z.bot.channel.ChannelConfigException;
import com.zifang.z.bot.channel.ChannelRegistry;
import com.zifang.z.bot.channel.HttpConsoleChannel;
import com.zifang.z.bot.config.BotConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * {@code z-bot serve} — 起 HTTP + SSE 通道，浏览器打开控制台。
 *
 * <p>P18 起这一路也走 {@link ChannelRegistry}：控制台不再被 {@code new}，
 * 而是 {@code create("http", …)} 产出的 {@link HttpConsoleChannel}。
 * 端口优先级与 gateway 一致 —— {@code --port} &gt; profile manifest 的
 * {@code channel.http.config.port} &gt; 缺省档的 {@code channel.http.default-port=8080}。</p>
 */
@Command(name = "serve", description = "启动 HTTP / SSE 服务与 web 控制台（通道由注册表产出）")
public class ServeCommand implements Callable<Integer> {

    @Mixin
    AgentOptions options = new AgentOptions();

    @Option(names = {"--port"}, paramLabel = "PORT", defaultValue = Option.NULL_VALUE,
            description = "监听端口（不给则用 manifest 的 channel.http.default-port）")
    Integer port;

    @Option(names = {"--host"}, defaultValue = "",
            description = "监听地址：缺省只绑 127.0.0.1；要同一网络的其他机器能连，显式写 0.0.0.0")
    String host;

    @Override
    public Integer call() throws Exception {
        BotAgent agent = options.newAgent();
        BotConfig cfg = agent.getConfig();
        File configDir = cfg == null ? null : cfg.getConfigDir();
        ChannelRegistry registry = ChannelRegistry.load(configDir);
        ChannelRegistry.Context ctx = new ChannelRegistry.Context(agent, null, configDir);
        if (port != null) {
            ctx.override("http", "port", String.valueOf(port.intValue()));
        }
        if (host != null && !host.trim().isEmpty()) {
            ctx.override("http", "host", host.trim());
        }
        Channel created;
        try {
            created = registry.create("http", ctx);
        } catch (ChannelConfigException e) {
            // 显式失败：缺键 / 被声明关掉 / kind 没工厂，全都原文打出来
            System.err.println("  serve 起不来：" + e.getMessage());
            agent.shutdown();
            return 1;
        }
        HttpConsoleChannel console = (HttpConsoleChannel) created;
        Runtime.getRuntime().addShutdownHook(new Thread(console::stop, "z-bot-serve-shutdown"));
        console.start();
        System.out.println("  控制台: " + console.consoleUrl());
        System.out.println("  通道声明来源: " + registry.sources());
        System.out.println("  Ctrl-C 退出");
        console.awaitTermination();
        console.stop();
        return 0;
    }
}
