package com.zifang.z.bot.acp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACP sessionId ↔ z-bot 会话 id 的<b>双向</b>映射表。
 *
 * <h2>两个 id，不是一个</h2>
 * <ul>
 *   <li>ACP id（{@code acp-<12hex>}）由 z-bot 在 {@code session/new} 时生成并回给客户端，
 *       是这条 <b>连接</b> 的句柄；</li>
 *   <li>z-bot 会话 id 是 {@code session/SessionManager} 落盘（state.db + sessions 目录）那条，
 *       跨进程存在。</li>
 * </ul>
 * 分开是为了让"重启动后怎么办"有明确答案，而不是把两个生命周期混成一个。
 *
 * <h2>重启语义（工单 §1.3 要的"要么显式恢复、要么大声拒绝"）</h2>
 * <ul>
 *   <li>ACP id <b>不跨进程</b>：进程重启后拿旧 ACP id 来 {@code session/load} ⇒
 *       {@link #resolve} 抛 {@link AcpProtocolException}，文案点名"ACP 会话 id 不跨进程"、
 *       并给出可恢复的 z-bot 会话 id 清单（来自 {@link AcpTurnTarget.Factory#knownZbotSessionIds()}）。
 *       这是<b>大声拒绝</b>，不是静默新建。</li>
 *   <li>z-bot 会话 id <b>跨进程</b>：同一个 id 既可以当 ACP id 传（旧连接），也可以显式按
 *       z-bot id {@code session/load}/{@code session/resume} 采纳 ⇒ {@link #adopt} 建一条新映射、
 *       历史由 server 侧回灌。这是<b>显式恢复</b>。</li>
 * </ul>
 *
 * <p>本表<b>不落盘</b>：写任何文件都会碰 {@code ~/.zbot} 的不变量（杠④），而 ACP id 按上面
 * 的语义本来就不该跨进程存活。映射若将来要持久化，得先解决杠④，属于 WIRING 事项。</p>
 */
public final class AcpSessionRegistry {

    /** 一条 ACP 会话的全部连接期状态。 */
    public static final class AcpSession {
        private final String acpSessionId;
        private final String zbotSessionId;
        private volatile AcpTurnTarget target;
        private final long createdAtMillis = System.currentTimeMillis();
        private volatile String cwd;
        private volatile String modeId;
        private volatile boolean closed;

        AcpSession(String acpSessionId, String zbotSessionId, AcpTurnTarget target, String cwd) {
            this.acpSessionId = acpSessionId;
            this.zbotSessionId = zbotSessionId;
            this.target = target;
            this.cwd = cwd;
        }

        public String acpSessionId() {
            return acpSessionId;
        }

        public String zbotSessionId() {
            return zbotSessionId;
        }

        public AcpTurnTarget target() {
            return target;
        }

        /** 换 target 只由 {@link AcpSessionRegistry#reconfigure} 调用（ACP 句柄不变）。 */
        void replaceTarget(AcpTurnTarget replacement) {
            this.target = replacement;
        }

        public String cwd() {
            return cwd;
        }

        public String modeId() {
            return modeId;
        }

        public void setModeId(String modeId) {
            this.modeId = modeId;
        }

        public boolean isClosed() {
            return closed;
        }

        public long createdAtMillis() {
            return createdAtMillis;
        }

        void markClosed() {
            this.closed = true;
        }

        void setCwd(String cwd) {
            this.cwd = cwd;
        }

        @Override
        public String toString() {
            return "AcpSession(" + acpSessionId + "↔" + zbotSessionId + ")";
        }
    }

    private final AcpTurnTarget.Factory factory;
    private final Map<String, AcpSession> byAcpId =
            new LinkedHashMap<String, AcpSession>();
    private final Map<String, AcpSession> byZbotId =
            new LinkedHashMap<String, AcpSession>();

    public AcpSessionRegistry(AcpTurnTarget.Factory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory 不能为 null");
        }
        this.factory = factory;
    }

    /** {@code session/new}：新 z-bot 会话 + 新 ACP 句柄，两个方向各记一遍。 */
    public synchronized AcpSession create(String cwd) {
        AcpTurnTarget target = factory.open(AcpTurnTarget.Demand.fresh(cwd));
        String zbotId = target.zbotSessionId();
        if (zbotId != null && byZbotId.containsKey(zbotId)) {
            // 一个 BotAgent 实例一条会话；撞上说明工厂没换实例，映射会立刻变成假账。
            factory.dispose(target);
            throw AcpProtocolException.internal(
                    "session/new 产出已存在的 z-bot 会话 id " + zbotId + "，拒绝登记重复映射", null);
        }
        return register(newAcpSessionId(), zbotId, target, cwd);
    }

    /** {@code session/load}/{@code session/resume} 按 z-bot 会话 id 显式恢复。 */
    public synchronized AcpSession adopt(String cwd, String zbotSessionId) {
        return adopt(cwd, zbotSessionId, null);
    }

    /**
     * 连接侧句柄的统一入口：先当在线 ACP 句柄用，再当落盘 z-bot 会话 id 用。
     *
     * <p>两种句柄都收，是因为 ACP 客户端手里只有 {@code sessionId} 一个字段，
     * 它可能带回来的就是 {@code session/list} 里看到的 z-bot id（重启后的恢复路径）。</p>
     */
    public synchronized AcpSession attach(String cwd, String sessionId) {
        AcpSession live = byAcpId.get(sessionId);
        if (live != null) {
            if (cwd != null && !cwd.isEmpty()) {
                live.setCwd(cwd);
            }
            return live;
        }
        return adopt(cwd, sessionId, null);
    }

    /**
     * 显式恢复。
     *
     * @param model 非空时按该模型开会话（{@code session/set_model} 的重开路径也走这里）
     */
    public synchronized AcpSession adopt(String cwd, String zbotSessionId, String model) {
        AcpSession live = byZbotId.get(zbotSessionId);
        if (live != null) {
            if (cwd != null && !cwd.isEmpty()) {
                live.setCwd(cwd);
            }
            return live;
        }
        if (!factory.knownZbotSessionIds().contains(zbotSessionId)) {
            throw unknownSession(zbotSessionId);
        }
        return register(newAcpSessionId(), zbotSessionId,
                factory.open(new AcpTurnTarget.Demand(cwd, zbotSessionId, model, null)), cwd);
    }

    /**
     * {@code session/set_model}：换模型 = 用同一个 z-bot 会话 id 重开 target。
     *
     * <p>为什么要重开：z-bot 的 model 是 {@code BotAgent.Builder.build()} 时钉进实例的
     * （{@code agent/BotAgent.java:174}），改 {@code BotConfig} 不会作用到已在跑的 agent ——
     * 只改记账字段就会造成"IDE 显示新模型、跑的还是旧模型"（红线 8 的账实分离）。
     * 重开保住了 ACP 句柄与落盘历史，模型这一维是真的换了。</p>
     *
     * <p>ACP 句柄不变（客户端不需要重新握手），{@code acpSessionId} 与
     * {@code zbotSessionId} 的映射关系也不变。</p>
     */
    public synchronized AcpSession reconfigure(AcpSession session, String model) {
        AcpTurnTarget replacement = factory.open(
                new AcpTurnTarget.Demand(session.cwd(), session.zbotSessionId(), model, null));
        AcpTurnTarget previous = session.target();
        session.replaceTarget(replacement);
        byAcpId.put(session.acpSessionId(), session);
        byZbotId.put(session.zbotSessionId(), session);
        factory.dispose(previous);
        return session;
    }

    /**
     * 双向解析：先按 ACP id，再按 z-bot 会话 id（允许两种句柄混用，恢复路径靠它）。
     *
     * @throws AcpProtocolException 两条都查不到 ⇒ 大声拒绝，并把可恢复清单附在文案里
     */
    public synchronized AcpSession resolve(String sessionId) {
        AcpSession byAcp = byAcpId.get(sessionId);
        if (byAcp != null) {
            return byAcp;
        }
        AcpSession byZbot = byZbotId.get(sessionId);
        if (byZbot != null) {
            return byZbot;
        }
        throw unknownSession(sessionId);
    }

    /** 反查：z-bot 会话 id → ACP 句柄；没有返回 null（{@code session/list} 用）。 */
    public synchronized AcpSession byZbotSessionId(String zbotSessionId) {
        return byZbotId.get(zbotSessionId);
    }

    public synchronized List<AcpSession> sessions() {
        return new ArrayList<AcpSession>(byAcpId.values());
    }

    public synchronized int size() {
        return byAcpId.size();
    }

    /** {@code session/close}：只关连接期状态，落盘会话仍在（可被 {@link #adopt} 再采纳）。 */
    public synchronized AcpSession close(String sessionId) {
        AcpSession session = resolve(sessionId);
        session.markClosed();
        byAcpId.remove(session.acpSessionId());
        byZbotId.remove(session.zbotSessionId());
        factory.dispose(session.target());
        return session;
    }

    /** 连接断开：关掉全部，别让 BotAgent 线程与在飞子进程漂着。 */
    public synchronized void closeAll() {
        for (AcpSession session : new ArrayList<AcpSession>(byAcpId.values())) {
            session.markClosed();
            factory.dispose(session.target());
        }
        byAcpId.clear();
        byZbotId.clear();
    }

    public List<String> knownZbotSessionIds() {
        return Collections.unmodifiableList(new ArrayList<String>(factory.knownZbotSessionIds()));
    }

    private AcpSession register(String acpId, String zbotId, AcpTurnTarget target, String cwd) {
        AcpSession session = new AcpSession(acpId, zbotId, target, cwd);
        byAcpId.put(acpId, session);
        if (zbotId != null) {
            byZbotId.put(zbotId, session);
        }
        return session;
    }

    private AcpProtocolException unknownSession(String sessionId) {
        List<String> restorable;
        try {
            restorable = factory.knownZbotSessionIds();
        } catch (RuntimeException e) {
            restorable = new ArrayList<String>();
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String id : restorable) {
            if (shown++ >= 8) {
                sb.append(", …");
                break;
            }
            sb.append(sb.length() == 0 ? "" : ", ").append(id);
        }
        return new AcpProtocolException(JsonRpc.INVALID_PARAMS,
                "unknown sessionId: " + sessionId
                        + " —— ACP 会话 id 不跨进程存活；重启后可用 session/list 取得 z-bot 会话 id"
                        + "（落盘可恢复：" + (sb.length() == 0 ? "无" : sb.toString()) + "）"
                        + "，再按该 id 调 session/load 或 session/resume",
                null, null);
    }

    private static String newAcpSessionId() {
        return "acp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
