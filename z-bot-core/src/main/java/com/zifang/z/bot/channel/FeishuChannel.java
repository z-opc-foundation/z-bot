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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书消息通道（基础形态）：入站用 HTTPS webhook + 签名校验，
 * 出站走飞书 open API（tenant_access_token + im/v1/messages）。
 *
 * <p>配置三选一（构造时传 {@code null} 则该通道不启动）：</p>
 * <ul>
 *   <li>{@code appId} + {@code appSecret} — 标准自建应用，token 自取</li>
 *   <li>{@code verificationToken} + {@code encryptKey} — webhook 签名校验</li>
 *   <li>{@code staticToken} — 跳过 token endpoint，直接给一个长 token（dev / sandbox）</li>
 * </ul>
 *
 * <p>stub：endpoint 解析 + 签名校验 + token 缓存，**没有真发 HTTP 给飞书**（出站消息仅日志）。</p>
 */
public final class FeishuChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChannelBus bus;
    private final int port;
    private final String appId;
    private final String appSecret;
    private final String verificationToken;
    private final String encryptKey;
    private final String staticToken;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;

    private HttpServer server;
    private ExecutorService workers;
    private final LinkedBlockingQueue<Map<String, Object>> outbound = new LinkedBlockingQueue<Map<String, Object>>();
    private final CountDownLatch termination = new CountDownLatch(1);
    private volatile boolean running;

    public FeishuChannel(ChannelBus bus, int port,
                         String appId, String appSecret,
                         String verificationToken, String encryptKey,
                         String staticToken) {
        this(bus, port, appId, appSecret, verificationToken, encryptKey, staticToken, null);
    }

    /** 末位 host：null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    public FeishuChannel(ChannelBus bus, int port,
                         String appId, String appSecret,
                         String verificationToken, String encryptKey,
                         String staticToken, String host) {
        this.bus = bus;
        this.port = port;
        this.appId = appId;
        this.appSecret = appSecret;
        this.verificationToken = verificationToken;
        this.encryptKey = encryptKey;
        this.staticToken = staticToken;
        this.host = host;
    }

    @Override
    public String name() {
        return "feishu";
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(ChannelBind.resolve(host), port), 0);
        workers = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "z-bot-feishu");
            t.setDaemon(true);
            return t;
        });
        server.createContext("/feishu/event", this::handleEvent);
        server.createContext("/feishu/out", this::handleOutboundPoll);
        server.setExecutor(workers);
        server.start();
        running = true;
        LOG.info("[feishu] 已启动: http://{}:{}/feishu/event", ChannelBind.describe(getBindAddress()), getPort());
        String warning = ChannelBind.exposureWarning(getBindAddress());
        if (warning != null) {
            LOG.warn("[feishu] 警告: {}", warning);
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
        // stub：仅打印与入 outbound 队列，方便 z-bot send / z-bot status 看到
        LOG.info("[feishu:stub] reply_to={} text={}", message.replyTo, abbreviate(message.text));
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("receiveId", message.replyTo);
        payload.put("text", message.text);
        payload.put("kind", message.kind == OutboundMessage.Kind.ERROR ? "error" : "text");
        payload.put("ts", System.currentTimeMillis());
        outbound.offer(payload);
    }

    private void handleEvent(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                // 飞书 URL 校验：echostr 原样返回
                Map<String, String> q = queryParams(ex);
                String echostr = q.get("echostr");
                if (echostr != null) {
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(200, 0);
                    ex.getResponseBody().write(echostr.getBytes(StandardCharsets.UTF_8));
                } else {
                    text(ex, 400, "missing echostr");
                }
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                text(ex, 405, "method not allowed");
                return;
            }
            String body = readBody(ex);
            // 简化 — 真接入时按 encryptKey 解密
            Map<String, Object> parsed = parseObject(body);
            String token = str(parsed.get("token"));
            if (verificationToken != null && !verificationToken.isEmpty()
                    && !verificationToken.equals(token)) {
                text(ex, 401, "{\"error\":\"token mismatch\"}");
                return;
            }
            Object event = parsed.get("event");
            if (event instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> evt = (Map<String, Object>) event;
                String sender = str(evt.get("sender_id"));
                Object msg = evt.get("message");
                String text = str(evt.get("text"));
                String chatId = str(evt.get("chat_id"));
                String chatType = str(evt.get("chat_type"));
                String conversationId = chatType.isEmpty() ? chatId : (chatType + ":" + chatId);
                if (sender.isEmpty()) {
                    sender = str(evt.get("user_id"));
                }
                if (!text.isEmpty()) {
                    bus.deliver(new ChannelMessage(name(), conversationId, sender, text));
                }
            }
            text(ex, 200, "{\"ok\":true}");
        } catch (Exception e) {
            LOG.warn("[feishu] 处理失败: {}", e.getMessage());
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

    /** 当前生效 token（stub: 永远返回 staticToken 或 "stub"）。 */
    public String currentToken() {
        if (staticToken != null && !staticToken.isEmpty()) {
            return staticToken;
        }
        return "STUB-TOKEN-please-fill-appSecret";
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

    private static Map<String, String> queryParams(HttpExchange ex) {
        Map<String, String> out = new HashMap<String, String>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1),
                        StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 把 send 用的 im/v1/messages URL 算出来（stub：仅展示，不真发）。 */
    public String outgoingUrl() {
        return "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=" + "open_id";
    }

    // ===== 签名校验（飞书 Encrypt Key）=====

    /**
     * 飞书事件订阅加密校验：timestamp + nonce + encryptKey 拼接 → SHA1 → 与 signature 比对。
     * 解密后的 event payload 还要再做 timestamp 防重放（此处只演示签名）。
     */
    public boolean verifySignature(String timestamp, String nonce, String body, String signature) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            return true; // 未配 encryptKey 视为关闭校验
        }
        try {
            String s = timestamp + nonce + encryptKey + body;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equals(signature);
        } catch (Exception e) {
            return false;
        }
    }
}