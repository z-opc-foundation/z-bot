package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.agent.kernel.mcp.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StreamableHTTP transport（MCP 2025-06-18 起的 HTTP 绑定）—— z-bot 侧实现，
 * 走 {@link McpClientFactory#wrap(String, McpTransport)} 这条既有缝，
 * <b>内核一个字节都不改</b>（{@code McpTransport} 是公开接口，只有 4 个方法）。
 *
 * <p>线上行为按规范逐条对齐（P21 EVIDENCE §11(b) 有对官方 Python SDK 1.27.1 起的
 * 真 server 的字节级断言）：</p>
 * <ul>
 *   <li>{@code POST <url>} + {@code Content-Type: application/json}，
 *       {@code Accept: application/json, text/event-stream} —— 两者都要给，
 *       少一个真 server 直接 406（{@code streamable_http.py:_validate_accept_header}）。</li>
 *   <li>{@code initialize} 的响应头里的 {@code mcp-session-id} 必须存下来，
 *       之后每个请求都带上；带错值 ⇒ 404 "Invalid or expired session ID"。</li>
 *   <li>握手后的每个请求带 {@code mcp-protocol-version}（值取<b>协商回来</b>的版本，不是我们自己想的）。</li>
 *   <li>notification / response 类消息 ⇒ 对端回 {@code 202 Accepted} 且<b>无响应体</b>，
 *       不能拿 2xx 当"它答对了"，也不能对着空体去 JSON 解析。</li>
 *   <li>请求类消息的响应有<b>双形态</b>：{@code application/json}（单条）与
 *       {@code text/event-stream}（SSE 帧流）。本实现两种都吃，SSE 里非本请求的帧
 *       不丢 —— 按 notification 派发（这正是内核 stdio 做不到的那条）。</li>
 *   <li>server 主动推的 {@code notifications/tools/list_changed} 走
 *       {@code GET} 独立 SSE 流（{@code streamable_http.py} 里 {@code GET_STREAM_KEY} 的落点），
 *       所以本 transport 打开一条常驻 GET SSE 流来收它。</li>
 *   <li>{@code close()} 发 {@code DELETE}（带 session id）显式结束会话。</li>
 * </ul>
 */
public final class StreamableHttpMcpTransport implements McpTransport, McpNotificationSource {

    private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpMcpTransport.class);

    /** 规范定的头名，官方 server 是按小写头做的（starlette 本身大小写不敏感）。 */
    static final String SESSION_HEADER = "mcp-session-id";
    static final String PROTOCOL_VERSION_HEADER = "mcp-protocol-version";
    static final String ACCEPT_BOTH = "application/json, text/event-stream";

    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    /** 不可变配置。 */
    public static final class Options {
        String serverName = "http";
        String url;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        boolean notificationStream = true;
        String clientName = "z-bot";
        String clientVersion = "0.2.0";

        public Options serverName(String v) {
            this.serverName = v;
            return this;
        }

        public Options url(String v) {
            this.url = v;
            return this;
        }

        public Options headers(Map<String, String> v) {
            this.headers = v == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(v);
            return this;
        }

        public Options timeoutMillis(long v) {
            this.timeoutMillis = v <= 0 ? DEFAULT_TIMEOUT_MILLIS : v;
            return this;
        }

        public Options notificationStream(boolean v) {
            this.notificationStream = v;
            return this;
        }

        public Options clientVersion(String v) {
            this.clientVersion = v == null ? "unknown" : v;
            return this;
        }
    }

    private final Options options;
    private final AtomicLong nextId = new AtomicLong();
    private final ExecutorService notificationDispatcher =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "mcp-http-notify-" + options.serverName);
                    t.setDaemon(true);
                    return t;
                }
            });

    private volatile boolean opened;
    private volatile String sessionId;
    private volatile String negotiatedProtocolVersion;
    private volatile McpNotificationListener listener;
    private volatile Thread getStreamThread;
    private volatile HttpURLConnection getStreamConnection;
    private volatile boolean getStreamRunning;

    private volatile String serverProtocolVersion;
    private volatile String serverInfoName;
    private volatile String serverInfoVersion;
    private final AtomicLong notificationCount = new AtomicLong();
    private final AtomicLong toolsListChangedCount = new AtomicLong();
    private final AtomicLong jsonShapeResponses = new AtomicLong();
    private final AtomicLong sseShapeResponses = new AtomicLong();
    private final List<String> wireLog = new ArrayList<String>();

    public StreamableHttpMcpTransport(Options options) {
        if (options == null || options.url == null || options.url.isEmpty()) {
            throw new IllegalArgumentException("mcp http url is empty");
        }
        this.options = options;
    }

    public StreamableHttpMcpTransport(String url) {
        this(new Options().url(url));
    }

    // ---------------------------------------------------------------- McpTransport

    @Override
    public synchronized void open() throws Exception {
        if (opened) {
            return;
        }
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", options.clientName);
        clientInfo.put("version", options.clientVersion);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", McpWire.PROTOCOL_VERSION);
        params.put("capabilities", new LinkedHashMap<String, Object>());
        params.put("clientInfo", clientInfo);
        long id = nextId.incrementAndGet();

        HttpReply reply = post(McpWire.request(id, "initialize", params), null, true);
        if (reply.status != 200) {
            throw new IOException("mcp initialize 返回 HTTP " + reply.status + ": "
                    + SecretRedaction.scrub(McpWire.abbreviate(reply.body, 200), options.headers));
        }
        // 会话是握手响应头给的，没给就等于这个 server 不接受有状态会话
        String sid = reply.header(SESSION_HEADER);
        if (sid != null && !sid.isEmpty()) {
            sessionId = sid;
        }
        String payload = reply.isSse() ? firstDataFrame(reply.body) : reply.body;
        JsonNode node = McpWire.read(payload);
        JsonNode result = node == null ? null : node.get("result");
        if (result == null || result.isNull()) {
            throw new IOException("mcp initialize 没有 result: "
                    + SecretRedaction.scrub(McpWire.abbreviate(payload, 200), options.headers));
        }
        Long echoedId = McpWire.idOf(node);
        if (echoedId == null || echoedId.longValue() != id) {
            throw new IOException("mcp initialize 响应 id 不匹配：发 " + id + " 收到 " + echoedId);
        }
        JsonNode pv = result.get("protocolVersion");
        serverProtocolVersion = pv == null ? null : pv.asText();
        negotiatedProtocolVersion = serverProtocolVersion == null
                ? McpWire.PROTOCOL_VERSION : serverProtocolVersion;
        JsonNode si = result.get("serverInfo");
        if (si != null && si.isObject()) {
            serverInfoName = text(si.get("name"));
            serverInfoVersion = text(si.get("version"));
        }
        opened = true;

        HttpReply ack = post(McpWire.notification("notifications/initialized", null), null, false);
        if (ack.status != 202 && ack.status != 200) {
            throw new IOException("notifications/initialized 返回 HTTP " + ack.status);
        }
        if (options.notificationStream) {
            startGetStream();
        }
    }

    @Override
    public synchronized String request(String requestJson) throws Exception {
        if (!opened) {
            throw new IllegalStateException("transport not open");
        }
        Long id = McpWire.idOf(requestJson);
        if (id == null) {
            HttpReply reply = post(requestJson, null, false);
            if (reply.status != 202 && reply.status != 200) {
                throw new IOException("mcp notification 返回 HTTP " + reply.status);
            }
            return null;
        }
        HttpReply reply = post(requestJson, null, true);
        if (reply.status == 404) {
            throw new IOException("mcp 会话失效（404 Invalid or expired session ID）");
        }
        if (reply.status != 200) {
            throw new IOException("mcp HTTP " + reply.status + ": "
                    + SecretRedaction.scrub(McpWire.abbreviate(reply.body, 200), options.headers));
        }
        if (reply.isSse()) {
            sseShapeResponses.incrementAndGet();
            return pickFromSse(reply.body, id.longValue());
        }
        jsonShapeResponses.incrementAndGet();
        JsonNode node = McpWire.read(reply.body);
        Long echoed = McpWire.idOf(node);
        if (echoed == null || echoed.longValue() != id.longValue()) {
            throw new IOException("mcp 响应 id 不匹配：发 " + id + " 收到 " + echoed
                    + "（形状 " + reply.contentType + "）");
        }
        return reply.body.trim();
    }

    @Override
    public synchronized void close() {
        getStreamRunning = false;
        HttpURLConnection g = getStreamConnection;
        getStreamConnection = null;
        if (g != null) {
            g.disconnect();
        }
        Thread t = getStreamThread;
        getStreamThread = null;
        if (t != null) {
            try {
                t.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String sid = sessionId;
        if (opened && sid != null) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(options.url).openConnection();
                c.setRequestMethod("DELETE");
                c.setRequestProperty(ACCEPT_KEY, ACCEPT_BOTH);
                c.setRequestProperty(SESSION_HEADER, sid);
                if (negotiatedProtocolVersion != null) {
                    c.setRequestProperty(PROTOCOL_VERSION_HEADER, negotiatedProtocolVersion);
                }
                for (Map.Entry<String, String> e : options.headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
                c.setConnectTimeout((int) options.timeoutMillis);
                c.setReadTimeout((int) options.timeoutMillis);
                c.getResponseCode();
            } catch (Exception e) {
                LOG.debug("[mcp:{}] DELETE 会话失败（忽略）: {}",
                        options.serverName, e.getMessage());
            }
        }
        opened = false;
        sessionId = null;
        notificationDispatcher.shutdownNow();
    }

    @Override
    public boolean isOpen() {
        return opened;
    }

    // -------------------------------------------------- McpNotificationSource

    @Override
    public void setNotificationListener(McpNotificationListener l) {
        this.listener = l;
    }

    @Override
    public boolean notificationCapable() {
        // 只有真开了 GET SSE 流才有 server→client 通道；关了就不能广告 listChanged
        return options.notificationStream;
    }

    // ---------------------------------------------------------------- 读数

    public String sessionId() {
        return sessionId;
    }

    public String serverProtocolVersion() {
        return serverProtocolVersion;
    }

    public String negotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    public String serverInfoName() {
        return serverInfoName;
    }

    public String serverInfoVersion() {
        return serverInfoVersion;
    }

    public long notificationCount() {
        return notificationCount.get();
    }

    public long toolsListChangedCount() {
        return toolsListChangedCount.get();
    }

    public long jsonShapeResponses() {
        return jsonShapeResponses.get();
    }

    public long sseShapeResponses() {
        return sseShapeResponses.get();
    }

    /** 脱敏后的线级台账（只留 method / status / 形状，不留 body）—— 给验收与 /status 用。 */
    public List<String> wireLog() {
        synchronized (wireLog) {
            return new ArrayList<String>(wireLog);
        }
    }

    // ---------------------------------------------------------------- 内部

    private static final String ACCEPT_KEY = "Accept";

    private String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private void noteWire(String line) {
        synchronized (wireLog) {
            if (wireLog.size() >= 200) {
                wireLog.remove(0);
            }
            wireLog.add(line);
        }
    }

    /**
     * @param expectBody true ⇒ 请求是"要响应的"（带 id），false ⇒ notification（只吃 202）
     */
    private HttpReply post(String body, String overrideSession, boolean expectBody) throws IOException {
        HttpURLConnection c = open("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty(ACCEPT_KEY, expectBody ? ACCEPT_BOTH : "application/json");
        c.setDoOutput(true);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        applySession(c, overrideSession);
        c.connect();
        OutputStream out = c.getOutputStream();
        try {
            out.write(payload);
            out.flush();
        } finally {
            out.close();
        }
        HttpReply reply = read(c);
        String method = methodOf(body);
        noteWire("POST " + (method == null ? "?" : method) + " -> " + reply.status
                + " " + reply.contentType);
        return reply;
    }

    private HttpURLConnection open(String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(options.url).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout((int) Math.min(options.timeoutMillis, Integer.MAX_VALUE));
        c.setReadTimeout((int) Math.min(options.timeoutMillis, Integer.MAX_VALUE));
        for (Map.Entry<String, String> e : options.headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        return c;
    }

    private void applySession(HttpURLConnection c, String overrideSession) {
        String sid = overrideSession != null ? overrideSession : sessionId;
        if (sid != null && !sid.isEmpty()) {
            c.setRequestProperty(SESSION_HEADER, sid);
        }
        if (negotiatedProtocolVersion != null) {
            c.setRequestProperty(PROTOCOL_VERSION_HEADER, negotiatedProtocolVersion);
        }
    }

    private static HttpReply read(HttpURLConnection c) throws IOException {
        HttpReply r = new HttpReply();
        r.status = c.getResponseCode();
        r.contentType = c.getContentType();
        Map<String, List<String>> h = c.getHeaderFields();
        if (h != null) {
            for (Map.Entry<String, List<String>> e : h.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    r.headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
                }
            }
        }
        InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? "" : drain(in);
        return r;
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String methodOf(String json) {
        JsonNode node = McpWire.read(json);
        return McpWire.methodOf(node);
    }

    /** SSE 里取第一个 {@code data:} 帧（initialize 用得上：官方 server 可能以 SSE 回握手）。 */
    static String firstDataFrame(String sse) {
        List<String> frames = sseFrames(sse);
        return frames.isEmpty() ? "" : frames.get(0);
    }

    /**
     * 从 SSE 文本里挑出 id 匹配的那条响应；<b>不匹配的帧不丢</b>，按 notification 派发。
     *
     * <p>这一句就是与内核 stdio 的分水岭：内核是 {@code continue}（丢弃），这里是 {@link #dispatch}。</p>
     */
    private String pickFromSse(String sse, long wantId) throws IOException {
        List<String> frames = sseFrames(sse);
        for (String frame : frames) {
            JsonNode node = McpWire.read(frame);
            Long id = McpWire.idOf(node);
            if (id != null && id.longValue() == wantId) {
                return frame.trim();
            }
            String method = McpWire.methodOf(node);
            if (method != null) {
                dispatch(method, McpWire.paramsOf(node));
            }
        }
        throw new IOException("SSE 流里没有 id=" + wantId + " 的响应（帧数 " + frames.size() + "）");
    }

    /** 把 SSE 文本切成帧：一帧可以有多行 {@code data:}，空行结束一帧；{@code :} 开头是心跳注释。 */
    static List<String> sseFrames(String sse) {
        List<String> out = new ArrayList<String>();
        if (sse == null || sse.isEmpty()) {
            return out;
        }
        StringBuilder data = new StringBuilder();
        boolean inFrame = false;
        for (String raw : sse.split("\r?\n", -1)) {
            String line = raw;
            if (line.isEmpty()) {
                if (inFrame && data.length() > 0) {
                    out.add(data.toString());
                    data.setLength(0);
                }
                inFrame = false;
                continue;
            }
            if (line.startsWith(":")) {
                continue; // keep-alive 注释
            }
            if (line.startsWith("data:")) {
                String v = line.substring(5);
                if (v.startsWith(" ")) {
                    v = v.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(v);
                inFrame = true;
            }
            // event:/id:/retry: 对 JSON-RPC over SSE 是固定值或断点续传用，本实现不需要
        }
        if (data.length() > 0) {
            out.add(data.toString());
        }
        return out;
    }

    /**
     * 常驻 GET SSE 流：server 主动消息（{@code notifications/tools/list_changed}）的唯一落点。
     * 官方 server 把无 related_request_id 的消息路由到 {@code _GET_stream}。
     */
    private void startGetStream() {
        getStreamRunning = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runGetStream();
            }
        }, "mcp-http-get-" + options.serverName);
        t.setDaemon(true);
        getStreamThread = t;
        t.start();
    }

    private void runGetStream() {
        HttpURLConnection c = null;
        try {
            c = open("GET");
            c.setRequestProperty(ACCEPT_KEY, "text/event-stream");
            c.setRequestProperty("Cache-Control", "no-cache");
            // 常驻流：读超时给 0，靠 close() 的 disconnect() 打断，不靠轮询
            c.setReadTimeout(0);
            applySession(c, null);
            getStreamConnection = c;
            int status = c.getResponseCode();
            if (status != 200) {
                noteWire("GET notification-stream -> " + status);
                LOG.warn("[mcp:{}] server 不接受通知流（HTTP {}）—— listChanged 不可用",
                        options.serverName, status);
                return;
            }
            noteWire("GET notification-stream -> 200 text/event-stream");
            InputStream in = c.getInputStream();
            StringBuilder data = new StringBuilder();
            boolean inFrame = false;
            java.io.BufferedReader rd =
                    new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while (getStreamRunning && (line = rd.readLine()) != null) {
                if (line.isEmpty()) {
                    if (inFrame && data.length() > 0) {
                        onFrame(data.toString());
                        data.setLength(0);
                    }
                    inFrame = false;
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String v = line.substring(5);
                    if (v.startsWith(" ")) {
                        v = v.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(v);
                    inFrame = true;
                }
            }
        } catch (IOException e) {
            if (getStreamRunning) {
                LOG.warn("[mcp:{}] 通知流断了: {}", options.serverName, e.getMessage());
            }
        } finally {
            getStreamRunning = false;
            getStreamConnection = null;
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void onFrame(String frame) {
        JsonNode node = McpWire.read(frame);
        String method = McpWire.methodOf(node);
        if (method == null) {
            return;
        }
        if (McpWire.idOf(node) != null) {
            return; // GET 流上的响应帧不属于任何等待方
        }
        dispatch(method, McpWire.paramsOf(node));
    }

    private void dispatch(String method, Map<String, Object> params) {
        notificationCount.incrementAndGet();
        if (McpNotificationListener.TOOLS_LIST_CHANGED.equals(method)) {
            toolsListChangedCount.incrementAndGet();
        }
        final McpNotificationListener l = listener;
        if (l == null) {
            return;
        }
        final String fm = method;
        final Map<String, Object> fp = params;
        try {
            notificationDispatcher.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        l.onNotification(fm, fp);
                    } catch (RuntimeException e) {
                        LOG.warn("[mcp:{}] notification 处理失败 {}: {}",
                                options.serverName, fm, e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** 一次 HTTP 往返的最小读数。 */
    private static final class HttpReply {
        int status;
        String contentType;
        String body = "";
        final Map<String, String> headers = new LinkedHashMap<String, String>();

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        boolean isSse() {
            return contentType != null && contentType.toLowerCase(Locale.ROOT)
                    .startsWith("text/event-stream");
        }
    }
}
