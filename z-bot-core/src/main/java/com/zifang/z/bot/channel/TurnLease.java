package com.zifang.z.bot.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话轮次租约（对标 {@code hermes gateway/turn_lease.py} 302 行）：把
 * <b>装历史 → 跑一轮 → 刷盘</b> 这一段按<b>解析后 session_id</b> 串行化。
 *
 * <p><b>为什么不按路由键上锁</b>（她 #64934 的理由，我们在 {@link ChannelBus} 上撞到同一形状）：
 * bus 的守卫按路由键（{@code channel:conversationId}）分 agent 实例，而真正的持久 transcript 归
 * session_id 所有，且 {@code switchSession}/{@code /resume} 会让 key→id <b>多对一</b>。
 * 两个路由键映射到同一个 session_id 时，两轮跑在两个不同的 agent 对象上，按 key 的守卫永远看不见
 * 这次碰撞：两次 flush 交织进同一份 transcript（行按完成序落库、后写者覆盖前者），
 * 第二轮读到的历史基线里没有第一轮那一问一答 ⇒ 留下永久的 {@code user;user} 交替楔死。
 * 按解析后 session_id 上锁正好关掉这条路。</p>
 *
 * <p>三条安全性质（与她同源）：</p>
 * <ul>
 *   <li><b>身份校验 + 幂等释放</b>：token 记着 owner（路由键）与 generation，{@link #release}
 *       只有当这个 token <b>就是</b>当前持有者时才放行；过期 unwind 绝不可能释放新一轮的租约。</li>
 *   <li><b>超时 fail-open</b>：持有者卡死时，等租约的一方在配置等待上限后降级为「不加锁照跑」并打
 *       一条响亮的 ERROR —— 宁可交织，绝不楔死会话。降级 token 什么都不持有，释放是 no-op。</li>
 *   <li><b>注册表有界</b>：租约表有条数上限，逐出只会挑「没持有者且没排队者」的空闲条目，
 *       绝不逐出活租约（正确性优先于上限）。</li>
 * </ul>
 *
 * <p><b>与她的一处必要差异</b>（不是扩面，是 Java 锁语义逼出来的）：她的 {@code asyncio.Lock}
 * 在有 waiter 排队时仍保持 locked，故 {@code idle = holder is None and not locked} 已足够；
 * {@code ReentrantLock} 在「持有者刚释放、等待者尚未醒来」这段窗口里 {@code isLocked()} 为假，
 * 若照抄就会把还有等待者的租约逐出，于是同一 session 出现两把锁 ⇒ 反而造出她要修的那个洞。
 * 故 {@link SessionLease#isIdle()} 额外要求 {@code !hasQueuedThreads()}。</p>
 *
 * <p>已知边界（照她的自省，不遮蔽）：跨进程共享同一 session 的另一条路（相同 {@code --config-dir}
 * / 同一份 {@code state.db} 的 CLI 进程）不在任何进程内锁的覆盖面里，那对需要库级租约。</p>
 */
public final class TurnLease {

    private static final Logger LOG = LoggerFactory.getLogger(TurnLease.class);

    /** 每会话租约表的上限；只逐出空闲条目，突发多会话时可临时越界。 */
    public static final int DEFAULT_MAX_LEASES = 512;

    /** 调用方没给正数等待上限时的兜底（毫秒）——与她 {@code DEFAULT_LEASE_WAIT=1800s} 同源。 */
    public static final long DEFAULT_LEASE_WAIT_MS = 1800_000L;

    private TurnLease() {
    }

    /** {@link SessionTurnLeaseRegistry#acquire} 返回的把手。 */
    public static final class Token {

        private final String ownerKey;
        private final long generation;
        private volatile String sessionId;
        private volatile boolean degraded;
        private volatile boolean released;

        Token(String sessionId, String ownerKey, long generation, boolean degraded) {
            this.sessionId = sessionId;
            this.ownerKey = ownerKey;
            this.generation = generation;
            this.degraded = degraded;
        }

        public String sessionId() {
            return sessionId;
        }

        public String ownerKey() {
            return ownerKey;
        }

        public long generation() {
            return generation;
        }

        /** {@code true} = 等租约超时后<b>未串行化</b>地放行（fail-open），什么都不持有。 */
        public boolean isDegraded() {
            return degraded;
        }

        public boolean isReleased() {
            return released;
        }

        @Override
        public String toString() {
            return "TurnLeaseToken(session_id=" + sessionId + ", owner_key=" + ownerKey
                    + ", generation=" + generation + ", degraded=" + degraded + ", released=" + released + ")";
        }
    }

    /** 一个 session_id 一份；进程内、按 session 粒度。 */
    static final class SessionLease {

        final ReentrantLock lock = new ReentrantLock(true);
        volatile Token holder;
        volatile Thread ownerThread;
        volatile long acquiredAtMillis;
        volatile long lastUsedMillis = System.currentTimeMillis();

        /**
         * 可被逐出：既没人持有、也没人在排队。
         *
         * <p>排队者这一项是 Java 侧相对 hermes 的必要加强，见类注释。</p>
         */
        boolean isIdle() {
            return holder == null && !lock.isLocked() && !lock.hasQueuedThreads();
        }

        /** 诊断：真正持锁的线程名（跨线程误释放的告警要指名它是谁）。 */
        String ownerThreadName() {
            Thread t = ownerThread;
            return t == null ? "?" : t.getName();
        }
    }

    /**
     * 按<b>解析后</b> session_id 序列化的租约注册表（她 {@code SessionTurnLeaseRegistry} 的同形物）。
     *
     * <p>与 hermes 的进程内单事件循环不同：我们是多线程 bus（{@code newCachedThreadPool}），
     * 所以用 {@link ReentrantLock#tryLock(long, TimeUnit)} 表达等价的「有界等待 + 超时降级」。</p>
     */
    public static final class SessionTurnLeaseRegistry {

        /** 表：session_id → 租约；LinkedHashMap 保插入序，逐出按 lastUsed 从旧到新。 */
        private final Map<String, SessionLease> leases = new LinkedHashMap<String, SessionLease>();
        private final int maxEntries;

        public SessionTurnLeaseRegistry() {
            this(DEFAULT_MAX_LEASES);
        }

        public SessionTurnLeaseRegistry(int maxEntries) {
            this.maxEntries = Math.max(1, maxEntries);
        }

        /** 当前跟踪的租约条数（含空闲项，与 hermes {@code __len__} 同口径）。 */
        public synchronized int size() {
            return leases.size();
        }

        /** 诊断：某 session 当前持有者（没有则 {@code null}）。 */
        public synchronized Token holderOf(String sessionId) {
            SessionLease lease = sessionId == null ? null : leases.get(sessionId);
            return lease == null ? null : lease.holder;
        }

        /**
         * 取 {@code sessionId} 的轮次租约，被占则等。
         *
         * @param waitMillis 等待上限；{@code null} 或非正数走 {@link #DEFAULT_LEASE_WAIT_MS}
         * @return token；{@code isDegraded()} 为真表示等超时 ⇒ 调用方<b>不加锁继续跑</b>（fail-open）。
         *         {@code sessionId} 为空时返回 {@code null}（没有 transcript 归属，也就无锁可上）。
         */
        public Token acquire(String sessionId, String ownerKey, long generation, Long waitMillis) {
            if (sessionId == null || sessionId.isEmpty()) {
                return null;
            }
            long wait = waitMillis == null || waitMillis.longValue() <= 0L
                    ? DEFAULT_LEASE_WAIT_MS : waitMillis.longValue();
            Token token = new Token(sessionId, ownerKey, generation, false);
            SessionLease lease = getOrCreate(sessionId);
            Token watched = lease.holder;
            if (watched != null && watched != token) {
                LOG.warn("[turn-lease] session {} 租约冲突：路由键 {} (gen {}) 排在在飞轮次之后"
                                + "（持有者 {} gen {}，已持有 {}ms）—— 两个路由键映射到了同一个 session_id，"
                                + "本轮串行化到上一轮的 flush 之后",
                        sessionId, ownerKey, generation, watched.ownerKey(), watched.generation(),
                        lease.acquiredAtMillis == 0L ? -1L
                                : System.currentTimeMillis() - lease.acquiredAtMillis);
            }
            boolean got;
            try {
                got = lease.lock.tryLock(wait, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                token.degraded = true;
                LOG.error("[turn-lease] session {} 等租约被中断（等待方 {} gen {}）—— fail-open："
                        + "本轮不串行化跑，transcript 写入可能交织", sessionId, ownerKey, generation);
                return token;
            }
            if (!got) {
                Token held = lease.holder;
                LOG.error("[turn-lease] session {} 等租约超时 {}ms（等待方 {} gen {}；持有者 {} gen {}）"
                                + " —— fail-open：本轮对着卡死的持有者不串行化跑，而不是楔死会话，"
                                + "transcript 写入可能交织",
                        sessionId, wait, ownerKey, generation,
                        held == null ? "?" : held.ownerKey(),
                        held == null ? "?" : String.valueOf(held.generation()));
                token.degraded = true;
                return token;
            }
            lease.holder = token;
            lease.ownerThread = Thread.currentThread();
            lease.acquiredAtMillis = System.currentTimeMillis();
            lease.lastUsedMillis = lease.acquiredAtMillis;
            return token;
        }

        /**
         * 一轮跑到一半 transcript 换了 id（压缩分叉 / {@code /new}）时，把<b>已持有</b>的租约
         * 别名到新 id 上，让后续按新 id 解析出来的轮次仍与本轮串行。
         *
         * <p>机制：同一个 {@link SessionLease} 对象同时挂在两个 key 下（旧映射等它空闲后再逐出），
         * 于是两边争同一把锁；不搬任何锁状态。只有当前持有者能 rebind（与释放同样做身份校验），
         * token 跟着走。若目标 id 已有<b>活的</b>租约，两条串行化域不能在半路合并 ⇒ 大声记一条并
         * 把租约留在旧 id（fail-open，持有者不能在轮中再等锁）。</p>
         */
        public boolean rebind(Token token, String newSessionId) {
            if (token == null || token.degraded || token.released
                    || newSessionId == null || newSessionId.isEmpty()
                    || newSessionId.equals(token.sessionId())) {
                return false;
            }
            SessionLease lease;
            SessionLease existing;
            String oldId;
            synchronized (this) {
                oldId = token.sessionId();
                lease = leases.get(oldId);
                if (lease == null || lease.holder != token) {
                    return false;
                }
                existing = leases.get(newSessionId);
                if (existing != null && existing != lease && !existing.isIdle()) {
                    Token held = existing.holder;
                    LOG.warn("[turn-lease] rebind 被挡：session {} 中途换到 {}（持有者 {} gen {}），"
                                    + "但目标 id 的租约是活的（持有者 {} gen {}）—— 租约留在旧 id，"
                                    + "{} 的 transcript 写入可能交织",
                            oldId, newSessionId, token.ownerKey(), token.generation(),
                            held == null ? "?" : held.ownerKey(),
                            held == null ? "?" : String.valueOf(held.generation()), newSessionId);
                    return false;
                }
                leases.put(newSessionId, lease);
                lease.lastUsedMillis = System.currentTimeMillis();
            }
            token.sessionId = newSessionId;
            return true;
        }

        /**
         * 释放 {@code token} 的租约。幂等 + 身份校验。
         *
         * @return 只有「这个 token 确实是当前持有者且锁被放开」才 {@code true}。
         *         降级 token、重复释放、槽位已交给更新一轮的陈旧 token 全是安全的 no-op。
         */
        public boolean release(Token token) {
            if (token == null || token.degraded || token.released) {
                return false;
            }
            SessionLease lease;
            synchronized (this) {
                lease = leases.get(token.sessionId());
            }
            if (lease == null) {
                return false;
            }
            if (lease.holder != token) {
                LOG.debug("[turn-lease] session {} 释放被跳过：token (key {} gen {}) 不是当前持有者",
                        token.sessionId(), token.ownerKey(), token.generation());
                return false;
            }
            // 与 asyncio 版的实质差异：{@code ReentrantLock} 的 owner 是线程，跨线程 release 会把
            // holder 清掉却解不开锁 ⇒ 那个 session 从此永久楔死（正是本类要修的症状）。
            // 故这里除了 token 身份，还要求线程身份；不满足就当空操作，绝不留下半把锁。
            if (!lease.lock.isHeldByCurrentThread()) {
                LOG.error("[turn-lease] session {} 释放被拒：token (key {} gen {}) 身份对得上但"
                                + "当前线程不是持锁线程 —— Java 锁按线程持有，跨线程释放会留下永久的锁。"
                                + "真持锁线程 {} 仍持有该轮",
                        token.sessionId(), token.ownerKey(), token.generation(),
                        lease.ownerThreadName());
                return false;
            }
            // 校验全过才盖章：半途标 released 会让真正的持锁线程再也放不掉这把锁
            token.released = true;
            lease.holder = null;
            lease.ownerThread = null;
            lease.acquiredAtMillis = 0L;
            lease.lastUsedMillis = System.currentTimeMillis();
            lease.lock.unlock();
            return true;
        }

        private synchronized SessionLease getOrCreate(String sessionId) {
            SessionLease lease = leases.get(sessionId);
            if (lease == null) {
                evictIdle();
                lease = new SessionLease();
                leases.put(sessionId, lease);
            }
            lease.lastUsedMillis = System.currentTimeMillis();
            return lease;
        }

        /**
         * 为新租约腾位置：按 lastUsed 从旧到新丢掉空闲条目。
         * 绝不逐出被持有或有排队者的租约 —— 正确性优先于上限。
         */
        private void evictIdle() {
            int overflow = leases.size() - maxEntries + 1;
            if (overflow <= 0) {
                return;
            }
            List<String> idle = new ArrayList<String>();
            for (Map.Entry<String, SessionLease> e : leases.entrySet()) {
                if (e.getValue().isIdle()) {
                    idle.add(e.getKey());
                }
            }
            final Map<String, SessionLease> view = leases;
            Collections.sort(idle, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    long ta = view.get(a) == null ? 0L : view.get(a).lastUsedMillis;
                    long tb = view.get(b) == null ? 0L : view.get(b).lastUsedMillis;
                    return ta < tb ? -1 : (ta > tb ? 1 : 0);
                }
            });
            for (int i = 0; i < idle.size() && i < overflow; i++) {
                SessionLease candidate = leases.get(idle.get(i));
                if (candidate != null && candidate.isIdle()) {
                    leases.remove(idle.get(i));
                }
            }
        }
    }
}
