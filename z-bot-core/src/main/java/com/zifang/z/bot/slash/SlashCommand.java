package com.zifang.z.bot.slash;

import com.zifang.z.bot.agent.BotAgent;

/**
 * 斜杠命令 SPI — 终端 / HTTP / 未来 TUI 共用同一份注册表。
 *
 * <p>命令只依赖 {@link BotAgent} 与参数字符串，返回纯文本输出；
 * 各通道自行决定怎么渲染（终端加配色，HTTP 直接进 JSON）。</p>
 */
public interface SlashCommand {

    /** 命令名，含斜杠，如 {@code /stop}。 */
    String name();

    String description();

    /**
     * 执行命令。
     *
     * @param args 斜杠名之后的原始参数（已 trim，可为空串）
     * @return 面向用户的纯文本输出
     */
    String execute(BotAgent agent, String args);
}
