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
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.center.BotLifecycle;
import com.zifang.z.bot.checkpoint.CheckpointManager;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.delegate.DelegateManager;
import com.zifang.z.bot.llm.LlmRouter;
import com.zifang.z.bot.llm.ResilientLlmProvider;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

    public static final String BOT_VERSION = "z-bot/0.2.0";

    /** 需要人工确认时 {@link #chat} 的返回值前缀，后接 {@code tool|args|reason}。 */
    public static final String WAIT_CONFIRM_PREFIX = "WAIT_CONFIRM:";

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
     * kernel 0.2.0 的显式运行状态：预算 / 中断 / steer 队列都在这里。
     * 中断是协作式的 — 只在迭代与工具边界经 {@code checkpoint()} 生效。
     */
    private final AgentContext context;
    /** 被 {@link ToolConfirmationNeeded} 暂停的那次调用；确认后要用它回灌 tool 结果。 */
    private volatile ToolCall pendingConfirmation;
    /** 最近一次请求的消息字符量（网关不回 usage 时给压缩引擎做上下文估算）。 */
    private volatile long lastRequestChars;
    /** 上下文压缩引擎（null = 未启用，如纯测试桩）；摘要走主 provider。 */
    private final CompressorEngine compressor;
    private final ContextEngine.Summarizer summarizer;
    /** 影子 git checkpoint（null = 未启用）；破坏性工具执行前打快照。 */
    private final CheckpointManager checkpoints;
    /** 最近一次生效的快照 id，/rollback 不带参数时回滚到它。 */
    private volatile String lastCheckpointId;
    /** delegate_task 子代理管理器（null = 未启用委托）。 */
    private final DelegateManager delegation;

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
        this.memory = new ConversationMemory(buildSystemPrompt(b.systemPrompt, b.toolkit));
        this.context = AgentContext.root(b.budgetOverride != null
                ? b.budgetOverride : new IterationBudget(b.maxSteps, b.tokenBudget));
        this.provider = b.provider != null ? Builder.wrapResilient(b.provider, b.config)
                : Builder.wrapResilient(LlmRouter.create(b.config.activeProvider()), b.config);
        if (b.contextEngine != null) {
            this.compressor = b.contextEngine;
            this.summarizer = b.summarizer;
        } else if (b.config != null && !b.noCompress) {
            this.compressor = new CompressorEngine(b.tokenBudget);
            this.summarizer = this::summarizeWithProvider;
        } else {
            this.compressor = null;
            this.summarizer = null;
        }
        this.checkpoints = b.checkpointManager;
        this.delegation = b.delegation;
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
        memory.add(Msg.user(mergeQueued(userMessage)));
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
        while (budget.canCall()) {
            maybeAnnounceGrace(budget);
            budget.onCall();
            step++;
            listener.onEvent(new StreamEvent.StepStart(step));
            context.interrupt().checkpoint();
            injectSteer(listener);
            applyCompression(listener);
            ChatCompletionsResponse response;
            try {
                ChatCompletionsRequest request = buildRequest();
                lastRequestChars = requestCharsOf(request);
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
                executeBatch(assistant.getToolCalls(), listener);
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
                + ", tokens=" + budget.tokensUsed() + "/" + budget.maxTokens() + ")，请简化您的请求。";
        listener.onEvent(new StreamEvent.Done(exhausted, budget.apiCalls(), null, null));
        return exhausted;
    }

    /** 预算耗尽走 grace 通道时给模型的收尾指令（对齐 hermes 的 grace call）。 */
    private void maybeAnnounceGrace(IterationBudget budget) {
        boolean underLimits = budget.apiCalls() < budget.maxIterations()
                && budget.tokensUsed() < budget.maxTokens();
        if (!underLimits) {
            memory.add(Msg.user("[system] 预算即将耗尽(steps=" + budget.apiCalls() + "/" + budget.maxIterations()
                    + ", tokens=" + budget.tokensUsed() + "/" + budget.maxTokens()
                    + ")，这是最后一次收尾机会：请立即总结当前进展并直接给出面向用户的最终答案，不要再调用工具。"));
        }
    }

    private void recordUsage(IterationBudget budget, ChatCompletionsResponse response) {
        // kernel 会把缺失的 usage 归一成 TokenUsage.empty()（全 0），所以不能只判 != null，
        // 否则网关不回传 usage 时（如本地 bench 代理）估算分支永远进不去、压缩阈值永远不触发。
        boolean hasUsage = response != null && response.getUsage() != null
                && response.getUsage().getPromptTokens() > 0;
        if (hasUsage) {
            int prompt = (int) response.getUsage().getPromptTokens();
            int completion = (int) response.getUsage().getCompletionTokens();
            budget.recordTokens(prompt, completion);
            if (compressor != null) {
                compressor.update(prompt, completion);
            }
            return;
        }
        if (response != null && response.getUsage() != null) {
            budget.recordTokens((int) response.getUsage().getPromptTokens(),
                    (int) response.getUsage().getCompletionTokens());
        }
        // 网关不回传 usage 时按请求体字符数粗估上下文规模喂给压缩引擎
        if (compressor != null && lastRequestChars > 0) {
            compressor.update((int) (lastRequestChars / 2), 0);
        }
    }

    /**
     * 达到阈值就把中段历史压成一条摘要消息（保最近 N 条原文），压缩结果回灌记忆。
     * 压缩锁在 {@link CompressorEngine} 里，这里只负责回写与事件。
     */
    private void applyCompression(StreamListener listener) {
        if (compressor == null || !compressor.shouldCompress()) {
            return;
        }
        List<Msg> history = memory.getMessages();
        List<Msg> compressed = compressor.compress(history, summarizer);
        if (compressed == history || compressed.size() >= history.size()) {
            return;
        }
        memory.load(compressed);
        listener.onEvent(new StreamEvent.Compacted(compressor.getLastSummary(),
                compressor.getLastFromCount(), compressor.getLastToCount()));
        LOG.info("[BotAgent] 上下文压缩: {} -> {} 条 (累计 {} 次)",
                compressor.getLastFromCount(), compressor.getLastToCount(), compressor.getCompressCount());
        persistSession();
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

    /** 运行中排队的用户插话在工具间隙注入消息流（/steer 语义）。 */
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
        memory.add(Msg.user(sb.toString()));
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
            pendingConfirmation = effectiveCall;
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
                futures.add(pool.submit(new Callable<ToolResult>() {
                    @Override
                    public ToolResult call() {
                        return toolkit.execute(tc.getName(), args);
                    }
                }));
            }
            for (int i = 0; i < calls.size(); i++) {
                ToolCall tc = calls.get(i);
                String callId = tc.getId() == null || tc.getId().isEmpty()
                        ? ("call_" + tc.getName() + "_" + System.currentTimeMillis() + "_" + i) : tc.getId();
                ToolResult result;
                try {
                    result = futures.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    result = ToolResult.failure(callId, tc.getName(),
                            "工具执行失败: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
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
     */
    public String confirmTool(String toolName, String argsJson) {
        Map<String, Object> args = parseArgs(argsJson == null ? "{}" : argsJson);
        args.put(Confirmations.CONFIRMED_ARG, Boolean.TRUE);
        String ck = snapshotBefore(toolName);
        ToolResult result = toolkit.execute(toolName, args);
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
            memory.add(toolCallMsg(toolName, argsJson == null ? "{}" : argsJson, callId));
        }
        memory.add(Msg.toolResult(callId, output));
        persistSession();
        return result.isError() ? ("Error: " + output) : output;
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
        }
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

    /** 本地已安装 skill：扫 {@code ~/.zbot/skills/<instanceCode>/} 一级子目录。 */
    public List<String> listInstalledSkills() {
        if (centerClient == null || centerClient.getInstanceCode() == null) {
            return Collections.emptyList();
        }
        File root = centerClient.skillsRoot();
        File[] children = root == null ? null : root.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<String> codes = new ArrayList<String>();
        for (File c : children) {
            if (c.isDirectory()) {
                codes.add(c.getName());
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
     * 协作式软中断：置位 kernel {@link InterruptFlag}，主循环在最近的迭代/工具边界停下，不强杀线程。
     */
    public void stop() {
        context.interrupt().request("用户请求停止");
    }

    public boolean isStopRequested() {
        return context.interrupt().isInterrupted();
    }

    /**
     * 用户插话：agent 运行中时在工具间隙注入（/steer 语义）；空闲时入队，
     * 下次 {@link #chat} 开头并入用户消息（/queue 语义）。
     */
    public void steer(String message) {
        context.steer().add(message);
    }

    /** kernel 显式运行状态（预算 / 中断 / steer 队列），供 /usage 与子代理派生使用。 */
    public AgentContext context() {
        return context;
    }

    /** {@code /compress [preview]} — 查看或手动触发上下文压缩。 */
    public String compressNow(String args) {
        if (compressor == null) {
            return "未启用上下文压缩引擎";
        }
        boolean preview = args != null && args.toLowerCase().contains("preview");
        if (preview) {
            return "context tokens=" + compressor.getContextTokens() + "/" + compressor.getMaxTokens()
                    + " shouldCompress=" + compressor.shouldCompress()
                    + " 已压缩次数=" + compressor.getCompressCount()
                    + "（输入 /compress 执行压缩）";
        }
        List<Msg> history = memory.getMessages();
        List<Msg> out = compressor.forceCompress(history, summarizer);
        if (out == history || out.size() >= history.size()) {
            return "没有可压缩的内容（历史太短或摘要为空）";
        }
        memory.load(out);
        persistSession();
        return "已压缩: " + history.size() + " -> " + out.size() + " 条 (累计 "
                + compressor.getCompressCount() + " 次)";
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
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    public Toolkit getToolkit() {
        return toolkit;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public String getModel() {
        return model;
    }

    public String getProviderCode() {
        return providerCode;
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
        messages.addAll(memory.getMessages());
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

    private static String buildSystemPrompt(String base, Toolkit toolkit) {
        StringBuilder sb = new StringBuilder();
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

    /** 默认身份 + center 长期记忆前缀。 */
    static String augmentWithLongTermMemory(String base, BotCenterClient client) {
        if (client == null) {
            return base;
        }
        try {
            String recall = client.recallMemory();
            return recall == null || recall.isEmpty() ? base : recall + "\n---\n\n" + base;
        } catch (Exception e) {
            LOG.debug("[BotAgent] recallMemory failed, fall through: {}", e.getMessage());
            return base;
        }
    }

    // ===== 参数与文本兜底解析 =====

    static Map<String, Object> parseArgs(String argumentsJson) {
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
        store.recordUsage(sessionManager.getCurrentSessionId(), model, promptTokens, completionTokens, apiCalls);
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

        /** 配置存在时给 provider 套上重试/降级装饰器；config 为 null（纯测试桩）不包。 */
        private static LlmProvider wrapResilient(LlmProvider provider, BotConfig config) {
            if (config == null) {
                return provider;
            }
            return new ResilientLlmProvider(provider, config.getRetryMaxAttempts(),
                    config.getRetryBackoffMs(), config.getFallbackModels());
        }

        public BotAgent build() {
            if (toolkit == null) {
                toolkit = new Toolkit();
            }
            if (sandbox == null) {
                sandbox = new Sandbox(System.getProperty("zbot.sandbox",
                        System.getProperty("user.home") + "/.zbot/workspace"));
            }
            if (builtinTools) {
                BuiltinTools.registerAll(toolkit, sandbox,
                        config == null ? null : config.getExecConfirmMode(),
                        config == null ? null : config.getExecConfirmWhitelist());
            }
            if (sessionManager == null) {
                if (config != null && config.getStateDbPath() != null
                        && !config.getStateDbPath().trim().isEmpty()) {
                    // state.db 是唯一事实来源；会话 JSON 目录仍作一次性迁移入口
                    sessionManager = new SessionManager(
                            new java.io.File(System.getProperty("user.home") + "/.zbot/sessions"),
                            new StateStore(new java.io.File(config.getStateDbPath().trim())));
                } else {
                    sessionManager = new SessionManager();
                }
            }
            if (centerClient == null && !noCenter && config != null && config.getCenterUrl() != null
                    && !config.getCenterUrl().isEmpty()) {
                centerClient = new BotCenterClient(config.getCenterUrl());
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
            if (systemPrompt == null && centerClient != null) {
                systemPrompt = augmentWithLongTermMemory(null, centerClient);
            }
            return new BotAgent(this);
        }
    }
}
