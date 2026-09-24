package com.zifang.z.bot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.center.BotLifecycle;
import com.zifang.z.bot.llm.LlmRouter;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private volatile boolean stopped;
    /** 被 {@link ToolConfirmationNeeded} 暂停的那次调用；确认后要用它回灌 tool 结果。 */
    private volatile ToolCall pendingConfirmation;

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
        this.provider = b.provider != null ? b.provider : LlmRouter.create(b.config.activeProvider());

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
     * @return 最终回复；需确认时为 {@code WAIT_CONFIRM:tool|args|reason}；达到步数上限时为提示文案
     */
    public String chat(String userMessage, StreamListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("BotAgent 正在运行中");
        }
        StreamListener l = listener == null ? StreamListener.NOOP : listener;
        stopped = false;
        pendingConfirmation = null;
        memory.add(Msg.user(userMessage));
        try {
            String reply = runReActLoop(userMessage, l);
            persistSession();
            firePostChat(userMessage, reply);
            return reply;
        } catch (ToolConfirmationNeeded e) {
            String wait = WAIT_CONFIRM_PREFIX + e.getToolName() + "|" + e.getToolArgs() + "|" + e.getReason();
            persistSession();
            return wait;
        } finally {
            running.set(false);
        }
    }

    private String runReActLoop(String userMessage, StreamListener listener) {
        for (int step = 1; step <= maxSteps; step++) {
            listener.onEvent(new StreamEvent.StepStart(step));
            if (stopped) {
                return "已中止。";
            }
            ChatCompletionsResponse response;
            try {
                response = provider.chat(buildRequest());
            } catch (Exception e) {
                LOG.warn("[BotAgent] step {} LLM 调用失败: {}", step, e.getMessage());
                listener.onEvent(new StreamEvent.ErrorEvent(e));
                return "Error: " + e.getMessage();
            }

            Msg assistant = response.toAssistantMsg();
            if (!assistant.getToolCalls().isEmpty()) {
                if (assistant.getContent() != null && !assistant.getContent().trim().isEmpty()) {
                    listener.onEvent(new StreamEvent.ThoughtDelta(assistant.getContent()));
                }
                memory.add(assistant);
                String terminal = null;
                for (ToolCall tc : assistant.getToolCalls()) {
                    String name = tc.getName() == null ? "?" : tc.getName();
                    String argsJson = tc.getArgumentsJson() == null ? "{}" : tc.getArgumentsJson();
                    if ("final_answer".equals(name) || "final".equals(name)) {
                        terminal = finalAnswerOf(argsJson, assistant.getContent());
                        memory.add(Msg.assistant(terminal));
                        break;
                    }
                    executeToolCall(tc, name, argsJson, listener);
                }
                if (terminal != null) {
                    finish(listener, terminal, step, response);
                    return terminal;
                }
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
        String exhausted = "已达到最大步数限制(" + maxSteps + ")，请简化您的请求。";
        listener.onEvent(new StreamEvent.Done(exhausted, maxSteps, null, null));
        return exhausted;
    }

    private void executeToolCall(ToolCall tc, String name, String argsJson, StreamListener listener) {
        Map<String, Object> args = parseArgs(argsJson);
        listener.onEvent(new StreamEvent.ToolCallRequest(name, args, argsJson));
        String callId = tc.getId() == null || tc.getId().isEmpty()
                ? ("call_" + name + "_" + System.currentTimeMillis()) : tc.getId();
        ToolResult result = toolkit.execute(name, args);
        if (Confirmations.isRequired(result)) {
            pendingConfirmation = new ToolCall(callId, name, argsJson);
            listener.onEvent(new StreamEvent.ToolResult(name, null, Confirmations.reason(result), false));
            throw new ToolConfirmationNeeded(name, argsJson, Confirmations.reason(result));
        }
        String output = result.getContent() == null ? "" : result.getContent();
        memory.add(Msg.toolResult(callId, output));
        listener.onEvent(new StreamEvent.ToolResult(name, output,
                result.isError() ? output : null, !result.isError()));
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
        ToolResult result = toolkit.execute(toolName, args);
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

    public void stop() {
        stopped = true;
    }

    public void reset() {
        memory.clear();
        stopped = false;
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
        List<com.zifang.z.agent.kernel.tool.Tool> tools = toolkit.getAllTools();
        Map<String, Object> providerParams = new LinkedHashMap<String, Object>();
        if (!tools.isEmpty() && config != null && config.getToolChoice() != null
                && !config.getToolChoice().isEmpty()) {
            providerParams.put("tool_choice", config.getToolChoice());
        }
        return new ChatCompletionsRequest(model, messages, tools, temperature, null, maxTokens, false, providerParams);
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
        listener.onEvent(new StreamEvent.FinalDelta(reply));
        listener.onEvent(new StreamEvent.Done(reply, steps, prompt, completion));
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
        private BotCenterClient centerClient;
        private int maxSteps;
        private int maxTokens;
        private double temperature;
        private String systemPrompt;
        private boolean builtinTools = true;

        private Builder(BotConfig config) {
            this.config = config;
            this.providerCode = config == null ? "openai" : config.getActiveProviderCode();
            this.model = config == null ? "gpt-4o-mini" : config.getModel();
            this.maxSteps = config == null ? 50 : config.getMaxSteps();
            this.maxTokens = config == null ? 8192 : config.getMaxTokens();
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
                        config == null ? null : config.getExecConfirmMode());
            }
            if (sessionManager == null) {
                sessionManager = new SessionManager();
            }
            if (centerClient == null && config != null && config.getCenterUrl() != null
                    && !config.getCenterUrl().isEmpty()) {
                centerClient = new BotCenterClient(config.getCenterUrl());
            }
            if (appCode == null && config != null) {
                appCode = config.getAppCode();
            }
            if (systemPrompt == null && centerClient != null) {
                systemPrompt = augmentWithLongTermMemory(null, centerClient);
            }
            return new BotAgent(this);
        }
    }
}
