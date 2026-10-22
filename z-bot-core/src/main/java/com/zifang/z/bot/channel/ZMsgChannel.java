package com.zifang.z.bot.channel;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.define.TypeReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * z-msg 通道（outbound-only）：把一条出站消息交给 <b>z-msg</b> 的
 * {@code POST /api/msg/send} 直发端点，由 z-msg 走它自己的管道（偏好 → 静默 → 限流 →
 * 模板兜底 → 幂等占位 → 发送 → 重试 → 降级 → 投递日志）。
 *
 * <p><b>本通道没有入站面</b>：不监听端口、不注册任何 HTTP 端点、不碰
 * {@link TurnLease} 的会话语义。它只是「一种把消息发出去的方式」，与
 * {@link FeishuChannel} / {@link DingTalkChannel} 的出站那一半同形，但连入站那一半都没有。
 * ⇒ 装配时它<b>不能</b>当会话来源通道（{@link ChannelRegistry#isOutbound} 判它
 * {@code outbound=true}，那是「可作投递目标」，不是「可收回消息」）。</p>
 *
 * <p><b>为什么走 manifest 缝登记</b>：新增 kind 不动核心装配逻辑 ——
 * {@link ChannelRegistry} 的缺省档声明形状、profile 覆盖、{@code requires} 缺键检查、
 * 工厂表都是现成的（见 {@code channels.builtin.properties}）。
 * 缺省档里 {@code enabled=false}：<b>地址与凭据不随包发</b>，必须由
 * {@code <configDir>/channels.properties} 显式打开。</p>
 *
 * <p><b>仓内零硬编码</b>：{@code base-url} 只能由 manifest / CLI 覆盖注入，
 * 本类<b>没有任何默认地址</b>（不像飞书有 {@code DEFAULT_API_BASE}）——
 * z-msg 是组织内服务，它的地址随部署变，猜一个默认值只会在换环境时静默发错地方。
 * {@code token} 同理：配了就发 {@code Authorization: Bearer …}，没配就不发这个头。</p>
 *
 * <p><b>台账：记 msgId</b>（红线 8 的兑现）。本通道自己给每次提交记一条义务
 * （{@link DeliveryLedger}）：{@code pending → attempting → delivered/failed}，
 * <b>delivered 时把 z-msg 回执里的 {@code msgId} 一起写进台账</b>
 * （{@code external_msg_id} 列）⇒ z-bot 的账与 z-msg 的 {@code z_msg_delivery_log}
 * 用同一个 msgId 串起来：z-bot 侧按 msgId 查得到「发给了谁、什么时候发的」，
 * z-msg 侧按 provider_message_id 查得到同一条投递。两本账的 join key 是本通道写进
 * 请求体的 {@code idempotencyKey}（= 台账 obligation id），它落在 z-msg 投递日志的
 * {@code idempotency_key} 列里。</p>
 *
 * <p><b>幂等口径</b>：义务 id = {@code sha256(收件人 + 正文)}（去掉重投标记后计算），
 * 同一收件人 + 同一正文 视为同一条投递 ⇒ 重复提交时 z-msg 回 409（幂等命中），
 * 本通道按「已提交」处理（不重复投递、不算失败），台账里保留首次那条的 msgId。</p>
 *
 * <p><b>失败语义</b>：提交失败（传输层失败 / 4xx / 5xx）⇒ 台账记 failed 态，
 * 并抛 {@link IOException}（文案里带状态码与 z-msg 的人话，不带 token）。
 * 5xx 属「可重试」：台账的 failed 行是天然的 Retry 边界（{@link DeliveryLedger}
 * 的清扫会把归属进程已死的 failed 行重新认领重投，预算 {@link DeliveryLedger#MAX_ATTEMPTS} 次），
 * 这与既有 outbound 通道的惯例一致（通道本身不做内部重试，重试由台账兜）。</p>
 */
public final class ZMsgChannel implements Channel {

    private static final Logger LOG = LoggerFactory.getLogger(ZMsgChannel.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** manifest 里 {@code channel.<name>.config.<key>} 的键名（也是缺键报错里写的名字）。 */
    public static final String KEY_BASE_URL = "base-url";
    /** 可选：z-msg 侧若在认证边界内，配了就发 {@code Authorization: Bearer <token>}。 */
    public static final String KEY_TOKEN = "token";
    /** z-msg 侧的介质（EMAIL / SMS / IN_APP …）；缺省 {@link #DEFAULT_MSG_CHANNEL}。 */
    public static final String KEY_MSG_CHANNEL = "msg-channel";
    /** z-msg 侧的归档分类（落进投递日志的 biz_type）；缺省 {@link #DEFAULT_BIZ_TYPE}。 */
    public static final String KEY_BIZ_TYPE = "biz-type";
    /** 可选：邮件标题；不给就让 z-msg 按 bizType 渲染。 */
    public static final String KEY_SUBJECT = "subject";

    /** z-msg 直发端点路径（{@code MsgSendController}，z-msg 仓）。 */
    public static final String SEND_PATH = "/api/msg/send";

    /** z-msg 侧介质缺省：邮件（卡里的验收场景就是「把总结发给某个邮箱」）。 */
    public static final String DEFAULT_MSG_CHANNEL = "EMAIL";
    /** z-msg 侧归档分类缺省：直发（与 z-msg 端点自身的缺省一致）。 */
    public static final String DEFAULT_BIZ_TYPE = "DIRECT_SEND";

    /** 通道名（台账 platform 列 / cron 投递口按它认人）。 */
    public static final String NAME = "zmsg";

    private final ChannelBus bus;
    private final String baseUrl;
    private final String token;
    private final String msgChannel;
    private final String bizType;
    private final String subject;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private volatile long lastHttpStatus;
    private volatile String lastMsgId = "";

    /**
     * 注册表用的构造。
     *
     * @param bus       只用它拿 {@link DeliveryLedger}（台账在网关启动时才挂上，所以是发信那一刻才取）
     * @param baseUrl   z-msg 的基地址（{@code http://host:port} 形状）；<b>没有默认值</b>，缺了就抛
     * @param token     可选；配了发 Bearer 头
     * @param msgChannel z-msg 侧介质；空 ⇒ {@link #DEFAULT_MSG_CHANNEL}
     * @param bizType   z-msg 侧归档分类；空 ⇒ {@link #DEFAULT_BIZ_TYPE}
     * @param subject   可选标题；空 ⇒ 不发这个字段
     */
    public ZMsgChannel(ChannelBus bus, String baseUrl, String token,
                       String msgChannel, String bizType, String subject) {
        this.bus = bus;
        this.baseUrl = baseUrl == null ? null : trimTrailingSlash(baseUrl.trim());
        this.token = token == null || token.trim().isEmpty() ? null : token.trim();
        this.msgChannel = msgChannel == null || msgChannel.trim().isEmpty()
                ? DEFAULT_MSG_CHANNEL : msgChannel.trim().toUpperCase(Locale.ROOT);
        this.bizType = bizType == null || bizType.trim().isEmpty()
                ? DEFAULT_BIZ_TYPE : bizType.trim();
        this.subject = subject == null || subject.trim().isEmpty() ? null : subject.trim();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** outbound-only 通道没有监听面；{@code running} 恒为 true（它不需要起停）。 */
    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public void start() {
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            // 不许静默：缺地址时第一条投递就会失败，启动期先喊出来
            LOG.warn("[zmsg] 出站不可用 —— 缺配置键 {}；send() 会抛 ChannelConfigException，"
                    + "不会假装已送达", lacking);
        }
    }

    @Override
    public void stop() {
        try {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        } catch (RuntimeException e) {
            LOG.debug("[zmsg] 出站连接池收尾异常: {}", e.getMessage());
        }
    }

    @Override
    public void awaitTermination() {
        // outbound-only 通道不常驻：没有可等待的线程
    }

    // ===== 出站 =====

    @Override
    public void send(OutboundMessage message) throws IOException {
        if (message == null) {
            return;
        }
        List<String> lacking = missingCredentialKeys();
        if (!lacking.isEmpty()) {
            throw new ChannelConfigException("zmsg 出站缺配置键: " + String.join(", ", lacking)
                    + " —— z-msg 的地址必须由 manifest（channel.zmsg.config.base-url）显式给出，"
                    + "仓内不写死任何地址", lacking);
        }
        String receiver = message.replyTo == null ? "" : message.replyTo.trim();
        if (receiver.isEmpty()) {
            throw new IOException("zmsg 出站没有收件人（replyTo 为空）—— 收件人由投递方给出，"
                    + "z-bot 侧不猜");
        }
        String content = message.text == null ? "" : message.text;

        // 台账：先落账，再产生副作用（红线 8）
        String oid = obligationId(receiver, content);
        DeliveryLedger ledger = ledger();
        if (ledger != null) {
            // ensure 语义：不覆盖既有行的 attempts/created_at（重投时那两列是重试预算）
            ledger.ensureObligation(oid, "zmsg:" + receiver, NAME, receiver, content);
            ledger.markAttempting(oid);
        }

        String body = buildRequestBody(receiver, content, oid);
        HttpResult r = call(body);
        lastHttpStatus = r.status;
        if (r.status == 409) {
            // z-msg 幂等命中：这条（收件人 + 正文）此前已提交过 ⇒ 不重复投递，也不算失败。
            // 台账里保留首次那条的 msgId（markDelivered 不动 external_msg_id）。
            if (ledger != null) {
                ledger.markDelivered(oid);
            }
            LOG.warn("[zmsg] 幂等命中（z-msg 回 409）—— 收件人={} 正文相同的这条此前已提交，未重复投递",
                    receiver);
            return;
        }
        if (r.status < 200 || r.status >= 300) {
            String reason = describeFailure(r.status, r.body);
            if (ledger != null) {
                ledger.markFailed(oid, reason);
            }
            throw new IOException(scrub(reason));
        }
        String msgId = extractMsgId(r.body);
        if (msgId.isEmpty()) {
            // HTTP 200 但拿不到回执 msgId ⇒ 不敢记 delivered（台账与真实世界分叉比慢一拍坏得多）
            String reason = "zmsg 提交成功但回执里没有 msgId（body=" + abbreviate(r.body) + "）";
            if (ledger != null) {
                ledger.markFailed(oid, reason);
            }
            throw new IOException(scrub(reason));
        }
        lastMsgId = msgId;
        if (ledger != null) {
            ledger.markDelivered(oid, msgId);
        }
        LOG.info("[zmsg] 已提交 z-msg receiver={} msgId={} text={}",
                receiver, msgId, abbreviate(content));
    }

    /**
     * 义务 id：{@code sha256(收件人 + 正文)} 的稳定摘要（去掉重投标记后计算）。
     *
     * <p>为什么按「收件人 + 正文」而不是每次调用一个新 id：同一封邮件重投（网关重启后的
     * 断点重投、或 5xx 之后的人工重试）必须落在<b>同一行</b>台账上，否则会出现
     * 「第一次失败、第二次成功」两行，其中一行永远停在 failed —— 账就分叉了。
     * 同样地，这个 id 也当作 z-msg 的 {@code idempotencyKey} 发过去：z-msg 侧因此
     * 只投递一次，重复提交回 409。</p>
     */
    public static String obligationId(String receiver, String content) {
        String text = content == null ? "" : content;
        // 断点重投的标记不是正文的一部分 ⇒ 算 id 前先摘掉，否则重投会算成新的一条
        if (text.startsWith(DeliveryLedger.RECOVERED_MARKER)) {
            text = text.substring(DeliveryLedger.RECOVERED_MARKER.length());
        }
        return DeliveryLedger.computeObligationId("zmsg:" + receiver, "zmsg:direct-send", text);
    }

    /** 出站请求体：z-msg {@code POST /api/msg/send} 的形状（{@code MsgSendRequest}）。 */
    String buildRequestBody(String receiver, String content, String obligationId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("channel", msgChannel);
        body.put("receiver", receiver);
        body.put("content", content == null ? "" : content);
        body.put("bizType", bizType);
        // 幂等键 = 台账义务 id ⇒ z-msg 的投递日志 idempotency_key 列里带着它（两本账的 join key）
        body.put("idempotencyKey", obligationId);
        if (subject != null) {
            body.put("subject", subject);
        }
        return JsonUtil.toJson(body);
    }

    /** 完整 URL：{@code <base-url> + /api/msg/send}。 */
    public String sendUrl() {
        return baseUrl + SEND_PATH;
    }

    private HttpResult call(String jsonBody) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(sendUrl())
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(jsonBody, JSON_MEDIA));
        if (token != null) {
            rb.header("Authorization", "Bearer " + token);
        }
        try (Response resp = http.newCall(rb.build()).execute()) {
            ResponseBody body = resp.body();
            return new HttpResult(resp.code(), body == null ? "" : body.string());
        } catch (IOException e) {
            throw new IOException("zmsg 出站传输失败: " + e.getClass().getSimpleName()
                    + ": " + scrub(String.valueOf(e.getMessage())), e);
        }
    }

    /** 提 z-msg 回执里的 {@code data.msgId}；取不到返回空串。 */
    static String extractMsgId(String responseBody) {
        Object data = parseObject(responseBody).get("data");
        if (data instanceof Map) {
            return str(((Map<?, ?>) data).get("msgId"));
        }
        return "";
    }

    /**
     * 失败文案：状态码 + z-msg 的人话（{@code message} / {@code msg}）。
     * 不带 URL、不带 token（URL 本身可能带凭据，token 只在请求头里）。
     */
    static String describeFailure(int status, String body) {
        Map<String, Object> parsed = parseObject(body);
        String msg = str(parsed.get("message"));
        if (msg.isEmpty()) {
            msg = str(parsed.get("msg"));
        }
        if (msg.isEmpty()) {
            msg = abbreviate(body);
        }
        return "zmsg 提交失败 http=" + status + (msg.isEmpty() ? "" : " · " + msg);
    }

    /**
     * 出站缺哪些配置键：{@code base-url} 是硬门槛（z-msg 的地址）。
     * {@code token} 可空（z-msg 的直发端点默认关在认证边界内），所以它不进 requires。
     */
    public List<String> missingCredentialKeys() {
        java.util.ArrayList<String> lacking = new java.util.ArrayList<String>();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            lacking.add(KEY_BASE_URL);
        }
        return lacking;
    }

    /** 最近一次出站的 HTTP 状态码；0 = 还没发过。 */
    public long lastHttpStatus() {
        return lastHttpStatus;
    }

    /** 最近一次提交成功时 z-msg 回执里的 msgId；没有过就是空串。 */
    public String lastMsgId() {
        return lastMsgId;
    }

    /** 通道名（台账 platform 列）。 */
    public String platform() {
        return NAME;
    }

    private DeliveryLedger ledger() {
        if (bus == null) {
            return null;
        }
        try {
            return bus.deliveryLedger();
        } catch (RuntimeException e) {
            LOG.debug("[zmsg] 取台账失败（继续投递）: {}", e.getMessage());
            return null;
        }
    }

    /** 把 token 从任何文案里洗掉 —— 凭据不许出现在日志与异常里。 */
    private String scrub(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        if (token != null && !token.isEmpty() && out.contains(token)) {
            out = out.replace(token, "***");
        }
        return out;
    }

    // ===== helpers =====

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.<String, Object>emptyMap();
        }
        try {
            Map<String, Object> parsed = JsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (RuntimeException e) {
            return Collections.<String, Object>emptyMap();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }
}
