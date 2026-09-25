package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通道总线：所有通道入站消息汇到 {@link #deliver(ChannelMessage)}，
 * bus 调 agent 跑一轮对话，把回复 fan-out 给订阅了该 {@code conversationId} 的通道。
 *
 * <p>对话隔离：每个 conversationId 维护独立的 BotAgent 实例（避免群/私聊间记忆串味），
 * 并发用 {@link SessionManager} 文件锁 + 串行 executor 保单会话串行。</p>
 *
 * <p><b>P16 网关送达三件套挂在这一层</b>（三件都得贴着「跑一轮」与「发出去」这两个动作）：</p>
 * <ol>
 *   <li>{@link TurnLease} —— 包住「装历史 → 跑一轮 → 刷盘」整段，键是<b>解析后的 session_id</b>，
 *       不是路由键。路由键（{@code agents} 的键）与 session_id 是<b>多对一</b>的
 *       （{@link #graftConversation} 让多个路由键共用同一份 transcript，斜杠命令 {@code /resume}
 *       与压缩分叉还会让一个路由键的 session_id 中途改变），按路由键上锁正好看不见这种碰撞。</li>
 *   <li>{@link DeliveryLedger} —— 红线 8 的执行点：{@code recordObligation} 在<b>任何发送动作之前</b>，
 *       {@code markAttempting} 紧贴 {@code channel.send}，{@code markDelivered} 只在 send 返回之后。
 *       崩溃在中间的行由 {@link Gateway#start()} 的 {@code sweepRecoverable} 带标记重投。</li>
 *   <li>{@link DeadTargets} —— send 前短路已确认不可达的目标；send 抛回「整会话级」错误就登记，
 *       send 成功就清标记（自愈）。</li>
 * </ol>
 *
 * <p>三件都是<b>可选接线</b>：没有 profile（{@code Gateway} 拿不到 configDir）时全部为空操作，
 * bus 退化成 P16 之前的行为，绝不让台账/登记表故障拖垮真投递。</p>
 *
 * <p>总线也带一个轻量 ring buffer（最近 200 条消息）供 {@code z-bot status} / 测试用。</p>
 */
public final class ChannelBus {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelBus.class);

    /** 用于总线状态回报的最近消息节点。 */
    public static final class Entry {
        public final long ts;
        public final String channel;
        public final String conversationId;
        public final String senderId;
        public final String text;
        public final String reply;

        Entry(long ts, String channel, String conversationId, String senderId, String text, String reply) {
            this.ts = ts;
            this.channel = channel;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.text = text;
            this.reply = reply;
        }

        @Override
        public String toString() {
            return ts + " " + channel + "|" + conversationId + "|" + senderId + " ← \"" + text + "\" → \"" + reply + "\"";
        }
    }

    private final BotAgent prototype;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "z-bot-bus");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, BotAgent> agents = new ConcurrentHashMap<String, BotAgent>();
    private final Map<String, Channel> byName = new ConcurrentHashMap<String, Channel>();
    private final List<Entry> history = new CopyOnWriteArrayList<Entry>();
    private final int historyLimit;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ===== P16 三件套（全部可选接线） =====

    /** 按解析后 session_id 串行的轮次租约；永不为空（它自己零成本，且 fail-open 保证楔不死）。 */
    private final TurnLease.SessionTurnLeaseRegistry turnLeases =
            new TurnLease.SessionTurnLeaseRegistry();
    /** 路由键 → 被并入的路由键（多对一：{@link #graftConversation}）。 */
    private final Map<String, String> graftedInto = new ConcurrentHashMap<String, String>();
    private final AtomicLong turnSeq = new AtomicLong();
    private final AtomicLong leaseTimeouts = new AtomicLong();
    private volatile DeliveryLedger ledger;
    private volatile DeadTargets deadTargets;
    private volatile long leaseWaitMillis = TurnLease.DEFAULT_LEASE_WAIT_MS;

    public ChannelBus(BotAgent prototype) {
        this(prototype, 200);
    }

    public ChannelBus(BotAgent prototype, int historyLimit) {
        this.prototype = prototype;
        this.historyLimit = historyLimit;
    }

    /** 注册通道（gateway 启动时调用）。 */
    public void register(Channel channel) {
        byName.put(channel.name(), channel);
    }

    /** 列出当前注册的通道名。 */
    public List<String> channelNames() {
        return new java.util.ArrayList<String>(byName.keySet());
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        running.set(true);
    }

    public void shutdown() {
        running.set(false);
        pool.shutdownNow();
        for (Channel c : byName.values()) {
            try {
                c.stop();
            } catch (Exception e) {
                LOG.warn("[ChannelBus] 关闭 {} 失败: {}", c.name(), e.getMessage());
            }
        }
    }

    // ===== P16 接线面 =====

    /** 送达台账；{@code null} = 不落账（无 profile 的进程内 bus）。 */
    public void setDeliveryLedger(DeliveryLedger ledger) {
        this.ledger = ledger;
    }

    public DeliveryLedger deliveryLedger() {
        return ledger;
    }

    public void setDeadTargets(DeadTargets deadTargets) {
        this.deadTargets = deadTargets;
    }

    public DeadTargets deadTargets() {
        return deadTargets;
    }

    /** 等租约的上限毫秒（超时按 fail-open 处理）。 */
    public void setLeaseWaitMillis(long millis) {
        this.leaseWaitMillis = millis <= 0L ? TurnLease.DEFAULT_LEASE_WAIT_MS : millis;
    }

    public TurnLease.SessionTurnLeaseRegistry turnLeases() {
        return turnLeases;
    }

    /** 观测：等租约超时 ⇒ 未串行化放行（fail-open）的次数。正常网关应当恒为 0。 */
    public long leaseTimeoutCount() {
        return leaseTimeouts.get();
    }

    /** 诊断：某路由键当前解析到的 session_id（没有活动 agent 时 {@code null}）。 */
    public String sessionIdOf(String conversationId) {
        BotAgent a = conversationId == null ? null : agents.get(graftTarget(conversationId));
        return a == null ? null : a.currentSessionId();
    }

    /**
     * 把 {@code conversationId} 这一路聊天<b>并入</b> {@code ontoConversationId} 的 agent
     * （⇒ 同一份记忆、同一个 session_id、同一份落盘 transcript）。
     *
     * <p>这就是「多对一」的那条路：同一个人换了端、群被镜像、运维把两个会话并成一条，
     * 路由键还在但 transcript 只有一份。它存在的意义是让「按路由键上锁不够」这件事
     * 在代码里可达、可测，而不是只写在注释里。</p>
     *
     * @return 绑定成功（两端都能解析到会话目录）；{@code false} = 参数为空或自指
     */
    public boolean graftConversation(String conversationId, String ontoConversationId) {
        if (conversationId == null || conversationId.isEmpty()
                || ontoConversationId == null || ontoConversationId.isEmpty()
                || conversationId.equals(ontoConversationId)) {
            return false;
        }
        BotAgent target = agentForRoute(ontoConversationId);
        if (target == null) {
            return false;
        }
        agents.put(conversationId, target);
        graftedInto.put(conversationId, ontoConversationId);
        LOG.info("[ChannelBus] 会话 {} 并入 {}（共用 session_id {}）", conversationId, ontoConversationId,
                safeSessionId(target));
        return true;
    }

    private String graftTarget(String conversationId) {
        String t = graftedInto.get(conversationId);
        return t == null ? conversationId : t;
    }

    /** 取（必要时派生）某路由键的 agent 实例。 */
    public BotAgent agentForRoute(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        return agents.computeIfAbsent(conversationId, k -> prototype.forkFor(k));
    }

    // ===== 投递主路径 =====

    /**
     * 入站：通道收到一条消息就投到 bus，bus 调度 agent 跑一轮，
     * 把回复 fan-out 回订阅该 conversationId 的通道。
     */
    public void deliver(ChannelMessage inbound) {
        if (!running.get()) {
            LOG.debug("[ChannelBus] 未启动，丢弃入站 {}", inbound);
            return;
        }
        pool.submit(() -> handle(inbound));
    }

    private void handle(ChannelMessage inbound) {
        BotAgent agent = agentForRoute(inbound.conversationId);
        String routeKey = inbound.conversationId;
        // 租约键 = <b>解析后</b>的 session_id（transcript 真正归属的那个 id），不是路由键。
        String sessionId = safeSessionId(agent);
        long seq = turnSeq.incrementAndGet();
        TurnLease.Token token = turnLeases.acquire(sessionId, routeKey, seq,
                Long.valueOf(leaseWaitMillis));
        String reply;
        try {
            reply = agent.chat(inbound.text, new StreamListener() {
                @Override
                public void onEvent(StreamEvent event) {
                    // 通道流式支持留到后续迭代；当前只回最终答复
                }
            });
        } catch (Exception e) {
            reply = "Error: " + e.getMessage();
        } finally {
            // 一轮跑中途 session_id 变了（/new、压缩分叉）⇒ 把租约别名到新 id 再放，
            // 免得新 id 的下一轮与本轮的收尾刷盘并发。
            String after = safeSessionId(agent);
            if (token != null && after != null && !after.equals(token.sessionId())) {
                turnLeases.rebind(token, after);
            }
            turnLeases.release(token);
        }
        if (token != null && token.isDegraded()) {
            leaseTimeouts.incrementAndGet();
        }
        record(inbound, reply);
        OutboundMessage out = reply.startsWith("Error:")
                ? OutboundMessage.error(inbound.conversationId, reply)
                : OutboundMessage.text(inbound.conversationId, reply);
        // 优先发给来源通道；多端订阅场景下 fan-out 由调用方在 deliver 时改 path
        dispatch(inbound, routeKey, seq, out);
    }

    /**
     * 出站：<b>先落账再产生副作用</b>（红线 8），再判死目标，再 send，再收敛状态。
     *
     * <p>台账/登记表的任何故障都不许影响真投递：全程 best-effort。</p>
     */
    private void dispatch(ChannelMessage inbound, String routeKey, long seq, OutboundMessage out) {
        final DeliveryLedger led = ledger;
        DeadTargets dead = deadTargets;
        String platform = inbound.channel;
        String chatId = inbound.conversationId;
        String oid = null;
        if (led != null) {
            // 触发消息的稳定引用：平台带 raw 时用原文（同一条 webhook 重放 ⇒ 同一义务）
            String messageRef = inbound.raw != null && !inbound.raw.isEmpty()
                    ? platform + ":" + chatId + ":" + Integer.toHexString(inbound.raw.hashCode())
                    : platform + ":" + chatId + ":" + seq;
            oid = DeliveryLedger.computeObligationId(routeKey, messageRef, out.text);
            try {
                led.recordObligation(oid, routeKey, platform, chatId, out.text);
            } catch (RuntimeException e) {
                LOG.warn("[delivery-ledger] 落账异常（继续投递）: {}", e.getMessage());
                oid = null;
            }
        }
        Channel source = byName.get(platform);
        if (source == null) {
            // 来源通道已经不在了：这条义务没有可发的副作用，直接结清，别留悬挂行
            markLedger(led, oid, STATE_DELIVERED, "no-channel");
            return;
        }
        if (dead != null && dead.isDead(platform, chatId)) {
            LOG.warn("[dead-targets] {}:{} 已判不可达 —— 跳过本次投递（义务留在台账里等自愈）", platform, chatId);
            markLedger(led, oid, STATE_FAILED, "dead-target");
            return;
        }
        markLedger(led, oid, STATE_ATTEMPTING, null);
        try {
            source.send(out);
        } catch (Exception e) {
            String kind = DeadTargets.classifySendError(e, null);
            LOG.warn("[ChannelBus] 回 {} 失败（kind={}）: {}", platform, kind, e.getMessage());
            if (dead != null && DeadTargets.isDeadErrorKind(kind)) {
                try {
                    dead.markDead(platform, chatId, kind + ": " + e.getMessage());
                } catch (RuntimeException ignored) {
                    // 登记表坏了不能连带丢台账
                }
            }
            markLedger(led, oid, STATE_FAILED, kind + ": " + e);
            return;
        }
        if (dead != null) {
            try {
                dead.clear(platform, chatId); // 发出去过一次 ⇒ 目标还活着，标记自愈
            } catch (RuntimeException ignored) {
            }
        }
        markLedger(led, oid, STATE_DELIVERED, null);
    }

    private static final String STATE_DELIVERED = DeliveryLedger.DELIVERED;
    private static final String STATE_FAILED = DeliveryLedger.FAILED;
    private static final String STATE_ATTEMPTING = DeliveryLedger.ATTEMPTING;

    /**
     * 把台账状态推进一步；{@code arg} 只给 {@code failed} 当原因用。
     * 台账是旁路观测面，写失败/异常一律吞掉 —— 绝不能因为它把真投递拖下水。
     */
    private static void markLedger(DeliveryLedger led, String oid, String state, String arg) {
        if (led == null || oid == null) {
            return;
        }
        try {
            if (STATE_DELIVERED.equals(state)) {
                led.markDelivered(oid);
            } else if (STATE_FAILED.equals(state)) {
                led.markFailed(oid, arg);
            } else {
                led.markAttempting(oid);
            }
        } catch (RuntimeException e) {
            LOG.warn("[delivery-ledger] {} 写入异常 id={}: {}", state, oid, e.getMessage());
        }
    }

    private static String safeSessionId(BotAgent agent) {
        if (agent == null) {
            return null;
        }
        try {
            return agent.currentSessionId();
        } catch (RuntimeException e) {
            LOG.warn("[turn-lease] 解析 session_id 失败（本轮不加锁跑）: {}", e.getMessage());
            return null;
        }
    }

    /** 本次启动真能发出去的通道名集合（台账认领范围）。 */
    public Set<String> deliverablePlatforms() {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, Channel> e : byName.entrySet()) {
            if (e.getValue().isRunning()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private void record(ChannelMessage in, String reply) {
        Entry e = new Entry(System.currentTimeMillis(), in.channel, in.conversationId,
                in.senderId, in.text, reply);
        history.add(e);
        while (history.size() > historyLimit) {
            history.remove(0);
        }
    }

    /** 最近消息历史（最多 historyLimit 条）。 */
    public List<Entry> history() {
        return new java.util.ArrayList<Entry>(history);
    }

    /** 清除某会话的 agent 实例（pairing 解绑时调用）。 */
    public void evictConversation(String conversationId) {
        agents.remove(conversationId);
        graftedInto.remove(conversationId);
    }
}
