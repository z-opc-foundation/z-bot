package com.zifang.z.bot.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 出站消息（bus → 通道）。通道负责把这条消息翻译成自家协议（HTTP 回调 / IM API）。
 *
 * <p>{@link #replyTo} 用于反向寻址入站会话；{@link #kind} 让通道走不同渲染路径
 * （普通文本 / 卡片 / 错误回执）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutboundMessage {

    public enum Kind { TEXT, ERROR }

    public String replyTo;
    public String text;
    public Kind kind = Kind.TEXT;

    public OutboundMessage() {
    }

    public OutboundMessage(String replyTo, String text) {
        this.replyTo = replyTo;
        this.text = text;
    }

    public static OutboundMessage text(String replyTo, String text) {
        return new OutboundMessage(replyTo, text);
    }

    public static OutboundMessage error(String replyTo, String text) {
        OutboundMessage m = new OutboundMessage(replyTo, text);
        m.kind = Kind.ERROR;
        return m;
    }
}