package com.zifang.z.bot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.agent.AgentContext;
import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.agent.InterruptFlag;
import com.zifang.z.agent.kernel.agent.IterationBudget;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.BuildInfo;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.center.BotLifecycle;
import com.zifang.z.bot.checkpoint.CheckpointManager;
import com.zifang.z.bot.context.CompressionLedger;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.cron.CronJob;
import com.zifang.z.bot.cron.CronScheduler;
import com.zifang.z.bot.cron.CronTools;
import com.zifang.z.bot.delegate.DelegateManager;
import com.zifang.z.bot.memory.MemoryStore;
import com.zifang.z.bot.mcp.McpManager;
import com.zifang.z.bot.memory.MemoryTools;
import com.zifang.z.bot.skill.SkillCommands;
import com.zifang.z.bot.skill.SkillGuard;
import com.zifang.z.bot.skill.SkillLoader;
import com.zifang.z.bot.skill.SkillSync;
import com.zifang.z.bot.llm.KeyPoolLlmProvider;
import com.zifang.z.bot.llm.LlmRouter;
import com.zifang.z.bot.llm.ResilientLlmProvider;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.ApprovalService;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * z-bot 的 ReAct 运行时 — 直接跑在 z-agent-kernel 的 SPI 上。
 *
 * <p>与老 z-agent-bot 的 {@code ZAgent + engine ReActAgent} 两层结构不同：这里只有一个类，
 * LLM 走 kernel {@link LlmProvider}，消息走 kernel {@link Msg}，工具走 kernel {@link com.zifang.z.agent.kernel.tool.Tool}，
 * 事件流是自己定义的 {@link StreamEvent}（不引 Reactor，调用线程同步派发）。</p>
 *
 * <p>单步语义：一次 {@code provider.chat} → 有 tool_calls 就逐个执行并把结果回灌记忆继续下一轮；
 * 纯文本即最终答案。弱模型把工具调用写成文本时，{@link #parseTextToolCall} 兜底解析。
 * 需要人工确认的工具抛 {@link ToolConfirmationNeeded}，由 {@link #chat} 转成
 * {@code WAIT_CONFIRM:tool|args|reason} 串，等 {@link #confirmTool} 恢复。</p>
 */
public class BotAgent {

    private static final Logger LOG = LoggerFactory.getLogger(BotAgent.class);

    /** 上报版本串：面值只在 {@link BuildInfo#REVISION} 一处，这里跟着 pom 的 {@code <revision>} 走。 */
    public static final String BOT_VERSION = BuildInfo.BOT_VERSION;

    /** 需要人工确认时 {@link #chat} 的返回值前缀，后接 {@code tool|args|reason}。 */
    public static final String WAIT_CONFIRM_PREFIX = "WAIT_CONFIRM:";

    /**
     * 动态上下文的抬头（P12 prompt 缓存不变量）：记忆 / 技能 / center 召回 / 时钟
     * 全部走 user 消息，标记必须显式，否则模型会把它当成用户原话的一部分。
     *
     * <p>P12e：这个标记同时是「不许落盘、不许进历史」的判据 —— 它只出现在<b>本次请求</b>
     * 的最后一行 user 上（见 {@link #injectVolatileContext(List)}），
     * {@code memory} / transcript / 任何渲染面都不该看到它。</p>
     */
    static final String VOLATILE_CONTEXT_HEADER = "[z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）";

    /** user 消息里动态上下文与用户原话的分隔。 */
    static final String VOLATILE_CONTEXT_FOOTER = "\n---\n";

    /** 时钟格式：带时区偏移，模型据此判断「今天」「上周」。 */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx zzz", Locale.ROOT);

    /** 模型用文本描述工具调用时的兜底解析（弱模型不吐 tool_calls 数组）。 */
    private static final Pattern TEXT_TOOL_CALL = Pattern.compile(
            "functions\\.(\\w+)\\(\\s*(\\{.*?\\})\\s*\\)", Pattern.DOTALL);
    private static final Pattern TEXT_TOOL_CALL_VERB = Pattern.compile(
            "(?:use|call|execute|run)\\s+(?:the\\s+)?(\\w+)\\s+(?:tool\\s+)?(?:with\\s+)?(?:args\\s+)?(\\{.*?\\})",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BotConfig config;
    private final LlmProvider provider;
    /** P26：本次任务累计的 cache 读命中（provider 报了多少就记多少，无 cache 字段 ⇒ 0）。 */
    private final java.util.concurrent.atomic.AtomicLong CACHE_READ = new java.util.concurrent.atomic.AtomicLong();
    /** P26：本次任务累计的 cache 写命中。 */
    private final java.util.concurrent.atomic.AtomicLong CACHE_WRITE = new java.util.concurrent.atomic.AtomicLong();
    private final String providerCode;
    private final String model;
    private final Toolkit toolkit;
    private final Sandbox sandbox;
    private final ConversationMemory memory;
    private final SessionManager sessionManager;
    private final BotCenterClient centerClient;
    private final BotLifecycle lifecycle;
    private final int maxSteps;
    private final int maxTokens;
    private final double temperature;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * kernel 0.2.1（根 pom 的 {@code z-agent-kernel.version} 面值）的显式运行状态：
     * 预算 / 中断 / steer 队列都在这里。
     * 中断是协作式的 — 迭代与工具边界经 {@code checkpoint()} 生效，
     * 工具内部（exec 读输出循环 / 文件读写 / mvn_build / delegate 子循环）经
     * {@link InterruptScope} 按<b>执行线程</b>找回本次会话的旗子后同样生效。
     */
    private final AgentContext context;
    /** 预算账本：在 kernel 累计口径之上补「压缩后退还」，主循环的「还能不能再打一次」由它判。 */
    private final BudgetLedger budgetLedger;
    /** 被 {@link ToolConfirmationNeeded} 暂停的那次调用；确认后要用它回灌 tool 结果。 */
    private volatile ToolCall pendingConfirmation;
    /**
     * 本轮（{@code chat()} 入口处算好的）动态上下文块。P12e：它是<b>请求侧</b>的东西，
     * 不进 {@code memory}、不进 transcript；同一轮的每一步复用同一份字节，
     * 下一轮 {@code chat()} 重算。空串 = 本轮还没开（例如纯桩直接戳 {@code buildRequest}）。
     */
    private volatile String turnVolatileBlock = "";
    /**
     * 每会话审批 FIFO（P11）。为 null 时退化为旧的"单槽 + 只有确认/不确认"行为
     * （纯测试桩、不带内置工具的 agent）。
     */
    private final ApprovalService approvals;
    /** 最近一次请求的消息字符量（网关不回 usage 时给压缩引擎做上下文估算）。 */
    private volatile long lastRequestChars;
    /** 上下文压缩引擎（null = 未启用，如纯测试桩）；摘要走主 provider。 */
    private final CompressorEngine compressor;
    private final ContextEngine.Summarizer summarizer;
    /**
     * 压缩的落盘侧（P14）：{@code compression_locks} 抢锁、失败冷却入库、防抖计数、
     * 血统分叉。null = 还没有可用的 store（纯测试桩），此时引擎退化为内存锁。
     */
    private volatile CompressionLedger compressionLedger;
    /** 分叉时派到的 {@code base #N} 标题：{@code SessionManager.saveMessages} 会用内存标题盖库，每次落盘后按这张表回填。 */
    private final Map<String, String> lineageTitles = new ConcurrentHashMap<String, String>();
    /** 影子 git checkpoint（null = 未启用）；破坏性工具执行前打快照。 */
    private final CheckpointManager checkpoints;
    /** 最近一次生效的快照 id，/rollback 不带参数时回滚到它。 */
    private volatile String lastCheckpointId;
    /** delegate_task 子代理管理器（null = 未启用委托）。 */
    private final DelegateManager delegation;
    /** 本地长期记忆三层（null = 未启用，如纯测试桩）。 */
    private final MemoryStore memoryStore;
    /** 本地技能根目录（<configDir>/skills 或 center 下发目录），可空。 */
    private final File skillsRoot;
    /** 定时任务调度器（null = 未启用）。 */
    private final CronScheduler cronScheduler;
    /** MCP bridge 聚合（null = 未启用），按 config.mcp.servers 拉起各 server 并把工具注入 toolkit。 */
    private final McpManager mcpManager;

    /**
     * 没有"可以等的人"：委托子代理为 true（{@code DelegateManager.buildChild} 盖上）。
     * 见 {@link #emitToolResult} 里那条自动 deny 分支。
     */
    private final boolean nonInteractive;
    /** 本 agent 替用户拒掉了几次审批（审计与测试对账用，只增不减）。 */
    private final java.util.concurrent.atomic.AtomicInteger subagentAutoDenied =
            new java.util.concurrent.atomic.AtomicInteger();
    /** {@link #shutdown()} 是否已经跑过 —— 异步委托的 {@code finally} 里那句收口唯一的可观测证据。 */
    private volatile boolean shutDown;

    /** 执行前需要打快照的破坏性工具（写文件 / 任意命令 / Maven 构建都会改沙箱）。 */
    private static final Set<String> CHECKPOINT_TOOLS = new HashSet<String>(
            Arrays.asList("write_file", "exec", "mvn_build"));

    protected BotAgent(Builder b) {
        this.config = b.config;
        this.providerCode = b.providerCode;
        this.model = b.model;
        this.sandbox = b.sandbox;
        this.toolkit = b.toolkit;
        this.sessionManager = b.sessionManager;
        this.centerClient = b.centerClient;
        this.maxSteps = b.maxSteps;
        this.maxTokens = b.maxTokens;
        this.temperature = b.temperature;
        // prompt 缓存不变量（P12）：system prompt 只在这里算一次，此后每轮逐字节重放。
        // 记忆 / 技能指引 / center 召回 / 时钟这些会变的 content 既不进 system prompt、
        // P12e 起也不再进 memory —— 它们在 chat() 入口算成一块，只在建 request 时贴到
        // 本轮最后一行 user 上（见 volatileContextBlock / injectVolatileContext）。
        this.memory = new ConversationMemory(buildSystemPrompt(b.systemPrompt, b.toolkit, b.memoryStore));
        this.context = AgentContext.root(b.budgetOverride != null
                ? b.budgetOverride : new IterationBudget(b.maxSteps, b.tokenBudget));
        this.budgetLedger = new BudgetLedger(this.context.budget());
        this.provider = b.provider != null ? Builder.wrapResilient(b.provider, b.config)
                : Builder.wrapResilient(LlmRouter.create(b.config.activeProvider()), b.config);
        // P26：换模型降级时重建在飞的 system 上下文 + 重置本侧记账（压缩侧计数需 context/ 给接口，见 §未做）
        if (this.provider instanceof ResilientLlmProvider) {
            ((ResilientLlmProvider) this.provider).setModelFallbackHook(
                    new ResilientLlmProvider.ModelFallbackHook() {
                        @Override
                        public com.zifang.z.agent.kernel.llm.ChatCompletionsRequest rebuildContext(
                                String fromModel, String toModel,
                                com.zifang.z.agent.kernel.llm.ChatCompletionsRequest inFlight) {
                            return BotAgent.this.rebuildForModel(fromModel, toModel, inFlight);
                        }

                        @Override
                        public void resetCompressionState(String fromModel, String toModel) {
                            BotAgent.this.resetModelSwitchState(fromModel, toModel);
                        }
                    });
        }
        if (b.contextEngine != null) {
            this.compressor = b.contextEngine;
            this.summarizer = b.summarizer;
        } else if (b.config != null && !b.noCompress) {
            this.compressor = newCompressorFromConfig(b);
            this.summarizer = this::summarizeWithProvider;
        } else {
            this.compressor = null;
            this.summarizer = null;
        }
        this.checkpoints = b.checkpointManager;
        this.delegation = b.delegation;
        this.nonInteractive = b.nonInteractive;
        this.memoryStore = b.memoryStore;
        this.skillsRoot = b.skillsRoot;
        if (b.config != null) {
            // 验收/测试用的平台覆盖口：skills.platform.override → SkillLoader 的量测口子
            String platformOverride = b.config.getSkillsPlatformOverride();
            if (!platformOverride.isEmpty()
                    && System.getProperty("zbot.skills.platform") == null) {
                System.setProperty("zbot.skills.platform", platformOverride);
            }
        }
        this.cronScheduler = b.cronScheduler;
        this.mcpManager = b.mcpManager;
        this.approvals = b.approvalService;
        if (this.approvals != null) {
            // exec 工具要知道自己属于哪个会话才能进对 FIFO；会话 id 归 BotAgent 管，这里只给取数钩子
            this.approvals.bindSessionKey(this::currentSessionId);
        }
        if (delegation != null) {
            delegation.attach(this);
        }

        if (centerClient != null && centerClient.isEnabled()) {
            this.lifecycle = registerAndStartLifecycle(centerClient, b.appCode);
        } else {
            this.lifecycle = null;
        }
    }

    /**
     * 用 {@code ~/.zbot/config.properties}（或环境变量）里的配置装配一个可用 agent：
     * provider 路由 + 内置工具 + 沙箱 + 会话目录 + 可选 center 接入。
     */
    public static BotAgent create() {
        return create(BotConfig.load());
    }

    public static BotAgent create(BotConfig config) {
        return builder(config).build();
    }

    public static Builder builder(BotConfig config) {
        return new Builder(config);
    }

    // ===== 对话主入口 =====

    public String chat(String userMessage) {
        return chat(userMessage, StreamListener.NOOP);
    }

    /**
     * 阻塞跑完整个 ReAct 循环，事件同步回抛给 {@code listener}。
     *
     * @return 最终回复；需确认时为 {@code WAIT_CONFIRM:tool|args|reason}；被打断时为「已中止」；预算耗尽时为提示文案
     */
    public String chat(String userMessage, StreamListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("BotAgent 正在运行中");
        }
        StreamListener l = listener == null ? StreamListener.NOOP : listener;
        context.interrupt().reset();
        pendingConfirmation = null;
        // P12e：本轮的动态上下文块<b>在这里算一次</b>，但<b>不并进记忆</b>——它只在建 request 时
        // 由 injectVolatileContext() 贴到本轮最后一行 user 上。算一次是为了同一轮的多步之间
        // 逐字节稳定（否则 step2 与 step1 在该行分叉，P12 的 prompt 缓存前缀白留）。
        // 记忆里存用户原话 ⇒ transcript 落盘干净、历史不堆时钟、渲染面天然无模板。
        this.turnVolatileBlock = volatileContextBlock();
        memory.add(Msg.user(mergeQueued(userMessage)));
        // 把本次会话的旗子绑到执行线程上：工具内部（exec / 文件 / mvn / delegate）
        // 从这里拿到它，才能在「工具正飞着」的时候断，而不是只在循环边界断。
        InterruptFlag boundBefore = InterruptScope.bind(context.interrupt());
        try {
            String reply = runReActLoop(userMessage, l);
            persistSession();
            firePostChat(userMessage, reply);
            return reply;
        } catch (ToolConfirmationNeeded e) {
            String wait = WAIT_CONFIRM_PREFIX + e.getToolName() + "|" + e.getToolArgs() + "|" + e.getReason();
            persistSession();
            return wait;
        } catch (InterruptFlag.AgentInterruptedException e) {
            String aborted = "已中止：" + e.getMessage();
            listener.onEvent(new StreamEvent.Done(aborted, context.budget().apiCalls(), null, null));
            persistSession();
            return aborted;
        } finally {
            InterruptScope.restore(boundBefore);
            running.set(false);
        }
    }

    /** 空闲期间排队的插话（/queue 语义）并入本次用户消息开头。 */
    private String mergeQueued(String userMessage) {
        List<String> queued = context.steer().drain();
        if (queued.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder(userMessage == null ? "" : userMessage);
        for (String q : queued) {
            sb.append("\n[User queued]: ").append(q);
        }
        return sb.toString();
    }

    private String runReActLoop(String userMessage, StreamListener listener) {
        IterationBudget budget = context.budget();
        int step = 0;
        while (budgetLedger.canCall()) {
            maybeAnnounceGrace(budget);
            budget.onCall();
            step++;
            listener.onEvent(new StreamEvent.StepStart(step));
            context.interrupt().checkpoint();
            injectSteer(listener);
            // 位点 1：轮首。上一轮真实 usage（或压缩后的回落值）留在这里判一次。
            applyCompressionAtSite(CompressorEngine.SITE_TURN_START, 0L, listener);
            ChatCompletionsResponse response;
            try {
                ChatCompletionsRequest request = buildRequest();
                lastRequestChars = requestCharsOf(request);
                // 位点 2：请求已经拼好、还没发出去 —— 只有字符数可估，但越线就得先压，
                // 压完必须重拼（否则这次粗估拦下的 oversized 请求照样发出去了）。
                if (applyCompressionAtSite(CompressorEngine.SITE_BEFORE_API_CALL, 0L, listener)) {
                    request = buildRequest();
                    lastRequestChars = requestCharsOf(request);
                }
                response = provider.chat(request);
            } catch (Exception e) {
                LOG.warn("[BotAgent] step {} LLM 调用失败: {}", step, e.getMessage());
                listener.onEvent(new StreamEvent.ErrorEvent(e));
                return "Error: " + e.getMessage();
            }
            recordUsage(budget, response);

            Msg assistant = response.toAssistantMsg();
            if (!assistant.getToolCalls().isEmpty()) {
                if (assistant.getContent() != null && !assistant.getContent().trim().isEmpty()) {
                    listener.onEvent(new StreamEvent.ThoughtDelta(assistant.getContent()));
                }
                memory.add(assistant);
                for (ToolCall tc : assistant.getToolCalls()) {
                    String name = tc.getName() == null ? "?" : tc.getName();
                    if ("final_answer".equals(name) || "final".equals(name)) {
                        String terminal = finalAnswerOf(tc.getArgumentsJson(), assistant.getContent());
                        memory.add(Msg.assistant(terminal));
                        finish(listener, terminal, step, response);
                        return terminal;
                    }
                }
                // 位点 3：工具批之后。这批工具往上下文里灌了多少字符是量得出来的，
                // 把它加到最近一次真实 usage 上就是「本批之后上下文有多大」的最优可用观测。
                long charsBeforeToolBatch = charsOf(memory.getMessages());
                executeBatch(assistant.getToolCalls(), listener);
                long addedToolTokens = Math.max(0L,
                        (charsOf(memory.getMessages()) - charsBeforeToolBatch) / 2);
                applyCompressionAtSite(CompressorEngine.SITE_AFTER_TOOL_BATCH, addedToolTokens, listener);
                continue;
            }

            String content = assistant.getContent();
            if (content == null || content.trim().isEmpty()) {
                if (step == 1) {
                    return "Error: LLM 返回空内容且没有工具调用";
                }
                continue;
            }
            ToolCall parsed = parseTextToolCall(content);
            if (parsed != null) {
                // 文本里的工具调用要还原成带 tool_calls 的 assistant 消息，
                // 否则后面那条 tool 结果在 OpenAI 协议里没有对应的 call id。
                memory.add(toolCallMsg(parsed.getName(),
                        parsed.getArgumentsJson() == null ? "{}" : parsed.getArgumentsJson(),
                        parsed.getId()));
                executeToolCall(parsed, parsed.getName(),
                        parsed.getArgumentsJson() == null ? "{}" : parsed.getArgumentsJson(), listener);
                continue;
            }
            memory.add(Msg.assistant(content));
            finish(listener, content, step, response);
            return content;
        }
        String exhausted = "已达到预算上限(steps=" + budget.apiCalls() + "/" + budget.maxIterations()
                + ", tokens=" + budgetLedger.effectiveTokensUsed() + "/" + budget.maxTokens()
                + (budgetLedger.refundedTokens() > 0 ? "（压缩已退还 " + budgetLedger.refundedTokens() + "）" : "")
                + ")，请简化您的请求。";
        listener.onEvent(new StreamEvent.Done(exhausted, budget.apiCalls(), null, null));
        return exhausted;
    }

    /** 预算耗尽走 grace 通道时给模型的收尾指令（对齐 hermes 的 grace call）。 */
    private void maybeAnnounceGrace(IterationBudget budget) {
        // 判「还没到上限」必须用净占用：压缩退还过的 token 已经不在请求里了，
        // 按累计值判会把还能干活的一轮误判成收尾轮。
        boolean underLimits = budget.apiCalls() < budget.maxIterations()
                && budgetLedger.effectiveTokensUsed() < budget.maxTokens();
        if (!underLimits) {
            memory.add(Msg.user("[system] 预算即将耗尽(steps=" + budget.apiCalls() + "/" + budget.maxIterations()
                    + ", tokens=" + budgetLedger.effectiveTokensUsed() + "/" + budget.maxTokens()
                    + ")，这是最后一次收尾机会：请立即总结当前进展并直接给出面向用户的最终答案，不要再调用工具。"));
        }
    }

    private void recordUsage(IterationBudget budget, ChatCompletionsResponse response) {
        // P26：cache 维度先归一再累加（kernel 目前不透传 cache 字段 ⇒ 恒为 0，见 EVIDENCE §0.8/§5）
        com.zifang.z.bot.llm.ModelUsage.Record usage = com.zifang.z.bot.llm.ModelUsage.fromResponse(response);
        if (usage != null) {
            CACHE_READ.addAndGet(usage.getCacheReadTokens());
            CACHE_WRITE.addAndGet(usage.getCacheWriteTokens());
        }
        // kernel 会把缺失的 usage 归一成 TokenUsage.empty()（全 0），所以不能只判 != null，
        // 否则网关不回传 usage 时（如本地 bench 代理）估算分支永远进不去、压缩阈值永远不触发。
        boolean hasUsage = response != null && response.getUsage() != null
                && response.getUsage().getPromptTokens() > 0;
        if (hasUsage) {
            int prompt = (int) response.getUsage().getPromptTokens();
            int completion = (int) response.getUsage().getCompletionTokens();
            budgetLedger.recordTokens(prompt, completion);
            if (compressor != null) {
                compressor.update(prompt, completion);
            }
            return;
        }
        if (response != null && response.getUsage() != null) {
            budgetLedger.recordTokens((int) response.getUsage().getPromptTokens(),
                    (int) response.getUsage().getCompletionTokens());
        }
        // 网关不回传 usage 时按请求体字符数粗估上下文规模喂给压缩引擎
        if (compressor != null && lastRequestChars > 0) {
            compressor.update((int) (lastRequestChars / 2), 0);
        }
    }

    /**
     * 三个评估位点共用的落地点（P14）。位点自己负责「判」（并在引擎里计数，好让单测钉住它真被调到了），
     * 这里只负责「判到之后做什么」：压一次、回灌记忆、把这次压缩登记成一次会话分叉、发事件、落盘。
     *
     * @param site            {@link CompressorEngine#SITE_TURN_START} /
     *                        {@link CompressorEngine#SITE_BEFORE_API_CALL} /
     *                        {@link CompressorEngine#SITE_AFTER_TOOL_BATCH}
     * @param addedTokens     仅位点 3 用：本批工具新灌进上下文的 token 估算
     * @return true = 这次真的压成功了（调用方据此决定是否重拼请求）
     */
    private boolean applyCompressionAtSite(int site, long addedTokens, StreamListener listener) {
        if (compressor == null) {
            return false;
        }
        ensureCompressionLedger();
        boolean triggered;
        if (site == CompressorEngine.SITE_BEFORE_API_CALL) {
            triggered = compressor.evaluateBeforeApiCall(lastRequestChars);
        } else if (site == CompressorEngine.SITE_AFTER_TOOL_BATCH) {
            long base = compressor.getContextTokens();
            long merged = Math.min(Integer.MAX_VALUE, base + addedTokens);
            triggered = compressor.evaluateAfterToolBatch((int) merged, 0);
        } else {
            triggered = compressor.evaluateAtTurnStart();
        }
        return triggered && applyCompression(listener);
    }

    /**
     * 达到阈值就把中段历史压成一条摘要消息（保最近 N 条原文），压缩结果回灌记忆。
     * 抢锁/冷却/防抖在 {@link CompressorEngine} + {@link CompressionLedger} 里，
     * 这里只负责回写、血统登记与事件。
     */
    private boolean applyCompression(StreamListener listener) {
        if (compressor == null) {
            return false;
        }
        List<Msg> history = memory.getMessages();
        List<Msg> compressed = compressor.compress(history, summarizer);
        if (compressed == history || compressed.size() >= history.size()) {
            return false;
        }
        // 省下来的空间必须当场还回预算，否则「压缩」只是把消息换短、账面上却一秒都没回本
        long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, compressed));
        // 分叉之前先把「压缩前的完整原文」落到父会话那一行 —— 父会话留着原文，
        // 检索沿血统回溯才有东西可回溯（先落子会话的话父行会是空的）。
        persistSession();
        memory.load(compressed);
        // 压缩 = 会话分叉：原文留在父会话里，压缩后的这条历史开一个新会话并挂 parent_session_id
        String forkTitle = forkSessionForCompression();
        listener.onEvent(new StreamEvent.Compacted(compressor.getLastSummary(),
                compressor.getLastFromCount(), compressor.getLastToCount()));
        LOG.info("[BotAgent] 上下文压缩: {} -> {} 条 (累计 {} 次, 本次退还预算 {} tokens, 分叉 {})",
                compressor.getLastFromCount(), compressor.getLastToCount(),
                compressor.getCompressCount(), refunded,
                forkTitle == null ? "未登记" : forkTitle + " @ " + sessionManager.getCurrentSessionId());
        persistSession();
        return true;
    }

    /**
     * 压缩之后建血统：新建子会话（当前会话即切到它上面）、挂父、派 {@code base #N} 标题。
     *
     * @return 派到的标题；store 不可用时 null（退化成「只压不分叉」，内存桩场景）
     */
    private String forkSessionForCompression() {
        CompressionLedger ledger = ensureCompressionLedger();
        if (ledger == null || sessionManager == null) {
            return null;
        }
        String parent = sessionManager.getCurrentSessionId();
        if (parent == null) {
            return null;
        }
        String child = sessionManager.createSession();
        String title = ledger.forkForCompression(parent, child);
        if (title != null) {
            lineageTitles.put(child, title);
        }
        return title;
    }

    /** 压缩引擎的配置口径全部来自 BotConfig（P14）：窗口 / 输出额度 / pct / keepRecent / 冷却 / 防抖 / 锁 TTL。 */
    private static CompressorEngine newCompressorFromConfig(Builder b) {
        BotConfig c = b.config;
        CompressorEngine e = new CompressorEngine(c.getContextWindow(), c.getContextMaxOutputTokens(),
                c.getContextCompressPct(), c.getContextCompressKeepRecent(),
                c.getContextCompressCooldownMillis(), c.getContextCompressMaxIneffective(),
                c.getContextCompressLockTtlMillis());
        return e.withEnabled(c.isContextCompressEnabled());
    }

    /** store 到手之后才挂得上（Builder 里 sessionManager 可能是 build() 中途才造的）。 */
    private CompressionLedger ensureCompressionLedger() {
        CompressionLedger ledger = compressionLedger;
        if (ledger != null || sessionManager == null || compressor == null) {
            return ledger;
        }
        StateStore store = sessionManager.getStore();
        if (store == null) {
            return null;
        }
        ledger = new CompressionLedger(store);
        compressionLedger = ledger;
        compressor.withGate(ledger, this::currentSessionId);
        return ledger;
    }

    /**
     * 压缩带来的净节省（token 估算口径：字符数 / 2，与 {@link #recordUsage}
     * 里网关不回 usage 时的口径一致）。
     */
    static long freedTokensOfCompression(List<Msg> before, List<Msg> after) {
        long saved = charsOf(before) - charsOf(after);
        return saved <= 0 ? 0L : saved / 2;
    }

    private static long charsOf(List<Msg> msgs) {
        long chars = 0;
        if (msgs != null) {
            for (Msg m : msgs) {
                chars += m.getContent() == null ? 0 : m.getContent().length();
            }
        }
        return chars;
    }

    /** 摘要调主 provider（无工具、低温），prompt 带事实承诺保留约束。 */    private String summarizeWithProvider(List<Msg> middle) {
        StringBuilder sb = new StringBuilder(CompressorEngine.SUMMARY_PROMPT_PREFIX);
        for (Msg m : middle) {
            String role = m.getRole() == null ? "user" : m.getRole().name().toLowerCase();
            sb.append(role).append(": ")
                    .append(m.getContent() == null ? "" : m.getContent()).append('\n');
        }
        try {
            ChatCompletionsRequest req = new ChatCompletionsRequest(model,
                    Collections.singletonList(Msg.user(sb.toString())),
                    Collections.<com.zifang.z.agent.kernel.tool.Tool>emptyList(),
                    0.2, null, 1024, false, Collections.<String, Object>emptyMap());
            ChatCompletionsResponse resp = provider.chat(req);
            return resp == null || resp.toAssistantMsg().getContent() == null
                    ? "" : resp.toAssistantMsg().getContent();
        } catch (Exception e) {
            LOG.warn("[BotAgent] 摘要生成失败，跳过本次压缩: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 运行中排队的用户插话在工具间隙注入消息流（hermes 的 steer 语义）：
     * <b>追加到最后一条 tool 结果尾部</b>，而不是新插一条 user 消息 ——
     * assistant 的 {@code tool_calls} 与 tool 结果在 OpenAI 协议里必须成对，
     * 中间塞一条 user 消息会让消息序列变成非法形状。没有任何 tool 结果可挂时
     * （首轮就插话）退回独立 user 消息。
     */
    private void injectSteer(StreamListener listener) {
        List<String> pending = context.steer().drain();
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : pending) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[User steer]: ").append(p);
        }
        listener.onEvent(new StreamEvent.SteerInjected(sb.toString()));
        if (!memory.appendToLastToolResult("\n" + sb)) {
            memory.add(Msg.user(sb.toString()));
        }
    }

    private void executeToolCall(ToolCall tc, String name, String argsJson, StreamListener listener) {
        Map<String, Object> args = parseArgs(argsJson);
        listener.onEvent(new StreamEvent.ToolCallRequest(name, args, argsJson));
        String callId = tc.getId() == null || tc.getId().isEmpty()
                ? ("call_" + name + "_" + System.currentTimeMillis()) : tc.getId();
        String ck = snapshotBefore(name);
        ToolResult result = toolkit.execute(name, args);
        settleCheckpoint(ck, result);
        emitToolResult(new ToolCall(callId, name, argsJson), result, listener);
    }

    /**
     * 破坏性工具执行前打影子 git 快照；快照失败只记日志，绝不拦工具执行。
     *
     * @return 快照 id，非破坏性工具或失败时返回 null
     */
    private String snapshotBefore(String toolName) {
        if (checkpoints == null || !CHECKPOINT_TOOLS.contains(toolName)) {
            return null;
        }
        try {
            String id = checkpoints.snapshot(toolName);
            LOG.info("[BotAgent] checkpoint {} before {}", id, toolName);
            return id;
        } catch (Exception e) {
            LOG.warn("[BotAgent] checkpoint 快照失败（不影响工具执行）: {}", e.getMessage());
            return null;
        }
    }

    /** 工具真执行了才认这个快照；被审批拦下（实际没跑）就丢弃，避免列表噪音。 */
    private void settleCheckpoint(String ck, ToolResult result) {
        if (ck == null) {
            return;
        }
        if (result != null && Confirmations.isRequired(result)) {
            checkpoints.discard(ck);
            return;
        }
        lastCheckpointId = ck;
    }

    /** 工具结果回灌记忆 + 回抛事件；需要确认时设置 pendingConfirmation 并抛出暂停信号。 */
    private void emitToolResult(ToolCall effectiveCall, ToolResult result, StreamListener listener) {
        String name = effectiveCall.getName();
        if (Confirmations.isRequired(result)) {
            if (nonInteractive) {
                // 子代理没有"可以等的人"。修之前这里照旧抛 ToolConfirmationNeeded，
                // chat() 捕获它（同文件 `catch (ToolConfirmationNeeded e)` 那一支）并把
                // "WAIT_CONFIRM:tool|args|reason" 揉成
                // **最终回复返回**，DelegateManager 再把那条字符串记成 TASK_COMPLETED
                // ⇒ 父模型收到的是一次"看起来成功完成"的委托，而实际一个字节都没执行、
                // 也没任何人被问过。
                // 现在按 hermes 的做法给子代理装非交互裁决：默认 deny（delegate_tool.py:57-85
                // 整块，_subagent_auto_deny 本体 :74-85 —— "Returns 'deny' so the subagent
                // sees a refusal it can recover from"），让子模型看见一句它能从中改道的
                // 拒绝、回合继续走。
                // 不设 pendingConfirmation、不抛暂停信号：那是交互路径的契约，
                // 子代理这边没有"可以等的人"来恢复它。
                String reason = Confirmations.reason(result);
                // exec 闸门已经替这次调用在本 agent 私有的队列里落了一条 pending
                // （入队发生在 BuiltinTools.exec 里，见 BuiltinToolsExecGateTest
                // #dangerousCommandQueuesRequestAndAsks，不在 BotAgent 这一层）。裁决既然当场做了，
                // 那条记录就必须同场结掉 —— 否则子代理每撞一次闸门就攒一条永不消费的待批，
                // 正是红线 8 禁止的账实分离。DENY 不留任何可复用痕迹。
                String requestId = Confirmations.requestId(result);
                if (approvals != null && requestId != null) {
                    approvals.resolve(approvals.currentSessionKey(), requestId,
                            ApprovalService.Resolution.DENY);
                }
                int n = subagentAutoDenied.incrementAndGet();
                LOG.warn("[BotAgent] 子代理 #{} 次申请人工确认被自动拒绝: {} ({})", n, name, reason);
                String denial = "[auto-denied] 子代理不能申请人工确认，该调用未执行：" + reason
                        + "（要放开请改父配置：agent.exec.confirm=off 或 agent.exec.confirm.whitelist）";
                memory.add(Msg.toolResult(effectiveCall.getId(), denial));
                listener.onEvent(new StreamEvent.ToolResult(name, null, denial, true));
                return;
            }
            pendingConfirmation = effectiveCall;
            enqueueApprovalRequest(name, effectiveCall, result);
            listener.onEvent(new StreamEvent.ToolResult(name, null, Confirmations.reason(result), false));
            throw new ToolConfirmationNeeded(name,
                    effectiveCall.getArgumentsJson() == null ? "{}" : effectiveCall.getArgumentsJson(),
                    Confirmations.reason(result));
        }
        String output = result.getContent() == null ? "" : result.getContent();
        memory.add(Msg.toolResult(effectiveCall.getId(), output));
        listener.onEvent(new StreamEvent.ToolResult(name, output,
                result.isError() ? output : null, !result.isError()));
    }

    /**
     * exec 闸门已经把请求排进本会话 FIFO；不带闸门的工具（memory 改写等）在这里补一条，
     * 否则 {@code /confirm} 消费队列会漏项。
     *
     * <p>补完之后一律"取代"：z-bot 的待批语义是<b>中断当前回合</b>（抛
     * {@link ToolConfirmationNeeded}），不是 hermes 那种"工具线程阻塞在自己那条审批上"。
     * 所以一个会话同时只可能有一条活待批 —— 新待批到来时，队列里更老的那些永远不会再被执行。
     * 留着它们就会出现"账记着 A、人放行后跑的是 B"（红线 8 禁止的账实分离）。</p>
     *
     * <p><b>子代理不会走到这里</b>（P27c）：{@code buildChild} 不注入 {@code approvalService}，
     * {@code build()} 便给每个子 agent 现造一个 {@link ApprovalService}（见 Builder 里的
     * {@code newApprovalService}）—— 那条队列是子 agent 私有的，父会话的 {@code /confirm}
     * 结构上读不到。修之前子代理照旧入队 + 抛 {@code ToolConfirmationNeeded}，于是同一时刻
     * 留着两样没人消费的东西：队列里一条永远 pending 的申请，和 {@code chat()} 揉成
     * {@code WAIT_CONFIRM:…} 字符串当最终回复的那次"成功"。现在 {@link #emitToolResult} 在
     * {@code nonInteractive} 时当场 deny，既不申请也不入队。</p>
     */
    private void enqueueApprovalRequest(String toolName, ToolCall call, ToolResult result) {
        if (approvals == null) {
            return;
        }
        String sessionKey = approvals.currentSessionKey();
        String argsJson = call.getArgumentsJson() == null ? "{}" : call.getArgumentsJson();
        String requestId = Confirmations.requestId(result);
        if (requestId == null) {
            requestId = approvals.submit(sessionKey, toolName, argsJson, argsJson,
                    toolName + " " + argsJson, Confirmations.reason(result), "tool-confirmation").id();
        }
        approvals.supersedePending(sessionKey, requestId);
    }

    /** 当前会话的待批队列（FIFO 顺序，只读快照）。 */
    public java.util.List<ApprovalService.Request> pendingApprovals() {
        return approvals == null ? java.util.Collections.<ApprovalService.Request>emptyList()
                : approvals.pending(approvals.currentSessionKey());
    }

    /**
     * 把本次会话的中断旗子带进池线程（提交时刻捕获，不是执行时刻）。
     *
     * <p>不带的话，并行批次里的工具就查不到「我这会话已被要求停止」，
     * 工具侧检查点在并行路径上形同虚设；也不还原的话，池线程会被复用，
     * 上一条会话的旗子会跟着线程漂到下一条会话上 —— 那是串台的另一种写法。</p>
     */
    private <T> java.util.concurrent.Callable<T> bindToPoolThread(final java.util.concurrent.Callable<T> task) {
        final InterruptFlag flag = context.interrupt();
        return new java.util.concurrent.Callable<T>() {
            @Override
            public T call() throws Exception {
                InterruptFlag previous = InterruptScope.bind(flag);
                try {
                    return task.call();
                } finally {
                    InterruptScope.restore(previous);
                }
            }
        };
    }

    /**
     * 一个工具批次调度：批内全部 parallel-safe（只读）才并发执行，否则按原顺序串行。
     *
     * <p>并行批次的事件时序保持对 listener 单线程回调：请求事件先按序广播，
     * 结果在收集阶段按原顺序回灌，避免 SSE/终端渲染交错。</p>
     */
    private void executeBatch(List<ToolCall> calls, StreamListener listener) {
        boolean allParallelSafe = calls.size() > 1;
        for (ToolCall tc : calls) {
            if (!toolkit.isParallelSafe(tc.getName())) {
                allParallelSafe = false;
                break;
            }
        }
        if (!allParallelSafe) {
            for (ToolCall tc : calls) {
                context.interrupt().checkpoint();
                String name = tc.getName() == null ? "?" : tc.getName();
                executeToolCall(tc, name,
                        tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson(), listener);
            }
            return;
        }

        for (ToolCall tc : calls) {
            Map<String, Object> args = parseArgs(tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson());
            listener.onEvent(new StreamEvent.ToolCallRequest(tc.getName(), args, tc.getArgumentsJson()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(calls.size(), 4));
        try {
            List<Future<ToolResult>> futures = new ArrayList<Future<ToolResult>>();
            for (int i = 0; i < calls.size(); i++) {
                final ToolCall tc = calls.get(i);
                final Map<String, Object> args =
                        parseArgs(tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson());
                futures.add(pool.submit(bindToPoolThread(new Callable<ToolResult>() {
                    @Override
                    public ToolResult call() {
                        return toolkit.execute(tc.getName(), args);
                    }
                })));
            }
            for (int i = 0; i < calls.size(); i++) {
                ToolCall tc = calls.get(i);
                String callId = tc.getId() == null || tc.getId().isEmpty()
                        ? ("call_" + tc.getName() + "_" + System.currentTimeMillis() + "_" + i) : tc.getId();
                ToolResult result;
                try {
                    result = futures.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptFlag.AgentInterruptedException) {
                        // 并行批次里的工具是被 /stop 断下来的，不是自己失败的：
                        // 揉成 ToolResult.failure 回灌给模型，等于告诉它「继续想别的办法」，
                        // 用户按了 stop 却还有一整轮在跑。原样上抛，由 chat() 走中止分支。
                        throw (InterruptFlag.AgentInterruptedException) cause;
                    }
                    result = ToolResult.failure(callId, tc.getName(),
                            "工具执行失败: " + (cause == null ? e.getMessage() : cause.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = ToolResult.failure(callId, tc.getName(), "工具执行被中断");
                }
                emitToolResult(new ToolCall(callId, tc.getName(),
                        tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson()), result, listener);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 确认并执行被 {@link ToolConfirmationNeeded} 暂停的工具调用，结果回灌当前会话记忆。
     *
     * <p>待确认的那次调用在记忆里已有对应的 assistant {@code tool_calls} 消息，
     * 所以只需补一条同 id 的 tool 结果；没有待确认记录时（如进程重启后手动放行）
     * 才合成 assistant 消息。</p>
     *
     * <p>决议档位缺省 {@code once}。终端的 {@code /confirm} 没有参数槽（channel 侧不在本期边界内），
     * 所以它只能表达 once；要 session/always/deny 走
     * {@link #confirmTool(String, String, ApprovalService.Resolution)}，或在 argsJson 里带保留键
     * {@link Confirmations#RESOLUTION_ARG}（该键在模型侧参数会被 {@link #parseArgs} 无条件剥除，
     * 只有人提交的 argsJson 才有效 —— 红线 7）。</p>
     */
    public String confirmTool(String toolName, String argsJson) {
        return confirmTool(toolName, argsJson, null);
    }

    /**
     * 四档决议版确认。
     *
     * <ul>
     *   <li>{@code ONCE} —— 只放行这一次调用；</li>
     *   <li>{@code SESSION} —— 同条命令本会话内不再问（纯内存，换会话即失效）；</li>
     *   <li>{@code ALWAYS} —— 唯一落盘的一档，写 {@code <configDir>/config.properties} 的
     *       {@code agent.exec.approval.always}；</li>
     *   <li>{@code DENY} —— 出队但不执行。</li>
     * </ul>
     *
     * <p>无论哪一档，硬线命令都在 {@link ExecGuard#decide} 第一关就被拒 —— 人也没权放行硬线。</p>
     */
    public String confirmTool(String toolName, String argsJson, ApprovalService.Resolution explicitResolution) {
        Map<String, Object> humanArgs = readArgs(argsJson == null ? "{}" : argsJson);
        Object humanResolution = humanArgs.remove(Confirmations.RESOLUTION_ARG);
        ApprovalService.Resolution resolution = explicitResolution != null ? explicitResolution
                : ApprovalService.Resolution.parse(humanResolution == null
                        ? null : humanResolution.toString(), ApprovalService.Resolution.ONCE);

        ApprovalService.Resolved resolved = null;
        if (approvals != null) {
            resolved = approvals.resolveNext(approvals.currentSessionKey(), resolution);
        }
        ApprovalService.Request head = resolved == null ? null : resolved.request();
        // 审批绑命令：人放行的是"队列里那一条"，不是调用方此刻传来的参数。通道/客户端可以拿
        // 一次放行去跑另一条命令（人看到的是 A，跑的是 B），所以有队头记录时一律照队头执行。
        String effectiveArgsJson = argsJson;
        if (head != null) {
            toolName = head.toolName();
            effectiveArgsJson = head.argsJson() == null ? "{}" : head.argsJson();
            humanArgs = readArgs(effectiveArgsJson);
            humanArgs.remove(Confirmations.RESOLUTION_ARG);
        }
        if (resolution == ApprovalService.Resolution.DENY) {
            String what = head != null ? head.command() : toolName;
            ToolCall pending = pendingConfirmation;
            if (pending != null && toolName.equals(pending.getName())) {
                // assistant 的 tool_calls 消息已在，这里只补一条"人被拒"的 tool 结果，模型才知道要改道
                memory.add(Msg.toolResult(pending.getId(), "用户拒绝执行该操作（deny）：" + what));
            }
            pendingConfirmation = null;
            persistSession();
            return "已拒绝（deny）：" + what + " —— 命令未执行";
        }

        humanArgs.put(Confirmations.CONFIRMED_ARG, Boolean.TRUE);
        String ck = snapshotBefore(toolName);
        ToolResult result = toolkit.execute(toolName, humanArgs);
        settleCheckpoint(ck, result);
        if (result == null) {
            return "执行失败：工具 " + toolName + " 无返回";
        }
        String output = result.getContent() == null ? "" : result.getContent();
        ToolCall pending = pendingConfirmation;
        String callId;
        if (pending != null && toolName.equals(pending.getName())) {
            callId = pending.getId();
            pendingConfirmation = null;
        } else {
            callId = "confirm_" + System.currentTimeMillis();
            memory.add(toolCallMsg(toolName, effectiveArgsJson, callId));
        }
        memory.add(Msg.toolResult(callId, output));
        persistSession();
        String stamp = resolution == ApprovalService.Resolution.SESSION ? "（session 档：本会话内同类命令免再问）"
                : resolution == ApprovalService.Resolution.ALWAYS ? "（always 档：已落盘 config.properties）" : "";
        return result.isError() ? ("Error: " + output) : (output + stamp);
    }

    // ===== 记忆 / 会话 =====

    public void clearMemory() {
        memory.clear();
    }

    public ConversationMemory getMemory() {
        return memory;
    }

    public String currentSessionId() {
        return sessionManager.getCurrentSessionId();
    }

    public String newSession() {
        String id = sessionManager.createSession();
        memory.clear();
        return id;
    }

    /**
     * 切换会话并把该会话的历史消息装回记忆。
     */
    public String switchSession(String sessionId) {
        sessionManager.switchSession(sessionId);
        memory.load(sessionManager.loadMessages(sessionId));
        return sessionId;
    }

    public List<SessionManager.SessionSummary> listSessions() {
        return sessionManager.listSessions();
    }

    public void deleteSession(String sessionId) {
        sessionManager.deleteSession(sessionId);
    }

    private void persistSession() {
        String sid = sessionManager.getCurrentSessionId();
        if (sid != null) {
            sessionManager.saveMessages(sid, memory.getConversationMessages());
            reassertLineageTitle(sid);
        }
    }

    /**
     * 分叉出来的会话标题（{@code base #N}）必须压过 {@code SessionManager} 那条
     * 「标题还是『新会话』就拿首条用户消息猜一个」的默认路径 —— 它每次 saveMessages 都会
     * 用猜出来的标题覆盖库里那行，所以每次落盘之后都要回填一次。
     */
    private void reassertLineageTitle(String sessionId) {
        String title = lineageTitles.get(sessionId);
        CompressionLedger ledger = compressionLedger;
        if (title == null || ledger == null) {
            return;
        }
        ledger.retitle(sessionId, title);
    }

    /** 压缩引擎（P14 自证面：位点计数、阈值口径、锁后端、冷却与防抖状态）。 */
    public CompressorEngine compressor() {
        return compressor;
    }

    /** 血统落盘侧（无 store 时为 null）。 */
    public CompressionLedger compressionLedger() {
        return compressionLedger;
    }

    /**
     * 沿血统回溯并去重的会话检索（P14）。
     *
     * <p>生产消费者在 {@code cli/SessionsCommand}（不在本棒写域），先把口径落在 core 里，
     * 并由 {@code CompressionLineageTest} 钉住「子会话摘要 + 父会话原文各命中一次 ⇒ 只回父会话一条」。</p>
     */
    public List<CompressionLedger.SearchHit> searchSessionsAlongLineage(String keyword, int limit) {
        CompressionLedger ledger = ensureCompressionLedger();
        return ledger == null ? new ArrayList<CompressionLedger.SearchHit>()
                : ledger.searchAlongLineage(keyword, limit);
    }

    // ===== center 集成 =====

    public String getInstanceCode() {
        return centerClient == null ? null : centerClient.getInstanceCode();
    }

    public BotCenterClient getCenterClient() {
        return centerClient;
    }

    public int syncSkillsFromCenter() {
        if (centerClient == null || !centerClient.isEnabled()) {
            return 0;
        }
        try {
            return centerClient.syncSkills();
        } catch (Exception e) {
            LOG.warn("[BotAgent] manual syncSkills failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * {@code /skills [view <name> | check <name> | sync [src] | install <dir>]}
     * —— 列出/查看/体检/同步/安装技能（center 下发 + 本地 <configDir>/skills）。
     */
    public String skillsManage(String args) {
        List<SkillLoader.Skill> local = skillsRoot == null
                ? Collections.<SkillLoader.Skill>emptyList() : SkillLoader.scan(skillsRoot);
        String a = args == null ? "" : args.trim();
        String lower = a.toLowerCase();
        if (lower.startsWith("view")) {
            String name = a.length() > 4 ? a.substring(4).trim() : "";
            for (SkillLoader.Skill s : local) {
                if (s.name.equalsIgnoreCase(name)) {
                    return "[" + s.name + "] v" + (s.version.isEmpty() ? "?" : s.version)
                            + "  " + (s.slash.isEmpty() ? "" : "(slash: " + s.slash + ")")
                            + (s.offerable() ? "" : "\n未进命令表: " + s.hiddenReason)
                            + (s.setupNote == null ? "" : "\nsetup: " + s.setupNote)
                            + "\n" + s.description + "\n\n" + s.body;
                }
            }
            return "未找到技能: " + name + "（/skills 查看列表）";
        }
        if (lower.startsWith("check")) {
            String name = a.length() > 5 ? a.substring(5).trim() : "";
            return skillHealthCheck(name);
        }
        if (lower.startsWith("sync")) {
            File src = a.length() > 4 ? new File(a.substring(4).trim()) : skillBundledDir();
            if (src == null || !src.isDirectory()) {
                return "同步源目录不存在: " + src + "（可用 skills.bundled.dir 或 /skills sync <dir> 指定）";
            }
            if (skillsRoot == null) {
                return "本地技能根未启用（configDir 不可用），无法 sync";
            }
            SkillSync.Report r = SkillSync.sync(src, skillsRoot, skillGuardSource());
            refreshSkillCommandTable();
            List<String> written = new ArrayList<String>(r.copied);
            written.addAll(r.updated);
            return r.describe() + (written.isEmpty()
                    ? "\n没有写入任何文件" : "\n写入: " + String.join(", ", written));
        }
        if (lower.startsWith("install")) {
            String dir = a.length() > 7 ? a.substring(7).trim() : "";
            File src = new File(dir);
            if (dir.isEmpty() || !new File(src, "SKILL.md").isFile()) {
                return "格式: /skills install <技能目录>（目录里要有 SKILL.md）";
            }
            if (skillsRoot == null) {
                return "本地技能根未启用（configDir 不可用），无法 install";
            }
            SkillSync.Report r = SkillSync.install(src, skillsRoot, skillGuardSource());
            refreshSkillCommandTable();
            return r.describe() + (r.suppressed.isEmpty() ? "" : "\n被拦下: " + r.suppressed);
        }
        List<String> codes = listInstalledSkills();
        StringBuilder sb = new StringBuilder();
        List<SkillCommands.Entry> table = skillCommandPlan(local).entries();
        for (String c : codes) {
            boolean isLocal = local.stream().anyMatch(s -> s.name.equals(c));
            String cmd = "";
            for (SkillCommands.Entry e : table) {
                if (e.skill.name.equals(c)) {
                    cmd = "  -> " + e.key;
                    break;
                }
            }
            sb.append("- ").append(c).append(isLocal ? " (local)" : "").append(cmd).append('\n');
        }
        for (SkillLoader.Skill s : local) {
            if (s.offerable()) {
                continue;
            }
            sb.append("- ").append(s.name).append(" (local)  -> 不进命令表: ")
                    .append(s.hiddenReason).append('\n');
        }
        if (skillsRoot != null) {
            SkillCommands.Plan plan = skillCommandPlan(local);
            String skipped = plan.describeSkipped();
            if (!skipped.isEmpty()) {
                sb.append("命令表账本（为什么没进）:\n").append(skipped).append('\n');
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "本地无已安装的 Skill（接入 center 后运行 /sync 拉取，"
                + "或把 <skill>/SKILL.md 放进 <configDir>/skills/）" : "已安装 Skill:\n" + out;
    }

    /** 技能 → 命令计划（保留的第一个 / 撞核心名跳过的账本都在这里）。 */
    public SkillCommands.Plan skillCommandPlan(List<SkillLoader.Skill> local) {
        com.zifang.z.bot.slash.SlashRegistry live =
                com.zifang.z.bot.slash.SlashRegistry.live();
        final java.util.Set<String> core = new java.util.HashSet<String>();
        if (live != null) {
            core.addAll(live.coreCommandNames());
        } else {
            for (String n : com.zifang.z.bot.slash.SlashRegistry.withBuiltinCommands()
                    .coreCommandNames()) {
                core.add(n);
            }
        }
        return SkillCommands.plan(local, core::contains);
    }

    /** 让 live 命令表跟着这次 sync/install 重新派生（不落第二份表，只是重算技能那一段）。 */
    private void refreshSkillCommandTable() {
        com.zifang.z.bot.slash.SlashRegistry live =
                com.zifang.z.bot.slash.SlashRegistry.live();
        if (live != null) {
            live.refreshSkillCommands();
        }
    }

    /** {@code /skills check <name>}：门控理由 + guard 结论 + origin_hash 台账。 */
    private String skillHealthCheck(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "格式: /skills check <name>";
        }
        File dir = skillsRoot == null ? null : new File(skillsRoot, name.trim());
        if (dir == null || !new File(dir, "SKILL.md").isFile()) {
            return "未找到技能目录: " + dir;
        }
        StringBuilder sb = new StringBuilder();
        SkillLoader.Skill s = SkillLoader.tryParse(dir);
        if (s != null) {
            sb.append("[").append(s.name).append("] platforms=").append(s.platforms)
                    .append(" environments=").append(s.environments)
                    .append(" 需要 env=").append(s.requiredEnvVars)
                    .append(" 需要命令=").append(s.requiredCommands).append('\n');
            sb.append(s.offerable() ? "进命令表: 是" : "进命令表: 否 —— " + s.hiddenReason).append('\n');
            if (s.setupNote != null) {
                sb.append("降级说明: ").append(s.setupNote).append('\n');
            }
        }
        SkillGuard.ScanResult scan = SkillGuard.scanSkill(dir, skillGuardSource());
        sb.append("guard(").append(SkillGuard.SCANNER_VERSION).append(") verdict=")
                .append(scan.verdict).append(" blocked=").append(scan.blocked).append('\n');
        for (SkillGuard.Finding f : scan.findings) {
            sb.append("  - ").append(f).append('\n');
        }
        Map<String, String> manifest = skillsRoot == null
                ? Collections.<String, String>emptyMap() : SkillSync.readManifest(skillsRoot);
        String origin = manifest.get(name.trim());
        sb.append("origin_hash=")
                .append(origin == null ? "(不在同步清单里)" : origin)
                .append("  当前目录指纹=").append(SkillSync.dirHash(dir));
        if (origin != null && !origin.isEmpty() && !origin.equals(SkillSync.dirHash(dir))) {
            sb.append(" ⇒ 本地已改动，sync 不会覆盖它");
        }
        return sb.toString().trim();
    }

    /** 同步源目录：{@code skills.bundled.dir} &gt; {@code <configDir>/skills-bundled}。 */
    public File skillBundledDir() {
        String v = config == null ? "" : config.getSkillsBundledDir();
        if (v != null && !v.trim().isEmpty()) {
            return new File(v.trim());
        }
        File cfg = config == null ? null : config.getConfigDir();
        return cfg == null ? null : new File(cfg, "skills-bundled");
    }

    /** guard 的信任级：{@code skills.guard.source}（缺省 bundled = 自带源，阈值宽松一档）。 */
    public String skillGuardSource() {
        String v = config == null ? null : config.getSkillsGuardSource();
        return v == null || v.trim().isEmpty() ? "bundled" : v.trim();
    }

    /** 本地已安装 skill：扫 {@code <profile>/skills/<instanceCode>/} 一级子目录。 */
    public List<String> listInstalledSkills() {
        List<String> codes = new ArrayList<String>();
        if (centerClient != null && centerClient.getInstanceCode() != null) {
            File root = centerClient.skillsRoot();
            File[] children = root == null ? null : root.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) {
                        codes.add(c.getName());
                    }
                }
            }
        }
        // 本地 <configDir>/skills 下的技能并入（同名去重，center 下发的优先）
        if (skillsRoot != null) {
            for (SkillLoader.Skill s : SkillLoader.scan(skillsRoot)) {
                if (!codes.contains(s.name)) {
                    codes.add(s.name);
                }
            }
        }
        Collections.sort(codes);
        return codes;
    }

    public void submitFeedback(int rating, String comment, String appCode, String skillCodes) {
        if (centerClient != null && centerClient.isEnabled()) {
            centerClient.submitFeedback(rating, comment, appCode, skillCodes);
        }
    }

    private void firePostChat(String userMessage, String reply) {
        if (centerClient == null || !centerClient.isEnabled()) {
            return;
        }
        try {
            centerClient.firePostChat(userMessage, reply, null);
        } catch (Exception e) {
            LOG.debug("[BotAgent] firePostChat failed (best-effort): {}", e.getMessage());
        }
    }

    private BotLifecycle registerAndStartLifecycle(BotCenterClient client, String appCode) {
        boolean ok = client.register(appCode, System.getProperty("user.name"), "z-bot", null, BOT_VERSION);
        if (!ok) {
            LOG.warn("[BotAgent] center 注册失败，以本地模式运行");
            return null;
        }
        BotLifecycle lc = new BotLifecycle(client, BOT_VERSION);
        lc.start();
        try {
            LOG.info("[BotAgent] initial skill sync wrote {} skills", client.syncSkills());
        } catch (Exception e) {
            LOG.warn("[BotAgent] initial skill sync failed: {}", e.getMessage());
        }
        return lc;
    }

    // ===== 运行时控制 =====

    /**
     * 协作式软中断：置位 kernel {@link InterruptFlag}，主循环在最近的迭代/工具边界停下，
     * 工具内部经 {@link InterruptScope} 在同一面旗子上停下（含把在飞的子进程整个杀掉）。
     *
     * <p>硬中断的附带语义（对齐 hermes）：<b>丢弃尚未注入的 steer 插话</b> —— 用户已经按了停止，
     * 那条「改道」的插话就永远没有落点了，留着它会在下一轮 chat 开头以 {@code [User steer]} 的
     * 形式冒出来，等于替用户记住了一件他自己叫停的事。</p>
     */
    public void stop() {
        context.interrupt().request("用户请求停止");
        context.steer().clear();
        if (delegation != null) {
            delegation.stopChildren();
        }
    }

    public boolean isStopRequested() {
        return context.interrupt().isInterrupted();
    }

    /**
     * 用户插话（{@code /steer}）：<b>单槽</b>语义 —— 后到的盖掉先到的，运行中时在工具间隙
     * 追加到最后一条 tool 结果；空闲时入队，下次 {@link #chat} 开头并入用户消息。
     *
     * <p>为什么是单槽而不是队列：插话的语义是「从现在起按这个来」，两条互相冲突的插话
     * 同时生效没有意义（hermes 的 {@code _pending_steer} 同样是单槽）。
     * 要一次排好几件事，走 {@link #enqueue}（{@code /queue}）。</p>
     */
    public void steer(String message) {
        context.steer().clear();
        context.steer().add(message);
    }

    /**
     * 排队消息（{@code /queue}）：多条按序保留，下次 {@link #chat} 开头以
     * {@code [User queued]} 逐条并入。与 {@link #steer} 共用 kernel {@code SteerQueue}
     * 作存储，差别只在写入端是否覆盖 —— 单槽是 steer 的语义，不是存储的语义。
     */
    public void enqueue(String message) {
        context.steer().add(message);
    }

    /** 当前待注入的插话/排队条数（{@code /usage} 与测试对账用）。 */
    public boolean hasPendingSteer() {
        return context.steer().hasPending();
    }

    /** kernel 显式运行状态（预算 / 中断 / steer 队列），供 /usage 与子代理派生使用。 */
    public AgentContext context() {
        return context;
    }

    /** 预算账本（累计 token + 压缩退还）；{@code /usage} 读它，不直接读 kernel 的累计值。 */
    public BudgetLedger budgetLedger() {
        return budgetLedger;
    }

    /** {@code /compress [preview]} — 查看或手动触发上下文压缩。 */
    public String compressNow(String args) {
        if (compressor == null) {
            return "未启用上下文压缩引擎";
        }
        boolean preview = args != null && args.toLowerCase().contains("preview");
        if (preview) {
            ensureCompressionLedger();
            StringBuilder sb = new StringBuilder();
            sb.append("context observed=").append(compressor.observedTokens())
                    .append("/limit=").append(compressor.compressionLimit())
                    .append(" shouldCompress=").append(compressor.shouldCompress())
                    .append(" 已压缩次数=").append(compressor.getCompressCount())
                    .append("（输入 /compress 执行压缩）\n");
            // 产品里没有 /config 斜杠命令（SlashRegistry 未注册），压缩口径就在这儿自证
            sb.append("位点调用次数 轮首/粗估/工具批 = ")
                    .append(compressor.siteHits(CompressorEngine.SITE_TURN_START)).append('/')
                    .append(compressor.siteHits(CompressorEngine.SITE_BEFORE_API_CALL)).append('/')
                    .append(compressor.siteHits(CompressorEngine.SITE_AFTER_TOOL_BATCH)).append('\n');
            sb.append(compressor.describe()).append('\n');
            if (config != null) {
                for (String line : config.contextCompressConfigLines()) {
                    sb.append(line).append('\n');
                }
            }
            String sid = sessionManager == null ? null : sessionManager.getCurrentSessionId();
            CompressionLedger ledger = compressionLedger;
            sb.append("session=").append(sid)
                    .append(" 压缩锁持有者=").append(ledger == null ? "无库" : String.valueOf(ledger.lockHolder(sid)))
                    .append(" 血统深度=").append(ledger == null ? -1 : ledger.lineageDepth(sid));
            return sb.toString().trim();
        }
        List<Msg> history = memory.getMessages();
        List<Msg> out = compressor.forceCompress(history, summarizer);
        if (out == history || out.size() >= history.size()) {
            return "没有可压缩的内容（历史太短或摘要为空）";
        }
        memory.load(out);
        long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, out));
        String forkTitle = forkSessionForCompression();
        persistSession();
        return "已压缩: " + history.size() + " -> " + out.size() + " 条 (累计 "
                + compressor.getCompressCount() + " 次，本次退还预算 " + refunded + " tokens，"
                + (forkTitle == null ? "未分叉（无 state.db）"
                : "分叉为 " + forkTitle + " @ " + sessionManager.getCurrentSessionId()) + ")";
    }

    /**
     * {@code /checkpoints [prune [n]]} — 列出沙箱快照；{@code prune n} 只保留最近 n 个（默认 20）。
     */
    public String checkpointManage(String args) {
        if (checkpoints == null) {
            return "未启用 checkpoint（config 模式启动或经 builder 注入后可用）";
        }
        try {
            if (args != null && args.toLowerCase(Locale.ROOT).startsWith("prune")) {
                String[] parts = args.trim().split("\\s+");
                int keep = parts.length >= 2 ? Integer.parseInt(parts[1]) : 20;
                int deleted = checkpoints.prune(keep);
                return "已修剪 " + deleted + " 个 checkpoint（保留最近 " + keep + " 个）";
            }
            List<CheckpointManager.Entry> entries = checkpoints.list();
            if (entries.isEmpty()) {
                return "暂无 checkpoint（write_file/exec/mvn_build 执行前会自动打快照）";
            }
            StringBuilder sb = new StringBuilder("checkpoints (" + entries.size() + ")\n");
            for (CheckpointManager.Entry e : entries) {
                sb.append(e.id.equals(lastCheckpointId) ? "* " : "  ")
                        .append(e.id).append("  ").append(e.time).append("  ")
                        .append(e.subject).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "checkpoint 操作失败: " + e.getMessage();
        }
    }

    /** {@code /memory [user|pending|forget [user]]} — 查看记忆三层 / 审批状态 / 清空。 */
    public String memoryManage(String args) {
        if (memoryStore == null) {
            return "未启用本地记忆";
        }
        String a = args == null ? "" : args.trim();
        boolean user = a.toLowerCase().contains("user");
        if (a.toLowerCase().startsWith("pending")) {
            ToolCall p = pendingConfirmation;
            return p == null ? "没有待审批的操作"
                    : "待审批: " + p.getName() + " " + p.getArgumentsJson() + "（/confirm 放行）";
        }
        if (a.toLowerCase().startsWith("forget")) {
            try {
                if (user) {
                    memoryStore.clearUser();
                } else {
                    memoryStore.clearMemory();
                }
                return "已清空 " + (user ? "USER" : "MEMORY") + ".md";
            } catch (Exception e) {
                return "清空失败: " + e.getMessage();
            }
        }
        String content = user ? memoryStore.readUser() : memoryStore.readMemory();
        return (user ? "USER.md:\n" : "MEMORY.md:\n") + (content.isEmpty() ? "（空）" : content);
    }

    /** 当前 cron 调度器（测试 / 高级用法可见）。未启用时返回 null。 */
    public CronScheduler getCronScheduler() {
        return cronScheduler;
    }

    /** {@code /cron [list|add <schedule> | <name> | <prompt>|remove|pause|resume <id>]}。 */
    public String cronManage(String args) {
        if (cronScheduler == null) {
            return "未启用 cron 调度（config 模式启动后可用）";
        }
        String a = args == null ? "" : args.trim();
        if (a.toLowerCase().startsWith("add")) {
            String rest = a.substring(3).trim();
            String[] parts = rest.split("\\|");
            if (parts.length < 3) {
                return "格式: /cron add <schedule> | <name> | <prompt>";
            }
            try {
                CronJob job = cronScheduler.add(parts[1].trim(), parts[2].trim(), parts[0].trim());
                return "已创建定时任务 " + job.id + " (" + job.schedule + ")";
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        if (a.toLowerCase().startsWith("remove") || a.toLowerCase().startsWith("pause")
                || a.toLowerCase().startsWith("resume")) {
            String[] parts = a.split("\\s+", 2);
            if (parts.length < 2) {
                return "格式: /cron " + parts[0] + " <id>";
            }
            String id = parts[1].trim();
            switch (parts[0].toLowerCase()) {
                case "remove":
                    return cronScheduler.remove(id) ? "已删除 " + id : "未找到任务: " + id;
                case "pause":
                    return cronScheduler.setEnabled(id, false) ? "已暂停 " + id : "未找到任务: " + id;
                default:
                    return cronScheduler.setEnabled(id, true) ? "已恢复 " + id : "未找到任务: " + id;
            }
        }
        List<CronJob> jobs = cronScheduler.list();
        if (jobs.isEmpty()) {
            return "暂无定时任务（/cron add every 5m | 名字 | 任务描述）";
        }
        StringBuilder sb = new StringBuilder("定时任务 (" + jobs.size() + ")\n");
        for (CronJob j : jobs) {
            sb.append("  ").append(j.id).append("  ").append(j.enabled ? "ON " : "OFF").append("  ")
                    .append(j.schedule).append("  ").append(j.name);
            if (!j.lastResult.isEmpty()) {
                sb.append("  最近: ").append(j.lastResult);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** {@code /rollback [id]} — 把沙箱恢复到指定快照，缺省最近一次。 */
    public String rollbackCheckpoint(String id) {
        if (checkpoints == null) {
            return "未启用 checkpoint（config 模式启动或经 builder 注入后可用）";
        }
        try {
            String target = checkpoints.rollback(id);
            lastCheckpointId = target;
            return "沙箱已回滚到 checkpoint " + target;
        } catch (Exception e) {
            return "回滚失败: " + e.getMessage();
        }
    }

    /** {@code /background <task>} — 异步委托子代理；{@code /background result <id>} 取回结果。 */
    public String submitBackground(String args) {
        if (delegation == null) {
            return "未启用子代理委托（agent.delegate.max.depth=0 或测试桩）";
        }
        return delegation.submitAsync(args == null ? "" : args.trim());
    }

    /** {@code /agents} — 查看异步委托台账。 */
    public String describeAgents() {
        if (delegation == null) {
            return "未启用子代理委托";
        }
        return delegation.describeAsync();
    }

    /** {@code /background result <id>} 的取回入口。 */
    public String backgroundResult(String id) {
        if (delegation == null) {
            return "未启用子代理委托";
        }
        return delegation.asyncResult(id);
    }

    /** delegate_task 管理器（null = 未启用）；测试与 /agents 渲染用。 */
    public DelegateManager getDelegation() {
        return delegation;
    }

    public void reset() {
        memory.clear();
        context.interrupt().reset();
        context.steer().clear();
        pendingConfirmation = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void shutdown() {
        shutDown = true;
        if (cronScheduler != null) {
            cronScheduler.stop();
        }
        if (mcpManager != null) {
            mcpManager.stopAll();
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
        // P27c：委托线程池跟着 agent 走 —— 修之前它是 `private static final`，全进程共用一口，
        // 任何 agent 的 shutdown() 都收不掉它（roadmap §W5 P27 记的那条"线程池不随生命周期
        // 关闭"，也是这份台账里唯一一条"记了未做"却没被写进代码的）。
        if (delegation != null) {
            delegation.shutdown();
        }
    }

    /** {@link #shutdown()} 是否已经跑过（异步委托 {@code finally} 里那句收口的可观测证据）。 */
    public boolean isShutDown() {
        return shutDown;
    }

    /** 本 agent 是否"没有可以等的人"（委托子代理为 true）。 */
    public boolean isNonInteractive() {
        return nonInteractive;
    }

    /** 被自动 deny 掉的审批次数（审计/测试对账）。 */
    public int subagentAutoDeniedApprovals() {
        return subagentAutoDenied.get();
    }

    public Toolkit getToolkit() {
        return toolkit;
    }

    public McpManager getMcpManager() {
        return mcpManager;
    }

    /** {@code /mcp [list|reload]} — 查看已注册 MCP 工具或热重载。 */
    public String mcpManage(String args) {
        if (mcpManager == null) {
            return "未启用 MCP（config 中 mcp.servers 为空时不会启动）";
        }
        String a = args == null ? "" : args.trim().toLowerCase();
        if (a.startsWith("reload")) {
            int total = mcpManager.reload();
            return "MCP reload 完成，共注册 " + total + " 个工具";
        }
        // default: list
        List<McpManager.BridgeStatus> snaps = mcpManager.snapshot();
        StringBuilder sb = new StringBuilder("MCP servers (" + snaps.size() + ")\n");
        for (McpManager.BridgeStatus s : snaps) {
            if (s.ok) {
                sb.append("- ").append(s.name).append(": OK, ")
                        .append(s.toolCount).append(" 工具 (")
                        .append(String.join(", ", s.registeredNames))
                        .append(")\n");
            } else {
                sb.append("- ").append(s.name).append(": ERROR, ").append(s.error).append('\n');
            }
        }
        return sb.toString().trim();
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * 给一个会话 id 派生新 agent：共享 provider / 工具 / sandbox / 上下文引擎 / checkpoint，
     * 独立 SessionManager（落在 {@code <configDir>/sessions/<id>/}）。
     * gateway / 通道层用 — 让每个会话拥有独立记忆。
     */
    public BotAgent forkFor(String conversationId) {
        File root = sessionManager == null ? null : sessionManager.getSessionDir().getParentFile();
        File convDir = root == null ? null : new File(root, conversationId);
        SessionManager newSm = convDir == null ? new SessionManager() : new SessionManager(convDir);
        return BotAgent.builder(config)
                .provider(provider)
                .sandbox(sandbox)
                .sessionManager(newSm)
                .model(model)
                .withoutCenter()
                .memoryStore(memoryStore)
                .skillsRoot(skillsRoot)
                .build();
    }

    public String getModel() {
        return model;
    }

    public String getProviderCode() {
        return providerCode;
    }

    /** 当前生效的 LlmProvider（含 Resilient/KeyPool 装饰层），供 HTTP 层取 listModels 等。 */
    public LlmProvider getProvider() {
        return provider;
    }

    public BotConfig getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return "BotAgent{provider='" + providerCode + "', model='" + model + "', tools=" + toolkit.size()
                + ", instanceCode=" + getInstanceCode() + "}";
    }

    // ===== 请求组装 =====

    private ChatCompletionsRequest buildRequest() {
        List<Msg> messages = new ArrayList<Msg>();
        messages.add(Msg.system(memory.getSystemPrompt()));
        messages.addAll(injectVolatileContext(memory.getMessages()));
        // 工具 schema 按 name 字典序 — 保持请求前缀稳定，提高 provider 侧 prompt-cache 命中
        List<com.zifang.z.agent.kernel.tool.Tool> tools =
                new ArrayList<com.zifang.z.agent.kernel.tool.Tool>(toolkit.getAllTools());
        tools.sort((a, b) -> String.valueOf(a.getName()).compareTo(String.valueOf(b.getName())));
        Map<String, Object> providerParams = new LinkedHashMap<String, Object>();
        if (!tools.isEmpty() && config != null && config.getToolChoice() != null
                && !config.getToolChoice().isEmpty()) {
            providerParams.put("tool_choice", config.getToolChoice());
        }
        return new ChatCompletionsRequest(model, messages, tools, temperature, null, maxTokens, false, providerParams);
    }

    /** 请求消息的总字符量 — 无 usage 网关时的上下文规模估算原料。 */
    private static long requestCharsOf(ChatCompletionsRequest request) {
        long chars = 0;
        for (Msg m : request.getMessages()) {
            chars += m.getContent() == null ? 0 : m.getContent().length();
        }
        return chars;
    }

    /**
     * system prompt 骨架：身份（SOUL + 人设）+ 工具面 + 执行规则。
     *
     * <p>只在构造期算一次，此后每轮逐字节重放。任何会在一轮之后改变的东西（长期记忆、
     * 技能指引、center 召回、当前时间）都不许进来 —— 它们一旦进 system prompt，
     * 同一会话第 2 轮请求的前缀就和第 1 轮不同，provider 侧的 prompt cache 直接击穿；
     * 更要紧的是「中途写盘不进 prompt」这件事根本没法保证。这些内容改道走
     * {@link #volatileContextBlock()}。</p>
     */
    private static String buildSystemPrompt(String base, Toolkit toolkit, MemoryStore store) {
        StringBuilder sb = new StringBuilder();
        String soul = store == null ? "" : store.readSoul();
        if (!soul.trim().isEmpty()) {
            // SOUL 属身份不属记忆（roadmap §2#9），所以它是骨架的一部分，但不随每轮重读
            sb.append(soul.trim()).append("\n\n");
        }
        sb.append(base == null || base.trim().isEmpty()
                ? "你是 z-bot，本地运行的 ReAct Agent，通过调用工具完成任务。\n" : base).append("\n");
        sb.append("可用工具：\n").append(toolkit.getToolsDescription()).append('\n');
        sb.append("执行规则：\n");
        sb.append("1. 收到用户消息后调用工具完成任务，不要只用文本描述工具调用\n");
        sb.append("2. 工具结果会自动注入对话，你不需要等待，直接继续下一步\n");
        sb.append("3. 工具成功就继续，失败就换方案\n");
        sb.append("4. 持续工作直到整个任务完成，不要中途停下\n");
        sb.append("5. 任务全部完成时，直接输出面向用户的最终答案\n");
        return sb.toString();
    }

    /**
     * 本轮的运行时上下文块：记忆 / 技能指引 / center 召回 / 时钟。
     *
     * <p>每轮在 {@code chat()} 入口算<b>一次</b>并存进 {@link #turnVolatileBlock}（同一轮多步之间
     * 逐字节相同，缓存前缀才不被自己击穿）。时钟始终在场，所以这个块永不为空；
     * 三条来源全空时只剩一行时间戳 + 分隔线。</p>
     *
     * <p>P12e：它的返回值<b>只</b>经 {@link #injectVolatileContext(List)} 贴到请求上，
     * 不再进 {@code memory}，因此也不会进 transcript / web content。</p>
     */
    private String volatileContextBlock() {
        StringBuilder body = new StringBuilder();
        String memory = memoryContextBlock(memoryStore);
        String skills = skillContextBlock(skillsRoot);
        String recall = centerRecallBlock(centerClient);
        body.append("当前时间: ").append(ZonedDateTime.now().format(CLOCK)).append('\n');
        if (!recall.isEmpty()) {
            body.append(recall);
        }
        if (!memory.isEmpty()) {
            body.append(memory);
        }
        if (!skills.isEmpty()) {
            body.append(skills);
        }
        return VOLATILE_CONTEXT_HEADER + "\n" + body.toString().trim() + "\n---\n";
    }

    /**
     * 动态上下文<b>唯一</b>的注入点（P12e）：只贴到本次请求<b>最后一行 user</b> 的开头，
     * 用户原话保持在最后（别让模板盖过正事）。
     *
     * <p>为什么是「最后一行 user」而不是「本轮那条原话」：同一轮里
     * {@link #maybeAnnounceGrace} 与 steer 退路（{@link #injectSteer}）还会各补一条 user 控制行，
     * 它们才是请求尾部。贴尾部保证<b>一次请求至多一块时钟</b>，历史行逐字是用户原话，
     * 于是 N 轮之后第 N 次请求里也只有 1 块「当前时间」（取证见 EVIDENCE §11.1）。</p>
     *
     * <p>不改入参：{@link ConversationMemory#getMessages()} 给的是快照，返回的是新列表。</p>
     */
    private List<Msg> injectVolatileContext(List<Msg> messages) {
        String block = turnVolatileBlock;
        if (block == null || block.isEmpty() || messages.isEmpty()) {
            return messages;
        }
        int tail = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == com.zifang.z.agent.kernel.types.MessageRole.USER) {
                tail = i;
                break;
            }
        }
        if (tail < 0) {
            return messages;
        }
        List<Msg> out = new ArrayList<Msg>(messages);
        out.set(tail, withVolatileContext(out.get(tail), block));
        return out;
    }

    /**
     * 把上下文块并进某一行消息的开头，除 content 外逐字段照抄（{@link Msg} 不可变，只能新建）。
     */
    private static Msg withVolatileContext(Msg message, String block) {
        String plain = message.getContent() == null ? "" : message.getContent();
        return new Msg(message.getRole(), message.getName(), block + plain, message.getType(),
                message.getToolCallId(), message.getToolCalls(), message.getMetadata());
    }

    /** 长期记忆两层（用户画像 + 记忆）；SOUL 不在这里（它在冻结的 system prompt 里）。 */
    static String memoryContextBlock(MemoryStore store) {
        if (store == null) {
            return "";
        }
        String user = store.readUser();
        String mem = store.readMemory();
        if (user.isEmpty() && mem.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("长期记忆（历史积累，供参考）：\n");
        if (!user.isEmpty()) {
            sb.append("[用户画像]\n").append(user).append('\n');
        }
        if (!mem.isEmpty()) {
            sb.append("[记忆]\n").append(mem).append('\n');
        }
        return sb.toString();
    }

    /**
     * 已安装技能的指引（最多 3 个、正文各截 600 字符），让模型能直接按技能干活。
     *
     * <p>P23：进这里的必须是过了门的技能（{@code platforms}/{@code environments} 不匹配的
     * 不进 prompt），并且要写明"为什么没进"，不许静默消失。</p>
     */
    static String skillContextBlock(File skillsRoot) {
        List<SkillLoader.Skill> all = SkillLoader.scan(skillsRoot);
        if (all.isEmpty()) {
            return "";
        }
        List<SkillLoader.Skill> skills = new java.util.ArrayList<SkillLoader.Skill>();
        List<String> hidden = new java.util.ArrayList<String>();
        for (SkillLoader.Skill s : all) {
            if (s.offerable()) {
                skills.add(s);
            } else {
                hidden.add(s.name + ": " + s.hiddenReason);
            }
        }
        if (skills.isEmpty()) {
            StringBuilder h = new StringBuilder("可用技能指引：\n");
            for (String s : hidden) {
                h.append("(未提供) ").append(s).append('\n');
            }
            return h.toString();
        }
        StringBuilder sb = new StringBuilder("可用技能指引：\n");
        int n = 0;
        for (SkillLoader.Skill s : skills) {
            if (n++ >= 3) {
                break;
            }
            sb.append("[").append(s.name).append("] ")
                    .append(s.description.isEmpty() ? "(无描述)" : s.description).append('\n');
            if (s.setupNote != null) {
                sb.append("(setup) ").append(s.setupNote).append('\n');
            }
            String body = s.body;
            if (body.length() > 600) {
                body = body.substring(0, 600) + "…";
            }
            if (!body.isEmpty()) {
                sb.append(body).append('\n');
            }
        }
        for (String s : hidden) {
            sb.append("(未提供) ").append(s).append('\n');
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────────── 技能 → 斜杠命令（P23）

    /** 本地技能根（{@code <configDir>/skills}），可能为 null。 */
    public File getSkillsRoot() {
        return skillsRoot;
    }

    /** 当前该被"供货"的本地技能：命令表与 prompt 共用这一份判定。 */
    public List<SkillLoader.Skill> offerableSkills() {
        return skillsRoot == null
                ? java.util.Collections.<SkillLoader.Skill>emptyList()
                : SkillLoader.scanOffers(skillsRoot);
    }

    /**
     * 真执行一次技能调用：把技能正文按 hermes 的注入形态拼成一条 user 消息，走 {@link #chat}。
     *
     * <p>技能正文里的指令文本只当语料，不当成对本进程的指令。</p>
     */
    public String invokeSkills(List<SkillLoader.Skill> skills, String instruction) {
        if (skills == null || skills.isEmpty()) {
            return "没有可执行的技能";
        }
        return chat(SkillCommands.buildInvocationMessage(skills, instruction));
    }

    /**
     * {@code /skill <name> [指令]} —— 显式加载。
     *
     * <p>它绕过 {@code environments} 相关性门（hermes: 显式加载就是显式同意），
     * 并把"它为什么没进命令表"照实说出来；缺前置时只降级、不丢技能。</p>
     */
    public String invokeSkillByName(String args) {
        String a = args == null ? "" : args.trim();
        if (a.isEmpty()) {
            return "格式: /skill <name> [指令]";
        }
        String name = a.split("\\s+", 2)[0];
        String instruction = a.length() > name.length() ? a.substring(name.length()).trim() : "";
        List<SkillLoader.Skill> all = skillsRoot == null
                ? java.util.Collections.<SkillLoader.Skill>emptyList()
                : SkillLoader.scan(skillsRoot);
        SkillLoader.Skill hit = null;
        for (SkillLoader.Skill s : all) {
            if (s.name.equalsIgnoreCase(name)
                    || SkillCommands.slug(s.slash).equalsIgnoreCase(SkillCommands.slug(name))
                    && !s.slash.isEmpty()) {
                hit = s;
                break;
            }
        }
        if (hit == null) {
            return "未找到技能: " + name + "（/skills 查看列表）";
        }
        String note = hit.offerable() ? "" : "提示: 该技能本被隐藏 —— " + hit.hiddenReason
                + "（显式加载绕过 environments/platforms 门）\n";
        String setup = hit.setupNote == null ? "" : "提示: " + hit.setupNote + "\n";
        return note + setup + invokeSkills(java.util.Collections.singletonList(hit), instruction);
    }

    /** center 下发的长期记忆召回；拿不到就空串（不阻塞本轮）。 */
    static String centerRecallBlock(BotCenterClient client) {
        if (client == null) {
            return "";
        }
        try {
            String recall = client.recallMemory();
            return recall == null || recall.isEmpty() ? "" : "center 召回：\n" + recall + '\n';
        } catch (Exception e) {
            LOG.debug("[BotAgent] recallMemory failed, fall through: {}", e.getMessage());
            return "";
        }
    }

    // ===== 参数与文本兜底解析 =====

    static Map<String, Object> parseArgs(String argumentsJson) {
        Map<String, Object> args = readArgs(argumentsJson);
        // __confirmed__ 是人 /confirm 放行后由 confirmTool 盖的章；这里吃进的全是模型自带参数，
        // 不剥掉就等于让模型自己写 {"command":"rm -rf x","__confirmed__":true} 跳过审批。
        args.remove(Confirmations.CONFIRMED_ARG);
        // 同理：__approval_resolution__ 只有人提交的 argsJson 才有效（confirmTool 走 readArgs 分支），
        // 模型自带就能把 once 伪造成 always，红线 7 不容。
        args.remove(Confirmations.RESOLUTION_ARG);
        return args;
    }

    private static Map<String, Object> readArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return new HashMap<String, Object>();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(argumentsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? new HashMap<String, Object>() : new HashMap<String, Object>(parsed);
        } catch (Exception e) {
            LOG.warn("Failed to parse tool arguments: {}", argumentsJson);
            return new HashMap<String, Object>();
        }
    }

    private static String finalAnswerOf(String argsJson, String fallback) {
        Map<String, Object> args = parseArgs(argsJson);
        Object answer = args.get("answer");
        if (answer != null) {
            return answer.toString();
        }
        return fallback == null ? "" : fallback;
    }

    /**
     * 弱模型把工具调用写成文本（{@code functions.exec({"command":"ls"})} / {@code call exec tool with args {...}}
     * / 只带工具名和引号参数）时，还原成真正的 {@link ToolCall}。
     */
    private ToolCall parseTextToolCall(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        ToolCall byPattern = matchToolCallPattern(TEXT_TOOL_CALL.matcher(content));
        if (byPattern == null) {
            byPattern = matchToolCallPattern(TEXT_TOOL_CALL_VERB.matcher(content));
        }
        if (byPattern != null && toolkit.contains(byPattern.getName())) {
            return byPattern;
        }
        return matchMentionedTool(content);
    }

    private static ToolCall matchToolCallPattern(Matcher m) {
        return m.find() ? new ToolCall("fb_" + System.currentTimeMillis(), m.group(1), m.group(2)) : null;
    }

    /**
     * 文本里点名了某个工具但没有 JSON 参数：按该工具 schema 的属性顺序，把引号内容映射回具名参数。
     */
    private ToolCall matchMentionedTool(String content) {
        for (com.zifang.z.agent.kernel.tool.Tool tool : toolkit.getAllTools()) {
            String name = tool.getName();
            if (!content.contains(name)) {
                continue;
            }
            Matcher q = QUOTED.matcher(content);
            List<String> quoted = new ArrayList<String>();
            while (q.find()) {
                quoted.add(q.group(1));
            }
            if (quoted.isEmpty()) {
                continue;
            }
            List<String> names = paramNames(tool);
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            for (int i = 0; i < quoted.size() && i < names.size(); i++) {
                args.put(names.get(i), quoted.get(i));
            }
            try {
                return new ToolCall("fb_" + System.currentTimeMillis(), name, JSON.writeValueAsString(args));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> paramNames(com.zifang.z.agent.kernel.tool.Tool tool) {
        Map<String, Object> schema = tool.getSchema();
        Object properties = schema == null ? null : schema.get("properties");
        List<String> names = new ArrayList<String>();
        if (properties instanceof Map) {
            for (Object key : ((Map<?, ?>) properties).keySet()) {
                names.add(String.valueOf(key));
            }
        }
        if (names.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                names.add("arg" + i);
            }
        }
        return names;
    }

    private Msg toolCallMsg(String toolName, String argsJson, String callId) {
        return new Msg(com.zifang.z.agent.kernel.types.MessageRole.ASSISTANT, null, argsJson,
                com.zifang.z.agent.kernel.message.MessageType.TOOL_CALL, callId,
                Collections.singletonList(new ToolCall(callId, toolName, argsJson)), Collections.emptyMap());
    }

    private void finish(StreamListener listener, String reply, int steps, ChatCompletionsResponse response) {
        Integer prompt = null;
        Integer completion = null;
        if (response != null && response.getUsage() != null) {
            prompt = (int) response.getUsage().getPromptTokens();
            completion = (int) response.getUsage().getCompletionTokens();
        }
        recordSessionUsage(steps, prompt, completion);
        listener.onEvent(new StreamEvent.FinalDelta(reply));
        listener.onEvent(new StreamEvent.Done(reply, steps, prompt, completion));
    }

    /** state.db 模式下给 session_model_usage 记一笔账（模型×任务）；JSON 模式是空操作。 */
    private void recordSessionUsage(int apiCalls, Integer promptTokens, Integer completionTokens) {
        if (sessionManager == null) {
            return;
        }
        StateStore store = sessionManager.getStore();
        if (store == null) {
            return;
        }
        String model = config == null ? null : config.getModel();
        // P26：cache 读/写命中随本笔账一起交给 store（列在位才落库，见 StateStore#recordUsage）。
        long cacheRead = takeAccumulated(CACHE_READ);
        long cacheWrite = takeAccumulated(CACHE_WRITE);
        store.recordUsage(sessionManager.getCurrentSessionId(), model, promptTokens, completionTokens, apiCalls,
                Long.valueOf(cacheRead), Long.valueOf(cacheWrite));
    }

    private static long takeAccumulated(java.util.concurrent.atomic.AtomicLong counter) {
        long v = counter.get();
        counter.set(0L);
        return v;
    }

    /** P26 降级链：换到 {@code toModel} 时重建在飞的 system 上下文（口径按新模型重算）。 */
    private com.zifang.z.agent.kernel.llm.ChatCompletionsRequest rebuildForModel(
            String fromModel, String toModel,
            com.zifang.z.agent.kernel.llm.ChatCompletionsRequest inFlight) {
        if (inFlight == null || toModel == null) {
            return inFlight;
        }
        // system 上下文按新模型重算：工具清单/记忆块口径不变，但系统提示里点名的是当前模型
        String systemPrompt = memory.getSystemPrompt();
        java.util.List<com.zifang.z.agent.kernel.message.Msg> msgs = new ArrayList<com.zifang.z.agent.kernel.message.Msg>();
        for (com.zifang.z.agent.kernel.message.Msg m : inFlight.getMessages()) {
            if (m == null) {
                continue;
            }
            if (com.zifang.z.agent.kernel.types.MessageRole.SYSTEM == m.getRole()
                    && systemPrompt != null && !systemPrompt.isEmpty()) {
                msgs.add(Msg.system(systemPrompt));
            } else {
                msgs.add(m);
            }
        }
        int maxTokens = inFlight.getMaxTokens() <= 0 ? configMaxTokens() : inFlight.getMaxTokens();
        LOG.warn("[BotAgent] 降级链 {} -> {}：system 上下文已重建（{} 条消息，maxTokens={}）",
                fromModel, toModel, msgs.size(), maxTokens);
        return new com.zifang.z.agent.kernel.llm.ChatCompletionsRequest(toModel, msgs, inFlight.getTools(),
                inFlight.getTemperature(), inFlight.getTopP(), maxTokens, inFlight.isStream(),
                inFlight.getProviderParams());
    }

    /** P26 降级链：换模型后清掉本侧按旧模型口径攒下的字符估算与 cache 累加。 */
    private void resetModelSwitchState(String fromModel, String toModel) {
        lastRequestChars = 0;
        CACHE_READ.set(0L);
        CACHE_WRITE.set(0L);
        // CompressorEngine 的 compressCount 没有对外重置口（context/ 归 w6-p14），
        // 这里只能把"换模型 ⇒ 旧压缩账作废"记进日志；接口需求见 _doc/005_testing/acceptance/p26/EVIDENCE.md §11。
        LOG.warn("[BotAgent] 换模型 {} -> {}：本侧字符估算已清零；压缩计数重置待 context/ 提供 reset 接口",
                fromModel, toModel);
    }

    private int configMaxTokens() {
        try {
            return config == null ? 0 : config.getMaxTokens();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ===== 装配 =====

    public static final class Builder {
        private final BotConfig config;
        private LlmProvider provider;
        private String providerCode;
        private String model;
        private String appCode;
        private Toolkit toolkit;
        private Sandbox sandbox;
        private SessionManager sessionManager;
        /** 覆盖默认 {@link CompressorEngine}；summarizer 需一起给。 */
        private CompressorEngine contextEngine;
        private ContextEngine.Summarizer summarizer;
        /** 纯测试桩关闭压缩（省一次摘要 LLM 调用）。 */
        private boolean noCompress;
        /** 影子 git checkpoint；config 模式缺省自动建在 {@code <configDir>/checkpoints/store}。 */
        private CheckpointManager checkpointManager;
        /** delegate_task 子代理管理器（build 时按深度创建并注册）。 */
        private DelegateManager delegation;
        /** 当前 agent 的委托深度：-1 = 未显式指定（config 模式默认按 0 处理）。 */
        private int delegateDepth = -1;
        /** 覆盖默认 IterationBudget（子代理注入父预算的 1/4 用）。 */
        private IterationBudget budgetOverride;
        /** 子代理禁接入 center，避免重复注册生命周期。 */
        private boolean noCenter;
        /** 没有可以等的人（委托子代理）：审批一律自动 deny，见 {@link #emitToolResult}。 */
        private boolean nonInteractive;
        /** 本地记忆三层；config 模式缺省建在 {@code <configDir>/memories}。 */
        private MemoryStore memoryStore;
        /** 本地技能根目录；config 模式缺省 {@code <configDir>/skills}。 */
        private File skillsRoot;
        /** cron 调度器；config 模式缺省建在 {@code <configDir>/cron}（60s tick，spawn 子代理执行）。 */
        private CronScheduler cronScheduler;
        /** MCP 客户端聚合；config 模式 + mcp.servers 非空时缺省建，测试可注入。 */
        private McpManager mcpManager;
        /** 每会话审批 FIFO；build 时缺省建一个（名单取自 config），测试可注入以复用同一队列。 */
        private ApprovalService approvalService;
        /** 关闭 MCP 自动装配（默认 true）。 */
        private boolean noMcp;
        private BotCenterClient centerClient;
        private int maxSteps;
        private int maxTokens;
        private long tokenBudget;
        private double temperature;
        private String systemPrompt;
        private boolean builtinTools = true;

        private Builder(BotConfig config) {
            this.config = config;
            this.providerCode = config == null ? "openai" : config.getActiveProviderCode();
            this.model = config == null ? "gpt-4o-mini" : config.getModel();
            this.maxSteps = config == null ? 50 : config.getMaxSteps();
            this.maxTokens = config == null ? 8192 : config.getMaxTokens();
            this.tokenBudget = config == null ? 400_000L : config.getTokenBudget();
            this.temperature = config == null ? 0.7 : config.getTemperature();
        }

        public Builder provider(LlmProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder providerCode(String providerCode) {
            this.providerCode = providerCode;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder appCode(String appCode) {
            this.appCode = appCode;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder sandbox(Sandbox sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public Builder sessionManager(SessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        public Builder contextEngine(CompressorEngine engine, ContextEngine.Summarizer summarizer) {
            this.contextEngine = engine;
            this.summarizer = summarizer;
            return this;
        }

        public Builder withoutCompressor() {
            this.noCompress = true;
            return this;
        }

        /** 覆盖默认 checkpoint 仓库位置（测试注入 TemporaryFolder 用）。 */
        public Builder checkpointManager(CheckpointManager checkpointManager) {
            this.checkpointManager = checkpointManager;
            return this;
        }

        /** 覆盖默认 IterationBudget（delegate 子代理注入父预算 1/4 用）。 */
        public Builder budget(IterationBudget budget) {
            this.budgetOverride = budget;
            return this;
        }

        /** 显式指定委托深度（root 测试注入 0；子代理装配时传父深度+1）。 */
        public Builder delegateDepth(int depth) {
            this.delegateDepth = depth;
            return this;
        }

        /** 禁接入 center（子代理用）。 */
        public Builder withoutCenter() {
            this.noCenter = true;
            return this;
        }

        /**
         * 声明"这个 agent 背后没有可以等的人"（委托子代理由 {@code DelegateManager} 盖上）。
         * 只改变**审批怎么裁决**（自动 deny 而不是挂起本回合），不放宽任何一档闸门：
         * {@code ExecGuard} 的红线档照旧拦，工具能不能跑、要不要问，判定逻辑一个字没动。
         */
        public Builder nonInteractive(boolean v) {
            this.nonInteractive = v;
            return this;
        }

        /** 本地技能根目录；测试注入用。 */
    public Builder skillsRoot(File skillsRoot) {
        this.skillsRoot = skillsRoot;
        return this;
    }

    /** 覆盖默认记忆目录（测试注入 TemporaryFolder 用）。 */
    public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        /** 注入现成 cron 调度器（测试用小 tick 注入）。 */
        public Builder cronScheduler(CronScheduler cronScheduler) {
            this.cronScheduler = cronScheduler;
            return this;
        }

        public Builder mcpManager(McpManager mcpManager) {
            this.mcpManager = mcpManager;
            return this;
        }

        /** 关闭 MCP 自动装配（仅用 config 时生效，{@link #mcpManager} 显式注入不受影响）。 */
        public Builder withoutMcp() {
            this.noMcp = true;
            return this;
        }

        public BotCenterClient centerClient() {
            return centerClient;
        }

        public Builder centerClient(BotCenterClient centerClient) {
            this.centerClient = centerClient;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /** 关掉内置工具注册（测试只想放自己的桩工具时用）。 */
        public Builder withoutBuiltinTools() {
            this.builtinTools = false;
            return this;
        }

        /**
         * 配置存在时给 provider 套装饰器；config 为 null（纯测试桩）不包。
         * 分层：内层 KeyPool（多 key 轮换，单 key 时原样）→ 外层 Resilient（重试+模型降级）。
         */
        private static LlmProvider wrapResilient(LlmProvider provider, BotConfig config) {
            if (config == null) {
                return provider;
            }
            LlmProvider pooled = KeyPoolLlmProvider.wrap(provider, config.activeProvider());
            // P26：退避/看门狗策略走自家 RetryPolicyConfig（llm.retry.* / llm.stream.*），
            // 底座指数档首值沿用 BotConfig 已有的 retry.backoff.ms，不抄第二份。
            return new ResilientLlmProvider(pooled, config.getRetryMaxAttempts(),
                    config.getRetryBackoffMs(), config.getFallbackModels(),
                    com.zifang.z.bot.llm.RetryPolicyConfig.of(config, null, config.getConfigDir()), null);
        }

        /**
         * 缺省审批服务：前缀白名单与 always 名单都取自 config，
         * ALWAYS 档落盘写回同一个 configDir（红线 1）。
         */
        private static ApprovalService newApprovalService(BotConfig config) {
            ApprovalService service = new ApprovalService(
                    config == null ? null : config.getExecConfirmWhitelist(),
                    config == null ? null : config.getExecAlwaysApprovals());
            if (config != null) {
                service.setPersistence(config::appendAlwaysApproval);
            }
            return service;
        }

        public BotAgent build() {
            if (toolkit == null) {
                toolkit = new Toolkit();
            }
            if (sandbox == null) {
                // 红线 1：沙箱根跟着 profile 走（--sandbox 已在 AgentOptions 里套好，这里只兜
                // builder 没被 CLI 装配的路径，并保留 -Dzbot.sandbox 覆盖）
                sandbox = new Sandbox(BotConfig.resolveWorkspaceDir(
                        config == null ? null : config.getConfigDir(), null).getAbsolutePath());
            }
            if (approvalService == null) {
                approvalService = newApprovalService(config);
            }
            if (builtinTools) {
                BuiltinTools.registerAll(toolkit, sandbox,
                        config == null ? null : config.getExecConfirmMode(),
                        config == null ? null : config.getExecConfirmWhitelist(),
                        approvalService);
            }
            if (sessionManager == null) {
                if (config != null && config.getStateDbPath() != null
                        && !config.getStateDbPath().trim().isEmpty()) {
                    // state.db 是唯一事实来源；会话 JSON 目录仍作一次性迁移入口
                    sessionManager = new SessionManager(
                            config.sessionsDir(),
                            new StateStore(new java.io.File(config.getStateDbPath().trim()),
                                    // P15：PRAGMA/重试窗口/BEGIN IMMEDIATE/坏库自愈/自动清理
                                    // 全部由 profile 的 config.properties 决定（CLI 侧读同一份）
                                    config.stateStoreOptions()));
                } else {
                    sessionManager = new SessionManager();
                }
            }
            if (centerClient == null && !noCenter && config != null && config.getCenterUrl() != null
                    && !config.getCenterUrl().isEmpty()) {
                centerClient = new BotCenterClient(config.getCenterUrl(), config.getConfigDir());
            }
            if (appCode == null && config != null) {
                appCode = config.getAppCode();
            }
            if (checkpointManager == null && config != null && config.getConfigDir() != null) {
                checkpointManager = new CheckpointManager(
                        new File(config.getConfigDir(), "checkpoints/store"), sandbox.root());
            }
            int maxDepth = config == null ? 2 : config.getDelegateMaxDepth();
            int d = delegateDepth >= 0 ? delegateDepth : (config != null ? 0 : -1);
            if (maxDepth > 0 && d >= 0 && d < maxDepth) {
                LlmProvider raw = provider != null ? provider : LlmRouter.create(config.activeProvider());
                File childSessions = config != null && config.getConfigDir() != null
                        ? new File(config.getConfigDir(), "delegate/children")
                        : new File(sandbox.root().getParentFile(), "delegate-children");
                delegation = new DelegateManager(config, raw, sandbox, childSessions, d, maxDepth);
                toolkit.register(delegation.delegateTool());
            }
            if (memoryStore == null && config != null && config.getConfigDir() != null) {
                memoryStore = new MemoryStore(new File(config.getConfigDir(), "memories"));
            }
            if (memoryStore != null) {
                try {
                    memoryStore.ensureSoul();
                } catch (Exception e) {
                    LOG.warn("[BotAgent] SOUL.md 生成失败: {}", e.getMessage());
                }
                toolkit.register(MemoryTools.memoryTool(memoryStore));
            }
            if (skillsRoot == null && config != null && config.getConfigDir() != null) {
                skillsRoot = new File(config.getConfigDir(), "skills");
            }
            if (cronScheduler == null && config != null && config.getConfigDir() != null) {
                final LlmProvider raw = provider != null ? provider : LlmRouter.create(config.activeProvider());
                final File cronSessions = new File(config.getConfigDir(), "cron/sessions");
                cronScheduler = new CronScheduler(new File(config.getConfigDir(), "cron"), 60,
                        new CronScheduler.TaskRunner() {
                            @Override
                            public String run(String prompt) {
                                BotAgent child = BotAgent.builder(config)
                                        .provider(raw)
                                        .sandbox(sandbox)
                                        .withoutCenter()
                                        .sessionManager(new SessionManager(cronSessions))
                                        .build();
                                try {
                                    return child.chat(prompt, StreamListener.NOOP);
                                } finally {
                                    child.shutdown();
                                }
                            }
                        });
                cronScheduler.start();
            }
            if (cronScheduler != null) {
                toolkit.register(CronTools.cronTool(cronScheduler));
            }
            if (mcpManager == null && config != null && !noMcp
                    && config.getMcpServers() != null && !config.getMcpServers().isEmpty()) {
                mcpManager = new McpManager(toolkit, config.getMcpServers());
                try {
                    mcpManager.startAll();
                } catch (RuntimeException e) {
                    LOG.warn("[BotAgent] MCP 装配失败: {}", e.getMessage());
                }
            }
            // 注意：center 召回不再折进 systemPrompt。它是每轮都可能变的东西，
            // 进 system prompt 就等于每轮击穿一次 prompt cache —— 改走 user 消息
            // （BotAgent#centerRecallBlock）。
            return new BotAgent(this);
        }
    }
}
