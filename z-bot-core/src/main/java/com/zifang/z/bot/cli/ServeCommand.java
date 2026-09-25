package com.zifang.z.bot.cli;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.channel.HttpChannel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code z-bot serve} — 起 HTTP + SSE 通道，浏览器打开控制台。
 */
@Command(name = "serve", description = "启动 HTTP / SSE 服务与 web 控制台")
public class ServeCommand implements Callable<Integer> {

    @Mixin
    AgentOptions options = new AgentOptions();

    @Option(names = {"--port"}, defaultValue = "8080", description = "监听端口（默认 8080）")
    int port;

    @Option(names = {"--host"}, defaultValue = "",
            description = "监听地址：缺省只绑 127.0.0.1；要同一网络的其他机器能连，显式写 0.0.0.0")
    String host;

    @Override
    public Integer call() throws Exception {
        BotAgent agent = options.newAgent();
        HttpChannel channel = new HttpChannel(agent, port, host);
        Runtime.getRuntime().addShutdownHook(new Thread(channel::stop, "z-bot-serve-shutdown"));
        channel.start();
        System.out.println("  控制台: " + channel.consoleUrl());
        System.out.println("  Ctrl-C 退出");
        channel.awaitTermination();
        channel.stop();
        return 0;
    }
}
