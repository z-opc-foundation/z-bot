package com.zifang.z.bot.channel;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.define.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.agent.AgentCatalog;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.cli.AgentOptions;
import com.zifang.z.bot.cliwrap.CliAgentConfig;
import com.zifang.z.bot.cliwrap.CliEngineBridge;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionResponse;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.cron.CronJob;
import com.zifang.z.bot.cron.CronScheduler;
import com.zifang.z.bot.local.DirAgentPool;
import com.zifang.z.bot.local.LocalProjectStore;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.slash.CommandCatalog;
import com.zifang.z.bot.slash.SlashCommand;
import com.zifang.z.bot.slash.SlashRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * HTTP 通道 — JDK 内置 {@link HttpServer}，无 Spring / 无 z-util-http。
 *
 * <p>端点分三组：</p>
 * <ul>
 *   <li>对话：{@code POST /bot/chat}（同步纯文本）、{@code POST /bot/chat/stream}（SSE，
 *       逐事件推送 ReAct 步骤）、{@code POST /bot/confirm}（放行待确认工具）</li>
 *   <li>会话：{@code GET/POST /api/sessions}、{@code /api/session/switch|delete}、{@code /api/session/messages}；
 *       桌面端适配面 {@code GET/POST /v1/sessions} 与 {@code GET/DELETE /v1/sessions/{id}}</li>
 *   <li>桌面端（z-bot-desk）：{@code POST /v1/chat/completions}（OpenAI 兼容，jar 档走内置 ReAct、
 *       CLI 档走 wrapper）、{@code GET/POST /v1/agents} 与 {@code GET/DELETE /v1/agents/{id}}、
 *       {@code GET /health}、{@code /workspace} 前缀（Vite 产物静态面）</li>
 *   <li>center 联动：{@code /api/skill/push|list|sync}、{@code /api/agent/register}，控制面 {@code /bot/status}、{@code /bot/clear}</li>
 * </ul>
 *
 * <p>{@code /workspace} 前缀下是带哈希文件名的静态资源，按前缀下发、<b>不进字面量台账</b>
 * （台账规则是 {@code "<path>".equals(path)}，前缀式静态面天然不在其中）。</p>
 *
 * <p>SSE 帧里 {@code data:} 不能带裸换行，所以文本统一转义成 {@code \n} 两字符，前端再还原。</p>
 */
public final class HttpChannel {

    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);
    private static final String CONSOLE_RESOURCE = "/web/index.html";

    private final BotAgent agent;
    private final int port;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;

    private HttpServer server;
    private ExecutorService workers;
    private final CountDownLatch termination = new CountDownLatch(1);
    /** 模型目录缓存（懒建；文件在 configDir/models-cache.json）。 */
    private volatile com.zifang.z.bot.llm.ModelCatalogCache modelCatalog;
    /** 本地执行模式的项目目录（懒建；文件在 configDir/projects.json）。 */
    private LocalProjectStore projectStore;
    /** 本地执行模式的目录 agent 池（懒建；key=目录绝对路径，""=默认沙箱根）。 */
    private DirAgentPool dirPool;

    private volatile AgentCatalog agentCatalog;

    public HttpChannel(BotAgent agent, int port) {
        this(agent, port, null);
    }

    public HttpChannel(BotAgent agent, int port, String host) {
        this.agent = agent;
        this.port = port;
        this.host = host;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(ChannelBind.resolve(host), port), 0);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "z-bot-http");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/", this::dispatch);
        server.setExecutor(workers);
        server.start();
        System.out.println("[HTTP] z-bot 已启动: " + consoleUrl());
        warnIfNotLoopback();
    }

    /** 控制台地址 — 印的是**实际**监听地址，绑通配时不能谎报成 127.0.0.1。 */
    public String consoleUrl() {
        return "http://" + ChannelBind.describe(getBindAddress()) + ":" + getPort() + "/index.html";
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        com.zifang.z.bot.llm.ModelCatalogCache mc = modelCatalog;
        if (mc != null) {
            mc.shutdown();
        }
        agent.shutdown();
        termination.countDown();
    }

    /** 实际监听端口（传入 0 时由系统分配）。 */
    public int getPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    /** 实际监听的地址 — 守卫测试据此确认缺省没有绑到通配。 */
    java.net.InetAddress getBindAddress() {
        return server == null ? null : server.getAddress().getAddress();
    }

    private void warnIfNotLoopback() {
        String warning = ChannelBind.exposureWarning(getBindAddress());
        if (warning != null) {
            System.out.println("[HTTP] 警告: " + warning);
        }
    }

    /** 阻塞到 {@link #stop()}，供 serve 子命令保持进程存活。 */
    public void awaitTermination() {
        try {
            termination.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== 路由台账（对位面的单一来源） =====

    /** 全表共用的鉴权口径 — 本期 HTTP 面<b>没有任何一条</b>要求凭据，唯一的门是"只绑回环"。 */
    public static final String AUTH_NONE_LOOPBACK_ONLY = "NONE(loopback-only)";
    /** 全表共用的绑址口径 — 要暴露到别的网卡得显式写 {@code --host}（见 {@link ChannelBind}）。 */
    public static final String BIND_LOOPBACK_DEFAULT = "LOOPBACK_DEFAULT(opt-in via --host)";

    /** "200" 本身不算证据：这一列规定每条路由要断言到什么形状才算过。 */
    public enum BodyShape {
        CONSOLE_HTML, PLAIN_TEXT, JSON_ARRAY, JSON_OBJECT, SSE_STREAM
    }

    /**
     * 台账里的一行 = 一个 <b>(方法, 路径)</b> 对。
     *
     * <p>三处必须同时对得上，任何一处单独改都会红：</p>
     * <ol>
     *   <li>{@link #dispatch} 里的 {@code "<path>".equals(path)} 字面量 ⇄ {@link #paths()}
     *       —— 由 {@code HttpRouteLedgerTest#ledgerPathsMatchDispatchLiterals()} 从<b>源码</b>机械复算；</li>
     *   <li>本表 ⇄ {@code _doc/005_testing/acceptance/p28/ROUTES.tsv}
     *       —— 由 {@code HttpRouteLedgerTest#routesTsvIsInSyncWithLedger()} 逐字节对账；</li>
     *   <li>{@code ROUTES.tsv} ⇄ 真进程 E2E 的分母 —— {@code p28_e2e.py} 逐行打，缺一行即红。</li>
     * </ol>
     *
     * 形状契约（{@link #fields()} 那一列的语法）。原子上界用 {@code ;}，原子内列表用 {@code ,}，
     * 这样 JUnit 断言和 {@code p28_e2e.py} 能用同一把尺解析，不必两边各抄一份清单。
     * <ul>
     *   <li>{@code nonempty} — 体非空</li>
     *   <li>{@code literal:X[,Y]} — 体里必须出现 X（逗号分隔则每个都要出现）</li>
     *   <li>{@code key:X[,Y]} — 体里必须出现 {@code X=} 与 {@code Y=}（文本键值面）</li>
     *   <li>{@code resource:P} — 体逐字节等于 classpath 资源 P</li>
     *   <li>{@code array} / {@code object} — JSON 顶层形状（"被包了一层"就靠这对原子区分）</li>
     *   <li>{@code fields:a,b} — 顶层必须有的字段名</li>
     *   <li>{@code count-of:k} — 顶层 {@code count} 必须等于数组 {@code k} 的长度（防恒 0 / 防自相矛盾）</li>
     *   <li>{@code rowfields:k:a,b} — 数组 {@code k}（顶层就是数组时写 {@code -}）每行的字段名</li>
     *   <li>{@code sse-events:a,b} — SSE 帧 {@code event:} 的名词表</li>
     * </ul>
     */
    public static final class Route {
        private final String method;
        private final String path;
        private final BodyShape shape;
        private final String auth;
        private final String bindScope;
        /** 形状契约原子串，语法见本类 javadoc 的 {@code 形状契约} 一节。 */
        private final String fields;
        /** 这个响应的"行数"从哪个控件来（实数据对拍用）；空串 = 没有行数概念。 */
        private final String rowCountFrom;
        /** 已知缺陷编号；空串 = 无。非空的必须在 EVIDENCE.md 里点名记账。 */
        private final String defect;

        Route(String method, String path, BodyShape shape, String fields, String rowCountFrom, String defect) {
            this.method = method;
            this.path = path;
            this.shape = shape;
            this.auth = AUTH_NONE_LOOPBACK_ONLY;
            this.bindScope = BIND_LOOPBACK_DEFAULT;
            this.fields = fields;
            this.rowCountFrom = rowCountFrom;
            this.defect = defect;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public BodyShape shape() {
            return shape;
        }

        public String auth() {
            return auth;
        }

        public String bindScope() {
            return bindScope;
        }

        public String fields() {
            return fields;
        }

        public String rowCountFrom() {
            return rowCountFrom;
        }

        public String defect() {
            return defect;
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final List<Route> ROUTES = Collections.unmodifiableList(Arrays.asList(
            // ---- 控制台（同一份 HTML 的四个别名） ----
            new Route("GET", "/", BodyShape.CONSOLE_HTML, "resource:/web/index.html", "", ""),
            new Route("GET", "/index.html", BodyShape.CONSOLE_HTML, "resource:/web/index.html", "", ""),
            new Route("GET", "/web", BodyShape.CONSOLE_HTML, "resource:/web/index.html", "", ""),
            new Route("GET", "/console", BodyShape.CONSOLE_HTML, "resource:/web/index.html", "", ""),
            // ---- 控制面（纯文本） ----
            new Route("GET", "/bot/status", BodyShape.PLAIN_TEXT,
                    "key:model,tools,provider,instance", "", ""),
            new Route("GET", "/bot/clear", BodyShape.PLAIN_TEXT,
                    "literal:Memory cleared", "", ""),
            new Route("POST", "/bot/stop", BodyShape.PLAIN_TEXT, "nonempty", "", ""),
            new Route("POST", "/bot/steer", BodyShape.PLAIN_TEXT, "nonempty", "", ""),
            // ---- 对话 ----
            new Route("GET", "/bot/tools", BodyShape.JSON_ARRAY,
                    "array;rowfields:-:name,description", "agent.getToolkit().getAllTools()", ""),
            new Route("POST", "/bot/chat", BodyShape.PLAIN_TEXT, "nonempty", "", ""),
            new Route("POST", "/bot/confirm", BodyShape.PLAIN_TEXT, "nonempty", "", ""),
            new Route("POST", "/bot/chat/stream", BodyShape.SSE_STREAM,
                    "sse-events:step,thought,tool_call,tool_result,steer,compact,final,done,error,confirm",
                    "每个 ReAct 步骤一帧", ""),
            // ---- OpenAI 兼容面 + 桌面端（z-bot-desk）适配面 ----
            // 引擎解析顺序：agent_type > agent_id 记录 > agent.engine 配置；jar/bot/缺省 →
            // 内置 ReAct（200 chat.completions 全形状），claude-code/hermes → CLI wrapper（同样 200；
            // stream=true 时回 SSE chunk）。错误统一走 400/404/409 的 {"error":{...}}。
            new Route("POST", "/v1/chat/completions", BodyShape.JSON_OBJECT,
                    "object;fields:id,object,created,model,choices", "", ""),
            new Route("GET", "/health", BodyShape.PLAIN_TEXT, "literal:ok", "", ""),
            new Route("GET", "/v1/sessions", BodyShape.JSON_ARRAY,
                    "array;rowfields:-:id,title,model,createdAt,updatedAt,messages", "", ""),
            new Route("POST", "/v1/sessions", BodyShape.JSON_OBJECT,
                    "object;fields:id,title,model,createdAt,updatedAt", "", ""),
            new Route("DELETE", "/v1/sessions", BodyShape.JSON_OBJECT,
                    "object;fields:deleted,id", "", ""),
            new Route("GET", "/v1/agents", BodyShape.JSON_OBJECT,
                    "object;fields:agents;rowfields:agents:id,name,type,status,createdAt", "", ""),
            new Route("POST", "/v1/agents", BodyShape.JSON_OBJECT,
                    "object;fields:id,name,type,status,createdAt", "", ""),
            new Route("DELETE", "/v1/agents", BodyShape.JSON_OBJECT,
                    "object;fields:deleted,id", "", ""),
            // ---- 本地执行模式（桌面端本地项目/目录 agent/模型自配） ----
            new Route("GET", "/v1/local/projects", BodyShape.JSON_OBJECT,
                    "object;fields:projects;rowfields:projects:id,name,dir,createdAt,sessions", "", ""),
            new Route("POST", "/v1/local/projects", BodyShape.JSON_OBJECT,
                    "object;fields:id,name,dir,createdAt", "", ""),
            new Route("DELETE", "/v1/local/projects", BodyShape.JSON_OBJECT,
                    "object;fields:deleted,id", "", ""),
            new Route("GET", "/api/local/model-config", BodyShape.JSON_OBJECT,
                    "object;fields:provider,model,baseUrl,hasApiKey,configDir", "", ""),
            new Route("PUT", "/api/local/model-config", BodyShape.JSON_OBJECT,
                    "object;fields:ok,provider,model,baseUrl,applied", "", ""),
            // ---- 会话 ----
            new Route("GET", "/api/sessions", BodyShape.JSON_ARRAY,
                    "array;rowfields:-:id,title,createdAt,messageCount",
                    "sessions/_index.json 的行数", ""),
            new Route("POST", "/api/sessions", BodyShape.JSON_OBJECT, "object;fields:ok,id", "", ""),
            new Route("GET", "/api/session/messages", BodyShape.JSON_ARRAY,
                    "array;rowfields:-:role,content,toolCalls,toolCallId,contentType,toolName,isFinal",
                    "sessions/<id>.json 的消息行数", ""),
            new Route("POST", "/api/session/switch", BodyShape.JSON_OBJECT, "object;fields:ok", 
                    "sessions/_index.json 里该 id 在不在", ""),
            new Route("POST", "/api/session/delete", BodyShape.JSON_OBJECT, "object;fields:ok",
                    "sessions/_index.json 里该 id 在不在", ""),
            // ---- 模型 / 技能 / 定时 / center ----
            new Route("GET", "/api/models", BodyShape.JSON_OBJECT,
                    "object;fields:provider,model,count,models,fetchedAt,stale,refreshing;"
                            + "count-of:models;rowfields:models:id,displayName,provider,contextWindow,"
                            + "maxOutputTokens,capabilities",
                    "ModelCatalogCache#catalog()（供应商 GET /v1/models）", ""),
            new Route("GET", "/api/skill/list", BodyShape.JSON_OBJECT,
                    "object;fields:instanceCode,count,skillCodes;count-of:skillCodes",
                    "configDir/skills 下的技能目录数", ""),
            new Route("GET", "/api/skill/sync", BodyShape.JSON_OBJECT,
                    "object;fields:ok,installed,instanceCode,timestamp", "", "D-P28-1"),
            // 同一个 sync 今天有**两个**方法面：控制台 fetch 用 GET（index.html:1368），
            // 既有单测用 POST（HttpChannelTest.skillEndpointsReportLocalOnlyState）⇒ 两条都登记，
            // 口径不一致这件事记在 EVIDENCE §1.4，不当成"新方法"。
            new Route("POST", "/api/skill/sync", BodyShape.JSON_OBJECT,
                    "object;fields:ok,installed,instanceCode,timestamp", "", "D-P28-1"),
            new Route("POST", "/api/skill/push", BodyShape.JSON_OBJECT,
                    "object;fields:ok,installed,instanceCode,timestamp", "", "D-P28-2"),
            new Route("GET", "/api/cron", BodyShape.JSON_OBJECT,
                    "object;fields:count,jobs;count-of:jobs;"
                            + "rowfields:jobs:id,name,prompt,schedule,enabled,lastRun,lastResult",
                    "CronScheduler#list() 落盘的任务数", ""),
            new Route("POST", "/api/cron", BodyShape.JSON_OBJECT, "object;fields:ok", "", ""),
            // center 未配置时这一条只回 {ok,error}；配置了才多 {instanceCode,timestamp} ⇒
            // 顶层字段按状态分叉，台账只钉"必有的那一列"，分叉本身记 EVIDENCE §1.1。
            new Route("POST", "/api/agent/register", BodyShape.JSON_OBJECT,
                    "object;fields:ok", "", ""),
            // ---- P19 命令表单源：整张 CommandCatalog 从这里读出去，控制台不再自己抄清单 ----
            // aliasOf 只在别名行出现（/quit /q /?），rowfields 是"出现过的列全集"口径，所以一起声明。
            new Route("GET", "/api/commands", BodyShape.JSON_ARRAY,
                    "array;rowfields:-:name,description,args,scope,endpoints,aliasOf",
                    "CommandCatalog.defs() + SlashRegistry.live() 的技能段", "")));

    /** 台账全量（<b>方法</b>粒度）。渲染与断言都从这一张表出发，别处不许再抄一份清单。 */
    public static List<Route> routes() {
        return ROUTES;
    }

    /** 台账里出现过的<b>路径</b>集合 —— 与 dispatch 的字面量一比一对账的就是这个。 */
    public static List<String> paths() {
        List<String> out = new ArrayList<String>();
        for (Route r : ROUTES) {
            if (!out.contains(r.path())) {
                out.add(r.path());
            }
        }
        return out;
    }

    /** 按路径查台账；不在表里 = 未登记的路由（漂移卫兵会红，运行时会 404）。 */
    public static List<Route> routesOf(String path) {
        List<Route> out = new ArrayList<Route>();
        for (Route r : ROUTES) {
            if (r.path().equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean ledgerAllows(String path, String method) {
        for (Route r : ROUTES) {
            if (r.path().equals(path) && r.method().equals(method)) {
                return true;
            }
        }
        return false;
    }

    private static String ledgerMethods(String path) {
        StringBuilder sb = new StringBuilder();
        for (Route r : ROUTES) {
            if (r.path().equals(path)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(r.method());
            }
        }
        return sb.length() == 0 ? "OPTIONS" : sb + ", OPTIONS";
    }

    // ===== 路由 =====

    private void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase();
        try {
            cors(ex);
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            // 桌面端前端面：Vite 产物文件名带哈希、不定名，这一面按前缀下发，不进字面量台账。
            if ("/workspace".equals(path) || path.startsWith("/workspace/")) {
                serveWorkspace(ex, path);
                return;
            }
            // 资源级动态段：desk 走 /v1/agents/{id}、/v1/sessions/{id}，id 在路径段里，
            // 形态带变量进不了字面量台账 ⇒ 方法口径借同名台账行，id 在这里剥出来。
            if (path.startsWith("/v1/agents/")) {
                v1Resource(ex, method, path, "/v1/agents");
                return;
            }
            if (path.startsWith("/v1/sessions/")) {
                v1Resource(ex, method, path, "/v1/sessions");
                return;
            }
            if (path.startsWith("/v1/local/projects/")) {
                v1LocalProjectResource(ex, method, path);
                return;
            }
            // 台账是唯一的门：不在表里的路径 404，在表里但方法没登记的回 405 + Allow。
            if (routesOf(path).isEmpty()) {
                json(ex, 404, error("not found: " + path));
                return;
            }
            if (!ledgerAllows(path, method)) {
                ex.getResponseHeaders().set("Allow", ledgerMethods(path));
                json(ex, 405, error(method + " 不是登记方法: " + path + "（登记：" + ledgerMethods(path) + "）"));
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path) || "/web".equals(path) || "/console".equals(path)) {
                serveConsole(ex);
            } else if ("/bot/status".equals(path)) {
                text(ex, 200, statusLine());
            } else if ("/bot/tools".equals(path)) {
                json(ex, 200, toolListResult());
            } else if ("/bot/clear".equals(path)) {
                agent.clearMemory();
                text(ex, 200, "Memory cleared");
            } else if ("/bot/chat".equals(path)) {
                String chatBody = readBody(ex);
                busyGuard(ex, () -> chat(ex, chatBody));
            } else if ("/bot/chat/stream".equals(path)) {
                String streamBody = readBody(ex);
                busyGuard(ex, () -> chatStream(ex, streamBody));
            } else if ("/v1/chat/completions".equals(path)) {
                v1ChatCompletions(ex, readBody(ex));
            } else if ("/health".equals(path)) {
                text(ex, 200, "ok");
            } else if ("/v1/sessions".equals(path)) {
                v1Sessions(ex, method, queryId(ex.getRequestURI().getQuery()));
            } else if ("/v1/agents".equals(path)) {
                v1Agents(ex, method, queryId(ex.getRequestURI().getQuery()));
            } else if ("/v1/local/projects".equals(path)) {
                if ("POST".equals(method)) {
                    v1LocalProjectCreate(ex, readBody(ex));
                } else if ("DELETE".equals(method)) {
                    String qid = queryId(ex.getRequestURI().getQuery());
                    if (qid.isEmpty()) {
                        json(ex, 400, error("DELETE /v1/local/projects 需要 ?id=<项目id>（或走 /v1/local/projects/{id}）"));
                    } else {
                        deleteLocalProject(ex, qid);
                    }
                } else {
                    v1LocalProjectsList(ex);
                }
            } else if ("/api/local/model-config".equals(path)) {
                if ("PUT".equals(method)) {
                    apiLocalModelConfigPut(ex, readBody(ex));
                } else {
                    apiLocalModelConfigGet(ex);
                }
            } else if ("/bot/confirm".equals(path)) {
                String confirmBody = readBody(ex);
                busyGuard(ex, () -> confirm(ex, confirmBody));
            } else if ("/bot/stop".equals(path)) {
                if (!agent.isRunning()) {
                    text(ex, 200, "当前没有正在运行的任务");
                } else {
                    agent.stop();
                    text(ex, 200, "已请求停止，将在最近的迭代边界生效");
                }
            } else if ("/bot/steer".equals(path)) {
                String steerBody = readBody(ex).trim();
                if (steerBody.isEmpty()) {
                    text(ex, 400, "steer 内容不能为空");
                } else {
                    agent.steer(steerBody);
                    text(ex, 200, agent.isRunning()
                            ? "已入队 steer，将在工具间隙注入"
                            : "已入队，将在下次对话开头并入");
                }
            } else if ("/api/sessions".equals(path)) {
                sessions(ex, method);
            } else if ("/api/models".equals(path)) {
                json(ex, 200, modelsResult(ex));
            } else if ("/api/session/switch".equals(path)) {
                sessionSwitch(ex, readBody(ex));
            } else if ("/api/session/delete".equals(path)) {
                sessionDelete(ex, readBody(ex));
            } else if ("/api/session/messages".equals(path)) {
                sessionMessages(ex);
            } else if ("/api/skill/push".equals(path) || "/api/skill/sync".equals(path)) {
                json(ex, 200, skillSyncResult());
            } else if ("/api/skill/list".equals(path)) {
                json(ex, 200, skillListResult());
            } else if ("/api/cron".equals(path)) {
                json(ex, 200, cronResult(method, readBody(ex)));
            } else if ("/api/agent/register".equals(path)) {
                json(ex, 200, agentRegisterResult(readBody(ex)));
            } else if ("/api/commands".equals(path)) {
                json(ex, 200, commandListResult());
            } else {
                json(ex, 404, error("not found: " + path));
            }
        } catch (BodyTooLargeException e) {
            LOG.warn("{} {} body 超限: {}", method, path, e.getMessage());
            try {
                json(ex, 413, error("请求体超过上限 " + InboundLimits.MAX_BODY_BYTES + " 字节"));
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            LOG.warn("{} {} 处理失败: {}", method, path, e.getMessage());
            try {
                json(ex, 500, error(e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /**
     * agent 一次只跑一轮对话；并发请求直接 409，免得第二条消息把记忆搅乱。
     */
    private void busyGuard(HttpExchange ex, RequestHandler action) throws IOException {
        if (agent.isRunning()) {
            text(ex, 409, "上一条消息还在处理中，请稍后再试");
            return;
        }
        action.handle();
    }

    private interface RequestHandler {
        void handle() throws IOException;
    }

    /**
     * OpenAI chat completions 兼容面（桌面端 z-bot-desk 的对话入口）。
     *
     * <p>引擎解析顺序：请求里的 {@code agent_type} &gt; {@code agent_id} 指向的目录条目
     * &gt; {@code agent.engine} 配置。{@code jar}/{@code bot}/缺省走内置 ReAct（带 session_id
     * 时先切会话，历史由会话记忆持有）；CLI 类型走 wrapper，无状态 one-shot、不读写会话记忆。</p>
     */
    private void v1ChatCompletions(HttpExchange ex, String body) throws IOException {
        Map<String, Object> parsed = parseObject(body);
        String prompt = "";
        Object messagesObj = parsed.get("messages");
        if (messagesObj instanceof List) {
            for (Object o : (List<?>) messagesObj) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if ("user".equals(String.valueOf(m.get("role"))) && m.get("content") != null) {
                        prompt = String.valueOf(m.get("content"));
                    }
                }
            }
        }
        if (prompt.trim().isEmpty()) {
            json(ex, 400, cliEngineError("messages 里没有 user 内容"));
            return;
        }
        String agentType = str(parsed.get("agent_type"));
        String agentId = str(parsed.get("agent_id"));
        Map<String, Object> agentRec = agentId.isEmpty() ? null : agentCatalog().get(agentId);
        String engine = !agentType.isEmpty() ? agentType
                : (agentRec != null ? str(agentRec.get("type"))
                        : CliEngineBridge.engineType(agent.getConfig()));
        if (engine.isEmpty()) {
            engine = CliEngineBridge.ENGINE_BOT;
        }
        boolean stream = Boolean.TRUE.equals(parsed.get("stream"))
                || "true".equalsIgnoreCase(String.valueOf(parsed.get("stream")));

        boolean builtinEngine = CliEngineBridge.ENGINE_BOT.equals(engine) || "jar".equals(engine);
        String projectId = str(parsed.get("project_id"));
        if (projectId.isEmpty()) {
            projectId = str(parsed.get("projectId"));
        }
        if (builtinEngine && !projectId.isEmpty()) {
            // 本地执行模式：project_id 把这轮对话路由进目录专属 agent（沙箱困在绑定文件夹里）
            Map<String, Object> project = projectStore().get(projectId);
            if (project == null) {
                json(ex, 404, cliEngineError("本地项目不存在: " + projectId));
                return;
            }
            String sid = str(parsed.get("session_id"));
            if (sid.isEmpty()) {
                sid = str(parsed.get("sessionId"));
            }
            if (!sid.isEmpty()) {
                projectStore().bindSession(projectId, sid);
            }
            BotAgent target = dirPool().forDir(str(project.get("dir")));
            v1BuiltinChat(ex, parsed, prompt, stream, target);
            // 本地玩法首轮不带 session_id，会话在 chat 内部新建 —— chat 同步跑完后才能拿到真 id
            String realSid = target.currentSessionId();
            if (realSid != null && !realSid.isEmpty()) {
                projectStore().bindSession(projectId, realSid);
            }
            return;
        }
        if (builtinEngine) {
            v1BuiltinChat(ex, parsed, prompt, stream, agent);
            return;
        }

        CliAgentConfig cli = cliConfigFor(engine, agentRec);
        if (stream) {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            try {
                CliEngineBridge.chatStream(cli, prompt, os);
            } finally {
                os.close();
            }
            return;
        }
        String reply = CliEngineBridge.chatSync(cli, prompt);
        byte[] out = ChatCompletionResponse.of(engine, reply)
                .toJson().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
    }

    /** 内置 ReAct 档：session_id 先切会话，FinalDelta 逐个翻成 OpenAI chunk。target=承接本轮的 agent（全局单例或目录专属实例）。 */
    private void v1BuiltinChat(HttpExchange ex, Map<String, Object> parsed, String prompt, boolean stream,
            BotAgent target)
            throws IOException {
        if (target.isRunning()) {
            json(ex, 409, cliEngineError("上一条消息还在处理中，请稍后再试"));
            return;
        }
        String sessionId = str(parsed.get("session_id"));
        if (sessionId.isEmpty()) {
            sessionId = str(parsed.get("sessionId"));
        }
        if (!sessionId.isEmpty() && !sessionId.equals(target.currentSessionId())) {
            if (!sessionExists(sessionId)) {
                json(ex, 404, cliEngineError("未找到会话: " + sessionId));
                return;
            }
            target.switchSession(sessionId);
        }
        if (!stream) {
            String reply = target.chat(prompt);
            byte[] out = ChatCompletionResponse.of("z-bot", displayableReply(reply))
                    .toJson().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        final String completionId = "chatcmpl-bot-" + RandomUtil.uuidCompact();
        final long created = System.currentTimeMillis() / 1000L;
        final Object lock = new Object();
        final boolean[] emitted = {false};
        StreamListener listener = new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                if (!(event instanceof StreamEvent.FinalDelta)) {
                    return;
                }
                String text = ((StreamEvent.FinalDelta) event).text;
                if (text == null || text.isEmpty()) {
                    return;
                }
                synchronized (lock) {
                    try {
                        write(os, ChatCompletionResponse.sseChunk(completionId, "z-bot", created, text));
                        emitted[0] = true;
                    } catch (IOException e) {
                        throw new IllegalStateException("SSE 连接已断开", e);
                    }
                }
            }
        };
        try {
            String reply = target.chat(prompt, listener);
            synchronized (lock) {
                if (!emitted[0] && reply != null && !reply.isEmpty()) {
                    write(os, ChatCompletionResponse.sseChunk(completionId, "z-bot", created,
                            displayableReply(reply)));
                }
                write(os, "data: [DONE]\n\n");
            }
        } catch (RuntimeException e) {
            LOG.warn("v1 内置档对话失败: {}", e.getMessage());
            synchronized (lock) {
                try {
                    write(os, "data: " + JsonUtil.toJson(cliEngineError(String.valueOf(e.getMessage()))) + "\n\n");
                    write(os, "data: [DONE]\n\n");
                } catch (IOException ignored) {
                }
            }
        } finally {
            os.close();
        }
    }

    /** WAIT_CONFIRM 是内部协议串，桌面端没有确认按钮，翻成可读文本。 */
    private static String displayableReply(String reply) {
        if (reply != null && reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX)) {
            return confirmData(reply);
        }
        return reply != null ? reply : "";
    }

    /** agent_id 指向的目录条目可覆盖命令/参数/工作目录；没有条目就用 agent.engine 的配置。 */
    private CliAgentConfig cliConfigFor(String type, Map<String, Object> agentRec) {
        CliAgentConfig base = CliEngineBridge.buildConfig(agent.getConfig(), type);
        if (agentRec == null) {
            return base;
        }
        String command = str(agentRec.get("command"));
        String workingDir = str(agentRec.get("workingDir"));
        List<String> args = base.getArgs();
        Object rawArgs = agentRec.get("args");
        if (rawArgs instanceof List) {
            args = new ArrayList<String>();
            for (Object a : (List<?>) rawArgs) {
                args.add(String.valueOf(a));
            }
        }
        return new CliAgentConfig(str(agentRec.get("id")), str(agentRec.get("name")), type,
                command.isEmpty() ? base.getCommand() : command, args,
                workingDir.isEmpty() ? base.getWorkingDir() : workingDir);
    }

    private AgentCatalog agentCatalog() {
        AgentCatalog c = agentCatalog;
        if (c == null) {
            synchronized (this) {
                if (agentCatalog == null) {
                    agentCatalog = new AgentCatalog(agent.getConfig().getConfigDir());
                }
                c = agentCatalog;
            }
        }
        return c;
    }

    private LocalProjectStore projectStore() {
        LocalProjectStore s = projectStore;
        if (s == null) {
            synchronized (this) {
                if (projectStore == null) {
                    projectStore = new LocalProjectStore(agent.getConfig().getConfigDir());
                }
                s = projectStore;
            }
        }
        return s;
    }

    private DirAgentPool dirPool() {
        DirAgentPool p = dirPool;
        if (p == null) {
            synchronized (this) {
                if (dirPool == null) {
                    dirPool = new DirAgentPool(agent);
                }
                p = dirPool;
            }
        }
        return p;
    }

    /**
     * 资源级动态段 {@code /v1/agents/{id}}、{@code /v1/sessions/{id}} 的唯一入口。
     * 台账里同名那几行就是方法分母：GET 读单条、DELETE 删单条，其余方法 405。
     */
    private void v1Resource(HttpExchange ex, String method, String path, String ledgerPath)
            throws IOException {
        String id = path.substring(ledgerPath.length() + 1);
        try {
            id = java.net.URLDecoder.decode(id, "UTF-8").trim();
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
        if (id.isEmpty()) {
            json(ex, 404, error("not found: " + path));
            return;
        }
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, DELETE, OPTIONS");
            json(ex, 405, error(method + " 不是登记方法: " + ledgerPath + "/{id}"
                    + "（登记：GET, DELETE, OPTIONS）"));
            return;
        }
        if ("/v1/agents".equals(ledgerPath)) {
            v1Agents(ex, method, id);
        } else {
            v1Sessions(ex, method, id);
        }
    }

    /** 桌面端会话面：形状按 desk 的 {@code SessionInfo}（列表行内嵌 messages，供前端直接还原历史）。 */
    private void v1Sessions(HttpExchange ex, String method, String id) throws IOException {
        if ("POST".equals(method)) {
            String newId = agent.newSession();
            Map<String, Object> out = null;
            for (SessionManager.SessionSummary s : agent.listSessions()) {
                if (newId.equals(s.id)) {
                    out = v1SessionRow(s);
                    break;
                }
            }
            json(ex, 201, out != null ? out : v1SessionRow(newId, "新会话", ""));
            return;
        }
        if ("DELETE".equals(method)) {
            if (id.isEmpty()) {
                json(ex, 400, cliEngineError("id 不能为空"));
                return;
            }
            if (!sessionExists(id)) {
                json(ex, 404, cliEngineError("未找到会话: " + id));
                return;
            }
            agent.deleteSession(id);
            projectStore().unbindSession(id);
            json(ex, 200, deleted(id));
            return;
        }
        if (!id.isEmpty()) {
            for (SessionManager.SessionSummary s : agent.listSessions()) {
                if (id.equals(s.id)) {
                    json(ex, 200, v1SessionRow(s));
                    return;
                }
            }
            json(ex, 404, cliEngineError("未找到会话: " + id));
            return;
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SessionManager.SessionSummary s : agent.listSessions()) {
            out.add(v1SessionRow(s));
        }
        json(ex, 200, out);
    }

    private Map<String, Object> v1SessionRow(SessionManager.SessionSummary s) {
        return v1SessionRow(s.id, s.createdAt, s.title);
    }

    private Map<String, Object> v1SessionRow(String id, String createdAt, String title) {
        long created = epochMillis(createdAt);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("title", title == null || title.isEmpty() ? "新会话" : title);
        m.put("model", "");
        // SessionSummary 只有 createdAt（"yyyy-MM-dd HH:mm:ss"），desk 的 SessionInfo 要毫秒数；
        // updatedAt 没有独立来源，先按 createdAt 近似下发
        m.put("createdAt", created);
        m.put("updatedAt", created);
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        // 必须用 agent 自带的那个 SessionManager：store 模式下消息只在 SQLite 里，
        // 拿 sessionDir 现造一个（store=null）读出来永远是空历史。
        for (Msg msg : agent.getSessionManager().loadMessages(id)) {
            String role = msg.getRole() == null ? "user" : msg.getRole().name().toLowerCase(Locale.ROOT);
            String content = msg.getContent() == null ? "" : msg.getContent();
            // desk 的 ChatMessage 只有 user/assistant 两种气泡，工具帧与空内容行还原不了也不该硬塞
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (content.isEmpty()) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("role", role);
            one.put("content", content);
            one.put("timestamp", created);
            messages.add(one);
        }
        m.put("messages", messages);
        return m;
    }

    /** SessionSummary.createdAt 是 "yyyy-MM-dd HH:mm:ss" 本地时间串；解不动就退回当下。 */
    private static long epochMillis(String createdAt) {
        if (createdAt == null || createdAt.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return java.time.LocalDateTime.parse(createdAt.trim(),
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    // ===== 本地执行模式（桌面端本地项目 + 目录 agent + 模型自配） =====

    /** GET /v1/local/projects — 项目列表，名下会话以轻量行内嵌（正文仍走 /v1/sessions/{id}）。 */
    private void v1LocalProjectsList(HttpExchange ex) throws IOException {
        Map<String, SessionManager.SessionSummary> all = new LinkedHashMap<String, SessionManager.SessionSummary>();
        for (SessionManager.SessionSummary s : agent.listSessions()) {
            all.put(s.id, s);
        }
        // 目录 agent 与全局 agent 各持 SessionManager 内存缓存，目录实例新建的会话只进共享库
        // 不进全局内存 —— 分母必须并上库直查，否则本地项目的会话列表永远缺首轮
        com.zifang.z.bot.store.StateStore store = agent.getSessionManager().getStore();
        if (store != null) {
            for (com.zifang.z.bot.store.StateStore.SessionRow row : store.listSessions()) {
                if (!all.containsKey(row.id)) {
                    SessionManager.SessionSummary s = new SessionManager.SessionSummary();
                    s.id = row.id;
                    s.title = row.title;
                    s.createdAt = row.createdAt;
                    s.messageCount = row.messageCount;
                    all.put(row.id, s);
                }
            }
        }
        List<Map<String, Object>> projects = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> p : projectStore().list()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", p.get("id"));
            row.put("name", p.get("name"));
            if (p.get("dir") != null) {
                row.put("dir", p.get("dir"));
            }
            row.put("createdAt", p.get("createdAt"));
            List<Map<String, Object>> sessions = new ArrayList<Map<String, Object>>();
            Object raw = p.get("sessions");
            if (raw instanceof List) {
                for (Object sid : (List<?>) raw) {
                    SessionManager.SessionSummary s = all.get(String.valueOf(sid));
                    if (s != null) {
                        sessions.add(lightSessionRow(s));
                    }
                }
            }
            row.put("sessions", sessions);
            projects.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("projects", projects);
        json(ex, 200, out);
    }

    /** POST /v1/local/projects — {name, dir?}；dir 缺省 = 默认沙箱根。 */
    private void v1LocalProjectCreate(HttpExchange ex, String body) throws IOException {
        Map<String, Object> req = parseObject(body);
        try {
            json(ex, 201, projectStore().create(str(req.get("name")), str(req.get("dir"))));
        } catch (IllegalArgumentException e) {
            json(ex, 400, cliEngineError(e.getMessage()));
        } catch (java.io.IOException e) {
            json(ex, 500, cliEngineError("projects.json 写失败: " + e.getMessage()));
        }
    }

    /** DELETE /v1/local/projects/{id} — 删项目（名下会话保留在会话库里，只是摘掉归属）。 */
    private void v1LocalProjectResource(HttpExchange ex, String method, String path) throws IOException {
        String id = path.substring("/v1/local/projects/".length());
        try {
            id = java.net.URLDecoder.decode(id, "UTF-8").trim();
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
        if (id.isEmpty()) {
            json(ex, 404, error("not found: " + path));
            return;
        }
        if (!"DELETE".equals(method)) {
            ex.getResponseHeaders().set("Allow", "DELETE, OPTIONS");
            json(ex, 405, error(method + " 不是登记方法: /v1/local/projects/{id}（登记：DELETE, OPTIONS）"));
            return;
        }
        deleteLocalProject(ex, id);
    }

    /** 项目删除的唯一实现：?id= 查询形态与 /{id} 路径段形态共用。 */
    private void deleteLocalProject(HttpExchange ex, String id) throws IOException {
        if (projectStore().delete(id)) {
            json(ex, 200, deleted(id));
        } else {
            json(ex, 404, cliEngineError("本地项目不存在: " + id));
        }
    }

    private Map<String, Object> lightSessionRow(SessionManager.SessionSummary s) {
        long created = epochMillis(s.createdAt);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", s.id);
        m.put("title", s.title == null || s.title.isEmpty() ? "新会话" : s.title);
        m.put("createdAt", created);
        m.put("updatedAt", created);
        return m;
    }

    /** GET /api/local/model-config — 当前生效的本地模型配置（apiKey 只回有无不回明文）。 */
    private void apiLocalModelConfigGet(HttpExchange ex) throws IOException {
        BotConfig cfg = agent.getConfig();
        BotConfig.Provider p = cfg.activeProvider();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider", p.getCode());
        out.put("model", cfg.getModel());
        out.put("baseUrl", p.getBaseUrl() == null ? "" : p.getBaseUrl());
        out.put("hasApiKey", p.getApiKey() != null && !p.getApiKey().trim().isEmpty());
        out.put("configDir", cfg.getConfigDir() == null
                ? BotConfig.defaultConfigDir().getAbsolutePath() : cfg.getConfigDir().getAbsolutePath());
        json(ex, 200, out);
    }

    /**
     * PUT /api/local/model-config — 自配模型落 {@code config.properties}（重启可重载），
     * 同时热清目录 agent 池：本地任务下一轮对话即用新配置重建；
     * 全局单例 agent 的模型客户端是构建期冻结的，重启客户端后才换。
     */
    private void apiLocalModelConfigPut(HttpExchange ex, String body) throws IOException {
        Map<String, Object> req = parseObject(body);
        String code = str(req.get("provider"));
        String model = str(req.get("model"));
        String baseUrl = str(req.get("baseUrl"));
        String apiKey = str(req.get("apiKey"));
        BotConfig cfg = agent.getConfig();
        if (code.isEmpty()) {
            code = cfg.getActiveProviderCode() == null ? "openai" : cfg.getActiveProviderCode();
        }
        File configDir = cfg.getConfigDir() != null ? cfg.getConfigDir() : BotConfig.defaultConfigDir();
        try {
            File propFile = new File(configDir, "config.properties");
            Properties props = new Properties();
            if (propFile.exists()) {
                java.io.FileInputStream fis = new java.io.FileInputStream(propFile);
                try {
                    props.load(fis);
                } finally {
                    fis.close();
                }
            }
            LinkedHashSet<String> codes = new LinkedHashSet<String>();
            for (String c : str(props.getProperty("providers")).split(",")) {
                if (!c.trim().isEmpty()) {
                    codes.add(c.trim());
                }
            }
            codes.add(code);
            StringBuilder joined = new StringBuilder();
            for (String c : codes) {
                if (joined.length() > 0) {
                    joined.append(",");
                }
                joined.append(c);
            }
            props.setProperty("providers", joined.toString());
            props.setProperty(code + ".type", AgentOptions.typeOf(code));
            if (!apiKey.isEmpty()) {
                props.setProperty(code + ".api.key", apiKey);
            }
            if (!baseUrl.isEmpty()) {
                props.setProperty(code + ".base.url", baseUrl);
            }
            if (!model.isEmpty()) {
                props.setProperty(code + ".model", model);
                props.setProperty("llm.model", model);
            }
            props.setProperty("llm.provider", code);
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            File tmp = new File(configDir, "config.properties.tmp");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            try {
                props.store(fos, "z-bot local model config");
            } finally {
                fos.close();
            }
            java.nio.file.Files.move(tmp.toPath(), propFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            json(ex, 500, cliEngineError("config.properties 写失败: " + e.getMessage()));
            return;
        }
        cfg.useProvider(code, AgentOptions.typeOf(code),
                apiKey.isEmpty() ? null : apiKey,
                baseUrl.isEmpty() ? null : baseUrl,
                model.isEmpty() ? null : model);
        dirPool().clear();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("provider", code);
        out.put("model", cfg.getModel());
        out.put("baseUrl", baseUrl.isEmpty() ? String.valueOf(cfg.activeProvider().getBaseUrl()) : baseUrl);
        out.put("applied", "local-tasks-immediate");
        json(ex, 200, out);
    }

    /** 桌面端 agent 目录面：内置 default 恒在；自定义条目可带 CLI 的 command/args/workingDir。 */
    private void v1Agents(HttpExchange ex, String method, String id) throws IOException {
        AgentCatalog catalog = agentCatalog();
        if ("POST".equals(method)) {
            String body = readBody(ex);
            Map<String, Object> req = body.trim().isEmpty()
                    ? new LinkedHashMap<String, Object>() : parseObject(body);
            try {
                json(ex, 201, catalog.create(req));
            } catch (IllegalArgumentException e) {
                json(ex, 400, cliEngineError(e.getMessage()));
            }
            return;
        }
        if ("DELETE".equals(method)) {
            if (id.isEmpty()) {
                json(ex, 400, cliEngineError("id 不能为空"));
                return;
            }
            if (AgentCatalog.DEFAULT_ID.equals(id)) {
                json(ex, 403, cliEngineError("内置 default agent 不可删"));
                return;
            }
            if (!catalog.delete(id)) {
                json(ex, 404, cliEngineError("未找到 agent: " + id));
                return;
            }
            json(ex, 200, deleted(id));
            return;
        }
        if (!id.isEmpty()) {
            Map<String, Object> one = catalog.get(id);
            if (one == null) {
                json(ex, 404, cliEngineError("未找到 agent: " + id));
                return;
            }
            json(ex, 200, one);
            return;
        }
        // desk 的 useAgentManager 读 data.agents —— 列表必须包一层，裸数组会让侧栏恒空
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("agents", catalog.list());
        json(ex, 200, out);
    }

    private static Map<String, Object> deleted(String id) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", true);
        out.put("id", id);
        return out;
    }

    /** 从 query string 里取 {@code id=...}（台账是字面量口径，动态段走 query，不改路径形态）。 */
    private static String queryId(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "id".equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim();
                } catch (java.io.UnsupportedEncodingException e) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return "";
    }

    /**
     * 桌面前端静态面：{@code classpath:/web/workspace} 下是 Vite 产物（build-dmg.sh 拷入）。
     * 无扩展名的未知路径回落 index.html（SPA 路由）；带扩展名找不到就 404，不伪造 200。
     */
    private void serveWorkspace(HttpExchange ex, String path) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "GET");
            json(ex, 405, error("只支持 GET: " + path));
            return;
        }
        String rel = "/workspace".equals(path) ? "/index.html" : path.substring("/workspace".length());
        if (rel.contains("..")) {
            text(ex, 400, "非法路径");
            return;
        }
        if (!rel.startsWith("/")) {
            rel = "/" + rel;
        }
        InputStream is = HttpChannel.class.getResourceAsStream("/web/workspace" + rel);
        if (is == null) {
            int slash = rel.lastIndexOf('/');
            String name = slash >= 0 ? rel.substring(slash + 1) : rel;
            if (name.contains(".")) {
                text(ex, 404, "未找到资源 " + rel);
                return;
            }
            rel = "/index.html";
            is = HttpChannel.class.getResourceAsStream("/web/workspace/index.html");
            if (is == null) {
                text(ex, 404, "桌面前端未打包（classpath:/web/workspace/index.html 不存在）");
                return;
            }
        }
        byte[] data;
        try {
            data = readAll(is);
        } finally {
            is.close();
        }
        ex.getResponseHeaders().set("Content-Type", contentTypeOf(rel));
        ex.getResponseHeaders().set("Cache-Control",
                rel.endsWith("index.html") ? "no-store" : "public, max-age=3600");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static String contentTypeOf(String rel) {
        String p = rel.toLowerCase();
        if (p.endsWith(".html")) {
            return "text/html; charset=utf-8";
        } else if (p.endsWith(".js") || p.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        } else if (p.endsWith(".css")) {
            return "text/css; charset=utf-8";
        } else if (p.endsWith(".json") || p.endsWith(".map")) {
            return "application/json; charset=utf-8";
        } else if (p.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (p.endsWith(".png")) {
            return "image/png";
        } else if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (p.endsWith(".ico")) {
            return "image/x-icon";
        } else if (p.endsWith(".woff2")) {
            return "font/woff2";
        } else if (p.endsWith(".woff")) {
            return "font/woff";
        }
        return "application/octet-stream";
    }

    /** OpenAI 惯例的 400 形状（agent.engine=bot 时兼容面的常答；CLI 引擎下 200 形状见台账注释分叉）。 */
    private static Map<String, Object> cliEngineError(String message) {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("message", message);
        err.put("type", "invalid_request_error");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error", err);
        return out;
    }

    private void chat(HttpExchange ex, String body) throws IOException {
        String message = messageOf(body);
        if (message.isEmpty()) {
            text(ex, 400, "消息不能为空");
            return;
        }
        text(ex, 200, agent.chat(message));
    }

    private void confirm(HttpExchange ex, String body) throws IOException {
        Map<String, Object> req = parseObject(body);
        String toolName = str(req.get("toolName"));
        if (toolName.isEmpty()) {
            text(ex, 400, "错误：toolName 不能为空");
            return;
        }
        text(ex, 200, agent.confirmTool(toolName, str(req.get("argsJson"))));
    }

    // ===== SSE =====

    private void chatStream(HttpExchange ex, String body) throws IOException {
        String message = messageOf(body);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        if (message.isEmpty()) {
            writeEvent(os, "error", "消息不能为空");
            os.close();
            return;
        }
        final Object lock = new Object();
        StreamListener listener = new StreamListener() {
            @Override
            public void onEvent(StreamEvent event) {
                String frame = formatEvent(event);
                if (frame == null) {
                    return;
                }
                synchronized (lock) {
                    try {
                        write(os, frame);
                    } catch (IOException e) {
                        throw new IllegalStateException("SSE 连接已断开", e);
                    }
                }
            }
        };
        try {
            String reply = agent.chat(message, listener);
            if (reply != null && reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX)) {
                synchronized (lock) {
                    writeEvent(os, "confirm", confirmData(reply));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("SSE chat 失败: {}", e.getMessage());
            synchronized (lock) {
                try {
                    writeEvent(os, "error", String.valueOf(e.getMessage()));
                } catch (IOException ignored) {
                }
            }
        } finally {
            os.close();
        }
    }

    /** {@code WAIT_CONFIRM:tool|args|reason} → 前端可读的 {@code [confirm] tool=… args=… reason=…}。 */
    private static String confirmData(String reply) {
        String[] parts = reply.substring(BotAgent.WAIT_CONFIRM_PREFIX.length()).split("\\|", 3);
        StringBuilder sb = new StringBuilder("[confirm] tool=");
        sb.append(parts.length > 0 ? parts[0] : "");
        sb.append(" args=").append(parts.length > 1 ? parts[1] : "{}");
        sb.append(" reason=").append(parts.length > 2 ? parts[2] : "高危操作");
        return sb.toString();
    }

    /** {@link StreamEvent} → SSE 帧（前端约定的事件名与字段格式）。 */
    private static String formatEvent(StreamEvent event) {
        if (event instanceof StreamEvent.StepStart) {
            return frame("step", "Step " + ((StreamEvent.StepStart) event).step);
        }
        if (event instanceof StreamEvent.ThoughtDelta) {
            return frame("thought", ((StreamEvent.ThoughtDelta) event).text);
        }
        if (event instanceof StreamEvent.ToolCallRequest) {
            StreamEvent.ToolCallRequest tc = (StreamEvent.ToolCallRequest) event;
            return frame("tool_call", "[tool] " + tc.name + " args=" + tc.argumentsJson);
        }
        if (event instanceof StreamEvent.ToolResult) {
            StreamEvent.ToolResult tr = (StreamEvent.ToolResult) event;
            return frame("tool_result", "[" + tr.name + "] " + (tr.success ? "OK: " : "ERROR: ")
                    + (tr.success ? String.valueOf(tr.result) : tr.error));
        }
        if (event instanceof StreamEvent.SteerInjected) {
            return frame("steer", "[steer] " + ((StreamEvent.SteerInjected) event).text);
        }
        if (event instanceof StreamEvent.Compacted) {
            StreamEvent.Compacted cp = (StreamEvent.Compacted) event;
            return frame("compact", "[compact] " + cp.fromCount + " -> " + cp.toCount
                    + " 条消息已压缩为摘要");
        }
        if (event instanceof StreamEvent.FinalDelta) {
            return frame("final", ((StreamEvent.FinalDelta) event).text);
        }
        if (event instanceof StreamEvent.Done) {
            StreamEvent.Done d = (StreamEvent.Done) event;
            return frame("done", "[DONE] steps=" + d.totalSteps + " replyLen=" + d.reply.length());
        }
        if (event instanceof StreamEvent.ErrorEvent) {
            return frame("error", String.valueOf(((StreamEvent.ErrorEvent) event).cause.getMessage()));
        }
        return null;
    }

    private static void writeEvent(OutputStream os, String event, String data) throws IOException {
        String frame = frame(event, data);
        if (frame != null) {
            write(os, frame);
        }
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String frame(String event, String data) {
        String oneLine = data == null ? "" : data.replace("\r", "").replace("\n", "\\n");
        return "event: " + event + "\ndata: " + oneLine + "\n\n";
    }

    // ===== 会话 / skill / center 端点 =====

    private void sessions(HttpExchange ex, String method) throws IOException {
        if ("POST".equals(method)) {
            json(ex, 200, singleton("id", agent.newSession()));
            return;
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (SessionManager.SessionSummary s : agent.listSessions()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", s.id);
            m.put("title", s.title);
            m.put("createdAt", s.createdAt);
            m.put("messageCount", s.messageCount);
            out.add(m);
        }
        json(ex, 200, out);
    }

    private void sessionSwitch(HttpExchange ex, String body) throws IOException {
        String id = str(parseObject(body).get("id"));
        if (id.isEmpty()) {
            json(ex, 400, error("id 不能为空"));
            return;
        }
        // 判词必须问过库：原先无条件 ok:true，切到一个不存在的 id 也照样说成功
        // （SessionManager#switchSession 对未知 id 是静默 no-op，BotAgent#switchSession 又把入参原样返回）。
        if (!sessionExists(id)) {
            json(ex, 404, error("未找到会话: " + id));
            return;
        }
        agent.switchSession(id);
        json(ex, 200, ok());
    }

    private void sessionDelete(HttpExchange ex, String body) throws IOException {
        String id = str(parseObject(body).get("id"));
        if (id.isEmpty()) {
            json(ex, 400, error("id 不能为空"));
            return;
        }
        // 库里没有就是没有：原先 deleteSession("不存在的 id") 也回 ok:true。
        if (!sessionExists(id)) {
            json(ex, 404, error("未找到会话: " + id));
            return;
        }
        agent.deleteSession(id);
        json(ex, 200, ok());
    }

    /** 会话在不在库里 —— 只读 {@link BotAgent#listSessions()}，不改 {@code BotAgent} 一个字节。 */
    private boolean sessionExists(String id) {
        for (SessionManager.SessionSummary s : agent.listSessions()) {
            if (id.equals(s.id)) {
                return true;
            }
        }
        return false;
    }

    private void sessionMessages(HttpExchange ex) throws IOException {
        String id = queryParam(ex, "id");
        List<Msg> messages = id.isEmpty()
                ? agent.getMemory().getConversationMessages()
                : agent.getSessionManager().loadMessages(id);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Msg m : messages) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("role", m.getRole() == null ? "user" : m.getRole().name().toLowerCase());
            map.put("content", m.getContent());
            map.put("toolCalls", m.getToolCalls());
            map.put("toolCallId", m.getToolCallId());
            if (!m.getToolCalls().isEmpty()) {
                map.put("contentType", "tool_call");
                map.put("toolName", m.getToolCalls().get(0).getName());
                map.put("isFinal", false);
            } else if (m.getRole() == com.zifang.z.agent.kernel.types.MessageRole.TOOL) {
                map.put("contentType", "tool_result");
                map.put("isFinal", false);
            } else {
                map.put("contentType", "text");
                map.put("isFinal", true);
            }
            out.add(map);
        }
        json(ex, 200, out);
    }

    private List<Map<String, Object>> toolListResult() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (com.zifang.z.agent.kernel.tool.Tool tool : agent.getToolkit().getAllTools()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", tool.getName());
            m.put("description", tool.getDescription());
            out.add(m);
        }
        return out;
    }

    /**
     * P19 单源：命令面就是 {@code CommandCatalog} 那一张表，HTTP 只序列化、不另立清单。
     *
     * <p>技能派生的那一段读 {@code SlashRegistry.live()}（注册表里已经有它，{@code /help} 与终端
     * 补全都从同一处取），所以这里既没有第二份命令清单、也没有第二次扫盘。</p>
     */
    private List<Map<String, Object>> commandListResult() {
        List<Map<String, Object>> out =
                new ArrayList<Map<String, Object>>(CommandCatalog.asRows());
        SlashRegistry live = SlashRegistry.live();
        if (live != null) {
            for (String key : live.skillCommandKeys()) {
                SlashCommand c = live.find(key);
                out.add(CommandCatalog.skillRow(key, c == null ? "" : c.description()));
            }
        }
        return out;
    }

    private Map<String, Object> skillSyncResult() {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("ok", true);
        resp.put("installed", agent.syncSkillsFromCenter());
        resp.put("instanceCode", agent.getInstanceCode());
        resp.put("timestamp", System.currentTimeMillis());
        return resp;
    }

    private Map<String, Object> skillListResult() {
        List<String> codes = agent.listInstalledSkills();
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("instanceCode", agent.getInstanceCode());
        resp.put("count", codes.size());
        resp.put("skillCodes", codes);
        return resp;
    }

    /**
     * {@code GET /api/cron} 列任务; {@code POST /api/cron} 操作:
     * {@code {action: add, name?, prompt, schedule}} / {@code {action: remove|pause|resume, id}}。
     */
    private Map<String, Object> cronResult(String method, String body) {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        CronScheduler scheduler = agent.getCronScheduler();
        if ("POST".equals(method)) {
            if (scheduler == null) {
                resp.put("ok", false);
                resp.put("error", "cron scheduler not enabled");
                return resp;
            }
            Map<String, Object> req = parseObject(body);
            String action = str(req.get("action"));
            if ("add".equals(action)) {
                String prompt = str(req.get("prompt"));
                String schedule = str(req.get("schedule"));
                if (prompt.isEmpty() || schedule.isEmpty()) {
                    resp.put("ok", false);
                    resp.put("error", "prompt 和 schedule 不能为空");
                    return resp;
                }
                CronJob job = scheduler.add(or(str(req.get("name")), prompt), prompt, schedule);
                resp.put("ok", true);
                resp.put("job", cronJobMap(job));
                return resp;
            }
            String id = str(req.get("id"));
            boolean ok;
            if ("remove".equals(action)) {
                ok = scheduler.remove(id);
            } else if ("pause".equals(action)) {
                ok = scheduler.setEnabled(id, false);
            } else if ("resume".equals(action)) {
                ok = scheduler.setEnabled(id, true);
            } else {
                resp.put("ok", false);
                resp.put("error", "未知 action: " + action);
                return resp;
            }
            resp.put("ok", ok);
            resp.put("message", ok ? "ok" : "未找到任务: " + id);
            return resp;
        }
        List<CronJob> jobs = scheduler == null ? Collections.<CronJob>emptyList() : scheduler.list();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (CronJob job : jobs) {
            out.add(cronJobMap(job));
        }
        resp.put("count", out.size());
        resp.put("jobs", out);
        return resp;
    }

    private static Map<String, Object> cronJobMap(CronJob job) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", job.id);
        m.put("name", job.name);
        m.put("prompt", job.prompt);
        m.put("schedule", job.schedule);
        m.put("enabled", job.enabled);
        m.put("lastRun", job.lastRun);
        m.put("lastResult", job.lastResult);
        return m;
    }

    /**
     * {@code GET /api/models} — 模型目录（ModelCatalogCache 分桶缓存）。
     * {@code ?refresh=1} 触发异步重拉，立即返回当前缓存。
     */
    private Map<String, Object> modelsResult(HttpExchange ex) {
        BotConfig cfg = agent.getConfig();
        String code = agent.getProviderCode();
        if (cfg != null && cfg.getActiveProviderCode() != null) {
            code = cfg.getActiveProviderCode();
        }
        com.zifang.z.bot.llm.ModelCatalogCache cache = modelCatalog();
        java.util.function.Supplier<List<com.zifang.z.agent.kernel.llm.Model>> supplier =
                () -> agent.getProvider().listModels();

        String query = ex.getRequestURI().getQuery();
        boolean refresh = query != null && query.contains("refresh=1");
        List<com.zifang.z.agent.kernel.llm.Model> models = refresh
                ? cache.refreshAsync(code, supplier)
                : cache.catalog(code, supplier);

        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (com.zifang.z.agent.kernel.llm.Model m : models) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", m.getId());
            row.put("displayName", m.getDisplayName());
            row.put("provider", m.getProvider());
            row.put("contextWindow", m.getContextWindow());
            row.put("maxOutputTokens", m.getMaxOutputTokens());
            List<String> caps = new ArrayList<String>();
            for (com.zifang.z.agent.kernel.llm.Model.Capability c : m.getCapabilities()) {
                caps.add(c.name());
            }
            row.put("capabilities", caps);
            out.add(row);
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("provider", code);
        resp.put("model", agent.getModel());
        resp.put("count", out.size());
        resp.put("models", out);
        resp.put("fetchedAt", cache.fetchedAt(code));
        resp.put("stale", cache.isStale(code));
        resp.put("refreshing", refresh);
        return resp;
    }

    private com.zifang.z.bot.llm.ModelCatalogCache modelCatalog() {
        com.zifang.z.bot.llm.ModelCatalogCache c = modelCatalog;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (modelCatalog == null) {
                BotConfig cfg = agent.getConfig();
                File dir = cfg == null ? null : cfg.getConfigDir();
                File f = dir == null ? new File(System.getProperty("java.io.tmpdir"), "zbot-models-cache.json")
                        : new File(dir, "models-cache.json");
                modelCatalog = new com.zifang.z.bot.llm.ModelCatalogCache(f);
            }
            return modelCatalog;
        }
    }

    private Map<String, Object> agentRegisterResult(String body) {
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        BotCenterClient client = agent.getCenterClient();
        if (client == null || !client.isEnabled()) {
            resp.put("ok", false);
            resp.put("error", "center client not enabled (未配置 center.url)");
            return resp;
        }
        Map<String, Object> req = parseObject(body);
        String appCode = or(str(req.get("appCode")), agent.getConfig().getAppCode());
        String userId = or(str(req.get("userId")), System.getProperty("user.name"));
        String userName = or(str(req.get("userName")), "z-bot");
        boolean ok = client.register(appCode, userId, userName, str(req.get("callbackUrl")), BotAgent.BOT_VERSION);
        resp.put("ok", ok);
        resp.put("instanceCode", client.getInstanceCode());
        resp.put("timestamp", System.currentTimeMillis());
        return resp;
    }

    private String statusLine() {
        return "Bot running, model=" + agent.getModel() + ", tools=" + agent.getToolkit().size()
                + ", provider=" + agent.getProviderCode()
                + ", instance=" + (agent.getInstanceCode() == null ? "local" : agent.getInstanceCode());
    }

    // ===== 静态资源 =====

    private void serveConsole(HttpExchange ex) throws IOException {
        InputStream is = HttpChannel.class.getResourceAsStream(CONSOLE_RESOURCE);
        if (is == null) {
            text(ex, 404, "未找到控制台资源 " + CONSOLE_RESOURCE);
            return;
        }
        byte[] html;
        try {
            html = readAll(is);
        } finally {
            is.close();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
    }

    // ===== 小工具 =====

    private static String messageOf(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            // curl -d '你好' 这类纯文本请求体直接当消息用
            return trimmed;
        }
        return str(parseObject(trimmed).get("message"));
    }

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {
});
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (RuntimeException e) {
            return Collections.emptyMap();
        }
    }

    private static String queryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return InboundLimits.readBody(ex);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = toUtf8(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** 紧凑 JSON 的 UTF-8 字节（对应 Jackson 的 {@code writeValueAsBytes}）。 */
    private static byte[] toUtf8(Object o) {
        return JsonUtil.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("error", message == null ? "unknown" : message);
        return m;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put(key, value);
        return m;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String or(String v, String fallback) {
        return v == null || v.isEmpty() ? (fallback == null ? "" : fallback) : v;
    }
}
