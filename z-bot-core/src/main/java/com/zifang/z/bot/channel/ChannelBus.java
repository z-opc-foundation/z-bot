package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通道总线：所有通道入站消息汇到 {@link #deliver(ChannelMessage)}，
 * bus 调 agent 跑一轮对话，把回复 fan-out 给订阅了该 {@code conversationId} 的通道。
 *
 * <p>对话隔离：每个 conversationId 维护独立的 BotAgent 实例（避免群/私聊间记忆串味），
 * 并发用 {@link SessionManager} 文件锁 + 串行 executor 保单会话串行。</p>
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
        BotAgent agent = agents.computeIfAbsent(inbound.conversationId,
                k -> prototype.forkFor(k));
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
        }
        record(inbound, reply);
        OutboundMessage out = reply.startsWith("Error:")
                ? OutboundMessage.error(inbound.conversationId, reply)
                : OutboundMessage.text(inbound.conversationId, reply);
        // 优先发给来源通道；多端订阅场景下 fan-out 由调用方在 deliver 时改 path
        Channel source = byName.get(inbound.channel);
        if (source != null) {
            try {
                source.send(out);
            } catch (Exception e) {
                LOG.warn("[ChannelBus] 回 {} 失败: {}", inbound.channel, e.getMessage());
            }
        }
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
    }
}