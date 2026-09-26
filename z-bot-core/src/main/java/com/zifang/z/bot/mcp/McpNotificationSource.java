package com.zifang.z.bot.mcp;

/**
 * 能收 server 主动通知的 transport（z-bot 侧的可选能力，内核 {@code McpTransport} 里没有这条缝）。
 *
 * <p>刻意用"额外接口"而不是给 {@code McpTransport} 加方法：内核接口是公开且只有 4 个方法
 * （{@code open/request/close/isOpen}），换实现不该被迫改内核。</p>
 *
 * <p>{@link McpBridge} 只在 {@code instanceof} 成立时才挂监听器，也才会在状态里
 * 把 {@code listChanged} 报成真 —— 广告出去的字段就是承诺，拿不到通知的通道不许广告。</p>
 */
public interface McpNotificationSource {

    /** 传 null 等于取消订阅。必须在 transport 打开之前或之后都能安全调用。 */
    void setNotificationListener(McpNotificationListener listener);

    /** 通知是否真能被投递（false ⇒ 上层不得广告 {@code listChanged}）。 */
    boolean notificationCapable();
}
