package com.zifang.z.bot.cli;

import com.zifang.z.bot.channel.PairingService;
import com.zifang.z.bot.config.BotConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * {@code z-bot pair <code>} — 本地用户在 gateway 发配对码后，到本地机器消费码；
 * 绑定关系写入 {@code <configDir>/pairing.json}（一次性消费）。
 *
 * <p>当前主要服务于"把 z-bot 配置接入 CI / 告警系统时把 conversation 授权给特定调用者"。
 * 后续飞书/钉钉通道也会复用同一份 pairing 存储。</p>
 */
@Command(name = "pair", description = "消费配对码（gateway 发出的 8 位码）")
public class PairCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Parameters(index = "0", paramLabel = "CODE", description = "8 位配对码")
    String code;

    @Override
    public Integer call() {
        BotConfig cfg = options.loadConfig();
        File configDir = cfg == null ? null : cfg.getConfigDir();
        if (configDir == null) {
            System.err.println("未找到 configDir（ZBOT_HOME 或 --config-dir）");
            return 2;
        }
        PairingService pairing = new PairingService(new File(configDir, "pairing.json"));
        PairingService.Pairing p = pairing.consume(code);
        if (p == null) {
            System.err.println("码无效或已过期");
            return 1;
        }
        System.out.println("已绑定: channel=" + p.channel + " conv=" + p.conversationId
                + " sender=" + p.senderId + " (expired=" + p.expiresAt + ")");
        return 0;
    }
}