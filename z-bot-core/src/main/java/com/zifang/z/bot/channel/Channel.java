package com.zifang.z.bot.channel;

/**
 * 通道 SPI（对齐 hermes gateway.run）：每个实现负责一种"消息进/出"形态
 * （HTTP / webhook / 飞书 / 钉钉 / 终端…），都通过 {@link ChannelBus} 转给同一个 BotAgent。
 *
 * <p>生命周期：{@link #start()} 打开 socket / 起线程；{@link #stop()} 释放；
 * {@link #awaitTermination()} 阻塞到 stop（gateway 进程用它常驻）。</p>
 *
 * <p>消息分发：通道收到入站消息后向 {@link ChannelBus#deliver(ChannelMessage)} 投递，
 * 出站消息由 {@link ChannelBus} 调用 {@link #send(OutboundMessage)} 写回通道。
 * 通道负责把 OutboundMessage 翻译成自家协议（HTTP 回调 POST / 飞书消息 / 钉钉消息）。</p>
 */
public interface Channel {

    /** 通道名（gateway 启动日志里看到的标识）。 */
    String name();

    /** 启动通道（建连接、起线程）。 */
    void start() throws Exception;

    /** 停通道（关闭连接、interrupt 线程）。幂等。 */
    void stop();

    /** 阻塞到 stop，常驻进程用。 */
    void awaitTermination();

    /** 出站：从 agent 回给外部（HTTP 回调 / IM API）。 */
    void send(OutboundMessage message) throws Exception;

    /** 通道是否已启动（用于 CLI 状态查询）。 */
    default boolean isRunning() {
        return true;
    }
}