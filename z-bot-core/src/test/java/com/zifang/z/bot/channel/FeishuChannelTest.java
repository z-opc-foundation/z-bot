package com.zifang.z.bot.channel;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FeishuChannel} 入站这一面的单测：URL 校验 / 事件分发 / 验签 / 加密模式解密，全走真 HTTP。
 * stub 的发送仍只走 outbound 队列，不发真飞书。
 *
 * <p><b>为什么这里有硬编码的"已知答案向量"</b>：P18 那版把飞书事件订阅的签名写成 SHA-1，
 * 而单测用<b>同一个</b> {@code sha1Hex} helper 现算签名再比 ⇒ 本地 12 支全绿，真飞书每一条入站都会 401
 * （算法错在尺子与被测物同源时是量不出来的）。现在算法层钉的是外部工具独立算出来的常量
 * （生成命令写在每条断言上方，可原样复算），并且额外要求"旧的那个错答案必须不认"。</p>
 */
public class FeishuChannelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** fixture 与签名 helper 共用一份 key —— 各写一遍迟早会漂。 */
    private static final String VERIFY_TOKEN = "verify-token-123";
    private static final String ENCRYPT_KEY = "encrypt-key-456";
    private static final String TS = "1700000000";

    // ===== 已知答案向量（独立实现算的，不是本仓 helper 现算的）=====

    /**
     * python3 -c "import hashlib; \
     *   print(hashlib.sha256(('1700000000'+'n-1'+'encrypt-key-456').encode()+b'{\"test\":1}').hexdigest())"
     */
    private static final String KAT_BODY = "{\"test\":1}";
    private static final String KAT_SIG =
            "c2bf4c3d3d0a19429d72e6e1e4e7592107018d4af2cc10200a086d0c0afd28de";

    /** 同上，body 换含中文的一串：ts=1700000000 nonce=n-2 ⇒ d866be55…（钉"摘要算的是 UTF-8 字节"）。 */
    private static final String KAT_CJK_BODY = "{\"event\":{\"message\":{\"content\":\"你好\"}}}";
    private static final String KAT_CJK_NONCE = "n-2";
    private static final String KAT_CJK_SIG =
            "d866be559648bf3f9e852331eda9fd139675391d6d6dad70511d1897ba6a31da";

    /**
     * AES 密文由 LibreSSL 生成（不是本仓 Java 自己加密再解），key = encrypt-key-456：
     * <pre>
     * printf '%s' '&lt;明文&gt;' &gt; v2.json                        # 500 B
     * /usr/bin/openssl enc -aes-256-cbc \
     *   -K $(python3 -c "import hashlib;print(hashlib.sha256(b'encrypt-key-456').hexdigest())") \
     *   -iv 00112233445566778899aabbccddeeff -in v2.json | \
     *   python3 -c "import sys,base64;print(base64.b64encode(bytes.fromhex('00112233445566778899aabbccddeeff')+sys.stdin.buffer.read()).decode())"
     * </pre>
     * 明文形状照官方 SDK 的 v2.0 事件（token 在 header 里、正文在 message.content 的字符串化 JSON 里）。
     */
    private static final String V2_ENCRYPT_B64 =
            "ABEiM0RVZneImaq7zN3u/wV2ADLAkqg05ee5z+c6ke1Z1ghXEfikB2TSopExHlsRTks1IkOf1TT6iP2Uom8d"
            + "CcLTcd42p+fDlCdBVugzg+BZpxqZoSgzQvjY0v/3Em7ALEFKoqfqYktWWuQdn/Guqucn0Lf5N30hvE990eJA"
            + "7fXO/KZJEPK8XxkMh0JlUQTpET+ed1cVh31vOwIFUGsvNQ2DG2gTFGuH6E4KGRUj/noTWUHT9awL23vM4gSA"
            + "/UWo+5+UFiOUJLMcL52thS7uGuJ4QGIQv403IwBrzV6X7Eza1OoYML5h079niMKr/LLbsUxXkzj9ry+EYFf0"
            + "jyg2OJcccvpiSY+j3C/jqlz/XNpw37iZklMWbTOmVJabWWYVdHkjs9uo8779vLC1MDRHaU9PWaaDJZCNOn1e"
            + "WAuIukWiZLnMhfOpBekEDab1uAK8aO57p/mK9BvVVGEg26ibN1JbmHCJBFgYWxCcgK91+S6FvXxSEDI2na3x"
            + "ATVC5n6ICvl1gxYYEjmAWWZQb4LDGJiXIzgC/y+GStnbFoZ/c+9kHyjMtOD6ypdZmFAR4+dP2lRsUssrGBfS"
            + "+rp1KGD65kdvzf+v0dKrhdAqsa2xgjKsSHb5ipB9BXF7aRoZYaVl0dyfNGlZ63vi5E2DUNmztU7xJ0PwKTeO"
            + "ufv7fi/btnjyR7DV0h+PQivR4GL4igot";
    private static final String V2_PLAINTEXT =
            "{\"schema\":\"2.0\",\"header\":{\"event_id\":\"e20260927\",\"token\":\"verify-token-123\","
            + "\"event_type\":\"im.message.receive_v1\",\"create_time\":\"1789000000000\",\"tenant_key\":\"t_1\","
            + "\"app_id\":\"cli_1\"},\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"ou_v2_sender\","
            + "\"user_id\":\"u_v2_sender\"},\"sender_type\":\"user\",\"tenant_key\":\"t_1\"},"
            + "\"message\":{\"message_id\":\"om_v2_1\",\"root_id\":\"\",\"parent_id\":\"\","
            + "\"create_time\":\"1789000000000\",\"chat_id\":\"oc_v2_chat\",\"chat_type\":\"p2p\","
            + "\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"加密正文\\\"}\"}}}";

    /** 同一把 key 加密的 url_verification 明文（iv=ffeeddccbbaa99887766554433221100）。 */
    private static final String CHALLENGE_ENCRYPT_B64 =
            "/+7dzLuqmYh3ZlVEMyIRAGtfS0oveRNPPTiex7iWEUwUBtdo24nB0u8WvkiNibXnxMZ5eruFfaQ0n29pFiES"
            + "qUWofiYa3Jlo6iFjCeiLeymZrzCy9k4gxNIZrwbUz0rm";

    /** 用<b>别人的</b> encrypt-key 加密、但用我们的 key 签名的载荷 ⇒ 签名门放行、解密必然失败 ⇒ 400。 */
    private static final String WRONGKEY_ENCRYPT_B64 =
            "AQIDBAUGBwgJCgsMDQ4PEAptkjqiS9Z1zFTJoLnrGnoxp0iFzk9J9c7cX6kk+EE0dQjGJ5j730go7Koiu7yH"
            + "UF8PkmWIwTbePEVTifbBfi+DtfAlyiT4iz73mV697b0HW2iCxE5YpqYaXLhO4t2Qnu4esGl82yKSIXc3+5sTEOw=";

    /** 16 字节 ⇒ 只有 IV 没有密文块，形状就不对。 */
    private static final String TOO_SHORT_ENCRYPT_B64 = "AAAAAAAAAAAAAAAAAAAAAA==";

    private FeishuChannel channel;
    private ChannelBus bus;
    private int port;

    @Before
    public void setUp() throws Exception {
        bus = new ChannelBus(stubAgent());
        bus.start();
        channel = new FeishuChannel(bus, 0, "app-id", "app-secret",
                VERIFY_TOKEN, ENCRYPT_KEY, null);
        channel.start();
        port = channel.getPort();
    }

    @After
    public void tearDown() {
        if (channel != null) {
            channel.stop();
        }
        if (bus != null) {
            bus.shutdown();
        }
    }

    private com.zifang.z.bot.agent.BotAgent stubAgent() throws Exception {
        return com.zifang.z.bot.agent.BotAgent.builder(null)
                .provider(new com.zifang.z.agent.kernel.llm.LlmProvider() {
                    @Override public String name() { return "stub"; }
                    @Override public java.util.List<com.zifang.z.agent.kernel.llm.Model> listModels() { return Collections.emptyList(); }
                    @Override public boolean supportsModel(String m) { return true; }
                    @Override public com.zifang.z.agent.kernel.llm.ChatCompletionsResponse chat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r) {
                        String last = r.getMessages().isEmpty() ? "" : r.getMessages().get(r.getMessages().size() - 1).getContent();
                        return new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse("id", "stub",
                                Collections.singletonList(new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse.Choice(0,
                                        "echo: " + last,
                                        Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new com.zifang.z.agent.kernel.types.TokenUsage(5L, 3L, 8L), "stop", null);
                    }
                    @Override public void streamChat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r,
                                                     java.util.function.Consumer<com.zifang.z.agent.kernel.llm.ChatCompletionsResponse> on,
                                                     java.util.function.Consumer<Throwable> err) { on.accept(chat(r)); }
                })
                .sandbox(new com.zifang.z.bot.tool.Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new com.zifang.z.bot.session.SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutCenter()
                .build();
    }

    @Test
    public void getEchostrIsNotAnsweredOnTheFeishuFace() throws Exception {
        // D-P30-1 的裁定：`echostr` 是**企微**的回调校验形状，不是飞书的 —— `lark_oapi` 1.5.3 全包
        // `echostr` 0 命中、hermes `plugins/platforms/feishu/` 0 命中，而她的 wecom 适配器有 6 处
        // （`callback_adapter.py:274-278`，且那份是解密后才回显）。挂在飞书面上就是一条验签/token 门
        // **之前**的查询参数原文回显 ⇒ 拆掉，GET 一律 405。
        HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                + "/feishu/event?echostr=attacker-controlled-probe").openConnection();
        con.setRequestMethod("GET");
        int rc = con.getResponseCode();
        assertEquals("GET 必须 405（这一面只有 POST）", 405, rc);
        String body = readAll(con.getErrorStream() == null ? con.getInputStream() : con.getErrorStream());
        assertFalse("门外的原文回显不许留下 attacker 那半轴: " + body,
                body.contains("attacker-controlled-probe"));

        // 阳性对照：同一路径同一端口的 POST url_verification（签名对、token 对）必须照旧 200 + 回显
        // challenge。缺了这一臂，上面的"405 且不回显"可以是"整个面被打死了"读出来的。
        String chal = "{\"type\":\"url_verification\",\"token\":\"" + VERIFY_TOKEN
                + "\",\"challenge\":\"post-arm-works\"}";
        String[] resp = postEvent(chal, TS, "n-get-echostr-control", Sign.CORRECT);
        assertEquals("同一路径的 POST 仍然活着: " + resp[1], "200", resp[0]);
        assertEquals("challenge 仍按 P30 的顺序（过 token 门才回显）给出",
                "{\"challenge\":\"post-arm-works\"}", resp[1]);
    }

    @Test
    public void postEventWithValidTokenDeliversToBus() throws Exception {
        String body = "{\"token\":\"verify-token-123\",\"event\":"
                + "{\"sender_id\":\"ou_1\",\"chat_id\":\"oc_chat1\",\"chat_type\":\"group\",\"text\":\"你好\"}}";
        String[] resp = postEvent(body, TS, "n-1", Sign.CORRECT);
        assertEquals("带正确签名的入站事件必须过签名门（否则后面那串断言全是空跑）: " + resp[1], "200", resp[0]);
        waitFor(() -> !bus.history().isEmpty(), 2000);
        assertEquals(1, bus.history().size());
        ChannelBus.Entry e = bus.history().get(0);
        assertEquals("feishu", e.channel);
        assertEquals("group:oc_chat1", e.conversationId);
        assertEquals("ou_1", e.senderId);
        assertEquals("你好", e.text);
        assertTrue("reply 应是 echo 形式，实际: " + e.reply, e.reply.contains("你好"));
    }

    @Test
    public void flatV1ToleranceStopsAtTheKeysAlreadyReadThere() throws Exception {
        // D-P30-2 的边界：`lark_oapi` 1.5.3 的类型化模型 `P2ImMessageReceiveV1Data` 只声明
        // {sender, message}（`_types` 实测就这两项），hermes 的分发只读 `payload["header"]["event_type"]`
        // （`adapter.py:3585-3596`）、零平铺分支 ⇒ 这里的平铺容错是**没有出处的多认一手**，
        // 它只准认代码里已经读过的那几个键。曾经有一条主张说"v1 正文埋在 `event.content`"，
        // 三处参照里一个字都找不到 ⇒ 不给无出处的键开读取点，并且由这一支钉住这条边界
        // （否则"没加"与"加了但没人管"在测试里长得一模一样）。
        int before = bus.history().size();
        String noSource = "{\"token\":\"" + VERIFY_TOKEN + "\",\"event\":"
                + "{\"chat_id\":\"oc_content\",\"chat_type\":\"group\",\"content\":\"from-content\"}}";
        String[] resp = postEvent(noSource, TS, "n-12", Sign.CORRECT);
        assertEquals("这一面是活的（否则下面那句'没投递'可以是整面被打死读出来的）: " + resp[1],
                "200", resp[0]);

        // 阳性对照：同一形状、同一个签名，只把键换成有出处的那一个 ⇒ 必须真投递。
        String[] ctrl = postEvent("{\"token\":\"" + VERIFY_TOKEN + "\",\"event\":"
                + "{\"chat_id\":\"oc_text\",\"chat_type\":\"group\",\"text\":\"from-text\"}}",
                TS, "n-13", Sign.CORRECT);
        assertEquals("对照请求本身要过门: " + ctrl[1], "200", ctrl[0]);
        waitFor(() -> bus.history().size() > before, 2000);
        assertEquals("`event.content` 不是读取点：无出处那一条不许产出投递",
                before + 1, bus.history().size());
        ChannelBus.Entry got = bus.history().get(bus.history().size() - 1);
        assertEquals("from-text", got.text);
        assertEquals("group:oc_text", got.conversationId);
    }

    @Test
    public void postEventWithBadTokenReturns401() throws Exception {
        // 必须带着正确签名进来，否则 401 是签名门给的，token 门根本没被走到（会假绿）。
        String body = "{\"token\":\"wrong-token\",\"event\":{\"chat_id\":\"x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-2", Sign.CORRECT);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自 token 门而不是签名门: " + resp[1], resp[1].contains("token mismatch"));
        // 等一小段时间确保没有异步投递
        Thread.sleep(150);
        assertTrue("未授权时不应投递", bus.history().isEmpty());
    }

    @Test
    public void tamperedSignatureIsRejectedWith401AndNeverReachesBus() throws Exception {
        // 猎物：token 是对的、签名是拿别人的 key 算的 ⇒ 只能被签名门挡下。
        // 这条是 LEDGER D10/D11 的杀手：把 verifySignature 改成恒真、或把 handleEvent 里的验签摘掉，都会让它变绿。
        String body = "{\"token\":\"verify-token-123\",\"event\":{\"chat_id\":\"oc_x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-3", Sign.FOREIGN_KEY);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自签名门: " + resp[1], resp[1].contains("signature mismatch"));
        Thread.sleep(150);
        assertTrue("错签名的内容不得进 bus", bus.history().isEmpty());
    }

    @Test
    public void missingSignatureHeadersAreRejectedWhenEncryptKeyConfigured() throws Exception {
        // fail-closed：配了 encrypt-key 却一个签名头都不带，也算拒（不许靠"不带头"绕过去）。
        String body = "{\"token\":\"verify-token-123\",\"event\":{\"chat_id\":\"oc_x\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-4", Sign.NONE);
        assertEquals("401", resp[0]);
        assertTrue("401 必须出自签名门: " + resp[1], resp[1].contains("signature mismatch"));
        Thread.sleep(150);
        assertTrue("缺签名的内容不得进 bus", bus.history().isEmpty());
    }

    @Test
    public void sha1SignedEventIsRejectedWith401AndNeverReachesBus() throws Exception {
        // 回归门：P18 那版的实现是 SHA-1（错），当时这串请求会拿到 200。
        // 算法若被改回去，这一支与上面 helper 层那支会立刻红 ⇒ "错摘要不许放行"。
        String body = "{\"token\":\"verify-token-123\",\"event\":{\"chat_id\":\"oc_sha1_legacy\",\"text\":\"hi\"}}";
        String[] resp = postEvent(body, TS, "n-5", Sign.LEGACY_SHA1);
        assertEquals("SHA-1 算的签名必须拒（飞书事件订阅是 SHA-256）: " + resp[1], "401", resp[0]);
        assertTrue("401 必须出自签名门: " + resp[1], resp[1].contains("signature mismatch"));
        Thread.sleep(150);
        assertTrue("错算法的内容不得进 bus", bus.history().isEmpty());
    }

    @Test
    public void unsignedEventIsStillAcceptedWhenEncryptKeyAbsent() throws Exception {
        // 反向对照：门是**配了 encrypt-key 才关**的。这条防的是"把守卫改成恒验"这种过头修法
        // （那样上面三条负例照样绿，而真实未加密接入方会被全拒）。
        FeishuChannel plain = new FeishuChannel(bus, 0, "app-id", "app-secret",
                VERIFY_TOKEN, null, null);
        try {
            plain.start();
            String body = "{\"token\":\"verify-token-123\",\"event\":"
                    + "{\"sender_id\":\"ou_9\",\"chat_id\":\"oc_plain\",\"text\":\"未加密接入\"}}";
            int rc = post(plain.getPort(), body, Sign.NONE);
            assertEquals("未配 encrypt-key 时未签名入站应被接受", 200, rc);
            waitFor(() -> !bus.history().isEmpty(), 2000);
            assertEquals(1, bus.history().size());
            assertEquals("未加密接入", bus.history().get(0).text);
        } finally {
            plain.stop();
        }
    }

    // ===== 解密（飞书加密模式）=====

    @Test
    public void decryptEventMatchesOpensslKnownAnswer() {
        // 密文是 LibreSSL 产的（见 V2_ENCRYPT_B64 上方生成命令）⇒ 这一支证"我们解得开别人加的密"，
        // 不是"自己加密再自己解开"。500 字节明文一个字符都不许多、不少（顺带证 PKCS#7 填充被正确摘掉）。
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", ENCRYPT_KEY, null);
        assertEquals(V2_PLAINTEXT, ch.decryptEvent(V2_ENCRYPT_B64));
    }

    @Test
    public void decryptEventFailsClosedOnWrongKeyAndMalformedInput() {
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", ENCRYPT_KEY, null);
        assertTrue("别人那把 key 的密文必须解不开",
                ch.decryptEvent(WRONGKEY_ENCRYPT_B64) == null);
        assertTrue("非 base64 必须解不开", ch.decryptEvent("@@@not-base64@@@") == null);
        assertTrue("只有 IV 没有密文块（16 字节）必须解不开",
                ch.decryptEvent(TOO_SHORT_ENCRYPT_B64) == null);
        assertTrue("空串必须解不开", ch.decryptEvent("") == null);
        FeishuChannel noKey = new FeishuChannel(bus, 0, "a", "b", "v", null, null);
        assertTrue("没配 encrypt-key 时不许假装能解", noKey.decryptEvent(V2_ENCRYPT_B64) == null);
    }

    @Test
    public void encryptedV2EventIsVerifiedDecryptedAndDelivered() throws Exception {
        // 整条链一次跑通：SHA-256 验签（算在密文原文上）⇒ AES 解出 v2 事件 ⇒ header.token 比对
        // ⇒ 从 sender/message.content 里取正文。少任何一环这里都红。
        String body = "{\"encrypt\":\"" + V2_ENCRYPT_B64 + "\"}";
        String[] resp = postEvent(body, TS, "n-6", Sign.CORRECT);
        assertEquals("加密事件应被解开: " + resp[1], "200", resp[0]);
        waitFor(() -> !bus.history().isEmpty(), 2000);
        assertEquals(1, bus.history().size());
        ChannelBus.Entry e = bus.history().get(0);
        assertEquals("feishu", e.channel);
        assertEquals("p2p:oc_v2_chat", e.conversationId);
        assertEquals("ou_v2_sender", e.senderId);
        assertEquals("加密正文", e.text);
    }

    @Test
    public void v2EventWithHeaderTokenIsDeliveredUnencrypted() throws Exception {
        // v2.0 的 token 在 header 里：只看顶层会把每一条真事件都判成 401（P30 修掉的那条）。
        String body = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"im.message.receive_v1\","
                + "\"token\":\"verify-token-123\"},\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"ou_h\"}},"
                + "\"message\":{\"chat_id\":\"oc_hdr\",\"chat_type\":\"group\",\"message_type\":\"text\","
                + "\"content\":\"{\\\"text\\\":\\\"明文 v2\\\"}\"}}}";
        String[] resp = postEvent(body, TS, "n-7", Sign.CORRECT);
        assertEquals("带 header.token 的 v2 事件必须过 token 门: " + resp[1], "200", resp[0]);
        waitFor(() -> !bus.history().isEmpty(), 2000);
        assertEquals(1, bus.history().size());
        assertEquals("group:oc_hdr", bus.history().get(0).conversationId);
        assertEquals("ou_h", bus.history().get(0).senderId);
        assertEquals("明文 v2", bus.history().get(0).text);
    }

    @Test
    public void nonTextMessageTypeIsNotDeliveredAsEmptyMessage() throws Exception {
        // 陌生类型不许投一条空消息（也不许 500）：投出去就是往会话里发垃圾，静默丢弃才是可诊断的形状。
        String body = "{\"schema\":\"2.0\",\"header\":{\"event_type\":\"im.message.receive_v1\","
                + "\"token\":\"verify-token-123\"},\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"ou_img\"}},"
                + "\"message\":{\"chat_id\":\"oc_img\",\"chat_type\":\"p2p\",\"message_type\":\"image\","
                + "\"content\":\"{\\\"image_key\\\":\\\"img_1\\\"}\"}}}";
        String[] resp = postEvent(body, TS, "n-8", Sign.CORRECT);
        assertEquals("200", resp[0]);
        Thread.sleep(150);
        assertTrue("非文本消息不该投一条空文本进总线: " + bus.history(), bus.history().isEmpty());
    }

    @Test
    public void encryptedEventThatCannotBeDecryptedGets400AndNeverReachesBus() throws Exception {
        // 签名是拿我们的 key 算的（放行），密文却是别人的 key 加密的（解不开）⇒
        // 必须 400 且一个字都不投。这条防的是"过了签名门就把剩下当可信"的修法。
        String body = "{\"encrypt\":\"" + WRONGKEY_ENCRYPT_B64 + "\"}";
        String[] resp = postEvent(body, TS, "n-9", Sign.CORRECT);
        assertEquals("解不开的密文必须 400: " + resp[1], "400", resp[0]);
        assertTrue("400 必须出自解密这一步: " + resp[1], resp[1].contains("decrypt failed"));
        Thread.sleep(150);
        assertTrue("解不开的内容不得进 bus", bus.history().isEmpty());
    }

    // ===== challenge（URL 校验）=====

    @Test
    public void encryptedUrlVerificationEchoesDecryptedChallenge() throws Exception {
        String body = "{\"encrypt\":\"" + CHALLENGE_ENCRYPT_B64 + "\"}";
        String[] resp = postEvent(body, TS, "n-10", Sign.CORRECT);
        assertEquals("加密的 url_verification 应被解开: " + resp[1], "200", resp[0]);
        assertEquals("challenge 必须是解密后那一个", "{\"challenge\":\"chal-777\"}", resp[1]);
    }

    @Test
    public void urlVerificationChallengeIsNotEchoedWithoutValidToken() throws Exception {
        // 未鉴权的请求不许拿 challenge 回显来验证"我打到了你的回调地址"（hermes 同一顺序：先 token 后回显）。
        String body = "{\"type\":\"url_verification\",\"token\":\"wrong-token\",\"challenge\":\"attacker-echo\"}";
        String[] resp = postEvent(body, TS, "n-11", Sign.CORRECT);
        assertEquals("401", resp[0]);
        assertFalse("未过 token 门不得回显 challenge: " + resp[1], resp[1].contains("attacker-echo"));
    }

    // ===== helper 层（算法本身）=====

    @Test
    public void currentTokenReturnsStaticOrStub() {
        FeishuChannel noStatic = new FeishuChannel(bus, 0, "app", "sec", "v", "e", null);
        assertEquals("STUB-TOKEN-please-fill-appSecret", noStatic.currentToken());
        FeishuChannel withStatic = new FeishuChannel(bus, 0, "app", "sec", "v", "e", "static-token-value");
        assertEquals("static-token-value", withStatic.currentToken());
    }

    @Test
    public void signatureHelperRejectsMismatchWithoutEncryptKey() {
        // encryptKey=null/empty 时 verifySignature 直接返回 true（关闭校验）
        FeishuChannel ch = new FeishuChannel(bus, 0, "app", "sec", "v", null, null);
        assertTrue(ch.verifySignature("ts", "nonce", bytes("body"), "anything"));
        FeishuChannel empty = new FeishuChannel(bus, 0, "app", "sec", "v", "", null);
        assertTrue(empty.verifySignature("ts", "nonce", bytes("body"), "anything"));
    }

    @Test
    public void signatureHelperMatchesFeishuKnownAnswer() {
        // 两条硬编码摘要来自 python3 的 hashlib（常量上方给了生成命令）：
        // 一条纯 ASCII、一条含中文 ⇒ 钉住"摘要 = sha256((ts+nonce+key) 的 UTF-8 字节 + 原始 body 字节)"。
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", ENCRYPT_KEY, null);
        assertTrue("python 算的 SHA-256 必须认",
                ch.verifySignature(TS, "n-1", bytes(KAT_BODY), KAT_SIG));
        assertTrue("含中文 body 的 SHA-256 必须认",
                ch.verifySignature(TS, KAT_CJK_NONCE, bytes(KAT_CJK_BODY), KAT_CJK_SIG));
        assertTrue("飞书文档明说大小写不敏感 ⇒ 大写 hex 也得认",
                ch.verifySignature(TS, "n-1", bytes(KAT_BODY), KAT_SIG.toUpperCase(java.util.Locale.ROOT)));
        assertFalse("同一串输入的 SHA-1 摘要必须不认（那是 P18 的错算法）",
                ch.verifySignature(TS, "n-1", bytes(KAT_BODY),
                        sha1Hex(TS + "n-1" + ENCRYPT_KEY + KAT_BODY)));
    }

    @Test
    public void signatureHelperRejectsWrongDigest() {
        // 兑现侧那一层（HTTP 层那几支钉"接线"，这条钉"算法"）：
        // 错签名、内容被改一个字节、缺 signature，都必须返回 false —— 恒真的实现当场变红。
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", "my-secret", null);
        String body = "{\"event\":{}}";
        assertFalse("错签名必须拒", ch.verifySignature("1700000000", "n-1", bytes(body), "deadbeef"));
        assertFalse("换了 key 算出来的签名必须拒",
                ch.verifySignature("1700000000", "n-1", bytes(body),
                        sha256Hex(("1700000000" + "n-1" + "another-key").getBytes(StandardCharsets.UTF_8),
                                bytes(body))));
        assertFalse("内容改一个字节，原签名必须失效",
                ch.verifySignature("1700000000", "n-1", bytes(body + " "),
                        sha256Hex(("1700000000" + "n-1" + "my-secret").getBytes(StandardCharsets.UTF_8),
                                bytes(body))));
        // 上面那一臂**杀不掉** G2（实现里摘掉 `sha256.update(rawBody)`）：它的签名是测试自己
        // 按"含 body"算的，实现不算 body 时两边还是不相等 ⇒ 靠巧合绿。真正能钉住"摘要必须含 body"
        // 的是攻击者形状：签名只算 ts+nonce+key，body 随你改 ⇒ 必须拒。
        assertFalse("不含 body 的摘要必须拒（G2 的猎物）",
                ch.verifySignature("1700000000", "n-1", bytes(body),
                        sha256Hex(("1700000000" + "n-1" + "my-secret").getBytes(StandardCharsets.UTF_8),
                                new byte[0])));
        assertFalse("缺 signature 必须拒", ch.verifySignature("1700000000", "n-1", bytes(body), null));
        assertFalse("缺 timestamp 必须拒", ch.verifySignature(null, "n-1", bytes(body), KAT_SIG));
        assertFalse("null body 必须拒", ch.verifySignature("1700000000", "n-1", null, KAT_SIG));
    }

    @Test
    public void signatureHelperAcceptsCorrectDigest() {
        FeishuChannel ch = new FeishuChannel(bus, 0, "a", "b", "v", "my-secret", null);
        long ts = 1700000000L;
        String nonce = "n-1";
        String body = "{\"event\":{}}";
        String sha256 = sha256Hex((ts + nonce + "my-secret").getBytes(StandardCharsets.UTF_8), bytes(body));
        assertTrue(ch.verifySignature(String.valueOf(ts), nonce, bytes(body), sha256));
    }

    @Test
    public void outgoingUrlIsStable() {
        assertNotNull(channel.outgoingUrl());
        assertTrue(channel.outgoingUrl().contains("open-apis/im/v1/messages"));
    }

    // ===== helpers =====

    /**
     * 签名怎么带。{@link #LEGACY_SHA1} 是 P18 那版错算法留下的形状：
     * 同一把 key、同一个 body，只是摘要用错 ⇒ 只有算法层能把它和红签名分开。
     */
    private enum Sign { NONE, CORRECT, FOREIGN_KEY, LEGACY_SHA1 }

    private String[] postEvent(String body, String ts, String nonce, Sign sign) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/feishu/event");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (sign != Sign.NONE) {
            con.setRequestProperty("X-Lark-Request-Timestamp", ts);
            con.setRequestProperty("X-Lark-Request-Nonce", nonce);
            con.setRequestProperty("X-Lark-Signature", signatureFor(sign, ts, nonce, body));
        }
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return new String[] {String.valueOf(code), is == null ? "" : readAll(is)};
    }

    private static String signatureFor(Sign sign, String ts, String nonce, String body) {
        if (sign == Sign.LEGACY_SHA1) {
            return sha1Hex(ts + nonce + ENCRYPT_KEY + body);
        }
        String key = sign == Sign.CORRECT ? ENCRYPT_KEY : "someone-elses-key";
        return sha256Hex((ts + nonce + key).getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
    }

    private int post(int targetPort, String body, Sign sign) throws IOException {
        URL url = new URL("http://127.0.0.1:" + targetPort + "/feishu/event");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return con.getResponseCode();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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

    private static void waitFor(java.util.function.BooleanSupplier cond, long maxMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static String sha256Hex(byte[] head, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(head);
            md.update(body);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    /** 只留着当"错算法的猎物"：与生产实现无共享路径，它红不该被当成生产代码红。 */
    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
