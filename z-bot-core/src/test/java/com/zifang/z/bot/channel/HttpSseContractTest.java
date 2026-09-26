package com.zifang.z.bot.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.zifang.z.bot.agent.StreamEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 靶子 3：{@code POST /bot/chat/stream} 的 SSE 契约在**真 HTTP 层**量。
 *
 * <p>不在 fixture 里造一个"假 SSE 字符串"来对拍 —— 那样生产路径的洞（帧分隔符、换行转义、
 * 客户端挂断后服务端是否还在跑）永远绿。这里一律走真 {@link HttpURLConnection} 打真
 * {@link HttpChannel}（127.0.0.1 + {@code bind(0)}），从响应体的**字节流**里自己切帧。</p>
 *
 * <p>词汇表的分母来自台账（{@code HttpChannel.routes()} 里 {@code sse-events:} 那一串），
 * 测试里不重抄一份事件名。</p>
 */
public class HttpSseContractTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private P28HttpFixture fx;

    @Before
    public void setUp() throws Exception {
        fx = P28HttpFixture.start(tmp);
    }

    @After
    public void tearDown() {
        if (fx != null) {
            fx.close();
        }
    }

    // ===== 握手 =====

    @Test
    public void handshakeIsEventStreamAndStartsBeforeAnyReply() throws Exception {
        fx.llm.sleepMillis(250);
        fx.llm.script("P28-SSE-HANDSHAKE");
        SseStream s = open("/bot/chat/stream", "{\"message\":\"p28 握手\"}");
        try {
            assertEquals("状态码不对就不许往下判形状: " + s.statusLine, 200, s.status);
            String ct = s.header("content-type");
            assertNotNull("SSE 没有 Content-Type: " + s.headers, ct);
            assertTrue("SSE 的 Content-Type 必须是 text/event-stream，实到 " + ct,
                    ct.startsWith("text/event-stream"));
            assertTrue("流式响应不能有 Content-Length（那等于把整段攒完再发）: " + s.headers,
                    !s.headers.containsKey("content-length"));
            // 250ms 的桩延迟 ⇒ 能在第一帧之前就读到 200 头，证明头不是攒完才发的
            Frame first = s.next();
            assertNotNull("开了流却一帧都没有", first);
            assertTrue("第一帧必须是台账词表里的事件名，实到 " + first.event + " 全帧=" + first.raw,
                    vocabulary().contains(first.event));
        } finally {
            s.close();
        }
    }

    // ===== 帧语法 =====

    @Test
    public void frameGrammarHoldsForEveryFrameOfARun() throws Exception {
        scriptARunWithAToolCall("P28-SSE-B");
        List<Frame> frames = collect("/bot/chat/stream", "{\"message\":\"p28 帧语法\"}", 40);
        assertTrue("这一跑至少该有 step/tool_call/tool_result/step/final/done 六帧，实到 "
                + describe(frames), frames.size() >= 6);
        Set<String> seen = new LinkedHashSet<String>();
        for (Frame f : frames) {
            assertNotNull("流里出现了没有 event: 行的帧: " + f.raw, f.event);
            assertTrue("帧内事件名必须在台账词表里，实到 " + f.event + " 台账=" + vocabulary()
                            + " 全帧=" + f.raw,
                    vocabulary().contains(f.event));
            assertTrue("SSE 帧必须逐字节等于 \"event: X\\ndata: Y\\n\\n\"，实到 " + jsonish(f.raw),
                    f.raw.equals("event: " + f.event + "\ndata: " + f.data + "\n\n"));
            assertFalse("data 里不许有裸换行（会把一帧劈成两帧）: " + jsonish(f.raw),
                    f.data.indexOf('\n') >= 0);
            assertFalse("帧里不许出现 \\r（前端按 \\n 切行）: " + jsonish(f.raw),
                    f.raw.indexOf('\r') >= 0);
            seen.add(f.event);
        }
        assertEquals("最后一帧必须是 done（前端靠它收尾）", "done",
                frames.get(frames.size() - 1).event);
        assertTrue("done 帧的载荷语法是 [DONE] steps=N replyLen=M，实到 "
                        + frames.get(frames.size() - 1).data,
                frames.get(frames.size() - 1).data.matches("\\[DONE] steps=\\d+ replyLen=\\d+"));

        // 结束帧的数字必须真等于前面的帧，不是随手写的一个数
        int stepFrames = 0;
        int finalLen = 0;
        for (Frame f : frames) {
            if ("step".equals(f.event)) {
                stepFrames++;
            }
            if ("final".equals(f.event)) {
                finalLen += unescape(f.data).length();
            }
        }
        String done = frames.get(frames.size() - 1).data;
        assertEquals("done.steps 必须等于实际 step 帧数: " + done, stepFrames,
                Integer.parseInt(match(done, "steps=(\\d+)")));
        assertEquals("done.replyLen 必须等于实际 final 正文长度: " + done, finalLen,
                Integer.parseInt(match(done, "replyLen=(\\d+)")));
        assertTrue("这一跑该出现 step", seen.contains("step"));
        assertTrue("这一跑该出现 tool_call", seen.contains("tool_call"));
        assertTrue("这一跑该出现 tool_result", seen.contains("tool_result"));
        assertTrue("这一跑该出现 final", seen.contains("final"));
    }

    @Test
    public void newlineInsideModelTextIsEscapedAndDoesNotBreakFraming() throws Exception {
        fx.llm.script("第一行\n第二行\r\n第三行");
        List<Frame> frames = collect("/bot/chat/stream", "{\"message\":\"p28 转义\"}", 40);
        Frame finalFrame = null;
        for (Frame f : frames) {
            if ("final".equals(f.event)) {
                finalFrame = f;
            }
        }
        assertNotNull("没有 final 帧，转义这一面无从可判: " + describe(frames), finalFrame);
        assertTrue("正文里的裸换行必须被转义成 \\\\n，实到 " + jsonish(finalFrame.raw),
                finalFrame.data.contains("\\n"));
        assertFalse("转义后仍不许有裸 \\n: " + jsonish(finalFrame.raw),
                finalFrame.data.indexOf('\n') >= 0);
        assertFalse("\\r 必须被吃掉而不是留着: " + jsonish(finalFrame.raw),
                finalFrame.data.indexOf('\r') >= 0);
        // 一跑几帧是确定的：加了转义不该把一帧劈成两帧
        assertEquals("帧序: " + describe(frames),
                Arrays.asList("step", "final", "done"), eventNames(frames));
    }

    // ===== 错误帧 =====

    @Test
    public void emptyMessageIsOneErrorFrameThenStreamCloses() throws Exception {
        List<Frame> frames = collect("/bot/chat/stream", "{\"message\":\"\"}", 10);
        assertEquals("空消息必须只回一帧 error 就收尾，实到: " + describe(frames), 1, frames.size());
        assertEquals("error", frames.get(0).event);
        assertFalse("error 帧不许是空话", frames.get(0).data.isEmpty());
        assertEquals("空消息这一跑不许碰供应商", 0, fx.llm.calls());
    }

    @Test
    public void confirmFrameIsAppendedWhenTheRunParksOnAHighRiskTool() throws Exception {
        fx.llm.scriptToolCall("risky", "{\"cmd\":\"rm -rf /\"}");
        List<Frame> frames = collect("/bot/chat/stream", "{\"message\":\"p28 确认\"}", 40);
        List<String> names = eventNames(frames);
        assertTrue("该有 tool_call 帧: " + names, names.contains("tool_call"));
        assertEquals("停在确认上的这一跑，最后一帧必须是 confirm 而不是 done: " + names,
                "confirm", names.get(names.size() - 1));
        Frame c = frames.get(frames.size() - 1);
        assertTrue("confirm 帧的载荷语法是 [confirm] tool=… args=… reason=…，实到 "
                + jsonish(c.data), c.data.startsWith("[confirm] tool=risky"));
        assertTrue("confirm 必须带上 reason，前端才能直接显示: " + jsonish(c.data),
                c.data.contains("reason="));
    }

    // ===== 中途 steer：真在这一跑里注入，不是"入队后没人发帧" =====

    @Test
    public void steerInjectedMidRunReachesThisConnectionAsAFrame() throws Exception {
        fx.llm.sleepMillis(200);
        fx.llm.scriptToolCall("echo", "{\"message\":\"p28 第一轮\"}");
        fx.llm.scriptToolCall("echo", "{\"message\":\"p28 第二轮\"}");
        fx.llm.script("P28-SSE-AFTER-STEER");
        SseStream s = open("/bot/chat/stream", "{\"message\":\"p28 steer\"}");
        List<Frame> frames = new ArrayList<Frame>();
        try {
            // 读到第一帧（step 1）后从**另一条连接**投 steer；桩的 200ms 延迟给出手窗口
            frames.add(s.next());
            assertEquals(200, fx.call("POST", "/bot/steer", "{\"x\":1}").code);
            while (frames.size() < 30) {
                Frame f = s.next();
                if (f == null) {
                    break;
                }
                frames.add(f);
                if ("done".equals(f.event)) {
                    break;
                }
            }
        } finally {
            s.close();
        }
        List<String> names = eventNames(frames);
        assertTrue("轮首注入的 steer 必须以帧的形式回到这条连接: " + names,
                names.contains("steer"));
        int i = names.indexOf("steer");
        assertTrue("steer 帧必须带 [steer] 前缀，实到 " + jsonish(frames.get(i).data),
                frames.get(i).data.startsWith("[steer]"));
        assertTrue("steer 帧必须带正文，实到 " + jsonish(frames.get(i).data),
                frames.get(i).data.length() > "[steer]".length());
    }

    // ===== 客户端挂断：服务端必须真停下来，而且不许把别人拖住 =====

    @Test
    public void clientHangUpAbortsTheRunWithinABound() throws Exception {
        fx.llm.sleepMillis(180);
        // 6 轮工具调用 + 收尾：不挂断要跑 7×180ms≈1.3s 以上
        for (int i = 0; i < 6; i++) {
            fx.llm.scriptToolCall("echo", "{\"message\":\"p28 长跑 " + i + "\"}");
        }
        fx.llm.script("P28-SSE-LONG-RUN");
        SseStream s = open("/bot/chat/stream", "{\"message\":\"p28 挂断\"}");
        int scriptedTotal = 7;
        try {
            assertNotNull(s.next());
            s.close();  // 客户端直接消失（模拟关浏览器页 / Ctrl-C）
        } finally {
            s.close();
        }
        // 有界地等它停：每 100ms 看一次调用计数是否还长，最多 6s
        long deadline = System.currentTimeMillis() + 6000L;
        int last = -1;
        int stableFor = 0;
        while (System.currentTimeMillis() < deadline) {
            int now = fx.llm.calls();
            stableFor = now == last ? stableFor + 1 : 0;
            last = now;
            if (stableFor >= 5) {
                break;
            }
            Thread.sleep(100L);
        }
        assertTrue("客户端已经挂断，服务端却把 " + scriptedTotal + " 轮脚本全跑完了（calls="
                        + fx.llm.calls() + "）—— 写失败的帧必须能把这一跑掐断",
                fx.llm.calls() < scriptedTotal);
        // 挂断不许把服务拖死：新连接必须在有界时间内被应答
        P28HttpFixture.Response after = fx.call("GET", "/bot/status", null);
        assertEquals("挂断之后新请求没被应答（服务端被这一跑占住了）", 200, after.code);
    }

    // ===== 词表的三方锁：台账 ⇄ 源码帧字面量 ⇄ StreamEvent 子类 =====

    @Test
    public void everyStreamEventSubtypeHasAnSseFrameMapping() throws Exception {
        Set<String> subclasses = new TreeSet<String>();
        for (Class<?> c : StreamEvent.class.getDeclaredClasses()) {
            if (StreamEvent.class.isAssignableFrom(c)) {
                subclasses.add(c.getSimpleName());
            }
        }
        assertEquals("StreamEvent 的子类是词汇表的分母，先钉一下数: " + subclasses,
                9, subclasses.size());

        String src = fixtureSourceOfHttpChannel();
        Set<String> mapped = new TreeSet<String>();
        Matcher m = Pattern.compile("instanceof StreamEvent\\.(\\w+)").matcher(src);
        while (m.find()) {
            mapped.add(m.group(1));
        }
        assertEquals("有 StreamEvent 子类没有 SSE 帧映射 —— 加事件时必须同时补 frame()，"
                        + "否则前端只会看到静默丢帧。缺: " + diff(subclasses, mapped),
                subclasses, mapped);
    }

    @Test
    public void sseEventNamesInLedgerMatchTheOnesTheSourceCanEmit() throws Exception {
        Set<String> declared = vocabulary();
        Set<String> emitted = new TreeSet<String>();
        Matcher m = Pattern.compile("frame\\(\"(\\w+)\"").matcher(fixtureSourceOfHttpChannel());
        while (m.find()) {
            emitted.add(m.group(1));
        }
        // confirm 不是 StreamEvent，是 chatStream 里 writeEvent(os, "confirm", …) 直接发的
        emitted.add("confirm");
        assertTrue("源码发的事件名里有没登记进台账的（台账才是分母）: " + diff(emitted, declared),
                declared.containsAll(emitted));
        assertEquals("台账登记了源码发不出的事件名（等于给前端广告了一个死词汇）",
                declared, emitted);
    }

    // ===== 小工具 =====

    private void scriptARunWithAToolCall(String finalContent) {
        fx.llm.scriptToolCall("echo", "{\"message\":\"p28 sse\"}");
        fx.llm.script(finalContent);
    }

    private static Set<String> vocabulary() {
        for (HttpChannel.Route r : HttpChannel.routes()) {
            for (String atom : r.fields().split(";")) {
                if (atom.startsWith("sse-events:")) {
                    return new TreeSet<String>(Arrays.asList(atom.substring("sse-events:".length())
                            .split(",")));
                }
            }
        }
        throw new AssertionError("台账里没有 sse-events: 原子串 —— 词汇表没了分母");
    }

    private static String fixtureSourceOfHttpChannel() throws IOException {
        java.io.File f = new java.io.File(
                "src/main/java/com/zifang/z/bot/channel/HttpChannel.java");
        if (!f.isFile()) {
            f = new java.io.File(
                    "z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java");
        }
        assertTrue("找不到 HttpChannel.java 源码，没法做源码级对拍: " + f, f.isFile());
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<String>(a);
        out.removeAll(b);
        return out;
    }

    private static String unescape(String data) {
        return data.replace("\\n", "\n");
    }

    private static String match(String haystack, String regex) {
        Matcher m = Pattern.compile(regex).matcher(haystack);
        assertTrue("对不上 " + regex + " 于 " + haystack, m.find());
        return m.group(1);
    }

    private static String jsonish(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }

    private static List<String> eventNames(List<Frame> frames) {
        List<String> out = new ArrayList<String>();
        for (Frame f : frames) {
            out.add(f.event);
        }
        return out;
    }

    private static String describe(List<Frame> frames) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : frames) {
            sb.append(f.event).append(' ');
        }
        return sb.toString().trim();
    }

    private SseStream open(String path, String body) throws IOException {
        HttpURLConnection conn =
                (HttpURLConnection) new URL(fx.base + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();
        SseStream s = new SseStream(conn);
        s.status = conn.getResponseCode();
        s.statusLine = conn.getResponseMessage();
        Map<String, String> h = new java.util.LinkedHashMap<String, String>();
        for (String k : conn.getHeaderFields().keySet()) {
            if (k != null) {
                h.put(k.toLowerCase(java.util.Locale.ROOT), conn.getHeaderField(k));
            }
        }
        s.headers = h;
        s.in = s.status < 400 ? conn.getInputStream() : conn.getErrorStream();
        return s;
    }

    private List<Frame> collect(String path, String body, int cap) throws IOException {
        SseStream s = open(path, body);
        try {
            List<Frame> out = new ArrayList<Frame>();
            Frame f;
            while (out.size() < cap && (f = s.next()) != null) {
                out.add(f);
                if ("done".equals(f.event) || "confirm".equals(f.event)) {
                    break;
                }
            }
            return out;
        } finally {
            s.close();
        }
    }

    private static final class SseStream {
        private HttpURLConnection conn;
        private InputStream in;

        /**
         * p28a 死于 150 轮时漏掉了这个构造器：{@link #open} 在 :377 用
         * {@code new SseStream(conn)}，而这里只有隐式无参构造 ⇒ testCompile 直接
         * COMPILATION ERROR（并且即便绕过，close() 里的 conn.disconnect() 也会 NPE）。
         * 本棒补回最小原样实现，不改 open() 侧的任何取数逻辑。
         */
        SseStream(HttpURLConnection conn) {
            this.conn = conn;
        }
        private int status;
        private String statusLine;
        private java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        String header(String name) {
            return headers.get(name);
        }

        /** 从字节流里按 {@code \n\n} 切一帧；null = 流结束。 */
        Frame next() throws IOException {
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                pending.write(c);
                byte[] b = pending.toByteArray();
                if (endsWithBlankLine(b)) {
                    return Frame.of(new String(b, 0, b.length - 1, StandardCharsets.UTF_8));
                }
                if (b.length > 200000) {
                    throw new AssertionError("单帧超过 200KB，八成是帧分隔符没了");
                }
            }
            byte[] tail = pending.toByteArray();
            if (tail.length == 0) {
                return null;
            }
            return Frame.of(new String(tail, StandardCharsets.UTF_8));
        }

        private static boolean endsWithBlankLine(byte[] b) {
            if (b.length < 4) {
                return false;
            }
            int n = b.length;
            return b[n - 1] == '\n' && b[n - 2] == '\n';
        }

        void close() {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            conn.disconnect();
        }
    }

    private static final class Frame {
        final String raw;
        final String event;
        final String data;

        private Frame(String raw, String event, String data) {
            this.raw = raw;
            this.event = event;
            this.data = data;
        }

        static Frame of(String raw) {
            String event = null;
            String data = null;
            for (String line : raw.split("\n", -1)) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                    if (data.startsWith(" ")) {
                        data = data.substring(1);
                    }
                }
            }
            return new Frame(raw, event, data == null ? "" : data);
        }
    }
}
