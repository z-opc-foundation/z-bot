package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DingTalkChannel} 的<b>入站验签</b>单测（P30 起手）。
 *
 * <p>这一层在 P30 之前<b>一条测试都没有</b>：全库 grep {@code /dingtalk/in} 在 test 目录下 0 命中
 * （只有 {@link WebhookChannelTest} 打的是另一个通道的同名形状），所以 {@code handleInbound} 既是
 * "广告里有验签、代码里没有"，也没有任何一支测试会发现它 —— 缺的不是断言，是猎物。</p>
 *
 * <p>签名算法本体靠 <b>python {@code hmac} 独立算出的已知答案</b>钉
 * （见 {@link #inboundSignMatchesIndependentlyComputedKnownAnswer}），而不是测试里再抄一遍
 * Java 的 Mac 调用 —— 后者会与实现同时漂（P18 飞书那 12 支绿测试就是这么漏掉 SHA-1/SHA-256 之差的）。
 * 已知答案的生成命令（可复算）：
 * <pre>
 * python3 -c 'import hmac,hashlib,base64; k=b"dingtalk-secret-789"; \
 *   print(base64.b64encode(hmac.new(k, b"1700000000000\n"+k, hashlib.sha256).digest()).decode())'
 * </pre>
 * 公式出处：钉钉开放平台文档《接收消息》"检查 timestamp 和 sign" 一节
 * （{@code sign = base64(HMAC-SHA256(secret, timestamp + "\\n" + secret))}，且要求
 * timestamp 与当前时间差在 1 小时以内）。四个错误构造（键/文互换、丢换行、改用 SHA-1、
 * 不做 HMAC）也各算了一份答案，用来证明那支已知答案<b>有判别力</b>而不只是"两串相等"。</p>
 */
public class DingTalkInboundVerifyTest {

    /** 已知答案用的那把"入站密钥"（与 python 命令里的一致，纯字符串不是真凭据）。 */
    private static final String KAT_KEY = "dingtalk-secret-789";
    private static final String WEBHOOK_ONLY_SECRET = "SECwebhook-sign-key-not-real";

    private static final String KAT_TS = "1700000000000";
    private static final String KAT_SIGN = "q0qWqbxn7IEWs8o4C2M3J2rt2tg61kruJ1Dfe1d/+7E=";
    private static final String KAT_TS_2 = "1700000000001";
    private static final String KAT_SIGN_2 = "FPOh45xCVQW+Aejsfsjjzf1oIQrsoYWJDt1E9cu4vso=";

    /** python 侧同料不同式的四个错误构造 —— 已知答案必须与它们<b>不</b>相等，否则那串毫无判别力。 */
    private static final String PREY_SWAPPED_KEY_AND_MSG = "/7v3lXAK8i5XNasdEca4dgqttLSZDxQ8oUtDBgK9Sn4=";
    private static final String PREY_MISSING_NEWLINE = "+HmLnh8THOKCyAEpteZp0mp66NXpK0v8aZmWx9AT0SQ=";
    private static final String PREY_SHA1_DIGEST = "FLL3WOQ0L1i1YskCdgdCqz42Ovw=";
    private static final String PREY_PLAIN_SHA256 = "nujFOyWsJQoPSLjO9u7Km0rgxkJ5dS43SU9ve31D3Jo=";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BotAgent agent;
    private ChannelBus bus;

    @Before
    public void setUp() throws Exception {
        agent = stubAgent();
        bus = new ChannelBus(agent);
        bus.start();
    }

    @After
    public void tearDown() {
        if (bus != null) {
            bus.shutdown();
        }
        if (agent != null) {
            agent.shutdown();
        }
    }

    // ===== 算法本体：已知答案，两个独立实现必须逐字符相等 =====

    @Test
    public void inboundSignMatchesIndependentlyComputedKnownAnswer() throws Exception {
        assertEquals("钉钉入站 sign 必须是 base64(HMAC-SHA256(key, ts + \"\\n\" + key))，"
                + "与 python hmac 算出的已知答案逐字符相等", KAT_SIGN, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS));
        assertEquals("换一个 ts 也必须对上（防止把 ts 写进常量或参与错误的拼接）",
                KAT_SIGN_2, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS_2));

        // 阳性对照的另一半：证明上面那串不是"任何实现都会算出同一个东西"的巧合
        assertNotEquals("键与原文不能互换", PREY_SWAPPED_KEY_AND_MSG, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS));
        assertNotEquals("拼接原文里的换行不能丢", PREY_MISSING_NEWLINE, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS));
        assertNotEquals("摘要不是 SHA-1（飞书那边刚犯过同一类错）",
                PREY_SHA1_DIGEST, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS));
        assertNotEquals("必须是 HMAC，不是裸 SHA-256",
                PREY_PLAIN_SHA256, DingTalkChannel.inboundSign(KAT_KEY, KAT_TS));
    }

    // ===== 接线：HTTP 层的门 =====

    @Test
    public void validSignedInboundIsDeliveredToBus() throws Exception {
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = now();
            String[] resp = post(ch.getPort(), inboundBody("cid_in_1", "staff_zhang", "入站验签过了"),
                    ts, sign(ts));
            assertEquals("带正确签名与新鲜时间戳的入站必须过门（否则下面全是空跑）: " + resp[1], "200", resp[0]);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            ChannelBus.Entry e = bus.history().get(0);
            assertEquals("dingtalk", e.channel);
            assertEquals("cid_in_1", e.conversationId);
            assertEquals("staff_zhang", e.senderId);
            assertEquals("入站验签过了", e.text);
        } finally {
            ch.stop();
        }
    }

    @Test
    public void foreignKeysSignatureIsRejectedWith401AndNeverReachesBus() throws Exception {
        // 猎物：ts 是新鲜的、格式是对的，只有签名是拿别人的 key 算的 ⇒ 只能被签名比对挡下。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = now();
            String[] resp = post(ch.getPort(), inboundBody("cid_forged", "attacker", "伪造的入站"),
                    ts, DingTalkChannel.inboundSign("someone-elses-secret", ts));
            assertEquals("401", resp[0]);
            assertTrue("401 必须出自验签门: " + resp[1], resp[1].contains("signature mismatch"));
            assertNothingReachesBus();
        } finally {
            ch.stop();
        }
    }

    @Test
    public void missingSignHeadersAreRejectedWhenSecretConfigured() throws Exception {
        // fail-closed：配了密钥却一个头都不带，也算拒（不许靠"不带头"绕过去）。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String[] resp = post(ch.getPort(), inboundBody("cid_forged", "attacker", "缺签名的入站"), null, null);
            assertEquals("401", resp[0]);
            assertTrue(resp[1], resp[1].contains("signature mismatch"));
            assertNothingReachesBus();
        } finally {
            ch.stop();
        }
    }

    @Test
    public void nonNumericTimestampIsRejectedWith401AndNeverReachesBus() throws Exception {
        // 签名本身是"按那串非数字原文"算对的 ⇒ 挡下它的只能是时间戳解析这一道。
        // 摘掉 try/catch 会变成 500（异常冒到 handleInbound 的兜底），这一支据此区分得开。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = "not-a-timestamp";
            String[] resp = post(ch.getPort(), inboundBody("cid_forged", "attacker", "坏时间戳"), ts, sign(ts));
            assertEquals("401", resp[0]);
            assertTrue(resp[1], resp[1].contains("signature mismatch"));
            assertNothingReachesBus();
        } finally {
            ch.stop();
        }
    }

    @Test
    public void staleTimestampIsRejectedEvenWithValidSign() throws Exception {
        // 钉钉文档的第二道检查：超出 1 小时窗口就不收。签名是对这条旧 ts 正确算的 ⇒
        // 挡下它的只能是窗口，不是比对。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = String.valueOf(System.currentTimeMillis() - DingTalkChannel.INBOUND_MAX_SKEW_MS - 120_000L);
            String[] resp = post(ch.getPort(), inboundBody("cid_replay", "attacker", "重放的旧消息"), ts, sign(ts));
            assertEquals("401", resp[0]);
            assertNothingReachesBus();
        } finally {
            ch.stop();
        }
    }

    @Test
    public void futureTimestampBeyondWindowIsRejected() throws Exception {
        // 窗口必须是 |now - ts|（两侧都管），只判"过期"的实现这一支当场变红。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = String.valueOf(System.currentTimeMillis() + DingTalkChannel.INBOUND_MAX_SKEW_MS + 120_000L);
            String[] resp = post(ch.getPort(), inboundBody("cid_future", "attacker", "超前两小时"), ts, sign(ts));
            assertEquals("401", resp[0]);
            assertNothingReachesBus();
        } finally {
            ch.stop();
        }
    }

    @Test
    public void inboundWindowIsTheOneHourTheDocAllows() {
        // 上面两支负例的偏移是**相对 INBOUND_MAX_SKEW_MS 取的**（各超出 2 分钟），所以把常量改成
        // 一天它们照样绿 —— 行为层结构上钉不住"1 小时"这个数字本身。这一支就是补那个洞：
        // 数字出自钉钉文档《接收消息》"timestamp 和系统当前时间戳之间的差值必须在 1 小时以内"。
        assertEquals("入站时间窗就是文档写的那 1 小时", 3_600_000L, DingTalkChannel.INBOUND_MAX_SKEW_MS);
    }

    @Test
    public void timestampFiveMinutesAgoIsAccepted() throws Exception {
        // 反向对照：防"把窗口收成 0/几毫秒"这种过头修法 —— 那样上面两支负例照样绿，
        // 而真实网络抖动的入站会被全拒。
        DingTalkChannel ch = startChannel(KAT_KEY, null);
        try {
            String ts = String.valueOf(System.currentTimeMillis() - 300_000L);
            String[] resp = post(ch.getPort(), inboundBody("cid_recent", "staff_li", "五分钟前发的"), ts, sign(ts));
            assertEquals("窗口内（5 分钟前）的入站必须收: " + resp[1], "200", resp[0]);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            assertEquals("五分钟前发的", bus.history().get(0).text);
        } finally {
            ch.stop();
        }
    }

    @Test
    public void inboundWithoutAnySecretIsAccepted() throws Exception {
        // 门是**配了密钥才关**的（与飞书 encrypt-key 同一约定）。这条防的是"恒验"那种过头修法。
        DingTalkChannel ch = startChannel(null, null);
        try {
            String[] resp = post(ch.getPort(), inboundBody("cid_open", "staff_wang", "未配密钥的接入"), null, null);
            assertEquals("未配置任何入站密钥时未签名入站应被接受", "200", resp[0]);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            assertEquals("未配密钥的接入", bus.history().get(0).text);
        } finally {
            ch.stop();
        }
    }

    // ===== 两把密钥的优先级（inbound-secret 与 webhook 加签 secret 不是一把时）=====

    @Test
    public void inboundSecretOverridesWebhookSecret() throws Exception {
        DingTalkChannel ch = startChannel(WEBHOOK_ONLY_SECRET, KAT_KEY);
        try {
            String ts = now();
            String[] wrong = post(ch.getPort(), inboundBody("cid_key", "attacker", "拿 webhook 密钥签的"),
                    ts, DingTalkChannel.inboundSign(WEBHOOK_ONLY_SECRET, ts));
            assertEquals("入站密钥已显式配置 ⇒ webhook 加签密钥签出来的必须拒", "401", wrong[0]);
            assertNothingReachesBus();

            String[] right = post(ch.getPort(), inboundBody("cid_key", "staff_zhou", "拿入站密钥签的"), ts, sign(ts));
            assertEquals("用 inbound-secret 签的必须收: " + right[1], "200", right[0]);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            assertEquals("拿入站密钥签的", bus.history().get(0).text);
        } finally {
            ch.stop();
        }
    }

    @Test
    public void blankInboundSecretFallsBackToWebhookSecret() throws Exception {
        // 自定义机器人两处是同一把 SEC 串：inbound-secret 留空（或全空白）必须退回 secret，
        // 而不是"退回空白 ⇒ 门被关掉"。
        DingTalkChannel ch = startChannel(KAT_KEY, "   ");
        try {
            String ts = now();
            assertEquals("inbound-secret 空白时按 secret 验签必须收", "200",
                    post(ch.getPort(), inboundBody("cid_fallback", "staff_sun", "回退到 secret"), ts, sign(ts))[0]);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
        } finally {
            ch.stop();
        }
    }

    /**
     * manifest 里那个键真的接得上：{@code channel.dingtalk.config.inbound-secret} 必须流进构造函数。
     * 打的是"接线"那一层 —— 上面两支直接调构造器，工厂忘了传也照样绿。
     */
    @Test
    public void registryManifestWiresInboundSecretKey() throws Exception {
        File configDir = tmp.newFolder("profile");
        File manifest = new File(configDir, ChannelRegistry.MANIFEST_FILE_NAME);
        try (FileWriter w = new FileWriter(manifest, StandardCharsets.UTF_8)) {
            w.write("channel.dingtalk.enabled=true\n"
                    + "channel.dingtalk.config.webhook-url=http://127.0.0.1:1/robot/send?access_token=stub\n"
                    + "channel.dingtalk.config.secret=" + WEBHOOK_ONLY_SECRET + "\n"
                    + "channel.dingtalk.config.inbound-secret=" + KAT_KEY + "\n");
        }
        ChannelRegistry registry = ChannelRegistry.load(configDir);
        DingTalkChannel ch = (DingTalkChannel) registry.create("dingtalk",
                new ChannelRegistry.Context(agent, bus, configDir).override("dingtalk", "port", "0"));
        ch.start();
        try {
            String ts = now();
            assertEquals("manifest 的 inbound-secret 必须压过 secret（工厂没传这个键的话，这里会 200）", "401",
                    post(ch.getPort(), inboundBody("cid_wire", "attacker", "只配了 manifest 键"),
                            ts, DingTalkChannel.inboundSign(WEBHOOK_ONLY_SECRET, ts))[0]);
            assertNothingReachesBus();
            assertEquals("按 manifest 的 inbound-secret 签的必须收", "200",
                    post(ch.getPort(), inboundBody("cid_wire", "staff_zhu", "manifest 接线"), ts, sign(ts))[0]);
            waitFor(() -> bus.history().size() == 1, 2000);
            assertEquals("manifest 接线", bus.history().get(0).text);
        } finally {
            ch.stop();
        }
    }

    // ===== helpers =====

    private DingTalkChannel startChannel(String secret, String inboundSecret) throws IOException {
        DingTalkChannel ch = new DingTalkChannel(bus, 0,
                "http://127.0.0.1:1/robot/send?access_token=stub-not-real", secret, null, inboundSecret);
        ch.start();
        return ch;
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis());
    }

    /**
     * 入站签名的生成：这里<b>调实现自己的</b> {@link DingTalkChannel#inboundSign}，因为时间戳必须是
     * "现在"（已知答案里那个 1700000000000 天然落在 1 小时窗口外，用它只能证"被窗口挡下"、证不了比对）。
     * 公式本身另有 {@link #inboundSignMatchesIndependentlyComputedKnownAnswer()} 用 python 的答案钉死，
     * 所以这一支的职责是<b>接线与窗口</b>，两支各管一层、缺一不可（LEDGER D10/D11 那个分工的翻版）。
     */
    private static String sign(String timestamp) throws Exception {
        return DingTalkChannel.inboundSign(KAT_KEY, timestamp);
    }

    private static String inboundBody(String conversationId, String senderId, String text) {
        return "{\"conversationId\":\"" + conversationId + "\",\"senderId\":\"" + senderId
                + "\",\"text\":\"" + text + "\"}";
    }

    private String[] post(int targetPort, String body, String timestamp, String sign) throws IOException {
        HttpURLConnection con = (HttpURLConnection)
                new URL("http://127.0.0.1:" + targetPort + "/dingtalk/in").openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (timestamp != null) {
            con.setRequestProperty(DingTalkChannel.HEADER_TIMESTAMP, timestamp);
        }
        if (sign != null) {
            con.setRequestProperty(DingTalkChannel.HEADER_SIGN, sign);
        }
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return new String[] {String.valueOf(code), is == null ? "" : readAll(is)};
    }

    private void assertNothingReachesBus() throws InterruptedException {
        Thread.sleep(150);
        assertTrue("未授权的入站内容一个字都不该进总线", bus.history().isEmpty());
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long maxMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private BotAgent stubAgent() throws Exception {
        return BotAgent.builder(null)
                .provider(new LlmProvider() {
                    @Override
                    public String name() {
                        return "stub";
                    }

                    @Override
                    public List<Model> listModels() {
                        return Collections.emptyList();
                    }

                    @Override
                    public boolean supportsModel(String m) {
                        return true;
                    }

                    @Override
                    public ChatCompletionsResponse chat(ChatCompletionsRequest r) {
                        String last = r.getMessages().isEmpty() ? ""
                                : r.getMessages().get(r.getMessages().size() - 1).getContent();
                        return new ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                                        "echo: " + last,
                                        Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new TokenUsage(5L, 3L, 8L), "stop", null);
                    }

                    @Override
                    public void streamChat(ChatCompletionsRequest r,
                                           java.util.function.Consumer<ChatCompletionsResponse> on,
                                           java.util.function.Consumer<Throwable> err) {
                        on.accept(chat(r));
                    }
                })
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutCenter()
                .build();
    }
}
