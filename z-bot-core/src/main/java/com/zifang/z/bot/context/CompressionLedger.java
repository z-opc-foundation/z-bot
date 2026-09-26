package com.zifang.z.bot.context;

import com.zifang.z.bot.store.StateStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * P14 的落盘侧：{@link CompressionGate} 的 sqlite 实现 + 「压缩 = 会话分叉」的血统登记。
 *
 * <p>三件事都只能有一个权威口径 —— 库：</p>
 * <ul>
 *   <li><b>抢锁</b>：走 {@code compression_locks}（P15 建的表，此前生产侧零消费者）。
 *       锁键用<b>血统根</b>，否则分叉出的每个子会话各持一把锁，等于没锁。</li>
 *   <li><b>冷却 / 防抖</b>：写 {@code state_meta}（不改 schema，P15 的表结构一个字节都不动），
 *       所以杀掉进程再起来，冷却还在。</li>
 *   <li><b>血统</b>：压缩成功后 {@link #forkForCompression} 挂 {@code parent_session_id}
 *       并给子会话派 {@code base #N} 标题；{@link #searchAlongLineage} 让检索沿血统回溯到
 *       「原文还活着的那一代」并去重。</li>
 * </ul>
 */
public class CompressionLedger implements CompressionGate {

    /** meta 键前缀：压缩失败冷却的截止时刻（epoch millis）。 */
    public static final String META_COOLDOWN_PREFIX = "compression.cooldown.";
    /** meta 键前缀：连续无效压缩计数。 */
    public static final String META_INEFFECTIVE_PREFIX = "compression.ineffective.";
    /** 分叉标题前缀：{@code base #N}，N 从 1 起（根会话不带这个标题）。 */
    public static final String FORK_TITLE_PREFIX = "base #";
    /** 血统回溯的最大深度（防御性；环由 StateStore.setParentSession 挡住）。 */
    public static final int MAX_LINEAGE_DEPTH = 32;
    /** 分叉时会话的结束原因。 */
    public static final String END_REASON_COMPRESSED = "compressed";

    private final StateStore store;
    private final String holder = "zbot-" + UUID.randomUUID();
    private final String processKey = "process:" + holder;

    public CompressionLedger(StateStore store) {
        this.store = store;
    }

    public boolean isBackedByDatabase() {
        return store != null;
    }

    public String holderId() {
        return holder;
    }

    // ===== CompressionGate：DB 抢锁 =====

    @Override
    public boolean tryAcquire(String sessionId, String holderIgnored, long ttlMillis) {
        if (store == null) {
            return true;
        }
        return store.tryAcquireCompressionLock(lockKey(sessionId), holder, Math.max(1L, ttlMillis));
    }

    @Override
    public void release(String sessionId, String holderIgnored) {
        if (store == null) {
            return;
        }
        store.releaseCompressionLock(lockKey(sessionId), holder);
    }

    /** 谁持有这条血统的压缩锁（null = 无主）；/compress preview 与单测都读它。 */
    public String lockHolder(String sessionId) {
        return store == null ? null : store.compressionLockHolder(lockKey(sessionId));
    }

    // ===== CompressionGate：冷却 / 防抖入库 =====

    @Override
    public long loadCooldownUntilMillis(String sessionId) {
        return readLong(META_COOLDOWN_PREFIX + stateKey(sessionId));
    }

    @Override
    public void storeCooldownUntilMillis(String sessionId, long untilMillis) {
        writeLong(META_COOLDOWN_PREFIX + stateKey(sessionId), untilMillis);
    }

    @Override
    public int loadIneffectiveStreak(String sessionId) {
        return (int) readLong(META_INEFFECTIVE_PREFIX + stateKey(sessionId));
    }

    @Override
    public void storeIneffectiveStreak(String sessionId, int streak) {
        writeLong(META_INEFFECTIVE_PREFIX + stateKey(sessionId), Math.max(0, streak));
    }

    @Override
    public String currentSessionId() {
        return null;    // 由引擎侧的 supplier 提供；见 CompressorEngine.setSessionIdSupplier
    }

    // ===== 血统：压缩 = 分叉 =====

    /**
     * 把「压缩后的这条子会话」挂到父会话下并派号。
     *
     * @param parentId  压缩发生时所在的会话
     * @param childId   已经建好的新会话（{@code SessionManager.createSession()} 出来的，库里已存在）
     * @return 派到的 {@code base #N} 标题；失败（无库 / 子不存在 / 会成环）返回 null
     */
    public String forkForCompression(String parentId, String childId) {
        if (store == null || childId == null || childId.trim().isEmpty()) {
            return null;
        }
        int depth = lineageDepth(parentId);
        String title = FORK_TITLE_PREFIX + (depth + 1);
        if (store.forkSession(parentId, childId, title, null, END_REASON_COMPRESSED) == null) {
            return null;
        }
        return retitle(childId, title) ? title : null;
    }

    /**
     * 只换取材：把某个会话的标题改掉，<b>其余列一律按库里现值回填</b>
     * （{@code upsertSession} 会把没传的列写成 null/0，直接拿它改标题会顺手清掉 token 账）。
     */
    public boolean retitle(String sessionId, String title) {
        if (store == null || sessionId == null) {
            return false;
        }
        StateStore.SessionRow row = findRow(sessionId);
        if (row == null) {
            return false;
        }
        store.upsertSession(row.id, title, row.model, row.provider, row.messageCount,
                row.tokens, row.apiCalls, row.source);
        return true;
    }

    /** 父链长度（自己不算）：根会话 = 0，第一次分叉出来的 = 1。 */
    public int lineageDepth(String sessionId) {
        if (store == null || sessionId == null) {
            return 0;
        }
        int n = 0;
        String p = store.parentOf(sessionId);
        Set<String> seen = new HashSet<String>();
        seen.add(sessionId);
        while (p != null && n < MAX_LINEAGE_DEPTH && seen.add(p)) {
            n++;
            p = store.parentOf(p);
        }
        return n;
    }

    /** 血统根：一直往上走到没有父为止（锁与冷却状态都按根记账，分叉不会把计数洗白）。 */
    public String lineageRoot(String sessionId) {
        if (store == null || sessionId == null) {
            return sessionId;
        }
        String cur = sessionId;
        for (int n = 0; n < MAX_LINEAGE_DEPTH; n++) {
            String p = store.parentOf(cur);
            if (p == null || p.equals(cur)) {
                return cur;
            }
            cur = p;
        }
        return cur;
    }

    /**
     * 沿血统回溯并去重的检索。
     *
     * <p>压缩把中段原文搬进了摘要消息，于是同一个词会同时在「子会话的摘要」和
     * 「父会话的原文」里命中两次。这里对每条命中往上走，只要能找到<b>同样命中的祖先</b>
     * 就把结果换成那个祖先（原文活着的那一代才有看头），再按会话 id 去重。</p>
     *
     * @return 回溯去重后的命中，最多 {@code limit} 条
     */
    public List<SearchHit> searchAlongLineage(String keyword, int limit) {
        List<SearchHit> out = new ArrayList<SearchHit>();
        if (store == null || keyword == null || keyword.trim().isEmpty() || limit <= 0) {
            return out;
        }
        List<StateStore.SearchHit> raw = store.search(keyword, Math.max(limit * 4, limit + 16));
        LinkedHashMap<String, String> bySession = new LinkedHashMap<String, String>();
        for (StateStore.SearchHit h : raw) {
            if (h.sessionId != null && !bySession.containsKey(h.sessionId)) {
                bySession.put(h.sessionId, h.snippet);
            }
        }
        Set<String> emitted = new HashSet<String>();
        for (java.util.Map.Entry<String, String> e : bySession.entrySet()) {
            String origin = e.getKey();
            for (int depth = 0; depth < MAX_LINEAGE_DEPTH; depth++) {
                String p = store.parentOf(origin);
                if (p == null || !bySession.containsKey(p) || p.equals(origin)) {
                    break;
                }
                origin = p;
            }
            if (emitted.add(origin)) {
                out.add(new SearchHit(origin, bySession.get(origin)));
            }
            if (out.size() >= limit) {
                return out;
            }
        }
        return out;
    }

    /** 一条命中：会话 id + 片段。单独定义是为了不把 {@code StateStore.SearchHit} 漏给测试面。 */
    public static final class SearchHit {
        public final String sessionId;
        public final String snippet;

        public SearchHit(String sessionId, String snippet) {
            this.sessionId = sessionId;
            this.snippet = snippet;
        }

        @Override
        public String toString() {
            return sessionId + " :: " + snippet;
        }
    }

    // ===== 内部 =====

    private StateStore.SessionRow findRow(String sessionId) {
        List<StateStore.SessionRow> rows = store.listSessions(true);
        if (rows != null) {
            for (StateStore.SessionRow r : rows) {
                if (sessionId.equals(r.id)) {
                    return r;
                }
            }
        }
        return null;
    }

    private String stateKey(String sessionId) {
        String root = sessionId == null ? null : lineageRoot(sessionId);
        return root == null ? processKey : root;
    }

    private String lockKey(String sessionId) {
        String root = sessionId == null ? null : lineageRoot(sessionId);
        return root == null ? processKey : root;
    }

    private long readLong(String key) {
        if (store == null) {
            return 0L;
        }
        String raw = store.getMeta(key);
        if (raw == null || raw.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void writeLong(String key, long value) {
        if (store == null) {
            return;
        }
        if (value <= 0L) {
            store.removeMeta(key);
            return;
        }
        store.setMeta(key, Long.toString(value));
    }
}
