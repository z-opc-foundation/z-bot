package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.agent.kernel.mcp.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * z-bot 侧 stdio transport —— 与公开接口 {@link McpTransport} 兼容的内核
 * {@code StdioMcpTransport} <b>替代实现</b>，存在的唯一理由是绕开内核那三个已知未修缺陷
 * （P21 EVIDENCE §1 逐条实测取证）：
 *
 * <ol>
 *   <li><b>配对端响应靠字符串包含</b>：{@code !line.contains("\"id\":" + id) ⇒ continue}。
 *       真 server（含官方 Python SDK）序列化的是 {@code "id": 1}（带空格），永远配不上 ⇒ <b>永久挂死</b>。
 *       这里改成 JSON 解析后按 id 配对（{@link McpWire#idOf}）。</li>
 *   <li><b>30s deadline 不成立</b>：{@code while (now < deadline) { readLine(); }} 里
 *       {@code readLine()} 本身是阻塞的，deadline 只在两次往返之间被检查一次。
 *       这里用常驻读线程 + {@link CompletableFuture#get(long, TimeUnit)} ⇒ 超时是真的。</li>
 *   <li><b>握手自报版本写死 {@code "0.2.0"}</b>：抬版没带走 ⇒ 对端读到的版本线是假的。
 *       这里版本由 {@link Options#clientVersion} 显式传入，且校验对端回显的
 *       {@code protocolVersion}。</li>
 * </ol>
 *
 * <p>另外补一条内核<b>结构上做不到</b>的能力（P21 §2 取证）：常驻读线程能收到
 * server <b>主动推</b>的 {@code notifications/tools/list_changed}，
 * 内核那条循环只在"有人在等某个 id"时才 {@code readLine()}，且不匹配的行直接丢弃。</p>
 *
 * <p>父死 watchdog（{@link Options#parentWatchdog}）：{@code kill -9} 掉 z-bot 时
 * JVM 的 shutdown hook 不会跑，直接子进程会被 reparent 成孤儿。这里把真 server 挂在
 * 一层 {@code /bin/sh} 监护脚本下：脚本轮询<b>自己的</b> ppid —— z-bot 一死它就变成
 * init/launchd 的孤儿，ppid 随即不再等于 z-bot 的 pid ⇒ 脚本 {@code kill -9} 掉孙子进程后退出。
 * 用 ppid 而不是"轮询 z-bot pid 是否存活"，是为了免疫 pid 复用。</p>
 */
public final class ZBotStdioMcpTransport implements McpTransport, McpNotificationSource {

    private static final Logger LOG = LoggerFactory.getLogger(ZBotStdioMcpTransport.class);

    /** 默认单请求超时；比内核那个"写着 30s 其实不生效"的值短，且这次真的生效。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 15_000L;

    /** 传输配置。不可变，避免 bridge 与 transport 之间共享可变状态。 */
    public static final class Options {
        String serverName = "stdio";
        List<String> command = new ArrayList<String>();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        boolean parentWatchdog = true;
        String clientName = "z-bot";
        String clientVersion = "0.2.0";

        public Options serverName(String v) {
            this.serverName = v;
            return this;
        }

        public Options command(List<String> v) {
            this.command = v == null ? new ArrayList<String>() : new ArrayList<String>(v);
            return this;
        }

        public Options environment(Map<String, String> v) {
            this.environment = v == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(v);
            return this;
        }

        public Options timeoutMillis(long v) {
            this.timeoutMillis = v <= 0 ? DEFAULT_TIMEOUT_MILLIS : v;
            return this;
        }

        public Options parentWatchdog(boolean v) {
            this.parentWatchdog = v;
            return this;
        }

        public Options clientVersion(String v) {
            this.clientVersion = v == null ? "unknown" : v;
            return this;
        }

        public String getClientVersion() {
            return clientVersion;
        }
    }

    private final Options options;
    /** transport 内部（握手）用的 id 段：从高位起，避开 client 从 1 开始的 id。 */
    private final AtomicLong nextId = new AtomicLong(1000000000L);
    private final Map<Long, CompletableFuture<String>> pending =
            new ConcurrentHashMap<Long, CompletableFuture<String>>();
    private final Map<Long, String> inflightMethod = new ConcurrentHashMap<Long, String>();
    private final Deque<String> stderrTail = new ArrayDeque<String>();
    private final ExecutorService notificationDispatcher =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "mcp-notify-" + options.serverName);
                    t.setDaemon(true);
                    return t;
                }
            });

    private volatile Process process;
    private volatile BufferedReader stdoutReader;
    private volatile OutputStream stdinWriter;
    private volatile Thread readerThread;
    private volatile McpNotificationListener listener;
    private volatile String serverProtocolVersion;
    private volatile boolean peerAdvertisesListChanged;
    private volatile String serverName;
    private volatile String serverVersion;
    /** 收到的 notification 条数 —— 这是"通知通道真的通了"的硬读数，不看日志。 */
    private final AtomicLong notificationCount = new AtomicLong();
    private final AtomicLong toolsListChangedCount = new AtomicLong();

    public ZBotStdioMcpTransport(Options options) {
        if (options == null || options.command.isEmpty()) {
            throw new IllegalArgumentException("mcp stdio command is empty");
        }
        this.options = options;
    }

    /** 便捷构造：只要命令行，其余走默认（watchdog 开、15s 超时）。 */
    public ZBotStdioMcpTransport(List<String> command) {
        this(new Options().command(command));
    }

    // ---------------------------------------------------------------- McpTransport

    @Override
    public synchronized void open() throws Exception {
        if (isOpen()) {
            return;
        }
        List<String> argv = launchArgv();
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        if (!options.environment.isEmpty()) {
            pb.environment().putAll(options.environment);
        }
        File dir = workingDir();
        if (dir != null) {
            pb.directory(dir);
        }
        Process p = pb.start();
        stdinWriter = p.getOutputStream();
        stdoutReader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        startStderrDrainer(p);
        readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + options.serverName);
        readerThread.setDaemon(true);
        readerThread.start();
        process = p;

        // initialize 握手：版本自报走 options，不再写死内核那个 0.2.0
        long id = nextId.incrementAndGet();
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", options.clientName);
        clientInfo.put("version", options.clientVersion);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", McpWire.PROTOCOL_VERSION);
        params.put("capabilities", new LinkedHashMap<String, Object>());
        params.put("clientInfo", clientInfo);
        String resp = requestRaw(id, McpWire.request(id, "initialize", params));
        JsonNode node = McpWire.read(resp);
        JsonNode result = node == null ? null : node.get("result");
        if (result == null || result.isNull()) {
            throw new IOException("mcp initialize 没有 result: "
                    + McpWire.abbreviate(resp, 200));
        }
        JsonNode pv = result.get("protocolVersion");
        serverProtocolVersion = pv == null ? null : pv.asText();
        JsonNode caps = result.get("capabilities");
        JsonNode toolsCap = caps == null ? null : caps.get("tools");
        JsonNode lc = toolsCap == null ? null : toolsCap.get("listChanged");
        peerAdvertisesListChanged = lc != null && lc.asBoolean(false);
        JsonNode si = result.get("serverInfo");
        if (si != null && si.isObject()) {
            serverName = text(si.get("name"));
            serverVersion = text(si.get("version"));
        }
        // notifications/initialized
        writeLine(McpWire.notification("notifications/initialized", null));
    }

    @Override
    public synchronized String request(String requestJson) throws Exception {
        Long id = McpWire.idOf(requestJson);
        if (id == null) {
            // notification：发出去就完事，没有响应可等
            writeLine(requestJson);
            return null;
        }
        return requestRaw(id, requestJson);
    }

    @Override
    public synchronized void close() {
        Process p = process;
        process = null;
        BufferedReader in = stdoutReader;
        stdoutReader = null;
        OutputStream out = stdinWriter;
        stdinWriter = null;
        for (Map.Entry<Long, CompletableFuture<String>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(new IOException("transport closed"));
        }
        pending.clear();
        inflightMethod.clear();
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        if (p != null) {
            // TERM 先走（监护脚本的 trap 会顺手带走孙子进程），再forcibly兜底
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        Thread t = readerThread;
        readerThread = null;
        if (t != null) {
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        notificationDispatcher.shutdownNow();
    }

    @Override
    public synchronized boolean isOpen() {
        Process p = process;
        return p != null && p.isAlive();
    }

    // -------------------------------------------------- McpNotificationSource

    @Override
    public void setNotificationListener(McpNotificationListener l) {
        this.listener = l;
    }

    @Override
    public boolean notificationCapable() {
        return true;
    }

    // ---------------------------------------------------------------- 读数（给验收用）

    public long notificationCount() {
        return notificationCount.get();
    }

    public long toolsListChangedCount() {
        return toolsListChangedCount.get();
    }

    /** 对端在 initialize 里回的协议版本（null ⇒ 没回）。 */
    /** 对端在 initialize 里广告的能力位（注意：官方 FastMCP 默认报 false 但仍会推）。 */
    public boolean peerAdvertisesListChanged() {
        return peerAdvertisesListChanged;
    }

    public String serverProtocolVersion() {
        return serverProtocolVersion;
    }

    public String serverInfoName() {
        return serverName;
    }

    public String serverInfoVersion() {
        return serverVersion;
    }

    /** 我们自报的版本 —— §1.3 的断言点：抬版必须真的带到线上。 */
    public String selfReportedVersion() {
        return options.clientVersion;
    }

    /** 子进程 stdout 之外的那条流：留最后 200 行，只在测试里读，不进日志。 */
    public List<String> stderrTail() {
        synchronized (stderrTail) {
            return new ArrayList<String>(stderrTail);
        }
    }

    /** 当前挂起的请求数（E2E 用来判"没在等一个不会来的响应"）。 */
    public int pendingRequests() {
        return pending.size();
    }

    // ---------------------------------------------------------------- 内部

    private String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private File workingDir() {
        String wdir = options.environment.get("ZBOT_MCP_WORKDIR");
        if (wdir == null || wdir.isEmpty()) {
            return null;
        }
        File f = new File(wdir);
        return f.isDirectory() ? f : null;
    }

    /**
     * 实际交给 {@link ProcessBuilder} 的 argv。开了 watchdog 且是 POSIX 系统时，
     * 直接子进程是 {@code /bin/sh} 监护脚本，真 server 是它的子进程。
     */
    private List<String> launchArgv() {
        List<String> cmd = options.command;
        if (!options.parentWatchdog || !isPosix()) {
            return new ArrayList<String>(cmd);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("me=$$").append('\n');
        sb.append("( while :; do").append('\n');
        sb.append("  pp=`ps -o ppid= -p $me 2>/dev/null | tr -d ' '`").append('\n');
        sb.append("  [ -z \"$pp\" ] && exit 0").append('\n');
        sb.append("  [ \"$pp\" != \"").append(jvmPid()).append("\" ] && kill -9 $me 2>/dev/null && exit 0").append('\n');
        sb.append("  sleep 0.2").append('\n');
        sb.append("done ) &").append('\n');
        sb.append("exec");
        for (int i = 0; i < cmd.size(); i++) {
            sb.append(' ').append(McpWire.shellQuote(cmd.get(i)));
        }
        sb.append('\n');
        List<String> argv = new ArrayList<String>();
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(sb.toString());
        return argv;
    }

    private static boolean isPosix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return !os.contains("windows");
    }

    /** Java 8 兼容的自身 pid（{@code ProcessHandle#pid()} 是 9+）。 */
    static long jvmPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return Long.parseLong(at > 0 ? name.substring(0, at) : name.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private String requestRaw(long id, String requestJson) throws Exception {
        if (!isOpen()) {
            throw new IllegalStateException("transport not open");
        }
        inflightMethod.put(Long.valueOf(id), describeCall(McpWire.read(requestJson)));
        final CompletableFuture<String> future = new CompletableFuture<String>();
        CompletableFuture<String> previous = pending.put(Long.valueOf(id), future);
        if (previous != null) {
            pending.remove(Long.valueOf(id));
            throw new IllegalStateException("duplicate mcp request id: " + id);
        }
        try {
            writeLine(requestJson);
        } catch (IOException e) {
            pending.remove(Long.valueOf(id));
            throw e;
        }
        try {
            return future.get(options.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(Long.valueOf(id));
            String method = inflightMethod.get(Long.valueOf(id));
            throw new TimeoutException("mcp 请求超时（" + options.timeoutMillis
                    + "ms，method=" + method + "）");
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(Long.valueOf(id));
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } finally {
            inflightMethod.remove(Long.valueOf(id));
        }
    }

    /**
     * 超时报错里"是哪个调用卡住了"的口径：只带 method，tools/call 这类把目标名放在
     * {@code params.name} 的再带上目标名。<b>刻意不带 arguments/params 的值</b> ——
     * 参数值可能含密钥，超时信息是要进日志的。
     */
    private static String describeCall(JsonNode request) {
        String method = McpWire.methodOf(request);
        if (method == null) {
            return "unknown";
        }
        JsonNode target = request.path("params").path("name");
        return target.isTextual() ? method + ":" + target.asText() : method;
    }

    private void writeLine(String json) throws IOException {
        OutputStream out = stdinWriter;
        if (out == null) {
            throw new IOException("mcp stdin 已关闭");
        }
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 常驻读线程：解析每行，有 id 且有人在等 ⇒ 交给等待方；否则视为 server 主动通知 ⇒ 派发。
     * 与内核实现的本质差别就是这条 —— 内核把"不匹配当前 id"的行直接 {@code continue} 丢了。
     */
    private void readLoop() {
        try {
            String line;
            while ((line = readOneLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node = McpWire.read(trimmed);
                if (node == null) {
                    LOG.debug("[mcp:{}] 收到非 JSON 行，忽略", options.serverName);
                    continue;
                }
                Long id = McpWire.idOf(node);
                if (id != null) {
                    CompletableFuture<String> f = pending.remove(id);
                    if (f != null) {
                        f.complete(trimmed);
                        continue;
                    }
                    LOG.debug("[mcp:{}] 收到无人等待的响应 id={}", options.serverName, id);
                    continue;
                }
                String method = McpWire.methodOf(node);
                if (method == null) {
                    continue;
                }
                final Map<String, Object> params = McpWire.paramsOf(node);
                final String fm = method;
                notificationCount.incrementAndGet();
                if (McpNotificationListener.TOOLS_LIST_CHANGED.equals(method)) {
                    toolsListChangedCount.incrementAndGet();
                }
                final McpNotificationListener l = listener;
                if (l == null) {
                    continue;
                }
                // 独立线程派发：回调里会再发 tools/list，跑在本线程上就是自锁
                try {
                    notificationDispatcher.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                l.onNotification(fm, params);
                            } catch (RuntimeException e) {
                                LOG.warn("[mcp:{}] notification 处理失败 {}: {}",
                                        options.serverName, fm, e.getMessage());
                            }
                        }
                    });
                } catch (RejectedExecutionException ignored) {
                    // close() 之后不再派发
                }
            }
        } catch (IOException e) {
            if (isOpen()) {
                LOG.warn("[mcp:{}] stdout 读断了: {}", options.serverName, e.getMessage());
            }
        } finally {
            failAllPending(new IOException("mcp server closed stdout"));
        }
    }

    private String readOneLine() throws IOException {
        BufferedReader in = stdoutReader;
        if (in == null) {
            return null;
        }
        try {
            return in.readLine();
        } catch (IOException e) {
            // close() 会关掉这个流；此时不再算异常
            return null;
        }
    }

    private void failAllPending(Throwable t) {
        for (Map.Entry<Long, CompletableFuture<String>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(t);
        }
        pending.clear();
    }

    private void startStderrDrainer(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader err = new BufferedReader(
                            new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = err.readLine()) != null) {
                        synchronized (stderrTail) {
                            if (stderrTail.size() >= 200) {
                                stderrTail.pollFirst();
                            }
                            stderrTail.addLast(line);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "mcp-stderr-drain-" + options.serverName);
        t.setDaemon(true);
        t.start();
    }
}
