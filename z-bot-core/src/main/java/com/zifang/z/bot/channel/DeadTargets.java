package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 已确认不可达投递目标的持久登记表（对标 {@code hermes gateway/dead_targets.py} 143 行）。
 *
 * <p>平台明确报「这个会话整体已经死了」（群被删 / 机器人被踢或被拉黑 / 用户注销）时，
 * 每次 cron tick、每次 fan-out 再往它发一次就是在白烧平台的防洪封额 + 刷日志。本登记表让投递层
 * 短路掉「已经证明是死的」目标，同时<b>自愈</b>：任何一次发送成功都会把标记清掉，
 * 用户重新拉机器人进群就自动恢复，不需要人工清理。</p>
 *
 * <p>范围刻意收窄：只记<b>整会话级</b>的死亡（{@code forbidden} 与会话级 {@code not_found}）。
 * 线程/话题级的 {@code not_found}（{@code thread not found}、{@code message to reply not found}
 * 等）<b>不</b>登记 —— 话题被删不代表父会话死了，她那边是靠适配器自己退化成「不带 reply_to 重发」
 * 自愈的。{@link #classifySendError} 把这两种分开，与 hermes {@code classify_send_error} +
 * {@code is_chat_level_not_found} 同口径（只搬有消费者的那一档档，不搬她全表的 8 种）。</p>
 *
 * <p>落盘在 {@code <configDir>/gateway/dead_targets.json}（红线 1：跟着 profile 走，绝不写
 * {@code ~/.zbot}），tmp + {@code ATOMIC_MOVE} 原子替换；读写都是 best-effort —— 文件损坏或
 * 不可写就退化成纯内存登记表，绝不在投递路径上抛。</p>
 */
public final class DeadTargets {

    private static final Logger LOG = LoggerFactory.getLogger(DeadTargets.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 与 hermes {@code _DEAD_ERROR_KINDS} 同名同集合：只有这两种意味着整会话不可达。 */
    static final String FORBIDDEN = "forbidden";
    static final String NOT_FOUND = "not_found";
    static final String UNKNOWN = "unknown";
    static final String THREAD_NOT_FOUND = "thread_not_found";

    /** 分类时最多往下读几层 cause（再深就是无意义包装，且怕自环）。 */
    static final int MAX_CAUSE_DEPTH = 5;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Map<String, Object>> dead = new LinkedHashMap<String, Map<String, Object>>();
    private final File path;

    public DeadTargets(File file) {
        this.path = file;
        load();
    }

    /** 默认位置：{@code <configDir>/gateway/dead_targets.json}。 */
    public static DeadTargets forConfigDir(File configDir) {
        return new DeadTargets(configDir == null ? null
                : new File(configDir, "gateway" + File.separator + "dead_targets.json"));
    }

    // ===== 错误分类 =====

    private static final String[] FORBIDDEN_SUBSTRINGS = {
            "forbidden", "bot was blocked", "blocked by the user", "user is deactivated",
            "not enough rights", "have no rights", "not a member", "chat was deleted",
    };

    /** 会话级 not found（她 {@code _CHAT_LEVEL_NOT_FOUND_SUBSTRINGS} 原样）。 */
    private static final String[] CHAT_LEVEL_NOT_FOUND = {"chat not found"};

    /**
     * 子会话/消息级 not found（她 {@code _SUBCHAT_NOT_FOUND_SUBSTRINGS} 原样）：
     * 命中这些意味着父会话可能还活着 ⇒ 绝不登记成死目标。
     */
    private static final String[] SUBCHAT_LEVEL_NOT_FOUND = {
            "message to edit not found", "message to reply not found", "thread not found",
            "topic_deleted", "message_id_invalid",
    };

    /**
     * 把发送异常/错误文本映射到错误类别。保守：认不出的一律 {@link #UNKNOWN}，
     * 绝不把「没分类的失败」当成「无害的失败」。
     *
     * @return {@link #FORBIDDEN} / {@link #NOT_FOUND} / {@link #THREAD_NOT_FOUND} / {@link #UNKNOWN}
     */
    public static String classifySendError(Throwable exc, String errorText) {
        String blob = blob(exc, errorText);
        if (blob.trim().isEmpty()) {
            return UNKNOWN;
        }
        for (String s : FORBIDDEN_SUBSTRINGS) {
            if (blob.contains(s)) {
                return FORBIDDEN;
            }
        }
        // 她那边 sub-chat 读法优先（宁可错杀不成漏放：两种标记都在时不算整会话死）
        for (String s : SUBCHAT_LEVEL_NOT_FOUND) {
            if (blob.contains(s)) {
                return THREAD_NOT_FOUND;
            }
        }
        for (String s : CHAT_LEVEL_NOT_FOUND) {
            if (blob.contains(s)) {
                return NOT_FOUND;
            }
        }
        return UNKNOWN;
    }

    /** {@code not_found} 这一类里，是否指「整个会话没了」。 */
    public static boolean isChatLevelNotFound(Throwable exc, String errorText) {
        return NOT_FOUND.equals(classifySendError(exc, errorText));
    }

    /** 该错误类别是否意味着目标整体不可达（hermes {@code is_dead_error_kind} 同义）。 */
    public static boolean isDeadErrorKind(String errorKind) {
        return FORBIDDEN.equals(errorKind) || NOT_FOUND.equals(errorKind);
    }

    private static String blob(Throwable exc, String errorText) {
        StringBuilder sb = new StringBuilder();
        // 一路往下读 cause：通道层普遍把 HTTP 失败包成 IllegalStateException/RuntimeException，
        // 只看最外层 message 的话「chat not found」永远落在 cause 里 ⇒ 全部降级成 UNKNOWN，
        // 死目标登记就成了摆设（每次投递继续撞墙）。深度封顶 + 自环防护。
        Throwable t = exc;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            sb.append(t.toString()).append(' ');
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        if (errorText != null) {
            sb.append(errorText);
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    // ===== 登记表 =====

    /** 目标是否已被证明不可达。 */
    public boolean isDead(String platform, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return false;
        }
        String key = normalize(platform, chatId);
        lock.lock();
        try {
            return dead.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /** 记下一条已确认不可达的目标；新加入时返回 {@code true}。 */
    public boolean markDead(String platform, String chatId, String reason) {
        if (chatId == null || chatId.isEmpty()) {
            return false;
        }
        String key = normalize(platform, chatId);
        boolean newly;
        lock.lock();
        try {
            newly = !dead.containsKey(key);
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("platform", platform == null ? "" : platform.trim().toLowerCase(java.util.Locale.ROOT));
            entry.put("chat_id", chatId);
            entry.put("reason", reason == null ? "" : (reason.length() > 200 ? reason.substring(0, 200) : reason));
            entry.put("marked_at", Long.valueOf(System.currentTimeMillis()));
            dead.put(key, entry);
            flushLocked();
        } finally {
            lock.unlock();
        }
        if (newly) {
            LOG.info("[dead-targets] {} 判为不可达（{}）—— 后续投递会跳过，直到有一次发送成功", key,
                    reason == null || reason.isEmpty() ? "无原因" : reason);
        }
        return newly;
    }

    /** 清掉标记（投递成功即自愈）；原本有标记才返回 {@code true}。 */
    public boolean clear(String platform, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return false;
        }
        String key = normalize(platform, chatId);
        boolean had;
        lock.lock();
        try {
            had = dead.remove(key) != null;
            if (had) {
                flushLocked();
            }
        } finally {
            lock.unlock();
        }
        if (had) {
            LOG.info("[dead-targets] 清除 {}（又发成功了 ⇒ 自愈）", key);
        }
        return had;
    }

    /** 当前死目标的不可变快照（诊断 / {@code z-bot status}）。 */
    public Map<String, Map<String, Object>> allDead() {
        lock.lock();
        try {
            Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
            for (Map.Entry<String, Map<String, Object>> e : dead.entrySet()) {
                out.put(e.getKey(), new HashMap<String, Object>(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return dead.size();
        } finally {
            lock.unlock();
        }
    }

    /** 登记表文件路径（{@code null} = 纯内存模式）。 */
    public File path() {
        return path;
    }

    private static String normalize(String platform, String chatId) {
        return (platform == null ? "" : platform.trim().toLowerCase(java.util.Locale.ROOT))
                + ":" + chatId.trim();
    }

    // ===== 持久化（best-effort）=====

    private void load() {
        if (path == null || !path.isFile()) {
            return;
        }
        try {
            Map<String, Object> raw = JSON.readValue(path,
                    new TypeReference<HashMap<String, Object>>() {
                    });
            if (raw == null) {
                return;
            }
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    dead.put(e.getKey(), entry);
                }
            }
        } catch (Exception e) {
            LOG.debug("[dead-targets] 载入 {} 失败（{}）—— 按空表启动", path, e.getMessage());
            dead.clear();
        }
    }

    /** 调用方必须已持锁。 */
    private void flushLocked() {
        if (path == null) {
            return;
        }
        File dir = path.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            LOG.debug("[dead-targets] 建目录失败 {}", dir);
            return;
        }
        try {
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Map<String, Object>> e : dead.entrySet()) {
                snapshot.put(e.getKey(), new LinkedHashMap<String, Object>(e.getValue()));
            }
            File tmp = new File(path.getAbsolutePath() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp, snapshot);
            try {
                java.nio.file.Files.move(tmp.toPath(), path.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), path.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // best-effort：内存里的状态保住，投递路径不许因它炸
            LOG.debug("[dead-targets] 落盘 {} 失败: {}", path, e.getMessage());
        }
    }
}
