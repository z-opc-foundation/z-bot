package com.zifang.z.bot.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 钉钉消息通道：入站 webhook + 加签校验，出站<b>真发 HTTP</b> 到钉钉群机器人 webhook。
 *
 * <p>P18 起 {@link #send(OutboundMessage)} 真的朝 {@link #signedWebhookUrl()} POST
 * {@code {"msgtype":"text","text":{"content":…}}}，并按响应里的 {@code errcode}/{@code errmsg}
 * 分类失败（此前是 stub：只打日志 + 进 outbound 队列）。缺 {@code webhook-url} 时抛
 * {@link ChannelConfigException} 写明缺哪个键，<b>不</b>静默降级成"看起来发成功了"。</p>
 *
 * <p>安全：{@code webhook-url} 里带着 {@code access_token}，加签用 {@code secret} ——
 * 两者都不进日志、不进异常文案（见 {@link #scrubUrl}）。</p>
 *
 * <p>钉钉签名规则（出站与入站<b>同一算法、不同载体</b>）：</p>
 * <ol>
 *   <li>取 timestamp + "\n" + secret → sign</li>
 *   <li>{@code sign = base64(HMAC-SHA256(secret, timestamp + "\n" + secret))}（加密模式）</li>
 *   <li>出站把 timestamp 和 sign 拼到 webhook URL 上（{@link #signedWebhookUrl()}）；
 *       入站它们在<b>请求头</b>里（{@link #HEADER_TIMESTAMP}/{@link #HEADER_SIGN}），
 *       由 {@link #verifyInbound} 校验，另要求时间戳在 1 小时窗口内 —— 钉钉文档《接收消息》的原文）</li>
 * </ol>
 *
 * <p>P30 之前入站是"广告里有验签、代码里没有"：{@code handleInbound} 直接解析 body 就投总线，
 * 任何能连到监听端口的人都能伪造成用户消息（缺省只绑回环，见 P11c，所以暴露面有限但不是零）。
 * 现在配了 {@code secret}（或显式的 {@code inbound-secret}）就 fail-closed 401。</p>
 */
public final class DingTalkChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(DingTalkChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** manifest 里 {@code channel.<name>.config.<key>} 的键名（也是缺键报错里写的名字）。 */
    public static final String KEY_WEBHOOK_URL = "webhook-url";
    public static final String KEY_SECRET = "secret";
    /** 入站验签用的密钥；不配就退回 {@link #KEY_SECRET}（自定义机器人两处是同一把 SEC 串）。 */
    public static final String KEY_INBOUND_SECRET = "inbound-secret";

    /**
     * 钉钉"机器人接收消息"把签名放在<b>请求头</b>（不是 query）：头名就是这两个小写串。
     * 出站是 URL 上的 {@code timestamp}/{@code sign}（见 {@link #signedWebhookUrl()}），两回事。
     */
    public static final String HEADER_TIMESTAMP = "timestamp";
    public static final String HEADER_SIGN = "sign";

    /**
     * 时间戳允许的偏移（毫秒）。钉钉文档《接收消息》原文：
     * "sign 使用的 timestamp 和系统当前时间戳之间的差值，必须在 1 小时以内" ⇒ 超了钉钉自己就不认了，
     * 我们也不必留更宽的口子（这一条同时是防重放窗口）。
     */
    static final long INBOUND_MAX_SKEW_MS = 3_600_000L;

    private final ChannelBus bus;
    private final int port;
    private final String webhookUrl;
    private final String secret;
    private final String inboundSecret;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private volatile long lastHttpStatus;
    private volatile int lastErrcode;

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
        this(bus, port, webhookUrl, secret, host, null);
    }

    /**
     * 注册表用的完整构造：末位 {@code inboundSecret} 是入站验签的独立密钥。
     *
     * @param inboundSecret {@code null}/空 ⇒ 用 {@code secret}（自定义机器人的加签密钥与验签密钥是同一把）；
     *                      企业内部机器人的 appSecret 与 webhook 加签密钥<b>不是</b>同一把时才需要它
     */
    public DingTalkChannel(ChannelBus bus, int port, String webhookUrl, String secret,
                           String host, String inboundSecret) {
        this.bus = bus;
        this.port = port;
        this.webhookUrl = webhookUrl;
        this.secret = secret;
        this.host = host;
        this.inboundSecret = inboundSecret;
    }

    /** 入站验签实际用哪把密钥：显式 {@code inbound-secret} 优先，否则退回 {@code secret}。 */
    private String inboundKey() {
        if (inboundSecret != null && !inboundSecret.trim().isEmpty()) {
            return inboundSecret.trim();
        }
        return secret == null ? "" : secret.trim();
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
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            // 不许静默：把"缺哪几个键"在启动期就喊出来（URL 本身带 access_token，只报键名不报值）
            LOG.warn("[dingtalk] 出站不可用 —— 缺配置键 {}；send() 会抛 ChannelConfigException，"
                    + "不会假装已送达", lacking);
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
        // OkHttp 的工作线程缺省非守护且 60s 空闲才收；网关 stop() 之后不该拖着一个不走的 JVM
        try {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        } catch (RuntimeException e) {
            LOG.debug("[dingtalk] 出站连接池收尾异常: {}", e.getMessage());
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
    public void send(OutboundMessage message) throws IOException {
        if (message == null) {
            return;
        }
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            throw new ChannelConfigException("dingtalk 出站缺配置键: " + String.join(", ", lacking)
                    + " —— 群机器人需要一个带 access_token 的 webhook-url"
                    + (secret == null || secret.trim().isEmpty()
                            ? "（可选：再配 " + KEY_SECRET + " 开加签）" : ""), lacking);
        }
        String url = signedWebhookUrl();
        String body = buildMessageBody(message);
        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(body, JSON_MEDIA))
                .build();
        int status;
        String respBody;
        try (Response resp = http.newCall(req).execute()) {
            status = resp.code();
            ResponseBody rb = resp.body();
            respBody = rb == null ? "" : rb.string();
        } catch (IOException e) {
            // 传输层失败：原因里不能带 URL（access_token 就在 query 上）
            throw new IOException(scrub("dingtalk 出站传输失败: " + e.getClass().getSimpleName()
                    + ": " + scrubUrl(String.valueOf(e.getMessage()))), e);
        }
        lastHttpStatus = status;
        Map<String, Object> parsed = parseObject(respBody);
        int errcode = intOf(parsed.get("errcode"), status == 200 ? 0 : -1);
        String errmsg = str(parsed.get("errmsg")).toLowerCase(Locale.ROOT);
        lastErrcode = errcode;
        if (status < 200 || status >= 300 || errcode != 0) {
            throw new IOException(describeFailure(status, errcode, errmsg));
        }
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("chatId", message.replyTo);
        payload.put("text", message.text);
        payload.put("kind", message.kind == OutboundMessage.Kind.ERROR ? "error" : "text");
        // P18：不再是 stub —— delivered=true 只在 errcode=0 之后才写得进来
        payload.put("delivered", Boolean.TRUE);
        payload.put("errcode", Integer.valueOf(errcode));
        outbound.offer(payload);
        LOG.info("[dingtalk] 已投递 chatId={} errcode=0 text={}",
                message.replyTo, abbreviate(message.text));
    }

    /** 出站请求体：钉钉群机器人 text 消息的形状。 */
    String buildMessageBody(OutboundMessage message) throws IOException {
        Map<String, Object> text = new HashMap<String, Object>();
        text.put("content", message.text == null ? "" : message.text);
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("msgtype", "text");
        body.put("text", text);
        if (message.kind == OutboundMessage.Kind.ERROR) {
            Map<String, Object> at = new HashMap<String, Object>();
            at.put("atUserIds", Collections.singletonList(
                    message.replyTo == null ? "" : message.replyTo));
            body.put("at", at);
        }
        return JSON.writeValueAsString(body);
    }

    /**
     * 出站缺哪些配置键：{@code webhook-url} 是硬门槛（access_token 就在它上面）。
     * {@code secret} 可空（未开加签的机器人），所以它不进 requires，只在日志里提示。
     */
    public List<String> missingCredentialKeys() {
        List<String> lacking = new ArrayList<String>();
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            lacking.add(KEY_WEBHOOK_URL);
        }
        return lacking;
    }

    /**
     * 把钉钉报回来的错归一成 {@link DeadTargets#classifySendError} 认得的口径。
     *
     * <p>群机器人 webhook 是"整会话级"的：token 失效／机器人被移出群都意味着这个 chat
     * 再也发不出去 ⇒ 归一成正则认识的串；其余原样带出 errmsg（不含 URL）。</p>
     */
    static String describeFailure(int httpStatus, int errcode, String errmsg) {
        String m = errmsg == null ? "" : errmsg.toLowerCase(Locale.ROOT);
        if (m.contains("token is not exist") || m.contains("bot is not exist")
                || m.contains("chat not found") || m.contains("not exist")) {
            return "dingtalk chat not found (errcode=" + errcode + ")";
        }
        if (m.contains("banned") || m.contains("keywords not in content")
                || m.contains("no permission") || m.contains("forbidden") || m.contains("sign")) {
            return "dingtalk not a member (errcode=" + errcode + ")";
        }
        return "dingtalk send failed http=" + httpStatus + " errcode=" + errcode + " errmsg=" + m;
    }

    /** 最近一次出站的 HTTP 状态码；0 = 还没发过。 */
    public long lastHttpStatus() {
        return lastHttpStatus;
    }

    /** 最近一次出站的钉钉侧 {@code errcode}；未发过时为 0。 */
    public int lastErrcode() {
        return lastErrcode;
    }

    private static int intOf(Object v, int dflt) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 把 URL 里的 {@code access_token}/{@code sign} 值洗掉 —— 任何文案都不许带着它们。 */
    static String scrubUrl(String urlOrText) {
        if (urlOrText == null) {
            return "";
        }
        return urlOrText
                .replaceAll("(?i)(access_token=)[^&#]*", "$1***")
                .replaceAll("(?i)(sign=)[^&#]*", "$1***");
    }

    private String scrub(String s) {
        String out = scrubUrl(s);
        if (secret != null && !secret.isEmpty() && out.contains(secret)) {
            out = out.replace(secret, "***");
        }
        return out;
    }

    private void handleInbound(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                text(ex, 405, "method not allowed");
                return;
            }
            // 门在解析之前：未授权的请求体一个字都不该进总线（配了密钥才关，与飞书同一约定）。
            if (!verifyInbound(ex.getRequestHeaders().getFirst(HEADER_TIMESTAMP),
                    ex.getRequestHeaders().getFirst(HEADER_SIGN))) {
                text(ex, 401, "{\"ok\":false,\"error\":\"signature mismatch\"}");
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

    /**
     * 入站验签：钉钉在请求头带 {@code timestamp} 与 {@code sign}，
     * {@code sign = base64(HMAC-SHA256(key, timestamp + "\n" + key))}，key 是机器人 appSecret
     * （企业内部机器人）或加签密钥（自定义机器人，形如 {@code SEC…}）—— 出处：钉钉开放平台
     * 文档《接收消息》("请检查 timestamp 和 sign" 一节，含同式的 Python/Java 示例)。
     *
     * <p>两道检查缺一不可：签名相等 <b>且</b> 时间戳在 {@link #INBOUND_MAX_SKEW_MS} 以内
     * （文档要求 1 小时，超出窗口钉钉侧就不认，留着只会给重放开门）。
     * 未配置任何入站密钥时返回 {@code true}（= 校验关闭），与
     * {@link FeishuChannel#verifySignature} 同一约定，便于"先本地接、后上密钥"的部署形状。</p>
     */
    public boolean verifyInbound(String timestamp, String sign) {
        String key = inboundKey();
        if (key.isEmpty()) {
            return true;
        }
        if (timestamp == null || sign == null) {
            return false;
        }
        String raw = timestamp.trim();
        long ts;
        try {
            ts = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(System.currentTimeMillis() - ts) > INBOUND_MAX_SKEW_MS) {
            LOG.warn("[dingtalk] 入站时间戳超出 {} 分钟窗口，拒收", INBOUND_MAX_SKEW_MS / 60_000L);
            return false;
        }
        try {
            // 签名原文用**传过来的那串** timestamp（不是 parseLong 之后再 toString 的），
            // 否则 " 1700000000000" 这类带空白的合法毫秒串会在钉钉侧算成另一个签名。
            byte[] expect = inboundSign(key, raw).getBytes(StandardCharsets.US_ASCII);
            byte[] got = sign.trim().getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expect, got);
        } catch (Exception e) {
            LOG.warn("[dingtalk] 入站验签异常: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 入站签名的算法本体：{@code base64(HMAC-SHA256(key, timestamp + "\n" + key))}。
     * 与出站的 {@link #signedWebhookUrl()} 同式（区别只在出站多做一次 URL 编码）。
     * 单测拿它钉"文档给的已知答案"，所以它是包内可见而不是 private。
     */
    static String inboundSign(String key, String timestamp) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal((timestamp + "\n" + key).getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(signData);
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
            LOG.warn("[dingtalk] 加签失败: {}", scrub(String.valueOf(e.getMessage())));
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

    /**
     * 通用 SHA-1 hex 工具，只被单测与诊断用。
     *
     * <p>注意：<b>入站验签不走它</b> —— 钉钉的入站签名是 HMAC-SHA256+base64
     * （{@link #verifyInbound}），飞书事件订阅的签名是 SHA-256（{@link FeishuChannel#verifySignature}），
     * 两处都不该拿裸 SHA-1 当校验（P30 之前 {@code FeishuChannel} 就是这么写的，见 roadmap §8.14）。</p>
     */
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