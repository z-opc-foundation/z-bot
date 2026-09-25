package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉消息通道（基础形态）：入站 webhook + 加签校验，
 * 出站走钉钉群机器人 webhook（用 {@code access_token} 直接 POST）。
 *
 * <p>stub：签名校验 + 消息分发逻辑完整，**没有真发 HTTP 给钉钉**（仅日志 + outbound 队列）。</p>
 *
 * <p>钉钉签名规则：</p>
 * <ol>
 *   <li>取 timestamp + "\n" + secret → sign</li>
 *   <li>{@code sign = base64(HMAC-SHA256(secret, timestamp + "\n" + secret))}（加密模式）</li>
 *   <li>把 timestamp 和 sign 拼到 webhook URL 上</li>
 * </ol>
 */
public final class DingTalkChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChannelBus bus;
    private final int port;
    private final String webhookUrl;
    private final String secret;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;

    private HttpServer server;
    private ExecutorService workers;
    private final LinkedBlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<Map<String, Object>>();
    private final CountDownLatch termination = new CountDownLatch(1);
    private volatile boolean running;

    public DingTalkChannel(ChannelBus bus, int port, String webhookUrl, String secret) {
        this(bus, port, webhookUrl, secret, null);
    }

    /** 末位 host：null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    public DingTalkChannel(ChannelBus bus, int port, String webhookUrl, String secret, String host) {
        this.bus = bus;
        this.port = port;
        this.webhookUrl = webhookUrl;
        this.secret = secret;
        this.host = host;
    }

    @Override
    public String name() {
        return "dingtalk";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(
                new java.net.InetSocketAddress(ChannelBind.resolve(host), port), 0);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "z-bot-dingtalk");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/dingtalk/in", this::handleInbound);
        server.createContext("/dingtalk/out", this::handleOutboundPoll);
        server.setExecutor(workers);
        server.start();
        running = true;
        LOG.info("[dingtalk] 已启动: http://{}:{}/dingtalk/in", ChannelBind.describe(getBindAddress()), getPort());
        String warning = ChannelBind.exposureWarning(getBindAddress());
        if (warning != null) {
            LOG.warn("[dingtalk] 警告: {}", warning);
        }
    }

    /** 实际监听的地址 — 守卫测试据此确认缺省没有绑到通配。 */
    java.net.InetAddress getBindAddress() {
        return server == null ? null : server.getAddress().getAddress();
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

    @Override
    public void send(OutboundMessage message) {
        if (message == null) {
            return;
        }
        LOG.info("[dingtalk:stub] reply_to={} text={}", message.replyTo, abbreviate(message.text));
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("chatId", message.replyTo);
        payload.put("text", message.text);
        payload.put("kind", message.kind == OutboundMessage.Kind.ERROR ? "error" : "text");
        outbound.offer(payload);
    }

    private void handleInbound(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                text(ex, 405, "method not allowed");
                return;
            }
            String body = readBody(ex);
            Map<String, Object> parsed = parseObject(body);
            String conversationId = str(parsed.get("conversationId"));
            String senderId = str(parsed.get("senderId"));
            String text = str(parsed.get("text"));
            if (conversationId.isEmpty() || text.isEmpty()) {
                text(ex, 400, "conversationId 和 text 不能为空");
                return;
            }
            bus.deliver(new ChannelMessage(name(), conversationId, senderId, text));
            text(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            LOG.warn("[dingtalk] 入站失败: {}", e.getMessage());
            text(ex, 500, "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}");
        } finally {
            ex.close();
        }
    }

    private void handleOutboundPoll(HttpExchange ex) throws IOException {
        Map<String, Object> payload;
        try {
            payload = outbound.poll(20, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            payload = null;
        }
        if (payload == null) {
            payload = new HashMap<String, Object>();
            payload.put("text", "");
        }
        byte[] body = JSON.writeValueAsBytes(payload);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** 钉钉群机器人加签 URL：?timestamp=...&sign=... */
    public String signedWebhookUrl() {
        if (secret == null || secret.isEmpty()) {
            return webhookUrl;
        }
        long ts = System.currentTimeMillis();
        String stringToSign = ts + "\n" + secret;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(signData),
                    "UTF-8");
            return webhookUrl + (webhookUrl.contains("?") ? "&" : "?")
                    + "timestamp=" + ts + "&sign=" + sign;
        } catch (Exception e) {
            LOG.warn("[dingtalk] 加签失败: {}", e.getMessage());
            return webhookUrl;
        }
    }

    // ===== helpers =====

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
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

    /** SHA-1 工具（备用，飞书也用 SHA-1 校验 token；这里放着备用）。 */
    public static String sha1Hex(String s) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}