package com.zifang.z.bot.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P1 新增配置段解析单测：exec 白名单 / 重试 / 降级模型 / 累计 token 预算。
 */
public class BotConfigP1Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void p1PropertiesAreParsed() throws Exception {
        File dir = tmp.newFolder("cfg");
        try (FileWriter w = new FileWriter(new File(dir, "config.properties"))) {
            w.write("agent.exec.confirm.whitelist=git status, docker compose\n");
            w.write("llm.retry.max=4\n");
            w.write("llm.retry.backoff.ms=250\n");
            w.write("llm.fallback.models=gpt-4o-mini,glm-4\n");
            w.write("agent.token.budget=123456\n");
        }
        BotConfig cfg = BotConfig.load(dir);

        assertEquals(2, cfg.getExecConfirmWhitelist().size());
        assertTrue(cfg.getExecConfirmWhitelist().contains("git status"));
        assertTrue(cfg.getExecConfirmWhitelist().contains("docker compose"));
        assertEquals(4, cfg.getRetryMaxAttempts());
        assertEquals(250L, cfg.getRetryBackoffMs());
        assertEquals(2, cfg.getFallbackModels().size());
        assertEquals(123456L, cfg.getTokenBudget());
    }

    @Test
    public void defaultsAreSaneWhenPropertiesMissing() {
        File dir = tmp.getRoot();
        BotConfig cfg = BotConfig.load(new File(dir, "no-such-dir"));

        assertTrue(cfg.getExecConfirmWhitelist().isEmpty());
        assertEquals(2, cfg.getRetryMaxAttempts());
        assertEquals(1000L, cfg.getRetryBackoffMs());
        assertTrue(cfg.getFallbackModels().isEmpty());
        assertEquals(400_000L, cfg.getTokenBudget());
    }

    /**
     * 红线 1：会话库必须跟着 configDir 走。
     *
     * <p>曾经缺省写死 {@code ~/.zbot/state.db} — {@code --config-dir} 起第二个 profile 时
     * memories/skills/cron 都隔离了，唯独 state.db 仍与默认 profile 共享同一个文件。</p>
     */
    @Test
    public void stateDbDefaultsInsideConfigDir() throws Exception {
        String saved = System.getProperty("zbot.state.db");
        System.clearProperty("zbot.state.db");
        try {
            File dir = tmp.newFolder("profile-a");
            assertEquals(new File(dir, "state.db").getAbsolutePath(),
                    BotConfig.load(dir).getStateDbPath());

            File explicit = tmp.newFolder("profile-b");
            File custom = new File(explicit, "custom.db");
            try (FileWriter w = new FileWriter(new File(explicit, "config.properties"))) {
                w.write("agent.state.db=" + custom.getAbsolutePath() + "\n");
            }
            assertEquals(custom.getAbsolutePath(), BotConfig.load(explicit).getStateDbPath());

            File viaSys = tmp.newFolder("profile-c");
            System.setProperty("zbot.state.db", new File(viaSys, "sys.db").getAbsolutePath());
            assertEquals(new File(viaSys, "sys.db").getAbsolutePath(),
                    BotConfig.load(viaSys).getStateDbPath());
        } finally {
            if (saved == null) {
                System.clearProperty("zbot.state.db");
            } else {
                System.setProperty("zbot.state.db", saved);
            }
        }
    }
}
