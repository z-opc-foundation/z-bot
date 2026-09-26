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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link InboundLimits}：四个入站面（飞书 / 钉钉 / webhook / HTTP 控制台）的 body 上限。
 *
 * <p>P30 收口时记下的 D-P30-3 —— 四个面里只有钉钉把门放在读之前，而四个面的读取本身都没有上限：
 * 一条不带任何凭据的 POST 就能让进程按 body 大小分配内存。这里钉三件事：</p>
 *
 * <ol>
 *   <li><b>边界</b>：正好 {@code MAX_BODY_BYTES} 必须收（否则正常事件被拒），超一个字节必须 413。
 *       四个面各测一对 —— 上限是共享的，但"接线"是每个面各自的，少接一个面那一面就没门。</li>
 *   <li><b>门是按字节累加的那一步，不是 {@code Content-Length}</b>：分块传输根本没有这个头，
 *       只信头的实现会让超大 body 一路进内存，所以 {@link #chunkedOversizedBodyWithNoContentLengthIsStillCapped()}
 *       不带 Content-Length 打。</li>
 *   <li><b>上限没抢在鉴权前面</b>：钉钉那条超尺寸、没签名的请求必须还是 401 —— 413 是"读不动"，
 *       不能被当成"先放行再挑尺寸"的口子（见 {@link #bodyCapDoesNotPreemptTheSignatureGate()}）。</li>
 * </ol>
 */
public class InboundBodyLimitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FEISHU_TOKEN = "verify-token-123";
    private static final String FEISHU_ENCRYPT_KEY = "encrypt-key-456";
    private static final String DINGTALK_KEY = "dingtalk-secret-789";

    private static final int MAX = InboundLimits.MAX_BODY_BYTES;

    private BotAgent agent;
    private ChannelBus bus;
    private FeishuChannel feishu;
    private WebhookChannel webhook;

    @Before
    public void setUp() throws Exception {
        // 显式给 sandbox/sessions 目录：本测试不碰 ~/.zbot（杠④ 的那份配置是凭据，不是测试素材）。
        agent = BotAgent.builder(null)
                .provider(stubProvider())
                .providerCode("stub")
                .sandbox(new Sandbox(tmp.newFolder("sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .model("stub")
                .withoutBuiltinTools()
                .withoutCenter()
                .build();
        bus = new ChannelBus(agent);
        bus.start();
        feishu = new FeishuChannel(bus, 0, "app-id", "app-secret",
                FEISHU_TOKEN, FEISHU_ENCRYPT_KEY, null);
        feishu.start();
        webhook = new WebhookChannel(bus, 0, "webhook");
        webhook.start();
    }

    @After
    public void tearDown() {
        if (feishu != null) {
            feishu.stop();
        }
        if (webhook != null) {
            webhook.stop();
        }
        if (bus != null) {
            bus.shutdown();
        }
        if (agent != null) {
            agent.shutdown();
        }
    }

    // ===== 1. 上限本身：单源常量，钉死字面值 =====

    @Test
    public void maxBodyBytesIsPinnedToTheHermesWecomLimit() {
        // 边界那几支都从这个常量取数（改了常量它们照样绿），所以字面值必须有独立的一支来钉：
        // 出处是 hermes wecom 的 `callback_adapter.py:57-59 _MAX_BODY = 65_536`。
        assertEquals("入站 body 上限必须是 64 KiB（hermes wecom _MAX_BODY 同值）", 65_536, MAX);
        assertTrue("上限至少得装得下一条正常事件（飞书 v2 事件实测几百字节）", MAX > 1024);
    }

    // ===== 2. 四个面各自的边界对：正好收、超一字节拒 =====

    @Test
    public void feishuAcceptsExactlyMaxAndRejectsOneByteOver() throws Exception {
        String body = pad("{\"token\":\"" + FEISHU_TOKEN + "\",\"p\":\"", "\"}", MAX);
        assertEquals("正好到上限的飞书事件必须收（否则真事件会被自己的门挡掉）: ", "200",
                post(feishuPort(), "/feishu/event", body, feishuSignedHeaders(body))[0]);
        String over = pad("{\"token\":\"" + FEISHU_TOKEN + "\",\"p\":\"", "\"}", MAX + 1);
        String[] r = post(feishuPort(), "/feishu/event", over, feishuSignedHeaders(over));
        assertEquals("超一个字节必须 413", "413", r[0]);
        assertTrue("413 的响应体要说明原因: " + r[1], r[1].contains("too large"));
    }

    @Test
    public void dingtalkAcceptsExactlyMaxAndRejectsOneByteOver() throws Exception {
        DingTalkChannel ch = startDingtalk();
        try {
            String body = pad("{\"conversationId\":\"cid_cap\",\"senderId\":\"s\",\"text\":\"", "\"}", MAX);
            assertEquals("正好到上限的钉钉入站必须收", "200", postDingtalk(ch.getPort(), body, true)[0]);
            String over = pad("{\"conversationId\":\"cid_cap\",\"senderId\":\"s\",\"text\":\"", "\"}", MAX + 1);
            String[] r = postDingtalk(ch.getPort(), over, true);
            assertEquals("超一个字节必须 413", "413", r[0]);
            assertTrue("响应体: " + r[1], r[1].contains("too large"));
        } finally {
            ch.stop();
        }
    }

    @Test
    public void webhookAcceptsExactlyMaxAndRejectsOneByteOver() throws Exception {
        String body = pad("{\"conversationId\":\"wh_cap\",\"text\":\"", "\"}", MAX);
        assertEquals("正好到上限的 webhook 入站必须收", "200",
                post(webhookPort(), "/webhook/in", body, Collections.<String, String>emptyMap())[0]);
        String over = pad("{\"conversationId\":\"wh_cap\",\"text\":\"", "\"}", MAX + 1);
        assertEquals("超一个字节必须 413", "413",
                post(webhookPort(), "/webhook/in", over, Collections.<String, String>emptyMap())[0]);
    }

    @Test
    public void httpConsoleAcceptsExactlyMaxAndRejectsOneByteOver() throws Exception {
        HttpChannel ch = new HttpChannel(agent, 0);
        ch.start();
        try {
            String base = "http://127.0.0.1:" + ch.getPort();
            // /bot/steer 是纯文本 body，且不启 agent —— 边界只需测读取本身，不必拖一轮对话。
            String ok = repeat('x', MAX);
            assertEquals("正好到上限的控制台 body 必须收", 200, code(postRaw(base + "/bot/steer", ok, false)));
            assertEquals("超一个字节必须 413", 413, code(postRaw(base + "/bot/steer", repeat('x', MAX + 1), false)));
        } finally {
            ch.stop();
        }
    }

    // ===== 3. 门不是 Content-Length：分块传输没有这个头 =====

    @Test
    public void chunkedOversizedBodyWithNoContentLengthIsStillCapped() throws Exception {
        // 若实现只信 Content-Length 头，这条请求根本不带那个头 ⇒ 会被整段收进内存。
        HttpURLConnection con = open("http://127.0.0.1:" + webhookPort() + "/webhook/in");
        con.setChunkedStreamingMode(0);
        try (OutputStream os = con.getOutputStream()) {
            os.write(repeat('x', MAX).getBytes(StandardCharsets.UTF_8));
            os.write(repeat('x', MAX).getBytes(StandardCharsets.UTF_8));
        }
        int rc = con.getResponseCode();
        assertEquals("不带 Content-Length 的分块超大 body 也必须 413", 413, rc);

        // 阳性对照：同一套分块写法打一个合法尺寸，必须还是 200 —— 否则上面那条红可以是"分块本身不被支持"。
        HttpURLConnection ok = open("http://127.0.0.1:" + webhookPort() + "/webhook/in");
        ok.setChunkedStreamingMode(0);
        try (OutputStream os = ok.getOutputStream()) {
            os.write(pad("{\"conversationId\":\"wh_chunk\",\"text\":\"", "\"}", 1024)
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("分块本身是支持的，上面的红只能来自尺寸: ", 200, ok.getResponseCode());
    }

    // ===== 4. 超限的 body 一个字都不进总线 =====

    @Test
    public void oversizedBodyNeverReachesTheBus() throws Exception {
        int before = bus.history().size();
        String over = pad("{\"conversationId\":\"wh_flood\",\"text\":\"", "\"}", MAX + 16);
        assertEquals("413", "413",
                post(webhookPort(), "/webhook/in", over, Collections.<String, String>emptyMap())[0]);
        TimeUnit.MILLISECONDS.sleep(150);
        assertEquals("超限 body 不该有任何一条进总线（连 conversationId 都不该被解析出来）",
                before, bus.history().size());
    }

    // ===== 5. 上限没抢在鉴权前面 =====

    @Test
    public void bodyCapDoesNotPreemptTheSignatureGate() throws Exception {
        DingTalkChannel ch = startDingtalk();
        try {
            String over = pad("{\"conversationId\":\"cid_cap\",\"senderId\":\"s\",\"text\":\"", "\"}", MAX + 1);
            // 没签名的超大请求：先被鉴权门挡下（401），压根不读 body —— 不能因为"尺寸也不对"就换成 413，
            // 那等于给未授权请求多开一条"我的 body 被处理过了"的回声。
            String[] unsigned = postDingtalk(ch.getPort(), over, false);
            assertEquals("未签名的超大 body 仍必须先吃 401，而不是 413", "401", unsigned[0]);
            // 反向对照：同一份 body 带对签名 ⇒ 413，证明上一句的红来自"没签名"而不是"尺寸"。
            assertEquals("同一份超限 body 签对了就该是 413（否则上面那句是空跑）",
                    "413", postDingtalk(ch.getPort(), over, true)[0]);
        } finally {
            ch.stop();
        }
    }

    // ===== 6. 接线守卫：入站 body 的读取口只有一个 =====

    @Test
    public void everyInboundBodyReadGoesThroughTheSingleSource() throws Exception {
        File dir = channelSourceDir();
        File[] files = dir.listFiles();
        assertNotNull("找不到 channel 源码目录: " + dir, files);
        assertTrue("扫描到 0 个源文件，这条守卫就是空跑: " + dir, files.length >= 20);

        List<String> holders = new ArrayList<String>();
        List<String> violators = new ArrayList<String>();
        for (File f : files) {
            if (!f.getName().endsWith(".java")) {
                continue;
            }
            List<String> hits = requestBodyEntryLines(read(f));
            if (!hits.isEmpty()) {
                holders.add(f.getName());
                if (!"InboundLimits.java".equals(f.getName())) {
                    violators.add(f.getName() + " -> " + hits);
                }
            }
        }
        assertEquals("InboundLimits 必须真的持有读取口（否则下面那条断言没有分母）",
                Collections.singletonList("InboundLimits.java"), holders);
        assertTrue("这些面还在自己读 request body，没走共享上限: " + violators, violators.isEmpty());

        // 阳性对照：判据必须认得出"自己读 body"这个形状。上面全绿也可能是我把谓词写空了。
        assertEquals(1, requestBodyEntryLines(PREY_INLINE_READER).size());
        assertEquals(0, requestBodyEntryLines("return InboundLimits.readBody(ex);\n").size());
    }

    /** 一个"某面自己拼无界读取"的样本，用来证明上一条守卫不是空跑。 */
    private static final String PREY_INLINE_READER =
            "    private static String readBody(HttpExchange ex) throws IOException {\n"
            + "        try (InputStream is = ex.getRequestBody()) {\n"
            + "            return new String(readAll(is), StandardCharsets.UTF_8);\n"
            + "        }\n"
            + "    }\n";

    /** 只认 {@code getRequestBody()} 这一个形状：它是入站 body 唯一的入口，与怎么写缓冲区无关。 */
    private static List<String> requestBodyEntryLines(String src) {
        List<String> out = new ArrayList<String>();
        int no = 0;
        for (String line : src.split("\n")) {
            no++;
            if (line.contains("getRequestBody()")) {
                out.add(no + ":" + line.trim());
            }
        }
        return out;
    }

    // ===== helpers =====

    private int feishuPort() {
        return feishu.getPort();
    }

    private int webhookPort() {
        return webhook.getPort();
    }

    private DingTalkChannel startDingtalk() throws IOException {
        DingTalkChannel ch = new DingTalkChannel(bus, 0,
                "http://127.0.0.1:1/robot/send?access_token=stub-not-real", DINGTALK_KEY, null, null);
        ch.start();
        return ch;
    }

    /** 钉钉入站：{@code signWith=true} 才带 timestamp/sign 头。 */
    private String[] postDingtalk(int port, String body, boolean signWith) throws Exception {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (signWith) {
            String ts = String.valueOf(System.currentTimeMillis());
            h.put(DingTalkChannel.HEADER_TIMESTAMP, ts);
            h.put(DingTalkChannel.HEADER_SIGN, DingTalkChannel.inboundSign(DINGTALK_KEY, ts));
        }
        return post(port, "/dingtalk/in", body, h);
    }

    /** 飞书的摘要算在原始 body 字节上 ⇒ 头必须按发出去的那份 body 现算。 */
    private Map<String, String> feishuSignedHeaders(String body) {
        String ts = "1700000000";
        String nonce = "n-cap";
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put(FeishuChannel.HEADER_REQUEST_TIMESTAMP, ts);
        h.put(FeishuChannel.HEADER_REQUEST_NONCE, nonce);
        h.put(FeishuChannel.HEADER_SIGNATURE, sha256Hex(ts + nonce + FEISHU_ENCRYPT_KEY, body));
        return h;
    }

    private static String[] post(int port, String path, String body, Map<String, String> headers)
            throws IOException {
        HttpURLConnection con = open("http://127.0.0.1:" + port + path);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            con.setRequestProperty(e.getKey(), e.getValue());
        }
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return respond(con);
    }

    private static HttpURLConnection postRaw(String url, String body, boolean chunked) throws IOException {
        HttpURLConnection con = open(url);
        if (chunked) {
            con.setChunkedStreamingMode(0);
        }
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return con;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(2000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return con;
    }

    private static String[] respond(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return new String[] {String.valueOf(code), is == null ? "" : readAll(is)};
    }

    private static int code(HttpURLConnection con) throws IOException {
        return con.getResponseCode();
    }

    /** {@code prefix + 'x' * n + suffix} 恰好 {@code totalBytes} 个字节（全 ASCII）。 */
    private static String pad(String prefix, String suffix, int totalBytes) {
        int n = totalBytes - prefix.length() - suffix.length();
        assertTrue("fixture 前后缀比总长还长: " + prefix + "… " + suffix, n > 0);
        String s = prefix + repeat('x', n) + suffix;
        assertEquals("padding 必须精确到字节", totalBytes, s.getBytes(StandardCharsets.UTF_8).length);
        return s;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static File channelSourceDir() {
        File f = new File("src/main/java/com/zifang/z/bot/channel");
        if (!f.isDirectory()) {
            f = new File("z-bot-core/src/main/java/com/zifang/z/bot/channel");
        }
        return f;
    }

    private static String read(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String head, String body) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(head.getBytes(StandardCharsets.UTF_8));
            md.update(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static LlmProvider stubProvider() {
        return new LlmProvider() {
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
        };
    }
}
