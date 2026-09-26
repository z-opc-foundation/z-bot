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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 飞书消息通道：入站用 HTTPS webhook + 签名校验（+ 加密模式解密），
 * 出站走飞书 open API（{@code tenant_access_token} 换取 + {@code im/v1/messages} 发送）。
 *
 * <p>入站这一面（{@code POST /feishu/event}）按顺序过四道：
 * 原始 body 字节的 SHA-256 验签（配了 {@code encrypt-key} 才关，缺头即 401）⇒
 * {@code {"encrypt": …}} 解密成事件 JSON ⇒ verification-token 比对（v2 在 {@code header.token}，
 * v1 在顶层）⇒ 事件投递（v1 平铺字段与 v2 的 {@code sender}/{@code message.content} 都认）。</p>
 *
 * <p>{@code url_verification} 只在过了 token 门之后回显 challenge。</p>
 *
 * <p><b>GET 不在这张合同里</b>（D-P30-1，09-27 裁定并拆掉旧的 {@code GET …?echostr=} 回显）：
 * 查询参数原样回显是<b>企微</b>的回调校验形状，两份读得到的权威里飞书面都没有它 ——
 * {@code lark_oapi} 1.5.3 全包 {@code echostr} <b>0</b> 命中、hermes {@code plugins/platforms/feishu/}
 * <b>0</b> 命中（她的 URL 校验是 POST + token 门后回显 challenge，{@code adapter.py:3552-3569}），
 * 而她的 wecom 适配器有 6 处（{@code plugins/platforms/wecom/callback_adapter.py:274-278}
 * 拿 {@code verify_url(msg_signature, timestamp, nonce, echostr)} 解出明文才回显）。
 * 这跟 P18 那次"SHA-1 属于企微不属于飞书"是同一形状的近亲串台 ⇒ 现在 GET/PUT/DELETE 一律 405。</p>
 *
 * <p>配置三选一（构造时传 {@code null} 则该通道不启动）：</p>
 * <ul>
 *   <li>{@code appId} + {@code appSecret} — 标准自建应用，token 自取</li>
 *   <li>{@code verificationToken} + {@code encryptKey} — webhook 签名校验 + 事件解密</li>
 *   <li>{@code staticToken} — 跳过 token endpoint，直接给一个长 token（dev / sandbox）</li>
 * </ul>
 *
 * <p><b>P18 起出站是真发 HTTP</b>（此前是 stub：只进 outbound 队列 + 打日志）：</p>
 * <ol>
 *   <li>{@link #resolveAccessToken()} POST {@code <api-base>/auth/v3/tenant_access_token/internal}
 *       换 {@code tenant_access_token}，按 {@code expire} 缓存、提前 60s 续期；</li>
 *   <li>{@link #send(OutboundMessage)} POST {@code <api-base>/im/v1/messages?receive_id_type=…}，
 *       带 {@code Authorization: Bearer <token>}；</li>
 *   <li>失败按 {@link #describeFailure HTTP 状态码 + 平台 code/msg} 归一成
 *       {@link DeadTargets#classifySendError} 认得的口径（{@code chat not found} /
 *       {@code not a member}），所以"整会话已死"能被 P16 的登记表短路掉；</li>
 *   <li>token 被平台判废（401/403）⇒ <b>作废缓存 + 重取一次 + 重发一次</b>，第二次还不行才抛。</li>
 * </ol>
 *
 * <p>缺凭据时<b>不静默</b>：{@link #send(OutboundMessage)} 抛
 * {@link ChannelConfigException}，消息原文写着缺哪一个配置键（{@code app-id}/{@code app-secret}/
 * {@code static-token}），{@link ChannelBus} 那条投递照 P16 红线 8 记成 {@code failed}，
 * 绝不记 {@code delivered}。</p>
 *
 * <p>安全：{@code appSecret} 与换到的 token 都不进日志、不进异常文案。
 * {@code api-base} 缺省是真域名 {@code https://open.feishu.cn/open-apis}；单测/验收把它指向
 * {@code 127.0.0.1} 的假端点断言协议字节，<b>从不</b>往真域名发。</p>
 */
public final class FeishuChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(FeishuChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** manifest 里 {@code channel.<name>.config.<key>} 的键名（也是缺键报错里写的名字）。 */
    public static final String KEY_APP_ID = "app-id";
    public static final String KEY_APP_SECRET = "app-secret";
    public static final String KEY_VERIFICATION_TOKEN = "verification-token";
    public static final String KEY_ENCRYPT_KEY = "encrypt-key";
    public static final String KEY_STATIC_TOKEN = "static-token";
    public static final String KEY_API_BASE = "api-base";
    public static final String KEY_RECEIVE_ID_TYPE = "receive-id-type";

    /** 真域名：只用它<b>算 URL 字符串</b>；验收一律指向 127.0.0.1 假端点。 */
    public static final String DEFAULT_API_BASE = "https://open.feishu.cn/open-apis";
    public static final String TOKEN_PATH = "/auth/v3/tenant_access_token/internal";
    public static final String MESSAGE_PATH = "/im/v1/messages";

    /** 事件订阅签名三件套（飞书侧的 HTTP 头名）；配了 {@code encrypt-key} 就必带。 */
    public static final String HEADER_SIGNATURE = "X-Lark-Signature";
    public static final String HEADER_REQUEST_TIMESTAMP = "X-Lark-Request-Timestamp";
    public static final String HEADER_REQUEST_NONCE = "X-Lark-Request-Nonce";
    public static final String DEFAULT_RECEIVE_ID_TYPE = "open_id";

    /** 距过期不足这么多毫秒就先续期（避免卡在边界上用到废 token）。 */
    static final long TOKEN_REFRESH_MARGIN_MS = 60_000L;

    private final ChannelBus bus;
    private final int port;
    private final String appId;
    private final String appSecret;
    private final String verificationToken;
    private final String encryptKey;
    private final String staticToken;
    /** null/空 = 只绑回环；显式写地址才暴露到别的网卡。 */
    private final String host;
    private final String apiBase;
    private final String receiveIdType;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt;
    private volatile long lastHttpStatus;
    private volatile int lastPlatformCode;

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
        this(bus, port, appId, appSecret, verificationToken, encryptKey, staticToken, host, null, null);
    }

    /**
     * 注册表用的完整构造：末两位是出站侧配置。
     *
     * @param apiBase        {@code null}/空 ⇒ {@link #DEFAULT_API_BASE}
     * @param receiveIdType  {@code null}/空 ⇒ {@link #DEFAULT_RECEIVE_ID_TYPE}
     */
    public FeishuChannel(ChannelBus bus, int port,
                         String appId, String appSecret,
                         String verificationToken, String encryptKey,
                         String staticToken, String host,
                         String apiBase, String receiveIdType) {
        this.bus = bus;
        this.port = port;
        this.appId = appId;
        this.appSecret = appSecret;
        this.verificationToken = verificationToken;
        this.encryptKey = encryptKey;
        this.staticToken = staticToken;
        this.host = host;
        this.apiBase = apiBase == null || apiBase.trim().isEmpty()
                ? DEFAULT_API_BASE : trimTrailingSlash(apiBase.trim());
        this.receiveIdType = receiveIdType == null || receiveIdType.trim().isEmpty()
                ? DEFAULT_RECEIVE_ID_TYPE : receiveIdType.trim();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
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
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            // 不许静默：启动时就把"缺哪几个键"喊出来，否则第一条真投递失败时没人知道为什么
            LOG.warn("[feishu] 出站不可用 —— 缺配置键 {}；send() 会抛 ChannelConfigException，"
                    + "不会假装已送达", lacking);
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
        // OkHttp 的工作线程缺省非守护且 60s 空闲才收；网关 stop() 之后不该拖着一个不走的 JVM
        try {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        } catch (RuntimeException e) {
            LOG.debug("[feishu] 出站连接池收尾异常: {}", e.getMessage());
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
    public void send(OutboundMessage message) throws IOException {
        if (message == null) {
            return;
        }
        String body = buildMessageBody(message);
        String token = resolveAccessToken();
        HttpResult r = call(outgoingUrl(), body, token);
        if (isAuthRejection(r) && !usingStaticToken()) {
            // token 被判废（平台侧提前吊销 / 时钟边界）⇒ 作废缓存重取一次并重发；第二次还不行才抛
            LOG.warn("[feishu] token 被平台判废（HTTP {} code={}）—— 作废缓存重取一次并重发",
                    Long.valueOf(r.status), Integer.valueOf(r.platformCode));
            cachedToken = null;
            cachedTokenExpireAt = 0L;
            token = resolveAccessToken();
            r = call(outgoingUrl(), body, token);
        }
        lastHttpStatus = r.status;
        lastPlatformCode = r.platformCode;
        if (r.status < 200 || r.status >= 300 || r.platformCode != 0) {
            // 平台 msg 里万一回显了什么（包括 token），一律洗过再往外冒
            throw new IOException(scrub(describeFailure(r.status, r.platformCode, r.platformMsg)));
        }
        Map<String, Object> payload = new HashMap<String, Object>();
        payload.put("receiveId", message.replyTo);
        payload.put("text", message.text);
        payload.put("kind", message.kind == OutboundMessage.Kind.ERROR ? "error" : "text");
        payload.put("ts", System.currentTimeMillis());
        // P18：不再是 stub —— delivered=true 只在平台 code=0 之后才写得进来
        payload.put("delivered", Boolean.TRUE);
        payload.put("messageId", extractMessageId(r.body));
        outbound.offer(payload);
        LOG.info("[feishu] 已投递 receive_id={} message_id={} text={}",
                message.replyTo, payload.get("messageId"), abbreviate(message.text));
    }

    /** 飞书把结果放在 {@code data.message_id}；取不到就返回空串（不影响"已送达"判定）。 */
    private String extractMessageId(String responseBody) {
        Object data = parseObject(responseBody).get("data");
        if (data instanceof Map) {
            return str(((Map<?, ?>) data).get("message_id"));
        }
        return "";
    }

    /** 出站请求体：飞书 {@code im/v1/messages} 的 {@code content} 是"字符串化的 JSON"，不是对象。 */
    String buildMessageBody(OutboundMessage message) throws IOException {
        Map<String, Object> content = new HashMap<String, Object>();
        content.put("text", message.text == null ? "" : message.text);
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("receive_id", message.replyTo == null ? "" : message.replyTo);
        body.put("msg_type", "text");
        body.put("content", JSON.writeValueAsString(content));
        if (message.kind == OutboundMessage.Kind.ERROR) {
            // 错误回执在飞书侧没有专门 msg_type，用文本前缀 + 平台可查的 uuid 区分
            body.put("uuid", "zbot-err-" + Integer.toHexString(
                    (message.replyTo + "|" + message.text).hashCode()));
        }
        return JSON.writeValueAsString(body);
    }

    private HttpResult call(String url, String jsonBody, String token) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(jsonBody, JSON_MEDIA))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String text = rb == null ? "" : rb.string();
            Map<String, Object> parsed = parseObject(text);
            return new HttpResult(resp.code(), text, intOf(parsed.get("code"), -1),
                    str(parsed.get("msg")));
        }
    }

    private static boolean isAuthRejection(HttpResult r) {
        return r.status == 401 || r.status == 403
                || r.platformCode == 99991661 || r.platformCode == 99991663 || r.platformCode == 99991664;
    }

    private boolean usingStaticToken() {
        return staticToken != null && !staticToken.isEmpty();
    }

    /**
     * 把"飞书侧报回来的错"归一成 {@link DeadTargets#classifySendError} 认得的口径。
     *
     * <p>只有整会话级的两种（{@code chat not found} / {@code not a member}）会被登记成死目标；
     * 其余原样带出平台 msg，方便人眼判断。文案里<b>不含</b> token 与 appSecret。</p>
     */
    static String describeFailure(int httpStatus, int platformCode, String platformMsg) {
        String m = platformMsg == null ? "" : platformMsg.toLowerCase(Locale.ROOT);
        if (m.contains("chat not found") || m.contains("chat_id_invalid") || m.contains("chat not exist")) {
            return "feishu chat not found (code=" + platformCode + ")";
        }
        if (m.contains("not in the chat") || m.contains("not a member") || m.contains("no permission")
                || m.contains("permission denied") || m.contains("blocked") || m.contains("forbidden")) {
            return "feishu not a member (code=" + platformCode + ")";
        }
        if (m.contains("thread not found") || m.contains("message to reply not found")) {
            return "feishu thread not found (code=" + platformCode + ")";
        }
        return "feishu send failed http=" + httpStatus + " code=" + platformCode + " msg=" + m;
    }

    /** 一次 HTTP 往返的读数（含平台侧 code/msg），只为把"状态码"和"响应形状"分开判。 */
    static final class HttpResult {
        final int status;
        final String body;
        final int platformCode;
        final String platformMsg;

        HttpResult(int status, String body, int platformCode, String platformMsg) {
            this.status = status;
            this.body = body;
            this.platformCode = platformCode;
            this.platformMsg = platformMsg;
        }
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

    private void handleEvent(HttpExchange ex) throws IOException {
        try {
            // POST 是这一面唯一的入站形状。GET ?echostr= 那半轴 09-27 拆掉了（D-P30-1）：
            // 它是**企微**的回调校验形状，挂在飞书面上等于在验签/token 门之前原文回显查询参数，
            // 出处对照见类 javadoc。
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                text(ex, 405, "method not allowed");
                return;
            }
            byte[] raw = readBodyBytes(ex);
            // 飞书事件订阅的签名算在**原始 body 字节**上 ⇒ 必须赶在解密/解析之前验；
            // 配了 encrypt-key 却缺任一头也拒（fail-closed，不能让攻击者靠"不带头"绕过）。
            if (encryptKey != null && !encryptKey.isEmpty()) {
                String ts = header(ex, HEADER_REQUEST_TIMESTAMP);
                String nonce = header(ex, HEADER_REQUEST_NONCE);
                String signature = header(ex, HEADER_SIGNATURE);
                if (!verifySignature(ts, nonce, raw, signature)) {
                    text(ex, 401, "{\"error\":\"signature mismatch\"}");
                    return;
                }
            }
            Map<String, Object> parsed = parseObject(new String(raw, StandardCharsets.UTF_8));
            String cipher = str(parsed.get("encrypt"));
            if (!cipher.isEmpty()) {
                // 加密模式：外层 {"encrypt": …} 里那层明文才是真事件。解不开就当没收到，
                // 绝不拿密文字段去凑 token/event（那等于把"解不开的垃圾"降级成"未授权也能过"）。
                String plain = decryptEvent(cipher);
                if (plain == null) {
                    text(ex, 400, "{\"error\":\"decrypt failed\"}");
                    return;
                }
                parsed = parseObject(plain);
            }
            // v2.0 事件的 token 在 header 里，v1 在顶层 ⇒ 只看顶层会把每一条真事件都判成 401。
            String token = nestedStr(parsed.get("header"), "token");
            if (token.isEmpty()) {
                token = str(parsed.get("token"));
            }
            if (verificationToken != null && !verificationToken.isEmpty()
                    && !verificationToken.equals(token)) {
                text(ex, 401, "{\"error\":\"token mismatch\"}");
                return;
            }
            if ("url_verification".equals(str(parsed.get("type")))) {
                // challenge 只在过了 token 门之后回显：否则未鉴权请求能拿它验证"我打到了你的回调地址"。
                Map<String, Object> echo = new HashMap<String, Object>();
                echo.put("challenge", str(parsed.get("challenge")));
                byte[] bytes = JSON.writeValueAsBytes(echo);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                return;
            }
            Map<String, Object> evt = asMap(parsed.get("event"));
            if (!evt.isEmpty()) {
                // 两种形状都得认，否则"解得开却投不出"：
                //   v2（im.message.receive_v1）sender/message 各一层对象，正文埋在 message.content 的字符串化 JSON 里；
                //   v1 把这些字段直接平铺在 event 上 —— **这条是 defensive tolerance，没有权威出处**
                //   （D-P30-2 裁定，09-27：`lark_oapi` 的类型化模型 `P2ImMessageReceiveV1Data`
                //   只声明 `sender` + `message`，hermes 的 webhook 分发只读 `payload["header"]["event_type"]`
                //   （`adapter.py:3585-3596`）、零平铺分支）。所以这里**只多认、不另加字段**：曾经记过一条
                //   "v1 正文埋在 event.content"，那句同样查不到出处 ⇒ 不照它加读取点。
                //   参照 hermes `adapter.py:459`（"receive_v1 docs say {user, bot}; accept 'app' defensively"）
                //   的做法：容错要写明它是容错。
                String sender = firstNonEmpty(
                        nestedStr(evt.get("sender"), "sender_id", "open_id"),
                        nestedStr(evt.get("sender"), "sender_id", "user_id"),
                        str(evt.get("sender_id")));
                Map<String, Object> msg = asMap(evt.get("message"));
                String chatId = firstNonEmpty(str(msg.get("chat_id")), str(evt.get("chat_id")));
                String chatType = firstNonEmpty(str(msg.get("chat_type")), str(evt.get("chat_type")));
                String text = firstNonEmpty(messageText(msg), str(evt.get("text")));
                String conversationId = chatType.isEmpty() ? chatId : (chatType + ":" + chatId);
                if (!text.isEmpty() && !chatId.isEmpty()) {
                    bus.deliver(new ChannelMessage(name(), conversationId, sender, text));
                }
            }
            text(ex, 200, "{\"ok\":true}");
        } catch (BodyTooLargeException e) {
            LOG.warn("[feishu] 入站超限: {}", e.getMessage());
            text(ex, 413, "{\"ok\":false,\"error\":\"request body too large\"}");
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

    /**
     * 当前生效 token：真换取来的（缓存未过期）&gt; {@code staticToken} &gt;
     * 一个<b>明写的占位串</b>（从未换过 token 又没有 static-token 时的诊断值，
     * 只给 {@code z-bot status} 看，绝不参与发送 —— 发送走 {@link #resolveAccessToken()}，它会抛）。
     */
    public String currentToken() {
        String cached = cachedToken;
        if (cached != null && System.currentTimeMillis() < cachedTokenExpireAt) {
            return cached;
        }
        if (staticToken != null && !staticToken.isEmpty()) {
            return staticToken;
        }
        return "STUB-TOKEN-please-fill-appSecret";
    }

    /** 换 token 用哪个端点（纯字符串构造；真域名正确性就按这个断言，绝不往它发一个字节）。 */
    public String tokenUrl() {
        return apiBase + TOKEN_PATH;
    }

    /** 出站缺哪些配置键：{@code app-id} + {@code app-secret} 与 {@code static-token} 两条路都没配齐才算缺。 */
    public List<String> missingCredentialKeys() {
        if (usingStaticToken()) {
            return Collections.emptyList();
        }
        List<String> lacking = new ArrayList<String>();
        if (appId == null || appId.trim().isEmpty()) {
            lacking.add(KEY_APP_ID);
        }
        if (appSecret == null || appSecret.trim().isEmpty()) {
            lacking.add(KEY_APP_SECRET);
        }
        return lacking;
    }

    /**
     * 生效的 {@code tenant_access_token}：{@code static-token} 直接用；否则按缓存 → 换取。
     *
     * @throws ChannelConfigException 两条路都没配（消息里写着缺哪个键）
     * @throws IOException            token 端点回了非 200 / {@code code!=0} / 没有 token
     */
    public String resolveAccessToken() throws IOException {
        if (usingStaticToken()) {
            return staticToken;
        }
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            throw new ChannelConfigException("feishu 出站缺配置键: " + String.join(", ", lacking)
                    + " —— 要么给 " + KEY_APP_ID + "+" + KEY_APP_SECRET + "（换取 tenant_access_token），"
                    + "要么直接给 " + KEY_STATIC_TOKEN, lacking);
        }
        String cached = cachedToken;
        if (cached != null && System.currentTimeMillis() < cachedTokenExpireAt - TOKEN_REFRESH_MARGIN_MS) {
            return cached;
        }
        return fetchToken();
    }

    /**
     * POST {@code <api-base>/auth/v3/tenant_access_token/internal} 换 token 并缓存。
     *
     * <p>{@code expire} 缺失时按 3600s 兜底；文案里对 {@code appSecret} 做<b>洗串</b>
     * （平台若把请求体回显在错误里，也不许让它进日志或异常）。</p>
     */
    private String fetchToken() throws IOException {
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("app_id", appId);
        req.put("app_secret", appSecret);
        String json = JSON.writeValueAsString(req);
        Request post = new Request.Builder()
                .url(tokenUrl())
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(json, JSON_MEDIA))
                .build();
        String token;
        long expireSeconds;
        int status;
        String body;
        try (Response resp = http.newCall(post).execute()) {
            status = resp.code();
            ResponseBody rb = resp.body();
            body = rb == null ? "" : rb.string();
            Map<String, Object> parsed = parseObject(body);
            token = str(parsed.get("tenant_access_token"));
            expireSeconds = intOf(parsed.get("expire"), 3600);
            lastHttpStatus = status;
            lastPlatformCode = intOf(parsed.get("code"), -1);
        }
        if (token.isEmpty() || status < 200 || status >= 300 || lastPlatformCode != 0) {
            throw new IOException(scrub("feishu tenant_access_token 换取失败 http=" + status
                    + " code=" + lastPlatformCode + " body=" + abbreviate(body.toLowerCase(Locale.ROOT))
                    + " —— 端点 " + tokenUrlWithoutSecret()));
        }
        cachedToken = token;
        cachedTokenExpireAt = System.currentTimeMillis() + Math.max(1L, expireSeconds) * 1000L;
        LOG.info("[feishu] tenant_access_token 已换取（有效期 {}s，端点 {}）",
                Long.valueOf(expireSeconds), tokenUrlWithoutSecret());
        return token;
    }

    /** token 端点 URL 里本来就没有凭据（app_id/app_secret 在请求体里），原样给出即可。 */
    private String tokenUrlWithoutSecret() {
        return tokenUrl();
    }

    /** 把可能出现在文案里的凭据换成 {@code ***}。 */
    private String scrub(String s) {
        String out = s;
        if (appSecret != null && !appSecret.isEmpty() && out.contains(appSecret)) {
            out = out.replace(appSecret, "***");
        }
        if (staticToken != null && !staticToken.isEmpty() && out.contains(staticToken)) {
            out = out.replace(staticToken, "***");
        }
        if (cachedToken != null && !cachedToken.isEmpty() && out.contains(cachedToken)) {
            out = out.replace(cachedToken, "***");
        }
        return out;
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

    /** 原始请求体字节：验签必须用这份，任何"先转字符串再转回去"的中间步都可能改动字节。 */
    private static byte[] readBodyBytes(HttpExchange ex) throws IOException {
        return InboundLimits.readBodyBytes(ex);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Collections.<String, Object>emptyMap();
    }

    /** 沿路径逐层取字符串（任一层不是对象或取不到 ⇒ 空串），用来读 v2 的 {@code header.token} / {@code sender.sender_id.open_id}。 */
    private static String nestedStr(Object parent, String... path) {
        Object cur = parent;
        for (String key : path) {
            cur = asMap(cur).get(key);
        }
        return str(cur);
    }

    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /**
     * v2 的 {@code message.content} 是<b>字符串化</b>的 JSON：文本消息形如 {@code {"text":"你好"}}
     * （与出站侧 {@code body.put("content", JSON.writeValueAsString(content))} 互为逆运算）。
     *
     * <p>非 {@code text} 类型（富文本 / 图片 / 卡片回传）本期不投递：返回空串 ⇒ 上层按"没有正文"
     * 处理，既不会投一条空消息，也不会因为遇到陌生形状而 500。</p>
     */
    private static String messageText(Map<String, Object> message) {
        if (message.isEmpty() || !"text".equals(str(message.get("message_type")))) {
            return "";
        }
        return nestedStr(parseObject(str(message.get("content"))), "text");
    }

    private static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    /**
     * send 用的完整 URL：{@code <api-base>/im/v1/messages?receive_id_type=<…>}。
     *
     * <p>P18 起它不再只是"算出来看看"—— {@link #send(OutboundMessage)} 真朝它发。
     * 缺省 {@code api-base} 是真域名，所以默认值就是线上正确形状；测试/验收把它指向 127.0.0.1。</p>
     */
    public String outgoingUrl() {
        String type;
        try {
            type = URLEncoder.encode(receiveIdType, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            type = receiveIdType; // UTF-8 恒在，走不到
        }
        return apiBase + MESSAGE_PATH + "?receive_id_type=" + type;
    }

    /** 生效的 api-base（诊断/断言用）。 */
    public String apiBase() {
        return apiBase;
    }

    /** 最近一次出站的 HTTP 状态码；0 = 还没发过。 */
    public long lastHttpStatus() {
        return lastHttpStatus;
    }

    /** 最近一次出站的飞书侧 {@code code}；-1 = 还没发过。 */
    public int lastPlatformCode() {
        return lastPlatformCode;
    }

    // ===== 入站：签名校验 + 加密模式解密（飞书 Encrypt Key）=====

    /**
     * 飞书事件订阅验签：{@code sha256((timestamp + nonce + encryptKey) 的 UTF-8 字节 + 原始 body 字节)} 的 hex。
     *
     * <p>算法照官方 Python SDK 的 {@code lark_oapi/event/dispatcher_handler.py}
     * （commit {@code 0b9e6e48b74bb4b34462fc67b7e738b27e73e697}，{@code _verify_sign} 那一支：
     * {@code bs = (timestamp + nonce + encrypt_key).encode(UTF_8) + request.body}）与飞书"签名校验"文档。
     * <b>不是 SHA-1</b>：P18 那版写成 SHA-1，而单测用同一个 helper 复算签名 ⇒ 本地全绿、
     * 真飞书每一条入站都会 401（口径订正见 {@code _doc/hermes-roadmap.md} §8.14）。</p>
     *
     * <p>比对走 {@link MessageDigest#isEqual}（定长时间），摘要与签名都是 hex ⇒ 只归一大小写
     * （文档明说大小写不敏感）。未配 encrypt-key 时返回 {@code true}（= 校验关闭），
     * 门由 {@link #handleEvent} 那一层把。</p>
     */
    public boolean verifySignature(String timestamp, String nonce, byte[] rawBody, String signature) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            return true;
        }
        if (timestamp == null || nonce == null || signature == null || rawBody == null) {
            return false;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((timestamp + nonce + encryptKey).getBytes(StandardCharsets.UTF_8));
            sha256.update(rawBody);
            byte[] digest = sha256.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            byte[] mine = hex.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] theirs = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(mine, theirs);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 加密模式解密：请求体是 {@code {"encrypt": base64(IV | ciphertext)}}，
     * AES key = {@code SHA-256(encryptKey)}（32 字节），IV = base64 解码后的<b>前 16 字节</b>，
     * AES-256-CBC + PKCS#7（JDK 的 {@code PKCS5Padding} 在 16 字节块上就是 PKCS#7）。
     *
     * <p>形状同样照官方 SDK 的 {@code core/utils/decryptor.py}：明文<b>直接就是事件 JSON</b>，
     * 没有长度前缀 —— 4 字节长度前缀是<b>企业微信</b>那套的形状，别混进来。</p>
     *
     * <p>失败返回 {@code null}，调用方必须 fail-closed 回 400。日志只写"哪一步失败"
     * （异常类型 / 密文字节数），绝不写 encryptKey、密文或明文。</p>
     */
    public String decryptEvent(String encryptBase64) {
        if (encryptKey == null || encryptKey.isEmpty()) {
            LOG.warn("[feishu] 收到加密事件但未配置 {}", KEY_ENCRYPT_KEY);
            return null;
        }
        if (encryptBase64 == null || encryptBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] blob = Base64.getDecoder().decode(encryptBase64.trim());
            if (blob.length < 32 || blob.length % 16 != 0) {
                // 至少 1 块 IV + 1 块密文，且必须是整块；不满足就是形状不对
                LOG.warn("[feishu] 密文长度不合法: bytes={}", blob.length);
                return null;
            }
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(blob, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(blob, 16, blob.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 不带 e.getMessage()：填充校验失败的消息里可能含明文片段
            LOG.warn("[feishu] 事件解密失败: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}