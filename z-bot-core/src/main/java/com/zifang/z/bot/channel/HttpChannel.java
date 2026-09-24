package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamEvent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>会话：{@code GET/POST /api/sessions}、{@code /api/session/switch|delete}、{@code /api/session/messages}</li>
 *   <li>center 联动：{@code /api/skill/push|list|sync}、{@code /api/agent/register}，控制面 {@code /bot/status}、{@code /bot/clear}</li>
 * </ul>
 *
 * <p>SSE 帧里 {@code data:} 不能带裸换行，所以文本统一转义成 {@code \n} 两字符，前端再还原。</p>
 */
public final class HttpChannel {

    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONSOLE_RESOURCE = "/web/index.html";

    private final BotAgent agent;
    private final int port;

    private HttpServer server;
    private ExecutorService workers;
    private final CountDownLatch termination = new CountDownLatch(1);

    public HttpChannel(BotAgent agent, int port) {
        this.agent = agent;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "z-bot-http");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/", this::dispatch);
        server.setExecutor(workers);
        server.start();
        System.out.println("[HTTP] z-bot 已启动: http://127.0.0.1:" + getPort() + "/index.html");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        agent.shutdown();
        termination.countDown();
    }

    /** 实际监听端口（传入 0 时由系统分配）。 */
    public int getPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    /** 阻塞到 {@link #stop()}，供 serve 子命令保持进程存活。 */
    public void awaitTermination() {
        try {
            termination.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            } else if ("/api/agent/register".equals(path)) {
                json(ex, 200, agentRegisterResult(readBody(ex)));
            } else {
                json(ex, 404, error("not found: " + path));
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
        agent.switchSession(id);
        json(ex, 200, ok());
    }

    private void sessionDelete(HttpExchange ex, String body) throws IOException {
        String id = str(parseObject(body).get("id"));
        if (id.isEmpty()) {
            json(ex, 400, error("id 不能为空"));
            return;
        }
        agent.deleteSession(id);
        json(ex, 200, ok());
    }

    private void sessionMessages(HttpExchange ex) throws IOException {
        String id = queryParam(ex, "id");
        List<Msg> messages = id.isEmpty()
                ? agent.getMemory().getConversationMessages()
                : new SessionManager(agent.getSessionManager().getSessionDir()).loadMessages(id);
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
            Map<String, Object> parsed = JSON.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (IOException e) {
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
        try (InputStream is = ex.getRequestBody()) {
            return new String(readAll(is), StandardCharsets.UTF_8);
        }
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
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
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
