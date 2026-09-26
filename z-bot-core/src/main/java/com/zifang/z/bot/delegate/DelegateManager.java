package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.agent.AgentContext;
import com.zifang.z.agent.kernel.agent.IterationBudget;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * delegate_task 子代理树（对齐 hermes subagent）：
 * 把独立子任务交给一个上下文隔离的子 {@link BotAgent} 执行，子代理
 * 共享父的 provider / 沙箱，但拥有独立预算（kernel {@code childBudget(1/4)}）、
 * 独立会话目录，且默认不带 delegate_task 工具（靠注册深度防递归）。
 *
 * <p>深度语义：root 是 depth 0；工具只注册在 {@code depth < maxDepth} 的
 * agent 上，所以委托链最长 maxDepth 层。子代理自身的 builder 会以
 * {@code depth + 1} 走同一条装配路径，深度用尽后子代理工具列表里自然没有
 * delegate_task。</p>
 */
public final class DelegateManager {

    private static final Logger LOG = LoggerFactory.getLogger(DelegateManager.class);

    /** 子代理预算比例（Hermes: 子 agent 预算 ≤ 父的 1/4）。 */
    public static final double CHILD_BUDGET_FRACTION = 0.25;

    /**
     * 委托现场台账（P27，对齐 hermes {@code delegation_live_log.py}）：
     * 每条委托<b>建账即落盘</b>，所以委托方进程被打死之后现场仍读得回来。
     * 根目录由 {@link #ledgerRootFor(BotConfig, File)} 推导，{@code null} 时台账自动降级为 no-op。
     */
    private final DelegationLedger liveLedger;

    /** 投递账（P27，对齐 {@code async_delegation.py:84} 的 {@code _MAX_DELIVERY_ATTEMPTS = 8}）。 */
    private final DelegationDelivery delivery;

    /** 在飞子代理 → 现场 id 反查，让 {@code /stop} 能把"停在半路"落到台账上。 */
    private final java.util.Map<BotAgent, String> liveIdByChild =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<BotAgent, String>());

    /** 现场 id 计数（同步委托没有 async 台账那条 id，另起一把）。 */
    private final AtomicInteger liveSeq = new AtomicInteger();

    private final BotConfig config;
    private final LlmProvider provider;
    private final Sandbox sandbox;
    private final File childSessionDir;
    private final int depth;
    private final int maxDepth;
    private final AtomicInteger seq = new AtomicInteger();
    /** 父 agent 构造完成后由 BotAgent 构造器回填，取父预算裁子预算。 */
    private final AtomicReference<BotAgent> parent = new AtomicReference<BotAgent>();
    /** 最近一次创建的子代理（测试断言预算/工具剥离用，包级可见）。 */
    BotAgent lastChild;
    /**
     * 正在飞的子代理（P12）：{@code /stop} 落在父 agent 上时，父的中断旗子与子是两套，
     * 不显式叫停子的话，父已经"中止"了而子的循环还在继续烧预算、继续跑工具。
     */
    private final java.util.Set<BotAgent> inFlight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public DelegateManager(BotConfig config, LlmProvider provider, Sandbox sandbox,
                           File childSessionDir, int depth, int maxDepth) {
        this.config = config;
        this.provider = provider;
        this.sandbox = sandbox;
        this.childSessionDir = childSessionDir;
        this.depth = depth;
        this.maxDepth = maxDepth;
        this.liveLedger = new DelegationLedger(ledgerRootFor(config, childSessionDir));
        this.delivery = new DelegationDelivery(liveLedger);
    }

    /**
     * 现场台账根目录：<b>不需要改 {@code BotAgent} 也不需要新配置项</b>就能定址 ——
     * 装配点上 {@code childSessionDir} 本身就是 {@code <configDir>/delegate/children}
     * （{@code BotAgent.java:1852-1854}），取它的父目录再进 {@code live/} 就是
     * {@code <configDir>/delegate/live}（形状抄她的 {@code cache/delegation/live}）。
     *
     * <p>{@code config}/{@code childSessionDir} 都推不出目录时返回 {@code null}，
     * 台账整体降级成 no-op（{@link DelegationLedger#enabled()} 为 false），
     * 委托照跑 —— 台账是旁路，不许变成委托的前置条件。</p>
     */
    public static File ledgerRootFor(BotConfig config, File childSessionDir) {
        if (config != null && config.getConfigDir() != null) {
            return new File(config.getConfigDir(), "delegate/live");
        }
        if (childSessionDir != null && childSessionDir.getParentFile() != null) {
            return new File(childSessionDir.getParentFile(), "live");
        }
        return null;
    }

    /** 现场台账（测试/E2E 直接读盘用；不会返回 null，可能 {@code enabled()==false}）。 */
    public DelegationLedger liveLedger() {
        return liveLedger;
    }

    /** 投递账（上限 {@value DelegationDelivery#MAX_DELIVERY_ATTEMPTS} 的那一本）。 */
    public DelegationDelivery delivery() {
        return delivery;
    }

    /** 某条委托的投递判词，例 {@code PENDING(0/8)}；现场不在 ⇒ {@code MISSING(-/1)}。 */
    public String describeDelivery(String id) {
        return delivery.describe(id);
    }

    /** {@code /agents} 追加行：四态计数 + 用掉的最大尝试数。 */
    public String describeDeliveries() {
        return delivery.summary();
    }

    /**
     * 收尾扫描：先把死了的现场判成 {@link DelegateState#UNKNOWN}，再回收超期的<b>终态</b>现场。
     *
     * @return 人话判词 {@code adopted=N pruned=M}
     */
    public String sweepLiveLedger() {
        long now = System.currentTimeMillis();
        int adopted = liveLedger.adoptOrphans(DelegationLedger.ORPHAN_STALE_MILLIS, now);
        int pruned = liveLedger.pruneStale(DelegationLedger.LIVE_RETENTION_MILLIS, now);
        return "adopted=" + adopted + " pruned=" + pruned;
    }

    /**
     * 启动时扫一次的入口（写盘旁路，绝不抛）。台账定不出根目录时返回空串且一行都不打 ——
     * 测试桩里 {@code config == null} 就是这个形状，不许往 stdout/stderr 喷噪声。
     *
     * <p>接线点见 {@code _doc/acceptance/p27/WIRING.md} 第 1 条（在 {@code BotAgent} 构造器
     * {@code delegation.attach(this)} 之后；那支文件本棒没权改）。</p>
     */
    public String sweepAtStartup() {
        if (!liveLedger.enabled()) {
            return "";
        }
        String verdict = sweepLiveLedger();
        LOG.info("[delegate] 启动扫现场: {} (root={}, 保留期={} 天)",
                verdict, liveLedger.root(), DelegationLedger.LIVE_RETENTION_DAYS);
        return verdict;
    }

    /** BotAgent 构造完成后回填父引用（由 Builder.build 调用）。 */
    public void attach(BotAgent agent) {
        parent.set(agent);
    }

    /** delegate_task 工具；非 parallel-safe，注册方必须按串行批处理。 */
    public Tool delegateTool() {
        return Toolkit.of("delegate_task",
                "把独立子任务委托给隔离上下文的子代理执行（子代理有自己的预算与工具，"
                        + "完成后把最终回复返回给你；适合并行拆解调研/生成类工作）",
                new com.zifang.z.agent.kernel.tool.ToolSchemaBuilder()
                        .string("task", "交给子代理的完整任务描述（自包含，子代理看不到当前对话）")
                        .string("label", "子任务短标签（可选，方便 /agents 里识别）", false)
                        .build(),
                args -> {
                    String task = str(args, "task");
                    if (task.trim().isEmpty()) {
                        return ToolResult.error("参数错误：task 不能为空");
                    }
                    if (depth + 1 > maxDepth) {
                        return ToolResult.error("已达到委托深度上限 " + maxDepth + "，不能再委派子代理");
                    }
                    return runChild(task, str(args, "label"));
                });
    }

    /** 同步执行一次委托：建现场 → 构建子 agent → 跑完 → 返回其最终回复与用量。 */
    private ToolResult runChild(String task, String label) {
        // 委托子循环的两个检查点：入口（父已按 stop 就不该再把子代理拉起来）
        // 与收工（子跑完这段时间里用户按了 stop，就别再回灌结果给模型继续下一轮）。
        com.zifang.z.bot.agent.InterruptScope.checkpoint();
        DelegationLedger.Entry live = liveLedger.create(newLiveId("dlg"), task, depth, label, null);
        BotAgent child;
        try {
            child = buildChild(label, live);
        } catch (Exception e) {
            LOG.warn("[delegate] 子代理构建失败: {}", e.getMessage());
            advanceQuiet(live, DelegateEvent.TASK_FAILED, "build 失败: " + e.getMessage());
            return ToolResult.error("子代理构建失败: " + e.getMessage());
        }
        lastChild = child;
        // 先登记反查再入在飞集合：反过来的话 `stopChildren()` 有一个窗口看得见子代理
        // 却查不到它的现场 id ⇒ 停止判决一条都没落，随后子代理自己收工把盘上写成 DONE
        // （P27 实测 flake：60 连跑 2 次 "expected:<STOPPED> but was:<DONE>"）。
        liveIdByChild.put(child, live.id);
        inFlight.add(child);
        advanceQuiet(live, DelegateEvent.TASK_SPAWNED, "同步委托起跑");
        try {
            String reply = child.chat(task, StreamListener.NOOP);
            IterationBudget used = child.context().budget();
            String head = "（子代理" + (label.isEmpty() ? "" : "[" + label + "] ")
                    + "完成 steps=" + used.apiCalls() + " tokens=" + used.tokensUsed() + "）";
            live.reply = reply;
            advanceQuiet(live, DelegateEvent.TASK_COMPLETED,
                    "steps=" + used.apiCalls() + " tokens=" + used.tokensUsed());
            com.zifang.z.bot.agent.InterruptScope.checkpoint();
            return ToolResult.text(head + "\n" + reply);
        } catch (com.zifang.z.agent.kernel.agent.InterruptFlag.AgentInterruptedException e) {
            // 用户按了停止，不是子代理出了错：揉成 ToolResult.error 回灌给模型，
            // 等于告诉它「换个办法再试」，父 agent 就停不下来了。原样上抛交给 chat()。
            advanceQuiet(live, DelegateEvent.TASK_STOPPED, "父侧 /stop 上抛");
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("[delegate] 子代理执行失败: {}", e.getMessage());
            advanceQuiet(live, DelegateEvent.TASK_FAILED, e.getMessage());
            return ToolResult.error("子代理执行失败: " + e.getMessage());
        } finally {
            inFlight.remove(child);
            liveIdByChild.remove(child);
            child.shutdown();
        }
    }

    /**
     * 叫停所有在飞的子代理（父 {@code /stop} 时由 {@link BotAgent#stop()} 调）。
     *
     * <p>P27：除了叫停，还要把"停在半路"这件事落到现场台账上 —— 否则盘上留一条
     * 永远 {@code RUNNING} 的假现场，重启后没人说得清它是死了还是被停了。</p>
     *
     * @return 被叫停的子代理条数（0 = 没有在飞的）
     */
    public int stopChildren() {
        int n = 0;
        for (BotAgent child : inFlight) {
            String liveId = liveIdByChild.get(child);
            if (liveId != null) {
                advanceQuiet(liveLedger.load(liveId), DelegateEvent.TASK_STOPPED, "父 /stop");
            }
            child.stop();
            n++;
        }
        return n;
    }

    /** 当前在飞的子代理条数（测试对账用）。 */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * 装配子代理：共享 provider / 沙箱，独立会话目录 + 预算（父的 1/4），
     * 委托深度 + 1，且不接入 center（避免子代理重复注册生命周期）。
     */
    private BotAgent buildChild(String label) {
        return buildChild(label, null);
    }

    /**
     * @param live 现场记录（可 null）：装配点上顺手把子会话目录写进台账，
     *             并且把"父引用没回填 ⇒ 子代理拿的是 builder 默认预算而不是父的 1/4"
     *             这件事留在盘上（§0 实测：{@code :164} 的裁切包在 {@code if (p != null)} 里，
     *             是一条没有取证也没测试的旁路）。
     */
    private BotAgent buildChild(String label, DelegationLedger.Entry live) {
        File sessionDir = new File(childSessionDir,
                "d" + depth + "-" + seq.incrementAndGet() + (label.isEmpty() ? "" : "-" + slug(label)));
        if (live != null) {
            live.childSession = sessionDir.getAbsolutePath();
        }
        BotAgent.Builder b = BotAgent.builder(config)
                .provider(provider)
                .sandbox(sandbox)
                .sessionManager(new SessionManager(sessionDir))
                .delegateDepth(depth + 1)
                .withoutCenter();
        BotAgent p = parent.get();
        if (p != null) {
            b.budget(p.context().budget().childBudget(CHILD_BUDGET_FRACTION));
        } else {
            LOG.warn("[delegate] 父引用未回填（attach 还没跑），子代理预算未裁成 {}", CHILD_BUDGET_FRACTION);
            advanceQuiet(live, DelegateEvent.BUDGET_UNCLAMPED,
                    "parent==null ⇒ 未裁预算，fraction=" + CHILD_BUDGET_FRACTION);
        }
        return b.build();
    }

    /** 新现场 id：形状跟她 {@code new_live_delegation_id()} 一样是"前缀 + 短随机"，但要可排序。 */
    private String newLiveId(String prefix) {
        return prefix + System.currentTimeMillis() + "-" + liveSeq.incrementAndGet();
    }

    /**
     * 落一条生命周期事件，<b>只 swallow 一种</b>异常：已经终态之后的第二次判决
     * （例如用户 {@code /stop} 与子代理自己抛异常同时发生）。
     *
     * <p>谁先落终态谁说了算 —— 台账不许被后到的事件改写；但状态机的"非法迁移大声失败"
     * 这条规矩在 {@link DelegateTransitions} 那一层原样保留，这里只是不拿它去炸线程池线程。
     * 其它异常（写盘失败）由 {@link DelegationLedger} 自己降级，不会冒到这里。</p>
     */
    private void advanceQuiet(DelegationLedger.Entry live, DelegateEvent event, String detail) {
        if (live == null) {
            return;
        }
        try {
            liveLedger.advance(live, event, detail);
        } catch (DelegateTransitions.IllegalTransitionException already) {
            LOG.debug("[delegate] 现场 {} 已是 {}，拒绝被 {} 改写: {}",
                    live.id, live.state, event.wireName(), already.getMessage());
        }
    }

    /** 异步任务台账（/background 与 /agents 共用）。 */
    private final Map<String, AsyncDelegation> async = new LinkedHashMap<String, AsyncDelegation>();

    /**
     * 异步委托：投进线程池立刻返回 id；完成后结果留在台账里，
     * {@code /agents} 查看、{@code /background result <id>} 取回。
     */
    public String submitAsync(String task) {
        if (depth + 1 > maxDepth) {
            return "已达到委托深度上限 " + maxDepth + "，不能再委派子代理";
        }
        BotAgent p = parent.get();
        int width = config == null ? 3 : config.getDelegateMaxChildren();
        synchronized (this) {
            // P27：闸门前身只数 RUNNING —— 连发的时候条目还都停在 QUEUED，
            // width=3 的口子实际能塞进任意多条（§9 产品缺陷 P27-D2，附复算用例）。
            long flying = async.values().stream()
                    .filter(d -> isFlying(d.status))
                    .count();
            if (flying >= width) {
                return "异步委托并发已满（" + flying + "/" + width + "），稍后再试或先 /agents 查看";
            }
        }
        String id = "bg" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        AsyncDelegation d = new AsyncDelegation(id, task);
        synchronized (this) {
            async.put(id, d);
        }
        // P27：建账先于执行 —— 现场目录 + 首条 state.json 现在就落盘（抄她
        // "pre-created with a header at dispatch time"）。委托方进程之后被打死，
        // 盘上也还留着一条 QUEUED 现场可判词。
        final DelegationLedger.Entry live = liveLedger.create(id, task, depth, "bg", null);
        StateStore store = store();
        if (store != null) {
            store.upsertDelegation(id, task, "QUEUED", "");
        }
        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                d.status = "RUNNING";
                advanceQuiet(live, DelegateEvent.TASK_SPAWNED, "异步委托起跑");
                StateStore s = store();
                if (s != null) {
                    s.upsertDelegation(id, task, "RUNNING", "");
                }
                BotAgent child = null;
                try {
                    child = buildChild("bg", live);
                    lastChild = child;
                    liveIdByChild.put(child, id);   // 同上：反查必须先于在飞登记
                    inFlight.add(child);
                    String reply = child.chat(task, StreamListener.NOOP);
                    d.reply = reply;
                    d.status = "DONE";
                    live.reply = reply;
                    // ★ 只推进生命周期轴：DONE 说的是"子代理自己收工了"，
                    //   投递轴这一格必须留在 PENDING —— 有人 claim + ack 才算送达。
                    //   修之前这里等于替消费者把 "ok" 一起写了（P16 同型洞）。
                    advanceQuiet(live, DelegateEvent.TASK_COMPLETED, "异步收工");
                } catch (Exception e) {
                    d.reply = "执行失败: " + e.getMessage();
                    d.status = "FAILED";
                    live.error = String.valueOf(e.getMessage());
                    advanceQuiet(live, DelegateEvent.TASK_FAILED, e.getClass().getSimpleName());
                } finally {
                    if (child != null) {
                        inFlight.remove(child);
                        liveIdByChild.remove(child);
                    }
                }
                if (s != null) {
                    s.upsertDelegation(id, task, d.status, d.reply);
                }
            }
        });
        return "已提交异步委托 " + id + "（/agents 查看进度，/background result " + id + " 取回结果）";
    }

    /**
     * 一条异步委托是否还占着并发槽位。<b>QUEUED 也算</b>：闸门如果只数 {@code RUNNING}，
     * 连发的时候条目还都停在 QUEUED，width=3 的口子实际能塞进任意多条（§9 产品缺陷 P27-D2）。
     *
     * <p>今天 {@link AsyncDelegation#status} 只会出现 QUEUED/RUNNING/DONE/FAILED 四种值
     * （写点：初值、线程池里起跑那一行、收工与失败两处判决；实测 grep 无第五种）。
     * 抽成单独一处是为了让
     * "摘掉 QUEUED 也算在飞"这一支能被<b>确定性</b>抓到 —— 连发那条用例靠时序，量不到它。</p>
     */
    static boolean isFlying(String status) {
        return !"DONE".equals(status) && !"FAILED".equals(status);
    }

    /** {@code /agents} — 台账快照。 */
    public synchronized String describeAsync() {
        if (async.isEmpty()) {
            return "暂无异步委托（/background <task> 提交）";
        }
        StringBuilder sb = new StringBuilder("异步委托 (" + async.size() + ")\n");
        for (AsyncDelegation d : async.values()) {
            sb.append("  ").append(d.id).append("  ").append(d.status)
                    .append("  投递=").append(delivery.describe(d.id))
                    .append("  ").append(d.task.length() > 40 ? d.task.substring(0, 40) + "…" : d.task)
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code /background result <id>} — 取回已完成委托的回复（这一次拉取本身就是一次投递）。 */
    public synchronized String asyncResult(String id) {
        String key = id == null ? "" : id.trim();
        AsyncDelegation d = async.get(key);
        if (d == null) {
            return "未知委托 id: " + id;
        }
        if (!"DONE".equals(d.status) && !"FAILED".equals(d.status)) {
            // 还没收工就谈不上投递：看进度不该烧尝试数。
            return d.id + " 还在 " + d.status + "，稍后再取";
        }
        String token = delivery.claim(key, "pull-console");
        String tail;
        if (token == null) {
            DeliveryState st = delivery.stateOf(key);
            tail = st == DeliveryState.DELIVERED
                    ? "\n（投递：此前已确认接住 " + delivery.describe(key) + "）"
                    : "\n（投递：" + delivery.describe(key) + " ⇒ 账上不记成功）";
        } else if (delivery.complete(key, token)) {
            tail = "\n（投递：DELIVERED " + delivery.describe(key) + "）";
        } else {
            tail = "\n（投递：ack 未生效 " + delivery.describe(key) + "）";
        }
        return "[" + d.status + "] " + d.task + "\n" + d.reply + tail;
    }

    // ===== 内部 =====

    /** 父 agent 会话库（state.db 模式下异步委托落库；JSON 模式返回 null 只留内存台账）。 */
    private StateStore store() {
        BotAgent p = parent.get();
        SessionManager sm = p == null ? null : p.getSessionManager();
        return sm == null ? null : sm.getStore();
    }

    private static final java.util.concurrent.ExecutorService ASYNC_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "zbot-delegate-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }

    private static String slug(String label) {
        return label.replaceAll("[^0-9A-Za-z_-]", "-");
    }

    /** 一条异步委托记录。 */
    static final class AsyncDelegation {
        final String id;
        final String task;
        volatile String status = "QUEUED";
        volatile String reply = "";

        AsyncDelegation(String id, String task) {
            this.id = id;
            this.task = task;
        }
    }
}
