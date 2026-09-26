package com.zifang.z.bot.mcp;

import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P21 §8：headers 脱敏的<b>双向</b>断言（安全红线）。
 *
 * <p>口径按工单钉死：<b>同一条测试里两条都要绿</b> ——
 * ① 哨兵<b>确实进了</b> config / 原始异常文本 / 对端回显（阳性对照，缺了它就是空跑满分）；
 * ② 哨兵<b>不出现在</b>任何对外产物（{@code toString()} / {@code toSafeMap()} /
 * {@code maskedHeaders()} / 状态行 / 线级台账）。缺一半即视为未交付。</p>
 *
 * <p>三条泄漏路径各拦一条（{@link SecretRedaction} 的类注释）：结构化视图走
 * {@code maskAll}，自由文本走 {@code scrub}，url 走 {@code maskUrl}。</p>
 */
public class SecretRedactionBidirectionalTest {

    /** 长度 ≥12 ⇒ 打码后保留首 2 尾 2；整串仍是一个可搜的哨兵。 */
    private static final String BEARER = "Bearer SENTINELhdr-ABCDEFGHIJKLMNOP";
    private static final String APIKEY = "SENTINELkey-QRSTUVWXYZ0123456789";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BotConfig loadWith(String... lines) throws IOException {
        File dir = tmp.newFolder("cfg");
        FileWriter w = new FileWriter(new File(dir, "config.properties"));
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        w.write(sb.toString());
        w.close();
        return BotConfig.load(dir);
    }

    private static BotConfig.McpServerEntry first(BotConfig cfg) {
        assertEquals(1, cfg.getMcpServers().size());
        return cfg.getMcpServers().get(0);
    }

    /** 配一个 http server：header 两条 secret，url 的 query 里再漏一条（三种产物一次覆盖）。 */
    private BotConfig.McpServerEntry entryWithSecrets(String url) throws IOException {
        BotConfig cfg = loadWith(
                "mcp.servers=corp=" + url,
                "mcp.server.corp.transport=http",
                "mcp.server.corp.headers=Authorization=" + BEARER + ";X-API-Key=" + APIKEY);
        return first(cfg);
    }

    // -------------------------------------------------------- ①+② 双向：配置侧产物

    @Test
    public void sentinelIsPinnedIntoTheConfigAndOutOfEveryRenderedProduct() throws Exception {
        BotConfig.McpServerEntry e = entryWithSecrets("http://127.0.0.1:43999/mcp?token=" + APIKEY);

        // —— 阳性对照：哨兵真的进到了 config 里（否则下面的"不含"全是空跑）
        assertEquals(BEARER, e.getHeaders().get("Authorization"));
        assertEquals(APIKEY, e.getHeaders().get("X-API-Key"));
        assertTrue("url 的 query 里确实带着 secret: " + e.getUrl(), e.getUrl().contains(APIKEY));

        // —— 负向：三条对外产物一律没有原文
        assertFalse("toString 漏了 header: " + e.toString(), e.toString().contains(BEARER));
        assertFalse("toString 漏了 url query: " + e.toString(), e.toString().contains(APIKEY));
        Map<String, Object> safe = e.toSafeMap();
        assertFalse("toSafeMap 漏了 query: " + safe, String.valueOf(safe).contains(APIKEY));
        assertFalse(String.valueOf(safe).contains(BEARER));
        Map<String, String> masked = e.maskedHeaders();
        assertFalse(String.valueOf(masked).contains(BEARER));
        assertFalse(String.valueOf(masked).contains(APIKEY));

        // —— 结构侧还要"看得见"：脱敏不等于把信息全抹掉，否则排障时什么也没有
        assertEquals(Arrays.asList("Authorization", "X-API-Key"),
                new java.util.ArrayList<String>(masked.keySet()));
        assertEquals("http://127.0.0.1:43999/mcp", safe.get("url"));
        assertTrue("打码后要留可辨识度: " + masked.get("Authorization"),
                masked.get("Authorization").startsWith("Be"));
        assertTrue(masked.get("Authorization").endsWith("OP"));
    }

    /** 走一遍真实的生产装配路径拿状态行（对端是 127.0.0.1 上的一次性 socket）。 */
    private List<Map<String, Object>> statusRowsOf(BotConfig.McpServerEntry e) {
        Toolkit tk = new Toolkit();
        McpManager mgr = new McpManager(tk, Collections.singletonList(e));
        mgr.startAll();
        List<Map<String, Object>> rows = mgr.toMapList();
        mgr.stopAll();
        return rows;
    }

    /**
     * 最难拦的一条：secret 顺着<b>对端回显的响应体</b>进异常文本。
     *
     * <p>猎物在同一条测试里钉三处：① 客户端<b>真的</b>把 {@code Authorization} 发上了线
     * （服务端侧抓到的请求行含哨兵）；② 对端<b>真的</b>把它回显进 401 响应体；
     * ③ 于是管理器状态行里既有 {@code <redacted:authorization>} 这个"确实替换过"的痕，
     * 又没有哨兵原文。少任一条都是空跑，不许记满分。</p>
     *
     * <p>刻意用一次性 {@link java.net.ServerSocket} 而不是 {@code com.sun.net.httpserver}：
     * 后者在 JDK8 上 {@code stop()} 曾把 surefire fork 卡死在 {@code preClose0}（本期量具纪律
     * 第 5 条）。这里的收尾是"关监听 + 带超时 join"，两条都有上限。</p>
     */
    @Test
    public void secretEscapingThroughPeerEchoIsCaughtBeforeTheStatusRow() throws Exception {
        final String echoedBody = "{\"jsonrpc\":\"2.0\",\"result\":null,\"echoed\":\"" + BEARER + "\"}";
        final java.util.concurrent.CountDownLatch served =
                new java.util.concurrent.CountDownLatch(1);
        final StringBuilder capturedRequest = new StringBuilder();
        java.net.ServerSocket listener = new java.net.ServerSocket(
                0, 4, java.net.InetAddress.getLoopbackAddress());
        final int port = listener.getLocalPort();
        Thread responder = new Thread(new Runnable() {
            @Override
            public void run() {
                try (java.net.Socket s = listener.accept()) {
                    s.setSoTimeout(8000);
                    readWholeRequest(s, capturedRequest);
                    byte[] body = echoedBody.getBytes(StandardCharsets.UTF_8);
                    java.io.OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    Thread.sleep(150L); // 让客户端把这行读完再撒手
                } catch (Exception ignored) {
                    // 一次性量具：对端断了就是测完了，真判据在断言里
                } finally {
                    served.countDown();
                }
            }
        }, "p21-401-echo");
        responder.setDaemon(true);
        responder.start();
        try {
            BotConfig.McpServerEntry e = entryWithSecrets(
                    "http://127.0.0.1:" + port + "/mcp?token=" + APIKEY);
            List<Map<String, Object>> rows = statusRowsOf(e);
            assertEquals(1, rows.size());
            assertTrue("这一次服务应当真发生过",
                    served.await(15, java.util.concurrent.TimeUnit.SECONDS));
            String error = String.valueOf(rows.get(0).get("error"));
            assertEquals(Boolean.FALSE, rows.get(0).get("ok"));

            // ① + ② 猎物在场
            String request = capturedRequest.toString();
            assertTrue("客户端没真把 header 发上线，这条断言就是空跑:\n" + request,
                    request.contains(BEARER));
            assertTrue("对端回显的原文里必须有哨兵，否则「不含」没有代价",
                    echoedBody.contains(BEARER));

            // ③ 出得了产物的只有脱敏位
            assertFalse("状态行把 header secret 原样带出去了: " + error, error.contains(BEARER));
            assertFalse("状态行把 url query 的 secret 原样带出去了: " + error,
                    error.contains(APIKEY));
            assertTrue("必须有替换过的痕才算脱敏生效: " + error,
                    error.contains("<redacted:authorization>"));
            assertTrue("脱敏后仍要说清错在哪: " + error, error.contains("没有 result"));
            assertFalse(String.valueOf(rows.get(0).keySet()).contains("headers"));
        } finally {
            listener.close();
            responder.join(3000L);
        }
    }

    /** 读到完整请求头 + 声明的 body 长度为止，避免半路关连接让客户端写成 broken pipe。 */
    private static void readWholeRequest(java.net.Socket s, StringBuilder sink) throws IOException {
        java.io.InputStream in = s.getInputStream();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        String text = "";
        int headerEnd = -1;
        while (headerEnd < 0 && (n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            headerEnd = text.indexOf("\r\n\r\n");
        }
        if (headerEnd < 0) {
            sink.append(text);
            return;
        }
        String head = text.substring(0, headerEnd);
        int want = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                try {
                    want = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int have = text.length() - (headerEnd + 4);
        while (have < want && (n = in.read(chunk)) > 0) {
            have += n;
        }
        sink.append(text);
    }

    // -------------------------------------------------------- ①+② 双向：脱敏原语

    @Test
    public void scrubReplacesEveryKnownSecretEvenWhenOneIsAPrefixOfAnother() {
        Map<String, String> secrets = new java.util.LinkedHashMap<String, String>();
        String shortOne = "ABCDEFGHIJ0";
        String longOne = "ABCDEFGHIJ0123456789";
        secrets.put("X-Short", shortOne);
        secrets.put("X-Long", longOne);
        String raw = "401 body echoed both tokens: " + longOne + " and also " + shortOne;

        // 猎物在场
        assertTrue(raw.contains(longOne));
        assertTrue(raw.contains(shortOne));

        String out = SecretRedaction.scrub(raw, secrets);
        assertFalse("短的先替会把长的吃剩一个尾巴: " + out, out.contains(longOne));
        assertFalse(out.contains(shortOne));
        assertTrue("替换位要留痕，不能静默吞掉: " + out, out.contains("<redacted:x-long>"));
        assertTrue(out.contains("<redacted:x-short>"));
        // 空 secret 表 ⇒ 原文透传（不能让 scrub 变成"啥都改写"）
        assertEquals(raw, SecretRedaction.scrub(raw, Collections.<String, String>emptyMap()));
        assertEquals(raw, SecretRedaction.scrub(raw, null));
    }

    @Test
    public void maskKeepsFixedLengthStarsSoTheSecretLengthDoesNotLeak() {
        String v = "SENTINELvalue-0123456789abcdefghij";
        String m = SecretRedaction.mask(v);
        assertFalse("打码后不该还能数出真实长度: " + m, m.contains(v));
        assertTrue("中间必须是定长星: " + m, m.matches("SE\\*{8}ij"));
        assertEquals(SecretRedaction.mask("另一个更长得多的哨兵值-xxxxxxxxxxxxxxxxxxxxx")
                .length(), m.length());
        assertEquals("********", SecretRedaction.mask("short-1"));
        assertEquals("", SecretRedaction.mask(""));
        assertEquals("null", SecretRedaction.mask(null));
        Map<String, String> all = SecretRedaction.maskAll(
                Collections.singletonMap("Authorization", v));
        assertEquals(1, all.size());
        assertEquals(m, all.get("Authorization"));
        assertTrue(SecretRedaction.maskAll(null).isEmpty());
        assertEquals(Collections.singletonList("Authorization"),
                SecretRedaction.headerNames(Collections.singletonMap("Authorization", v)));
        assertTrue(SecretRedaction.headerNames(null).isEmpty());
    }

    @Test
    public void maskUrlDropsQueryAndUserInfoButKeepsThePath() {
        String raw = "https://user:pass@corp.example.com:8443/mcp/team?token=" + APIKEY;
        assertTrue(raw.contains(APIKEY));
        String m = SecretRedaction.maskUrl(raw);
        assertFalse(m, m.contains(APIKEY));
        assertFalse("user-info 也是凭证: " + m, m.contains("pass"));
        assertTrue("path 要留着才排得了障: " + m, m.contains("/mcp/team"));
        assertEquals("http://127.0.0.1:43999/mcp",
                SecretRedaction.maskUrl("http://127.0.0.1:43999/mcp?x=1"));
        assertEquals("", SecretRedaction.maskUrl(""));
    }

    /** stdio 条目没有 header 位，但 {@code command} 本身可能带 token ⇒ 走同一条 mask 口径。 */
    @Test
    public void stdioEntryRenderKeepsCommandButHttpEntryNeverKeepsRawHeaders() throws Exception {
        BotConfig.McpServerEntry stdio = new BotConfig.McpServerEntry("fs",
                Arrays.asList("node", "/srv/fs.js", "--token=" + APIKEY));
        assertTrue("stdio 条目的命令行本来就是要给人看的", stdio.toString().contains("--token="));
        assertFalse(stdio.isHttp());
        assertEquals(java.util.Collections.<String, String>emptyMap(), stdio.maskedHeaders());

        BotConfig.McpServerEntry http = entryWithSecrets(
                "http://127.0.0.1:43999/mcp?token=" + APIKEY);
        assertFalse(http.toString().contains(APIKEY));
        assertFalse(http.maskedHeaders().containsValue(APIKEY));
        assertFalse(http.maskedHeaders().containsValue(BEARER));
    }
}
