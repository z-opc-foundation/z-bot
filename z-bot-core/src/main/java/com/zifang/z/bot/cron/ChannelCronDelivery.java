package com.zifang.z.bot.cron;

import com.zifang.z.bot.channel.Channel;
import com.zifang.z.bot.channel.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * cron 结果的通道投递（对齐 hermes {@code scheduler.py:_deliver_result} 的路由段）。
 *
 * <p>路由串（{@link CronJob#deliver}）语法照她的契约（{@code scheduler.py:1225} 注释）：
 * {@code local} | {@code origin} | {@code <通道名>[:会话]} | {@code all}，逗号可组合。
 * 与她的差别只有两点，且都是有原因的：</p>
 * <ul>
 *   <li>通道名用我们自己的 {@link Channel#name()}，不是 hermes 的 platform 枚举
 *       —— 我们的通道 SPI 里只有 name，没有 platform 概念；</li>
 *   <li>不做她的 {@code :1564-1577} "platform 已配置但没启用" 判定：{@link Channels#find}
 *       返回 null 就是不可用，报 {@code unknown channel}。少一个状态也就少一处说谎。</li>
 * </ul>
 *
 * <p>跨代理边界：这里只<b>引用</b>既有的 {@link Channel} 接口（出站协议由通道自己实现），
 * 不 import 也不改 {@code Gateway} / {@code ChannelBus}（P16 的边界）。通道表怎么来由
 * {@link Channels} 注入，主编对接时把 bus 的按名查表递进来即可。</p>
 */
public final class ChannelCronDelivery implements CronDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelCronDelivery.class);

    /** 通道查询口（{@code ChannelBus.byName} 的只读等价面，由装配方提供）。 */
    public interface Channels {
        /** 按 {@link Channel#name()} 取通道；没有返回 null。 */
        Channel find(String name);

        /** 当前可用通道名（{@code all} 路由要展开它）。 */
        List<String> names();
    }

    private final Channels channels;
    private final CronDelivery local;
    private final boolean wrap;

    public ChannelCronDelivery(Channels channels) {
        this(channels, new LocalCronDelivery(), true);
    }

    /**
     * @param localFallback local 档与 origin 降级档的实现（测试注入捕获打印）
     * @param wrap          是否在正文外加任务名/job id 抬头（她的 {@code cron.wrap_response}）
     */
    public ChannelCronDelivery(Channels channels, CronDelivery localFallback, boolean wrap) {
        if (channels == null) {
            throw new IllegalArgumentException("channels 不能为空（否则没有任何通道可投）");
        }
        this.channels = channels;
        this.local = localFallback == null ? new LocalCronDelivery() : localFallback;
        this.wrap = wrap;
    }

    @Override
    public String deliver(CronJob job, String text) {
        String route = normalize(job.deliver);
        if (route.isEmpty() || "local".equals(route)) {
            return local.deliver(job, text);
        }

        List<Target> targets = new ArrayList<Target>();
        boolean originDegraded = false;
        for (String raw : route.split(",")) {
            String part = raw.trim().toLowerCase(Locale.ROOT);
            if (part.isEmpty()) {
                continue;
            }
            if ("local".equals(part)) {
                String err = local.deliver(job, text);
                if (err != null) {
                    return err;
                }
                continue;
            }
            if ("all".equals(part)) {
                // 她的 all：展开"当前真有一个已配置 home 目标"的平台（scheduler.py:1249）。
                // 我们的等价物 = 此刻注册着的通道；一个都没有就是没投递目标（下面照实报）。
                List<String> names = safeNames();
                if (names.isEmpty()) {
                    LOG.warn("[cron] {} deliver=all 但当前没有任何通道", job.id);
                }
                for (String n : names) {
                    addUnique(targets, new Target(n, defaultTarget(job)));
                }
                continue;
            }
            if ("origin".equals(part)) {
                Target t = resolveOrigin(job);
                if (t != null) {
                    addUnique(targets, t);
                    continue;
                }
                // #43014 的结论：CLI/工具建的任务从来没有会话来源可解析，
                // 这时降级 local 并记一行 info，而不是每次跑都冒一个 "no delivery target" 错误。
                LOG.info("[cron] {} deliver=origin 但没捕获到来源通道（也没有可回退的默认通道）"
                        + " —— 按 local 投递，结果同时记在 lastResult", job.id);
                originDegraded = true;
                continue;
            }
            int colon = part.indexOf(':');
            if (colon < 0) {
                // 她缺省段取该平台的 home chat_id；我们没有 home 配置，能兜的只有任务自己的 origin。
                String fallback = originConversation(job);
                if (fallback == null) {
                    String msg = "no target for deliver=" + part + "（通道 " + part
                            + " 可用，但这个任务没记录会话来源，请写 " + part + ":<会话id>）";
                    LOG.warn("[cron] {} {}", job.id, msg);
                    return msg;
                }
                addUnique(targets, new Target(part, fallback));
            } else {
                addUnique(targets, new Target(part.substring(0, colon), part.substring(colon + 1)));
            }
        }

        if (targets.isEmpty()) {
            if (originDegraded) {
                return local.deliver(job, text);
            }
            String msg = "no delivery target resolved for deliver=" + route;
            LOG.warn("[cron] {} {}", job.id, msg);
            return msg;
        }

        String body = wrap ? wrap(job, text) : (text == null ? "" : text);
        List<String> errors = new ArrayList<String>();
        for (Target t : targets) {
            Channel ch = channels.find(t.channel);
            if (ch == null) {
                String msg = "unknown channel '" + t.channel + "'";
                LOG.warn("[cron] {} {}", job.id, msg);
                errors.add(msg);
                continue;
            }
            try {
                ch.send(OutboundMessage.text(t.target, body));
                LOG.info("[cron] {} 结果已投递 → {}:{}", job.id, t.channel, t.target);
            } catch (Exception e) {
                String msg = "send to " + t.channel + " failed: " + e.getMessage();
                LOG.warn("[cron] {} {}", job.id, msg);
                errors.add(msg);
            }
        }
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String e : errors) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(e);
            }
            return sb.toString();
        }
        if (originDegraded) {
            local.deliver(job, text);
        }
        return null;
    }

    /** 来源能不能解析：只看 {@link CronJob#origin}（她的 job["origin"] 同样只在网关建任务时才有）。 */
    private static Target resolveOrigin(CronJob job) {
        String o = job == null ? null : trimToNull(job.origin);
        if (o == null) {
            return null;
        }
        String lower = o.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon <= 0 || colon == lower.length() - 1) {
            return null;   // 残缺形（":x" / "feishu:" / 无冒号）—— 当作没捕获到，按 #43014 降级
        }
        return new Target(lower.substring(0, colon), o.substring(colon + 1));
    }

    private static String originConversation(CronJob job) {
        Target t = resolveOrigin(job);
        return t == null ? null : t.target;
    }

    private String defaultTarget(CronJob job) {
        String conv = originConversation(job);
        return conv == null ? "cron" : conv;
    }

    private List<String> safeNames() {
        try {
            List<String> n = channels.names();
            return n == null ? new ArrayList<String>() : n;
        } catch (Exception e) {
            LOG.warn("[cron] 枚举通道名失败: {}", e.getMessage());
            return new ArrayList<String>();
        }
    }

    private static String wrap(CronJob job, String text) {
        return "[cron] " + job.name + " (" + job.id + ")\n" + (text == null ? "" : text);
    }

    private static void addUnique(List<Target> targets, Target t) {
        for (Target e : targets) {
            if (e.equals(t)) {
                return;
            }
        }
        targets.add(t);
    }

    private static String normalize(Object deliver) {
        if (deliver == null) {
            return "local";
        }
        String v = deliver.toString().trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? "local" : v;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** 一个投递目标 = 通道名 + 反向寻址用的会话 id（{@code OutboundMessage.replyTo}）。 */
    static final class Target {
        final String channel;
        final String target;

        Target(String channel, String target) {
            this.channel = channel;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Target)) {
                return false;
            }
            Target t = (Target) o;
            return channel.equals(t.channel) && target.equals(t.target);
        }

        @Override
        public int hashCode() {
            return channel.hashCode() * 31 + target.hashCode();
        }

        @Override
        public String toString() {
            return channel + ":" + target;
        }
    }

    /**
     * 建任务时先校验路由串的<b>语法</b>（不做通道存在性判定 —— 她的路由是"到点再解析"，
     * {@code scheduler.py:1242} 的注释写得很明白：现在没接的通道，接上那天就能投）。
     *
     * @return null = 合法；否则一句能直接回给用户的错
     */
    public static String validateRoute(String route) {
        String v = normalize(route);
        for (String raw : v.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                return "deliver 路由有空段: " + route;
            }
            int colon = part.indexOf(':');
            if (colon == 0 || colon == part.length() - 1) {
                return "deliver 路由缺通道名或会话 id: " + part;
            }
            if (colon > 0) {
                String name = part.substring(0, colon);
                if ("local".equals(name) || "origin".equals(name) || "all".equals(name)) {
                    return "deliver 的 local/origin/all 不带 :<会话>: " + part;
                }
            }
        }
        return null;
    }
}
