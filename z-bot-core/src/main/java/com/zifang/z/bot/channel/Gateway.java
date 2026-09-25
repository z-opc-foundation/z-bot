package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * z-bot gateway：把多个通道注册到 {@link ChannelBus}，常驻进程管理生命周期。
 *
 * <p>对外暴露：</p>
 * <ul>
 *   <li>{@link #register(Channel)} — 注册通道（HTTP/webhook/IM）</li>
 *   <li>{@link #start()} / {@link #stop()} / {@link #awaitTermination()}</li>
 *   <li>{@link #status()} — 健康摘要（运行中的通道数、bus 历史最近 N 条、pairing 待用码数）</li>
 * </ul>
 *
 * <p>无第三方线程池/无 Spring；通道自己持有 executor / socket，gateway 只调度 start / stop 顺序。</p>
 */
public final class Gateway {

    private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

    private final BotAgent prototype;
    private final ChannelBus bus;
    private final PairingService pairing;
    private final List<Channel> channels = new ArrayList<Channel>();
    private volatile boolean running;

    public Gateway(BotAgent prototype) {
        this(prototype, null);
    }

    public Gateway(BotAgent prototype, PairingService pairing) {
        this.prototype = prototype;
        this.pairing = pairing;
        this.bus = new ChannelBus(prototype);
        this.bus.start();
    }

    public ChannelBus bus() {
        return bus;
    }

    public PairingService pairing() {
        return pairing;
    }

    public synchronized void register(Channel channel) {
        channels.add(channel);
        bus.register(channel);
    }

    public List<Channel> channels() {
        return new ArrayList<Channel>(channels);
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        for (Channel c : channels) {
            try {
                c.start();
            } catch (Exception e) {
                LOG.warn("[gateway] 启动通道 {} 失败: {}", c.name(), e.getMessage());
                throw e;
            }
        }
        running = true;
        LOG.info("[gateway] 启动 {} 个通道: {}", channels.size(), names());
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        bus.shutdown();
        for (Channel c : channels) {
            try {
                c.stop();
            } catch (Exception e) {
                LOG.warn("[gateway] 停通道 {} 失败: {}", c.name(), e.getMessage());
            }
        }
        if (prototype != null) {
            try {
                prototype.shutdown();
            } catch (Exception e) {
                LOG.warn("[gateway] 停原型 agent 失败: {}", e.getMessage());
            }
        }
        running = false;
    }

    public void awaitTermination() {
        for (Channel c : channels) {
            c.awaitTermination();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** 健康摘要：哪些通道在线 + bus 最近若干条消息 + pairing 待用码数。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> chans = new ArrayList<Map<String, Object>>();
        for (Channel c : channels) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", c.name());
            m.put("running", c.isRunning());
            chans.add(m);
        }
        out.put("running", running);
        out.put("channels", chans);
        out.put("recentMessages", bus.history().size());
        if (pairing != null) {
            out.put("activePairings", pairing.list().size());
        }
        return out;
    }

    private String names() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(channels.get(i).name());
        }
        return sb.toString();
    }
}