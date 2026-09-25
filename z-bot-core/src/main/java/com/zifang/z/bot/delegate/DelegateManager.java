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

    /** 同步执行一次委托：构建子 agent → 跑完 → 返回其最终回复与用量。 */
    private ToolResult runChild(String task, String label) {
        // 委托子循环的两个检查点：入口（父已按 stop 就不该再把子代理拉起来）
        // 与收工（子跑完这段时间里用户按了 stop，就别再回灌结果给模型继续下一轮）。
        com.zifang.z.bot.agent.InterruptScope.checkpoint();
        BotAgent child;
        try {
            child = buildChild(label);
        } catch (Exception e) {
            LOG.warn("[delegate] 子代理构建失败: {}", e.getMessage());
            return ToolResult.error("子代理构建失败: " + e.getMessage());
        }
        lastChild = child;
        inFlight.add(child);
        try {
            String reply = child.chat(task, StreamListener.NOOP);
            IterationBudget used = child.context().budget();
            String head = "（子代理" + (label.isEmpty() ? "" : "[" + label + "] ")
                    + "完成 steps=" + used.apiCalls() + " tokens=" + used.tokensUsed() + "）";
            com.zifang.z.bot.agent.InterruptScope.checkpoint();
            return ToolResult.text(head + "\n" + reply);
        } catch (com.zifang.z.agent.kernel.agent.InterruptFlag.AgentInterruptedException e) {
            // 用户按了停止，不是子代理出了错：揉成 ToolResult.error 回灌给模型，
            // 等于告诉它「换个办法再试」，父 agent 就停不下来了。原样上抛交给 chat()。
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("[delegate] 子代理执行失败: {}", e.getMessage());
            return ToolResult.error("子代理执行失败: " + e.getMessage());
        } finally {
            inFlight.remove(child);
            child.shutdown();
        }
    }

    /**
     * 叫停所有在飞的子代理（父 {@code /stop} 时由 {@link BotAgent#stop()} 调）。
     *
     * @return 被叫停的子代理条数（0 = 没有在飞的）
     */
    public int stopChildren() {
        int n = 0;
        for (BotAgent child : inFlight) {
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
        File sessionDir = new File(childSessionDir,
                "d" + depth + "-" + seq.incrementAndGet() + (label.isEmpty() ? "" : "-" + slug(label)));
        BotAgent.Builder b = BotAgent.builder(config)
                .provider(provider)
                .sandbox(sandbox)
                .sessionManager(new SessionManager(sessionDir))
                .delegateDepth(depth + 1)
                .withoutCenter();
        BotAgent p = parent.get();
        if (p != null) {
            b.budget(p.context().budget().childBudget(CHILD_BUDGET_FRACTION));
        }
        return b.build();
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
            long running = async.values().stream().filter(d -> "RUNNING".equals(d.status)).count();
            if (running >= width) {
                return "异步委托并发已满（" + running + "/" + width + "），稍后再试或先 /agents 查看";
            }
        }
        String id = "bg" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        AsyncDelegation d = new AsyncDelegation(id, task);
        synchronized (this) {
            async.put(id, d);
        }
        StateStore store = store();
        if (store != null) {
            store.upsertDelegation(id, task, "QUEUED", "");
        }
        ASYNC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                d.status = "RUNNING";
                StateStore s = store();
                if (s != null) {
                    s.upsertDelegation(id, task, "RUNNING", "");
                }
                try {
                    BotAgent child = buildChild("bg");
                    lastChild = child;
                    inFlight.add(child);
                    try {
                        String reply = child.chat(task, StreamListener.NOOP);
                        d.reply = reply;
                        d.status = "DONE";
                    } finally {
                        inFlight.remove(child);
                    }
                } catch (Exception e) {
                    d.reply = "执行失败: " + e.getMessage();
                    d.status = "FAILED";
                }
                if (s != null) {
                    s.upsertDelegation(id, task, d.status, d.reply);
                }
            }
        });
        return "已提交异步委托 " + id + "（/agents 查看进度，/background result " + id + " 取回结果）";
    }

    /** {@code /agents} — 台账快照。 */
    public synchronized String describeAsync() {
        if (async.isEmpty()) {
            return "暂无异步委托（/background <task> 提交）";
        }
        StringBuilder sb = new StringBuilder("异步委托 (" + async.size() + ")\n");
        for (AsyncDelegation d : async.values()) {
            sb.append("  ").append(d.id).append("  ").append(d.status)
                    .append("  ").append(d.task.length() > 40 ? d.task.substring(0, 40) + "…" : d.task)
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code /background result <id>} — 取回已完成委托的回复。 */
    public synchronized String asyncResult(String id) {
        AsyncDelegation d = async.get(id == null ? "" : id.trim());
        if (d == null) {
            return "未知委托 id: " + id;
        }
        if (!"DONE".equals(d.status) && !"FAILED".equals(d.status)) {
            return d.id + " 还在 " + d.status + "，稍后再取";
        }
        return "[" + d.status + "] " + d.task + "\n" + d.reply;
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
