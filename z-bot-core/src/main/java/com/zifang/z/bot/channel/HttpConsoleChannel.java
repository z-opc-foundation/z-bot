package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;

/**
 * 把 {@link HttpChannel}（浏览器拉模式控制台，直接吃 agent，不挂总线）适配成 {@link Channel} SPI。
 *
 * <p>原先这个包装是 {@code cli/GatewayCommand} 的私有内部类；本期把它提到 {@code channel/} 下，
 * 因为 {@link ChannelRegistry} 要能自己产出 {@code http} 这一路 —— 内部类在别人手里，
 * 注册表就接不上这条线。语义与原来逐字一致：{@link #send(OutboundMessage)} 是<b>刻意的空转</b>
 * （没有"主动推给浏览器"的接口），所以它在 manifest 里声明 {@code outbound=false}，
 * 不许被当成 cron / fan-out 的投递目标。</p>
 */
public final class HttpConsoleChannel implements Channel {

    private final HttpChannel inner;

    public HttpConsoleChannel(HttpChannel inner) {
        this.inner = inner;
    }

    /** 与控制台等价的一行构造（{@code port=0} 表示让系统挑端口）。 */
    public HttpConsoleChannel(BotAgent agent, int port, String host) {
        this(new HttpChannel(agent, port, host));
    }

    public HttpChannel delegate() {
        return inner;
    }

    public String consoleUrl() {
        return inner.consoleUrl();
    }

    public int port() {
        return inner.getPort();
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
        // 拉模式：浏览器自己来取。这里没有任何东西被"送出去"，所以 outbound=false。
    }
}
