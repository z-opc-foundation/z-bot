package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 通用 Webhook 通道：上游服务 POST 消息进来 → 转 {@link ChannelMessage} 给 bus；
 * 出站用轮询 + 短轮转的方式：
 * <ul>
 *   <li>入站 POST {@code /webhook/in}：{@code { conversationId, senderId, text }} → bus</li>
 *   <li>出站 GET {@code /webhook/out}：上游轮询拿走队列里待发送的回复</li>
 *   <li>备用：入站 POST 时若带 {@code callbackUrl}，回执也尝试 POST 回去（一次性，回完即弃）</li>
 * </ul>
 *
 * <p>为什么不用单一回调：飞书/钉钉/Slack 都是单向 webhook + 加密校验，本通道提供"入站 webhook +
 * 轮询出站"这一最通用形态；具体 IM 平台在自家通道里做签名 + 富文本渲染。</p>
 */
public final class WebhookChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChannelBus bus;
    private final int port;
    private final String name;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;

    private HttpServer server;
    private ExecutorService workers;
    private final LinkedBlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<Map<String, Object>>();
    private final CountDownLatch termination = new CountDownLatch(1);
    private volatile boolean running;

    public WebhookChannel(ChannelBus bus, int port) {
        this(bus, port, "webhook");
    }

    public WebhookChannel(ChannelBus bus, int port, String name) {
        this(bus, port, name, null);
    }

    public WebhookChannel(ChannelBus bus, int port, String name, String host) {
        this.bus = bus;
        this.port = port;
        this.name = name;
        this.host = host;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(ChannelBind.resolve(host), port), 0);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "z-bot-webhook");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/webhook/in", this::handleInbound);
        server.createContext("/webhook/out", this::handleOutboundPoll);
        server.setExecutor(workers);
        server.start();
        running = true;
        LOG.info("[webhook] 已启动: http://{}:{}/webhook/in", ChannelBind.describe(getBindAddress()), getPort());
        String warning = ChannelBind.exposureWarning(getBindAddress());
        if (warning != null) {
            LOG.warn("[webhook] 警告: {}", warning);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        termination.countDown();
    }

    @Override
    public void awaitTermination() {
        try {
            termination.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return server == null ? port : server.getAddress().getPort();
    }

    /** 实际监听的地址 — 守卫测试据此确认缺省没有绑到通配。 */
    java.net.InetAddress getBindAddress() {
        return server == null ? null : server.getAddress().getAddress();
    }

    @Override
    public void send(OutboundMessage message) {
        if (message == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("conversationId", message.replyTo);
        payload.put("text", message.text);
        payload.put("kind", message.kind == OutboundMessage.Kind.ERROR ? "error" : "text");
        payload.put("ts", System.currentTimeMillis());
        outbound.offer(payload);
    }

    // ===== 路由 =====

    private void handleInbound(HttpExchange ex) throws IOException {
        cors(ex);
        try {
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                text(ex, 405, "method not allowed");
                return;
            }
            String body = readBody(ex);
            Map<String, Object> req = parseObject(body);
            String conversationId = str(req.get("conversationId"));
            String text = str(req.get("text"));
            String senderId = str(req.get("senderId"));
            String callbackUrl = str(req.get("callbackUrl"));
            if (conversationId.isEmpty() || text.isEmpty()) {
                text(ex, 400, "conversationId 和 text 不能为空");
                return;
            }
            ChannelMessage msg = new ChannelMessage(name, conversationId, senderId.isEmpty() ? "anonymous" : senderId, text);
            msg.raw = body;
            bus.deliver(msg);
            // 如果带了 callbackUrl，给 reply 加一条 callback 任务（bus 异步回完才执行，所以现在登记）
            if (!callbackUrl.isEmpty()) {
                postBackLater(callbackUrl, conversationId, text);
            }
            text(ex, 200, "{\"ok\":true}");
        } catch (BodyTooLargeException e) {
            LOG.warn("[webhook] 入站超限: {}", e.getMessage());
            try {
                text(ex, 413, "{\"ok\":false,\"error\":\"request body too large\"}");
            } catch (IOException ignored) {
            }
        } catch (Exception e) {
            LOG.warn("[webhook] 入站处理失败: {}", e.getMessage());
            try {
                text(ex, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private void postBackLater(String callbackUrl, String conversationId, String triggerText) {
        // bus 回完会把 OutboundMessage 投到 outbound 队列；
        // 这里挂个监听器：出队时尝试 POST 到 callbackUrl
        workers.submit(() -> {
            Map<String, Object> payload = null;
            try {
                payload = outbound.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (payload == null || !conversationId.equals(payload.get("conversationId"))) {
                if (payload != null) {
                    outbound.offer(payload); // 不是这条会话的回执，放回队列
                }
                return;
            }
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(callbackUrl).openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setConnectTimeout(3000);
                con.setReadTimeout(5000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(JSON.writeValueAsBytes(payload));
                }
                int rc = con.getResponseCode();
                LOG.info("[webhook] 回执 callbackUrl={} rc={} conv={}", callbackUrl, rc, conversationId);
            } catch (IOException e) {
                LOG.warn("[webhook] 回执失败 callbackUrl={} conv={}: {}", callbackUrl, conversationId, e.getMessage());
            }
        });
    }

    private void handleOutboundPoll(HttpExchange ex) throws IOException {
        cors(ex);
        try {
            Map<String, Object> payload;
            try {
                payload = outbound.poll(20, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                payload = null;
            }
            if (payload == null) {
                payload = new LinkedHashMap<String, Object>();
                payload.put("text", "");
                payload.put("ts", System.currentTimeMillis());
            }
            byte[] body = JSON.writeValueAsBytes(payload);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } finally {
            ex.close();
        }
    }

    // ===== helpers =====

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return InboundLimits.readBody(ex);
    }

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.<String, Object>emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(body, new TypeReference<HashMap<String, Object>>() {
            });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (IOException e) {
            return Collections.<String, Object>emptyMap();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}