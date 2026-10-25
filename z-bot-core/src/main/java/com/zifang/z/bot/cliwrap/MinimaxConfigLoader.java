package com.zifang.z.bot.cliwrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从 hermes 配置（~/.hermes/config.yaml）加载 minimax 的 anthropic 兼容端点配置。
 *
 * <p>claude code 通过 {@code ANTHROPIC_BASE_URL} + {@code ANTHROPIC_API_KEY} 走 minimax 的
 * anthropic 兼容端点（{@code https://api.minimaxi.com/anthropic}），无需 /login。
 *
 * <p>实测铁证：claude -p --model MiniMax-M3 用此配置成功，14 delta 8.37s→11.74s 真流式。
 * key 读取逻辑：逐行扫 {@code - name: minimax} 段，取 api_key 行。
 */
public final class MinimaxConfigLoader {

    /**
     * minimax 的 anthropic 兼容端点（实测确认，非 /v1/messages）。
     */
    public static final String ANTHROPIC_BASE_URL = "https://api.minimaxi.com/anthropic";
    public static final String DEFAULT_MODEL = "MiniMax-M3";
    private static final Logger LOG = Logger.getLogger(MinimaxConfigLoader.class.getName());
    private static volatile String cachedApiKey;

    private MinimaxConfigLoader() {
    }

    /**
     * 加载 minimax api key（缓存）。从 ~/.hermes/config.yaml 的 custom_providers minimax 段读。
     */
    public static String loadApiKey() {
        if (cachedApiKey != null && !cachedApiKey.isEmpty()) {
            return cachedApiKey;
        }
        synchronized (MinimaxConfigLoader.class) {
            if (cachedApiKey != null && !cachedApiKey.isEmpty()) {
                return cachedApiKey;
            }
            try {
                Path configPath = Paths.get(System.getProperty("user.home"), ".hermes", "config.yaml");
                if (!Files.exists(configPath)) {
                    LOG.warning("hermes config not found: " + configPath);
                    return null;
                }
                List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
                boolean inMinimax = false;
                for (String l : lines) {
                    String t = l.trim();
                    if (t.equals("- name: minimax")) {
                        inMinimax = true;
                        continue;
                    }
                    if (inMinimax) {
                        // 下一个 provider 项则跳出
                        if (t.startsWith("- name:")) {
                            break;
                        }
                        if (t.startsWith("api_key:")) {
                            cachedApiKey = t.split(":", 2)[1].trim();
                            LOG.info("Loaded minimax api_key from hermes config: "
                                    + (cachedApiKey != null ? cachedApiKey.substring(0, Math.min(15, cachedApiKey.length())) + "..." : "null"));
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load minimax key from ~/.hermes/config.yaml", e);
            }
            return cachedApiKey;
        }
    }

    public static String getBaseUrl() {
        return ANTHROPIC_BASE_URL;
    }

    public static String getDefaultModel() {
        return DEFAULT_MODEL;
    }
}
