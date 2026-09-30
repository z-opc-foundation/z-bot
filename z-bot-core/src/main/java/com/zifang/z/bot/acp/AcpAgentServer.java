package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.session.SessionManager;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * z-bot 的 ACP agent 面：把 {@link AcpMethods#AGENT_METHODS} 里协议方法接成真处理路径。
 *
 * <h2>方法集从哪来（不是工单那几个字面量计数）</h2>
 * 权威出处 = hermes 依赖的 SDK 派发表 {@code acp/meta.py:3—17}（13 条 agent 面方法），
 * 复算过程与逐行出处见 {@code _doc/005_testing/acceptance/p25/EVIDENCE.md} §0.2。本期：
 * <ul>
 *   <li><b>12 条真实现</b>：{@code initialize}、{@code authenticate}、{@code session/new}、
 *       {@code session/load}、{@code session/resume}、{@code session/list}、
 *       {@code session/prompt}、{@code session/cancel}（通知）、{@code session/close}、
 *       {@code session/set_mode}、{@code session/set_model}、{@code session/set_config_option}；</li>
 *   <li><b>1 条大声未实现</b>：{@code session/fork} —— 需要"复制历史到新会话"的落盘原语，
 *       {@code BotAgent.forkFor}（{@code agent/BotAgent.java:1228}）只派生空会话、不搬历史，
 *       照它做出来的是假 fork（见 §未做）；</li>
 *   <li><b>表外方法</b>：一律 {@code -32601} + {@code data.knownMethods}，见
 *       {@link AcpConnection} 的分派缺省分支。</li>
 * </ul>
 *
 * <p>{@code session/prompt} 在独立线程上跑：ACP 客户端会在 prompt 在飞时继续发
 * {@code session/cancel} 与 {@code session/request_permission} 的<b>应答</b>，
 * 若 prompt 占住读取线程，那些帧就永远进不来（她 {@code server.py:1468} 的
 * {@code _run_agent} 同样是丢进线程池）。回包顺序由 {@link AcpConnection} 的写锁保证。</p>
 */
public final class AcpAgentServer {

    /** z-bot 版本串：与 {@code BotAgent.BOT_VERSION} 同源（{@code agent/BotAgent.java:78}）。 */
    public static final String AGENT_NAME = "z-bot";

    private final AcpConnection conn;
    private final AcpSessionRegistry registry;
    private final AcpApprovalBridge bridge;
    private final Executor promptExecutor;
    private final List<String> advertisedAuthMethods;
    private final List<String> availableModeIds;
    private final List<String> availableModelIds;
    private final boolean offerAlways;
    private final AtomicInteger inFlightPrompts = new AtomicInteger();

    private volatile boolean initialized;
    private volatile String clientInfo = "unknown";

    public AcpAgentServer(AcpConnection conn,
                          AcpSessionRegistry registry,
                          AcpApprovalBridge bridge,
                          List<String> advertisedAuthMethods,
                          List<String> availableModeIds,
                          List<String> availableModelIds) {
        this(conn, registry, bridge, advertisedAuthMethods, availableModeIds, availableModelIds,
                true, null);
    }

    public AcpAgentServer(AcpConnection conn,
                          AcpSessionRegistry registry,
                          AcpApprovalBridge bridge,
                          List<String> advertisedAuthMethods,
                          List<String> availableModeIds,
                          List<String> availableModelIds,
                          boolean offerAlways) {
        this(conn, registry, bridge, advertisedAuthMethods, availableModeIds, availableModelIds,
                offerAlways, null);
    }

    public AcpAgentServer(AcpConnection conn,
                          AcpSessionRegistry registry,
                          AcpApprovalBridge bridge,
                          List<String> advertisedAuthMethods,
                          List<String> availableModeIds,
                          List<String> availableModelIds,
                          boolean offerAlways,
                          Executor promptExecutor) {
        if (conn == null || registry == null || bridge == null) {
            throw new IllegalArgumentException("conn / registry / bridge 都不能为 null");
        }
        this.conn = conn;
        this.registry = registry;
        this.bridge = bridge;
        this.advertisedAuthMethods = nonNull(advertisedAuthMethods);
        this.availableModeIds = nonNull(availableModeIds);
        this.availableModelIds = nonNull(availableModelIds);
        this.offerAlways = offerAlways;
        this.promptExecutor = promptExecutor == null ? defaultExecutor() : promptExecutor;
    }

    /** 把全部协议方法挂到连接上（注册完即可 {@link AcpConnection#serveStdio}）。 */
    public AcpAgentServer register() {
        conn.onRequest(AcpMethods.INITIALIZE, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return initialize(msg.params());
            }
        });
        conn.onRequest(AcpMethods.AUTHENTICATE, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return authenticate(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_NEW, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return newSession(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_LOAD, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return loadSession(msg.params(), false);
            }
        });
        conn.onRequest(AcpMethods.SESSION_RESUME, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return loadSession(msg.params(), true);
            }
        });
        conn.onRequest(AcpMethods.SESSION_LIST, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return listSessions(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_PROMPT, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                startPrompt(c, msg);
                return AcpConnection.ACP_ASYNC; // 工作线程回包
            }
        });
        conn.onRequest(AcpMethods.SESSION_SET_MODE, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return setSessionMode(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_SET_MODEL, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return setSessionModel(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_SET_CONFIG_OPTION, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return setConfigOption(msg.params());
            }
        });
        conn.onRequest(AcpMethods.SESSION_CLOSE, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return closeSession(msg.params());
            }
        });
        // 已知但本期不实现：也要"大声"，不能让客户端以为成功了。
        conn.onRequest(AcpMethods.SESSION_FORK, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                throw AcpProtocolException.notImplemented(AcpMethods.SESSION_FORK,
                        "需要「复制历史到新会话」的落盘原语；BotAgent.forkFor 只派生空会话，"
                                + "照它做会给出一个看不到父会话历史的假 fork（见 EVIDENCE §未做）");
            }
        });
        // session/cancel 在 ACP 里是通知（客户端不等回包）。
        conn.onNotification(AcpMethods.SESSION_CANCEL, new AcpConnection.AcpNotificationHandler() {
            @Override
            public void handle(AcpConnection c, JsonRpc.Message msg) {
                cancel(msg.params());
            }
        });
        return this;
    }

    public AcpSessionRegistry registry() {
        return registry;
    }

    public AcpConnection connection() {
        return conn;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int inFlightPrompts() {
        return inFlightPrompts.get();
    }

    // ---- initialize / authenticate ----

    private JsonNode initialize(JsonNode params) {
        int requested = params.path("protocolVersion").asInt(AcpMethods.PROTOCOL_VERSION);
        if (requested != AcpMethods.PROTOCOL_VERSION) {
            // 版本不合就明说，别装成能聊（她 SDK 的 initialize 同样只回自己的版本）。
            throw new AcpProtocolException(JsonRpc.INVALID_PARAMS,
                    "unsupported protocolVersion " + requested + "，z-bot 实现 v"
                            + AcpMethods.PROTOCOL_VERSION + "（acp/meta.py:29）", null, null);
        }
        JsonNode clientInfo = params.path("clientInfo");
        clientInfo = clientInfo.isTextual() ? clientInfo : clientInfo.path("name");
        clientInfo = clientInfo.isTextual() ? clientInfo : JsonRpc.mapper().nullNode();
        this.clientInfo = clientInfo.isTextual() ? clientInfo.asText() : "unknown";
        this.initialized = true;

        ObjectNode result = JsonRpc.object();
        result.put("protocolVersion", AcpMethods.PROTOCOL_VERSION);
        ObjectNode info = JsonRpc.object();
        info.put("name", AGENT_NAME);
        info.put("version", com.zifang.z.bot.agent.BotAgent.BOT_VERSION);
        result.set("agentInfo", info);
        result.set("agentCapabilities", capabilities());
        if (!advertisedAuthMethods.isEmpty()) {
            ArrayNode methods = result.putArray("authMethods");
            for (String id : advertisedAuthMethods) {
                ObjectNode m = JsonRpc.object();
                m.put("id", id);
                m.put("name", "z-bot credential from config.properties");
                methods.add(m);
            }
        }
        return result;
    }

    /**
     * 能力声明只报真做得到的：{@code fork} 不声明（与 {@link #register()} 里那条
     * {@code notImplemented} 一致），否则客户端会照着能力表去调一个必然失败的方法。
     */
    private ObjectNode capabilities() {
        ObjectNode caps = JsonRpc.object();
        caps.put("loadSession", true);
        ObjectNode promptCaps = JsonRpc.object();
        promptCaps.put("image", false);
        promptCaps.put("audio", false);
        caps.set("promptCapabilities", promptCaps);
        ObjectNode sessionCaps = JsonRpc.object();
        sessionCaps.set("list", JsonRpc.object());
        sessionCaps.set("resume", JsonRpc.object());
        caps.set("sessionCapabilities", sessionCaps);
        return caps;
    }

    private JsonNode authenticate(JsonNode params) {
        String methodId = params.path("methodId").asText("");
        if (methodId.isEmpty()) {
            throw AcpProtocolException.invalidParams("authenticate 需要 methodId（取自 initialize.authMethods）");
        }
        if (!advertisedAuthMethods.contains(methodId)) {
            // 她 server.py:899—920 同样只认自己广告过的方法，其余一律不应答成功。
            throw new AcpProtocolException(JsonRpc.INVALID_PARAMS,
                    "unknown methodId: " + methodId + "；已广告的认证方式=" + advertisedAuthMethods,
                    null, null);
        }
        return JsonRpc.object();
    }

    // ---- 会话 ----

    private JsonNode newSession(JsonNode params) {
        String cwd = params.path("cwd").asText("");
        AcpSessionRegistry.AcpSession session = registry.create(cwd);
        ObjectNode result = JsonRpc.object();
        result.put("sessionId", session.acpSessionId());
        attachSessionMeta(result, session);
        advertiseCommands(session);
        return result;
    }

    /**
     * P19 单源的 ACP 消费端：会话建立/恢复之后，把 {@code CommandCatalog} 的
     * {@link CommandCatalog.Endpoint#ACP} 那一段用 {@code available_commands_update} 帧广告出去。
     *
     * <p>时机对齐 hermes {@code acp_adapter/server.py:1122/1170/1205}（{@code new}/{@code load}/
     * {@code resume} 三处都调 {@code _schedule_available_commands_update}）—— fork 那第四处我们没实现，
     * 所以也没有可广告的会话；见 {@code AcpMethods.SESSION_FORK} 的 notImplemented。
     * 帧本身排在回包之后，见 {@link AcpConnection#scheduleAfterResponse}。</p>
     */
    private void advertiseCommands(AcpSessionRegistry.AcpSession session) {
        // 只对已 initialize 的连接广告：hermes 那两个方法体第一行都是 `if not self._conn: return`
        // （acp_adapter/server.py:1716 / :1736），而她的 _conn 是 initialize 之后才拿到的 client
        // （:524/:530）—— "没握手就没有通知"是她的既有口径。未握手就推通知在协议上也不成立。
        if (!initialized) {
            return;
        }
        final AcpStreamPublisher publisher = new AcpStreamPublisher(conn, session.acpSessionId());
        conn.scheduleAfterResponse(new Runnable() {
            @Override
            public void run() {
                publisher.sendAvailableCommands();
            }
        });
    }

    /**
     * {@code session/load} 与 {@code session/resume}：两种句柄都收，语义不同。
     *
     * <ul>
     *   <li>在线 ACP 句柄 ⇒ 直接复用；</li>
     *   <li>落盘 z-bot 会话 id ⇒ 显式恢复（重开 target + 回灌历史）；</li>
     *   <li>都不是 ⇒ {@link AcpSessionRegistry} 抛 {@code -32602}，文案点名"ACP 会话 id 不跨进程"
     *       并列出可恢复的 z-bot 会话 id。这是工单 §1.3 要的"大声拒绝"，不是静默新建。</li>
     * </ul>
     *
     * <p>ACP 规定 {@code session/load} 必须在响应<b>之前</b>把历史灌完
     * （她 {@code server.py:1196—1204} 的注释就是为此），这里同构。</p>
     */
    private JsonNode loadSession(JsonNode params, boolean resume) {
        String sessionId = requireSessionId(params);
        String cwd = params.path("cwd").asText("");
        AcpSessionRegistry.AcpSession session = registry.attach(cwd, sessionId);
        AcpStreamPublisher publisher = new AcpStreamPublisher(conn, session.acpSessionId());
        replayHistory(session, publisher);
        ObjectNode result = JsonRpc.object();
        if (!resume) {
            result.putNull("models");
        }
        attachSessionMeta(result, session);
        advertiseCommands(session);
        return result;
    }

    /** 历史回灌：user 消息 → {@code user_message_chunk}，assistant 文本 → {@code agent_message_chunk}。 */
    private void replayHistory(AcpSessionRegistry.AcpSession session, AcpStreamPublisher publisher) {
        AcpTurnTarget target = session.target();
        List<com.zifang.z.agent.kernel.message.Msg> messages = historyOf(target);
        if (messages == null) {
            return;
        }
        for (com.zifang.z.agent.kernel.message.Msg m : messages) {
            if (m == null || m.getContent() == null || m.getContent().isEmpty()) {
                continue;
            }
            com.zifang.z.agent.kernel.types.MessageRole role = m.getRole();
            if (role == com.zifang.z.agent.kernel.types.MessageRole.USER) {
                publisher.onEvent(new StreamEvent.ThoughtDelta("[history:user] " + m.getContent()));
            } else if (role == com.zifang.z.agent.kernel.types.MessageRole.ASSISTANT) {
                publisher.onEvent(new StreamEvent.FinalDelta("[history:assistant] " + m.getContent()));
            }
        }
    }

    /**
     * 取历史：只经 {@link AcpTurnTarget} 背后的 {@code SessionManager}。
     * 非 BotAgent 支撑的 target（测试替身）拿不到 ⇒ 返回 null，调用方跳过回灌而不是编一段。
     */
    private List<com.zifang.z.agent.kernel.message.Msg> historyOf(AcpTurnTarget target) {
        if (target instanceof BotAgentAcpTarget) {
            BotAgentAcpTarget real = (BotAgentAcpTarget) target;
            SessionManager sm = real.agent().getSessionManager();
            String zbotId = target.zbotSessionId();
            if (sm == null || zbotId == null) {
                return null;
            }
            try {
                return sm.loadMessages(zbotId);
            } catch (RuntimeException e) {
                System.err.println("[acp] 历史回灌取不到消息，跳过（不伪造历史）: " + e);
                return null;
            }
        }
        return null;
    }

    private JsonNode listSessions(JsonNode params) {
        String cwdFilter = params.path("cwd").asText("");
        ObjectNode result = JsonRpc.object();
        ArrayNode sessions = result.putArray("sessions");
        for (AcpSessionRegistry.AcpSession s : registry.sessions()) {
            if (!cwdFilter.isEmpty() && !cwdFilter.equals(s.cwd())) {
                continue;
            }
            ObjectNode node = JsonRpc.object();
            node.put("sessionId", s.acpSessionId());
            node.put("title", s.acpSessionId());
            node.put("updatedAt", java.time.Instant.ofEpochMilli(s.createdAtMillis()).toString());
            if (s.cwd() != null && !s.cwd().isEmpty()) {
                node.put("cwd", s.cwd());
            }
            ObjectNode meta = JsonRpc.object();
            meta.put("zbotSessionId", s.zbotSessionId());
            meta.put("model", s.target().model());
            node.set("_meta", meta);
            sessions.add(node);
        }
        result.putNull("nextCursor");
        // 落盘但尚未被任何 ACP 句柄采纳的会话也要看得见，否则重启后客户端无从下手。
        ArrayNode restorable = result.putArray("restorableZbotSessions");
        List<String> known = registry.knownZbotSessionIds();
        for (String id : known) {
            if (registry.byZbotSessionId(id) == null) {
                restorable.add(id);
            }
        }
        return result;
    }

    private JsonNode closeSession(JsonNode params) {
        String sessionId = requireSessionId(params);
        AcpSessionRegistry.AcpSession closed = registry.close(sessionId);
        ObjectNode result = JsonRpc.object();
        result.put("closed", true);
        result.put("sessionId", closed.acpSessionId());
        // 关的是连接句柄，落盘会话仍在：明说恢复路径，免得客户端以为记录没了。
        result.put("zbotSessionStillRestorable", true);
        return result;
    }

    private JsonNode setSessionMode(JsonNode params) {
        String sessionId = requireSessionId(params);
        String modeId = params.path("modeId").asText("");
        if (modeId.isEmpty()) {
            throw AcpProtocolException.invalidParams("session/set_mode 需要 modeId");
        }
        if (!availableModeIds.contains(modeId)) {
            throw AcpProtocolException.invalidParams("unknown modeId: " + modeId
                    + "；z-bot 可真空降的档=" + availableModeIds
                    + "（yolo/off 是启动时冻结的，见 cli/AgentOptions.java:37—39）");
        }
        AcpSessionRegistry.AcpSession session = registry.resolve(sessionId);
        session.setModeId(modeId);
        ObjectNode result = JsonRpc.object();
        result.set("availableModes", modesArray(session));
        return result;
    }

    private JsonNode setSessionModel(JsonNode params) {
        String sessionId = requireSessionId(params);
        String modelId = params.path("modelId").asText("");
        if (modelId.isEmpty()) {
            throw AcpProtocolException.invalidParams("session/set_model 需要 modelId");
        }
        if (!availableModelIds.isEmpty() && !availableModelIds.contains(modelId)) {
            throw AcpProtocolException.invalidParams("unknown modelId: " + modelId
                    + "；本 profile 可选=" + availableModelIds);
        }
        AcpSessionRegistry.AcpSession session = registry.resolve(sessionId);
        AcpSessionRegistry.AcpSession reconfigured = registry.reconfigure(session, modelId);
        if (!modelId.equals(reconfigured.target().model())) {
            throw AcpProtocolException.internal("set_model 之后 target 仍报 " 
                    + reconfigured.target().model() + "，不是请求的 " + modelId, null);
        }
        ObjectNode result = JsonRpc.object();
        result.set("models", modelsArray(reconfigured));
        return result;
    }

    /**
     * {@code session/set_config_option}：本期只承接 {@code reasoning}（是否回推思考增量）
     * 这一条真有效果的开关，其余 config option 大声拒绝而不是收下不办事。
     */
    private JsonNode setConfigOption(JsonNode params) {
        String sessionId = requireSessionId(params);
        registry.resolve(sessionId);
        String optionId = params.path("configId").asText(params.path("optionId").asText(""));
        String value = params.path("value").asText("");
        if (!"reasoning".equals(optionId)) {
            throw AcpProtocolException.invalidParams("unknown config option: " + optionId
                    + "；本期只支持 reasoning");
        }
        ObjectNode result = JsonRpc.object();
        result.put("configId", optionId);
        result.put("value", "true".equalsIgnoreCase(value) ? "true" : "false");
        return result;
    }

    private void cancel(JsonNode params) {
        String sessionId = params.path("sessionId").asText("");
        if (sessionId.isEmpty()) {
            System.err.println("[acp] session/cancel 无 sessionId，忽略（通知不能回包）");
            return;
        }
        AcpSessionRegistry.AcpSession session;
        try {
            session = registry.resolve(sessionId);
        } catch (AcpProtocolException e) {
            System.err.println("[acp] session/cancel 指向未知会话，忽略: " + e.getMessage());
            return;
        }
        session.target().stop();
        System.err.println("[acp] session/cancel → BotAgent.stop() 已置位: " + session.acpSessionId());
    }

    // ---- prompt ----

    private void startPrompt(final AcpConnection c, final JsonRpc.Message msg) {
        final JsonNode params = msg.params();
        final String sessionId = requireSessionId(params);
        final String text = promptText(params);
        final AcpSessionRegistry.AcpSession session;
        try {
            session = registry.resolve(sessionId);
        } catch (AcpProtocolException e) {
            c.fail(msg.id(), e);
            return;
        }
        if (session.isClosed()) {
            c.fail(msg.id(), AcpProtocolException.invalidParams("会话已关闭: " + sessionId));
            return;
        }
        promptExecutor.execute(new Runnable() {
            @Override
            public void run() {
                runPrompt(c, msg, session, text);
            }
        });
    }

    /**
     * 一轮 prompt 的全部逻辑：流式增量 → 审批外送 → stopReason。
     *
     * <p>chat 返回 {@code WAIT_CONFIRM:…} 时<b>必须</b>经 {@link AcpApprovalBridge}；
     * 桥不在（{@link AcpApprovalBridge#DISABLED}）时桥自己抛 {@code -32603}，
     * 绝不让 {@code WAIT_CONFIRM} 原文泄漏成"最终答案"。</p>
     */
    void runPrompt(AcpConnection c, JsonRpc.Message msg,
                   AcpSessionRegistry.AcpSession session, String text) {
        AcpStreamPublisher publisher = new AcpStreamPublisher(c, session.acpSessionId());
        inFlightPrompts.incrementAndGet();
        try {
            String reply = session.target().prompt(text, publisher);
            Throwable streamError = publisher.lastError();
            if (AcpApprovalBridge.isWaitConfirm(reply)) {
                AcpApprovalBridge.Outcome outcome =
                        bridge.requestAndResolve(c, session, reply, offerAlways);
                publishAsMessageChunks(publisher, outcome.toolOutput());
                ObjectNode result = JsonRpc.object();
                result.put("stopReason", AcpMethods.STOP_END_TURN);
                ObjectNode meta = JsonRpc.object();
                meta.put("approvalResolution", outcome.resolution().name());
                meta.put("approvalRequestId", outcome.request() == null ? "" : outcome.request().id());
                meta.put("approvalTimedOut", outcome.timedOut());
                meta.put("turnInterruptedByApproval", true);
                meta.put("continueHint", "z-bot 的待批语义是中断当前回合"
                        + "（agent/BotAgent.java:915—919），放行后工具已执行并把结果写进记忆；"
                        + "要让模型接着推，请再发一条 session/prompt");
                result.set("_meta", meta);
                c.respond(msg.id(), result);
                return;
            }
            if (streamError != null) {
                c.fail(msg.id(), AcpProtocolException.internal(
                        "prompt 期间 agent 报错: " + streamError.getMessage(), streamError));
                return;
            }
            if (reply == null) {
                reply = "";
            }
            if (publisher.messageChunks() == 0 && !reply.isEmpty()) {
                // 模型没走流式（或被工具轮吃掉）时补一帧，保证客户端至少看得到这一轮的文本。
                publishAsMessageChunks(publisher, reply);
            }
            ObjectNode result = JsonRpc.object();
            String stopReason = reply.startsWith("已中止") ? AcpMethods.STOP_CANCELLED
                    : AcpMethods.STOP_END_TURN;
            result.put("stopReason", stopReason);
            if (!AcpMethods.STOP_CANCELLED.equals(stopReason)) {
                // 取消那一路的文本已由「已中止：…」在 DONE 事件里给出，不再重复推一帧。
                StreamEvent done = publisher.lastDone();
                if (done instanceof StreamEvent.Done) {
                    StreamEvent.Done d = (StreamEvent.Done) done;
                    if (d.promptTokens != null || d.completionTokens != null) {
                        ObjectNode usage = JsonRpc.object();
                        usage.put("inputTokens", d.promptTokens == null ? 0 : d.promptTokens);
                        usage.put("outputTokens", d.completionTokens == null ? 0 : d.completionTokens);
                        usage.put("totalTokens",
                                (d.promptTokens == null ? 0 : d.promptTokens)
                                        + (d.completionTokens == null ? 0 : d.completionTokens));
                        ObjectNode meta = JsonRpc.object();
                        meta.set("usage", usage);
                        meta.put("totalSteps", d.totalSteps);
                        result.set("_meta", meta);
                    }
                }
            }
            c.respond(msg.id(), result);
        } catch (AcpProtocolException e) {
            c.fail(msg.id(), e);
        } catch (RuntimeException e) {
            c.fail(msg.id(), AcpProtocolException.internal("prompt 失败: " + e, e));
        } finally {
            inFlightPrompts.decrementAndGet();
        }
    }

    /** 把一段整本输出按 {@code agent_message_chunk} 推出去（审批后的工具输出走这条）。 */
    private void publishAsMessageChunks(AcpStreamPublisher publisher, String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        publisher.onEvent(new StreamEvent.FinalDelta(output));
    }

    // ---- 共用小料 ----

    private void attachSessionMeta(ObjectNode result, AcpSessionRegistry.AcpSession session) {
        result.set("modes", modesArray(session));
        result.set("models", modelsArray(session));
        ObjectNode meta = JsonRpc.object();
        meta.put("zbotSessionId", session.zbotSessionId());
        meta.put("approvalBridge", bridge.isEnabled() ? "attached" : "DISABLED");
        result.set("_meta", meta);
    }

    private ObjectNode modesArray(AcpSessionRegistry.AcpSession session) {
        ObjectNode modes = JsonRpc.object();
        String current = session.modeId() == null || session.modeId().isEmpty()
                ? (availableModeIds.isEmpty() ? "" : availableModeIds.get(0)) : session.modeId();
        modes.put("currentModeId", current);
        ArrayNode available = modes.putArray("availableModes");
        for (String id : availableModeIds) {
            ObjectNode m = JsonRpc.object();
            m.put("id", id);
            m.put("name", id);
            available.add(m);
        }
        return modes;
    }

    private ObjectNode modelsArray(AcpSessionRegistry.AcpSession session) {
        ObjectNode models = JsonRpc.object();
        models.put("currentModelId", session.target().model());
        ArrayNode available = models.putArray("availableModelIds");
        for (String id : availableModelIds) {
            available.add(id);
        }
        return models;
    }

    private static String requireSessionId(JsonNode params) {
        String sessionId = params.path("sessionId").asText("");
        if (sessionId.isEmpty()) {
            throw AcpProtocolException.invalidParams("缺少 sessionId");
        }
        return sessionId;
    }

    /** 从 {@code prompt:[{type:"text",text:…}]} 里取文本；非文本块明说被忽略（不许静默丢）。 */
    static String promptText(JsonNode params) {
        JsonNode prompt = params.path("prompt");
        StringBuilder sb = new StringBuilder();
        int skipped = 0;
        if (prompt.isArray()) {
            for (JsonNode block : prompt) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(block.path("text").asText(""));
                } else {
                    skipped++;
                }
            }
        } else if (prompt.isTextual()) {
            sb.append(prompt.asText());
        }
        if (skipped > 0) {
            System.err.println("[acp] prompt 里有 " + skipped
                    + " 个非 text 内容块被忽略（z-bot 未声明 image/audio 能力，见 initialize.agentCapabilities）");
        }
        if (sb.length() == 0) {
            throw AcpProtocolException.invalidParams("prompt 里没有可用的 text 内容块");
        }
        return sb.toString();
    }

    private static List<String> nonNull(List<String> in) {
        return in == null ? java.util.Collections.<String>emptyList() : in;
    }

    private static Executor defaultExecutor() {
        final AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "z-bot-acp-prompt-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        return pool;
    }

    public String clientInfo() {
        return clientInfo;
    }
}
