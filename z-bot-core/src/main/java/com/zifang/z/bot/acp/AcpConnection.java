package com.zifang.z.bot.acp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ACP 连接：换行分帧的 JSON-RPC 2.0 分派器。
 *
 * <h2>三件事它负责，别的事不归它</h2>
 * <ol>
 *   <li><b>分派</b>：请求 → {@link AcpRequestHandler}，通知 → {@link AcpNotificationHandler}。
 *       <b>未知方法大声失败</b>（工单 §1.1）：请求回 {@code -32601} 且 {@code data.knownMethods}
 *       带上全集；通知按 JSON-RPC 规矩不能回包，于是走 stderr + {@link #unknownNotifications()}
 *       计数，测试据此断言"没有静默忽略"。</li>
 *   <li><b>出向请求</b>：z-bot 作为 agent 要主动问客户端（{@code session/request_permission}）。
 *       等待<b>一律带上限</b>（{@link #CLIENT_REQUEST_TIMEOUT_MILLIS}），超时按 hermes 同样的
 *       保守选择处理：当作未放行。无界 await 是杠②永挂体检点名的靶子。</li>
 *   <li><b>写串行化</b>：所有帧经 {@link AcpTransport}，读写在不同线程上并发是常态
 *       （prompt 在工作线程跑、响应在读取线程回来）。</li>
 * </ol>
 *
 * <p>读取循环 {@link #serveStdio(InputStream)} 与分派 {@link #handleLine(String)} 分开，
 * 是为了让测试能同线程喂帧、逐帧看结果，而不必先起线程。</p>
 */
public final class AcpConnection {

    /** 等客户端答一次 {@code session/request_permission} 的上限秒数（对齐她 60s 的缺省 timeout）。 */
    public static final long CLIENT_REQUEST_TIMEOUT_MILLIS = 60_000L;

    /** 一个请求的处理者。同步回包用 {@code return}，异步（prompt 那种长跑）自己调 respond/fail。 */
    public interface AcpRequestHandler {
        JsonNode handle(AcpConnection conn, JsonRpc.Message msg) throws Exception;
    }

    public interface AcpNotificationHandler {
        void handle(AcpConnection conn, JsonRpc.Message msg) throws Exception;
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final AcpTransport transport;
    private final Map<String, AcpRequestHandler> requests =
            new LinkedHashMap<String, AcpRequestHandler>();
    private final Map<String, AcpNotificationHandler> notifications =
            new LinkedHashMap<String, AcpNotificationHandler>();
    private final Map<String, PendingCall> outbound = new ConcurrentHashMap<String, PendingCall>();
    private final AtomicLong outboundIds = new AtomicLong();
    private final List<String> unknownNotifications =
            java.util.Collections.synchronizedList(new ArrayList<String>());
    private final PrintStream stderr;

    private volatile boolean closed;

    /** 对端发来的、被成功分派掉的帧数（含响应）；用于 E2E 对拍"确实收到了"。 */
    private final AtomicLong framesHandled = new AtomicLong();

    public AcpConnection(AcpTransport transport) {
        this(transport, System.err);
    }

    public AcpConnection(AcpTransport transport, PrintStream stderr) {
        if (transport == null) {
            throw new IllegalArgumentException("transport 不能为 null");
        }
        this.transport = transport;
        this.stderr = stderr == null ? System.err : stderr;
    }

    // ---- 注册表 ----

    public AcpConnection onRequest(String method, AcpRequestHandler handler) {
        requests.put(method, handler);
        return this;
    }

    public AcpConnection onNotification(String method, AcpNotificationHandler handler) {
        notifications.put(method, handler);
        return this;
    }

    /** 已注册的方法名（请求 + 通知并集，插入序）；测试据此核对"派发表 = 协议面"。 */
    public List<String> registeredMethods() {
        List<String> all = new ArrayList<String>(requests.keySet());
        for (String m : notifications.keySet()) {
            if (!all.contains(m)) {
                all.add(m);
            }
        }
        return all;
    }

    public boolean isClosed() {
        return closed;
    }

    public long framesHandled() {
        return framesHandled.get();
    }

    /** 收到过的未知通知方法名（按到达序）。空 ⇒ 没有"大声失败"过。 */
    public List<String> unknownNotifications() {
        synchronized (unknownNotifications) {
            return new ArrayList<String>(unknownNotifications);
        }
    }

    // ---- 入向 ----

    /**
     * 读循环：一行一条 JSON，直到 EOF 或连接关闭。
     *
     * <p>EOF 是正常退出（IDE 关掉管道），不抛异常。</p>
     */
    public void serveStdio(InputStream in) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF_8));
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    handleLine(line);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                stderr.println("[acp] 读循环异常退出: " + e);
            }
        }
    }

    /**
     * 处理一行报文。这一句是整个"未知方法不许静默忽略"的落点。
     *
     * @return true 表示这帧被分派掉了（含未知方法的"大声失败"），false 表示解析失败已回错误
     */
    public boolean handleLine(String line) {
        JsonRpc.Message msg;
        try {
            msg = JsonRpc.parse(line);
        } catch (AcpProtocolException e) {
            framesHandled.incrementAndGet();
            // 解析错误连 id 都拿不到，按 JSON-RPC 规矩回 null id。
            send(e.requestId() == null ? JsonRpc.error(null, e.code(), e.getMessage(), e.data())
                    : JsonRpc.error(e.requestId(), e.code(), e.getMessage(), e.data()));
            return false;
        }
        framesHandled.incrementAndGet();
        if (msg.isResponse()) {
            deliverResponse(msg);
            return true;
        }
        String method = msg.method();
        if (msg.isRequest()) {
            dispatchRequest(msg, method);
            return true;
        }
        AcpNotificationHandler nh = notifications.get(method);
        if (nh == null) {
            // 通知不能回包（JSON-RPC 2.0），所以"大声"体现在 stderr + 可断言的计数上。
            unknownNotifications.add(method);
            stderr.println("[acp] 未知通知方法，已丢弃并记录（不回包，JSON-RPC 禁止对通知应答）: "
                    + method + "；已知方法见 " + AcpMethods.AGENT_METHODS);
            return true;
        }
        try {
            nh.handle(this, msg);
        } catch (Exception e) {
            stderr.println("[acp] 通知 " + method + " 处理异常（通知无回包）: " + e);
        }
        return true;
    }

    private void dispatchRequest(JsonRpc.Message msg, String method) {
        AcpRequestHandler handler = requests.get(method);
        if (handler == null) {
            // §1.1：未知方法 ⇒ -32601 且把已知方法全集带在 data 里。
            AcpProtocolException notFound = AcpProtocolException.methodNotFound(method);
            fail(msg.id(), notFound);
            stderr.println("[acp] " + notFound.getMessage());
            return;
        }
        try {
            JsonNode result = handler.handle(this, msg);
            if (result != ACP_ASYNC) {
                respond(msg.id(), result);
            }
        } catch (AcpProtocolException e) {
            fail(msg.id(), e);
        } catch (Exception e) {
            fail(msg.id(), AcpProtocolException.internal(
                    method + " 处理失败: " + e, e));
            stderr.println("[acp] " + method + " 内部异常: " + e);
        }
    }

    /** 异步处理标记：handler 已经自己接管了这条请求的应答权。 */
    public static final JsonNode ACP_ASYNC = JsonRpc.object().put("__acp_async__", Boolean.TRUE);

    /** handler 异步完成后自己回包。 */
    public void respond(JsonNode id, JsonNode result) {
        send(JsonRpc.response(id, result));
    }

    public void fail(JsonNode id, AcpProtocolException e) {
        send(JsonRpc.error(id, e.code(), e.getMessage(), e.data()));
    }

    // ---- 出向 ----

    /**
     * 向客户端发一次请求并<b>有上限地</b>等回包。
     *
     * @return 响应的 {@code result}；超时或对端报错时返回 {@code null}（调用方须按"未放行"处理）
     */
    public JsonNode requestClient(String method, JsonNode params, long timeoutMillis) {
        String id = "z-bot-" + outboundIds.incrementAndGet();
        PendingCall call = new PendingCall();
        outbound.put(id, call);
        try {
            send(JsonRpc.request(id, method, params));
            if (!call.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                stderr.println("[acp] 等客户端应答 " + method + " 超时 " + timeoutMillis + "ms，按未放行处理");
                return null;
            }
            return call.result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stderr.println("[acp] 等客户端应答 " + method + " 被中断，按未放行处理");
            return null;
        } finally {
            outbound.remove(id);
        }
    }

    /** 发一条通知（{@code session/update} 走这里）。 */
    public void notify(String method, JsonNode params) {
        send(JsonRpc.notification(method, params));
    }

    private void deliverResponse(JsonRpc.Message msg) {
        String id = msg.idText();
        if (id == null) {
            stderr.println("[acp] 收到无 id 的响应，忽略");
            return;
        }
        PendingCall call = outbound.get(id);
        if (call == null) {
            stderr.println("[acp] 收到未知 id 的响应（可能已超时出队）: " + id);
            return;
        }
        if (msg.error() != null) {
            stderr.println("[acp] 客户端报错: " + msg.error_message());
            call.result = null;
        } else {
            call.result = msg.result();
        }
        call.latch.countDown();
    }

    private void send(String line) {
        transport.sendLine(line);
    }

    public void close() {
        closed = true;
        transport.close();
        // 叫醒所有还在等客户端的人，别让线程挂在 latch 上。
        for (PendingCall call : outbound.values()) {
            call.latch.countDown();
        }
        outbound.clear();
    }

    private static final class PendingCall {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile JsonNode result;
    }
}
