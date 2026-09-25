package com.zifang.z.bot.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 入站消息（通道 → bus → agent）。
 *
 * <p>统一字段：哪个通道来的（channel）、会话 id（conversationId，IM 群 / 私聊 / 用户）、
 * 发送方（senderId，用于 audit/限额）、文本（text，可选）、原始负载（raw，IM 平台带签名的
 * 完整 webhook body，给前端 / 调试用）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelMessage {

    public String channel;
    public String conversationId;
    public String senderId;
    public String text;
    public String raw;

    public ChannelMessage() {
    }

    public ChannelMessage(String channel, String conversationId, String senderId, String text) {
        this.channel = channel;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.text = text;
    }

    @Override
    public String toString() {
        return "[" + channel + "|" + conversationId + "|" + senderId + "] " + text;
    }
}