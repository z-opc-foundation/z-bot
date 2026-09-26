package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * z-bot gateway：把多个通道注册到 {@link ChannelBus}，常驻进程管理生命周期。
 *
 * <p>对外暴露：</p>
 * <ul>
 *   <li>{@link #register(Channel)} — 注册通道（HTTP/webhook/IM）</li>
 *   <li>{@link #start()} / {@link #stop()} / {@link #awaitTermination()}</li>
 *   <li>{@link #status()} — 健康摘要（运行中的通道数、bus 历史最近 N 条、pairing 待用码数、
 *       P16 三件套的收敛读数）</li>
 * </ul>
 *
 * <p>P16 起，网关启动时多做三件事（都在 {@link #start()} 里，顺序有讲究）：</p>
 * <ol>
 *   <li><b>陈旧实例锁自愈</b>（{@link Supervisor#tryAcquireInstanceLock}）：上一次崩了留下的
 *       {@code <configDir>/gateway/gateway.lock} 若归属进程已死则抢过来；活着则拒绝再起一个
 *       —— 两个网关同时 sweep 同一份台账会把重投预算烧双份。</li>
 *   <li><b>通道启动</b>（原有语义不变）。</li>
 *   <li><b>断点重投</b>（{@link DeliveryLedger#sweepRecoverable}）：把归属进程已死的未投递义务
 *       认领回来重发；{@code attempting}/{@code failed} 两类歧义行带
 *       {@link DeliveryLedger#RECOVERED_MARKER} 前缀。带着待续跑的活启动会向
 *       {@link Supervisor#checkAndRecordInterruptedBoot()} 记账，60 秒内第 3 次即<b>跳过</b>自动续跑
 *       —— 反复弄死网关的那条逻辑不能把进程拖进紧密重启动循环。</li>
 * </ol>
 *
 * <p>三件套的数据目录全部由 profile 派生（红线 1）：{@code BotConfig.getConfigDir()} 给不出目录时
 * 整个自愈层保持空操作，绝不回落到写死的 {@code ~/.zbot}。</p>
 *
 * <p>无第三方线程池/无 Spring；通道自己持有 executor / socket，gateway 只调度 start / stop 顺序。</p>
 */
public final class Gateway {

    private static final Logger LOG = LoggerFactory.getLogger(Gateway.class);

    private final BotAgent prototype;
    private final ChannelBus bus;
    private final PairingService pairing;
    private final List<Channel> channels = new ArrayList<Channel>();
    private final Supervisor supervisor;
    private final File configDir;
    private volatile Supervisor.InstanceLock instanceLock;
    private volatile boolean running;

    public Gateway(BotAgent prototype) {
        this(prototype, null);
    }

    public Gateway(BotAgent prototype, PairingService pairing) {
        this.prototype = prototype;
        this.pairing = pairing;
        this.bus = new ChannelBus(prototype);
        this.configDir = profileConfigDir(prototype);
        this.supervisor = configDir == null ? null : new Supervisor(configDir);
        attachSelfHealing();
        this.bus.start();
    }

    public ChannelBus bus() {
        return bus;
    }

    public PairingService pairing() {
        return pairing;
    }

    /** 自愈监督器；无 profile 时是 {@code null}（进程内纯 bus 用法）。 */
    public Supervisor supervisor() {
        return supervisor;
    }

    /** 本实例持有的网关锁（{@code null} = 未启动或无 profile）。 */
    public Supervisor.InstanceLock instanceLock() {
        return instanceLock;
    }

    /** 台账数据文件位置（诊断 / 验收脚本核对用）。 */
    public File stateDbFile() {
        return ledgerFile();
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
        // 1) 陈旧锁自愈：上一进程没干净收尾时，锁文件里的 pid + 启动时刻说了算
        if (supervisor != null) {
            instanceLock = supervisor.tryAcquireInstanceLock();
        }
        // 2) 通道启动（原有语义）
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
        // 3) 断点重投（必须在通道起来之后，否则认领了也发不出去）
        recoverPendingDeliveries();
    }

    /**
     * 启动清扫 + 重投。返回本次真正重投出去的条数；{@code -1} 表示被熔断器挡下（跳过自动续跑）。
     *
     * <p>只有<b>真认领到活</b>才向熔断器记账 —— 一次干净启动不许给断路器攒账。</p>
     */
    synchronized int recoverPendingDeliveries() {
        DeliveryLedger ledger = bus.deliveryLedger();
        if (ledger == null) {
            return 0;
        }
        List<DeliveryLedger.Claimed> claimed;
        try {
            Set<String> platforms = bus.deliverablePlatforms();
            claimed = ledger.sweepRecoverable(platforms);
        } catch (RuntimeException e) {
            LOG.warn("[gateway] 台账清扫异常（跳过续跑，网关照常服务）: {}", e.getMessage());
            return 0;
        }
        if (claimed.isEmpty()) {
            return 0;
        }
        if (supervisor != null && supervisor.checkAndRecordInterruptedBoot()) {
            LOG.warn("[gateway] {} 条待续跑义务先搁置 —— 熔断已触发，见 Supervisor 的告警", claimed.size());
            return -1;
        }
        int sent = 0;
        for (DeliveryLedger.Claimed c : claimed) {
            if (redeliver(ledger, c)) {
                sent++;
            }
        }
        LOG.info("[gateway] 断点重投：认领 {} 条，发出 {} 条（其余原地转 failed 等下次）",
                claimed.size(), sent);
        return sent;
    }

    /** 重投一条义务；成功结清、失败标 failed（下次启动还认得回来）。 */
    private boolean redeliver(DeliveryLedger ledger, DeliveryLedger.Claimed c) {
        Channel target = null;
        for (Channel ch : channels) {
            if (ch.name().equals(c.platform)) {
                target = ch;
                break;
            }
        }
        if (target == null) {
            LOG.warn("[gateway] 义务 {} 的平台 {} 本次没起来 —— 留在台账里", c.obligationId, c.platform);
            return false;
        }
        try {
            ledger.markAttempting(c.obligationId);
            target.send(OutboundMessage.text(c.chatId, c.contentForDelivery()));
            ledger.markDelivered(c.obligationId);
            DeadTargets dead = bus.deadTargets();
            if (dead != null) {
                dead.clear(c.platform, c.chatId);
            }
            LOG.info("[gateway] 重投 {} → {}:{}（带标记={}）", c.obligationId, c.platform, c.chatId,
                    c.needsMarker());
            return true;
        } catch (Exception e) {
            String kind = DeadTargets.classifySendError(e, null);
            LOG.warn("[gateway] 重投 {} 失败（{}）: {}", c.obligationId, kind, e.getMessage());
            if (DeadTargets.isDeadErrorKind(kind)) {
                DeadTargets dead = bus.deadTargets();
                if (dead != null) {
                    dead.markDead(c.platform, c.chatId, kind + ": " + e.getMessage());
                }
            }
            try {
                ledger.markFailed(c.obligationId, "recovered-redelivery: " + e.getMessage());
            } catch (RuntimeException ignored) {
                // 台账故障不外溢
            }
            return false;
        }
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
        if (supervisor != null) {
            supervisor.shutdown();
            // 干净收尾 ⇒ 熔断账本清零，下次带活启动不该背着这次的债
            supervisor.clearInterruptedBoots();
        }
        if (instanceLock != null) {
            instanceLock.release();
            instanceLock = null;
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

    /** 健康摘要：哪些通道在线 + bus 最近若干条消息 + pairing 待用码数 + 送达三件套读数。 */
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
        DeliveryLedger ledger = bus.deliveryLedger();
        if (ledger != null) {
            out.put("deliveryObligations", ledger.countsByState());
            out.put("deliveryRows", Integer.valueOf(ledger.totalRows()));
        }
        DeadTargets dead = bus.deadTargets();
        if (dead != null) {
            out.put("deadTargets", Integer.valueOf(dead.size()));
        }
        out.put("turnLeases", Integer.valueOf(bus.turnLeases().size()));
        out.put("leaseFailOpen", Long.valueOf(bus.leaseTimeoutCount()));
        if (instanceLock != null) {
            out.put("instanceLock", instanceLock.diagnostic());
        }
        return out;
    }

    // ===== 自愈层接线（全部由 profile 派生；任一环故障 ⇒ 退化成无自愈） =====

    private void attachSelfHealing() {
        File db = ledgerFile();
        if (db == null) {
            LOG.info("[gateway] 无 profile 上下文 —— 送达台账/死目标登记表不启用（不会写死 ~/.zbot）");
            return;
        }
        try {
            bus.setDeliveryLedger(new DeliveryLedger(db, txConfig()));
        } catch (RuntimeException e) {
            LOG.warn("[gateway] 送达台账建不起来（{}）—— 本次不带落账投递，网关照常服务", e.getMessage());
        }
        try {
            bus.setDeadTargets(DeadTargets.forConfigDir(configDir));
        } catch (RuntimeException e) {
            LOG.warn("[gateway] 死目标登记表建不起来（{}）—— 按纯内存处理", e.getMessage());
        }
    }

    private static File profileConfigDir(BotAgent agent) {
        if (agent == null) {
            return null;
        }
        BotConfig cfg = agent.getConfig();
        // 只在配置真给了目录时才派生数据目录；缺省档一律不用 defaultConfigDir()，
        // 否则进程内测试会往真实 ~/.zbot 写东西（红线 1 的反面）。
        return cfg == null ? null : cfg.getConfigDir();
    }

    private File ledgerFile() {
        if (configDir == null) {
            return null;
        }
        BotConfig cfg = prototype == null ? null : prototype.getConfig();
        String explicit = cfg == null ? null : cfg.getStateDbPath();
        if (explicit != null && !explicit.trim().isEmpty()) {
            return new File(explicit.trim());
        }
        return new File(configDir, "state.db");
    }

    private StateStore.Options txConfig() {
        BotConfig cfg = prototype == null ? null : prototype.getConfig();
        return cfg == null ? StateStore.Options.defaults() : cfg.stateStoreOptions();
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
