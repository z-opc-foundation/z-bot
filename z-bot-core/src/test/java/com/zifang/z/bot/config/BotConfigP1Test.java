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
}
