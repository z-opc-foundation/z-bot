package com.zifang.z.bot.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 配对码授权（对齐 hermes pairing）：用户先在 IM 里私聊 bot 发 {@code /pair}，
 * gateway 给一个 8 位大写字母+数字码 + 1h 过期；用户到本地 {@code z-bot pair <code>}
 * 把这条会话（conversationId）绑到一个本地用户，之后该会话无需再配对。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>码表 = 大写字母 + 数字（去 0/O/1/I 等易混字符），8 位</li>
 *   <li>落地 {@code <configDir>/pairing.json}（数组，原子写）</li>
 *   <li>{@link #consume(String)} 同时校验过期；过期项懒清理</li>
 * </ul>
 */
public final class PairingService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long DEFAULT_TTL_MS = 60L * 60L * 1000L;

    private final File file;
    private final long ttlMs;
    private final SecureRandom rng = new SecureRandom();

    public PairingService(File file) {
        this(file, DEFAULT_TTL_MS);
    }

    public PairingService(File file, long ttlMs) {
        this.file = file;
        this.ttlMs = ttlMs;
        if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
            file.getParentFile().mkdirs();
        }
    }

    /** 生成一个 8 位配对码，并绑定（channel, conversationId, senderId, ttl）。 */
    public synchronized Pairing issue(String channel, String conversationId, String senderId) {
        cleanupExpiredLocked();
        Pairing p = new Pairing();
        p.code = randomCode();
        p.channel = channel;
        p.conversationId = conversationId;
        p.senderId = senderId;
        p.createdAt = Instant.now().toString();
        p.expiresAt = Instant.now().plusMillis(ttlMs).toString();
        List<Pairing> all = readAll();
        all.add(p);
        writeAll(all);
        return p;
    }

    /**
     * 用配对码换出绑定关系，验证后立即作废（一次性）。
     * 找不到 / 已过期 / 不匹配都返回 null（不告诉调用方失败原因）。
     */
    public synchronized Pairing consume(String code) {
        cleanupExpiredLocked();
        List<Pairing> all = readAll();
        for (Iterator<Pairing> it = all.iterator(); it.hasNext(); ) {
            Pairing p = it.next();
            if (p.code.equalsIgnoreCase(code)) {
                it.remove();
                writeAll(all);
                return p;
            }
        }
        return null;
    }

    /** 当前未过期的配对码（管理 / 调试用）。 */
    public synchronized List<Pairing> list() {
        cleanupExpiredLocked();
        return Collections.unmodifiableList(readAll());
    }

    public long getTtlMs() {
        return ttlMs;
    }

    private void cleanupExpiredLocked() {
        List<Pairing> all = readAll();
        long now = System.currentTimeMillis();
        List<Pairing> kept = new ArrayList<Pairing>();
        for (Pairing p : all) {
            try {
                if (Instant.parse(p.expiresAt).toEpochMilli() > now) {
                    kept.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        if (kept.size() != all.size()) {
            writeAll(kept);
        }
    }

    private List<Pairing> readAll() {
        if (!file.isFile()) {
            return new ArrayList<Pairing>();
        }
        try {
            Pairing[] arr = JSON.readValue(file, Pairing[].class);
            return arr == null ? new ArrayList<Pairing>() : new ArrayList<Pairing>(java.util.Arrays.asList(arr));
        } catch (IOException e) {
            return new ArrayList<Pairing>();
        }
    }

    private void writeAll(List<Pairing> all) {
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[PairingService] 写入失败: " + e.getMessage());
        }
    }

    private String randomCode() {
        char[] out = new char[8];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[rng.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pairing {
        public String code;
        public String channel;
        public String conversationId;
        public String senderId;
        public String createdAt;
        public String expiresAt;
    }
}