package com.zifang.z.bot.channel;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.cron.CronScheduler;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * p28a 对位面测试的公共夹具：真起一个 {@link HttpChannel}（JDK HttpServer，端口 0），
 * LLM 是脚本替身，所有落盘都在 {@link TemporaryFolder} 里，绝不碰 {@code ~/.zbot}，绝不打厂商 API。
 *
 * <p>缺省绑回环（{@code host=null ⇒ ChannelBind.resolve → loopback}），
 * {@link #base} 因此恒是 {@code http://127.0.0.1:<实际端口>}；要测"绑通配必须显式 opt-in"
 * 走 {@link #start(TemporaryFolder, String)} 传 host。</p>
 */
public final class P28HttpFixture implements AutoCloseable {

    /** 脚本替身固定吐 2 个模型 —— {@code /api/models} 的"库里有 N 行"就以它为参照。 */
    public static final int STUB_MODEL_COUNT = 2;
    public static final String[] STUB_MODEL_IDS = {"p28-model-a", "p28-model-b"};
    public static final String[] TOOL_NAMES = {"echo", "risky"};

    public final HttpChannel channel;
    public final BotAgent agent;
    public final StubProvider llm;
    public final File configDir;
    public final File sessionDir;
    public final File skillsDir;
    public final File cronDir;
    public final String base;

    private P28HttpFixture(HttpChannel channel, BotAgent agent, StubProvider llm, File configDir,
                           File sessionDir, File skillsDir, File cronDir) {
        this.channel = channel;
        this.agent = agent;
        this.llm = llm;
        this.configDir = configDir;
        this.sessionDir = sessionDir;
        this.skillsDir = skillsDir;
        this.cronDir = cronDir;
        this.base = "http://127.0.0.1:" + channel.getPort();
    }

    public static P28HttpFixture start(TemporaryFolder tmp) throws Exception {
        return start(tmp, null, "");
    }

    public static P28HttpFixture start(TemporaryFolder tmp, String host) throws Exception {
        return start(tmp, host, "");
    }

    /** 一个测试里要起两个服务时用不同 ns —— TemporaryFolder 同名目录会给第二次直接拒。 */
    public static P28HttpFixture start(TemporaryFolder tmp, String host, String ns) throws Exception {
        StubProvider llm = new StubProvider();
        Toolkit toolkit = new Toolkit();
        toolkit.register(Toolkit.of("echo", "回显文本", schema("message"), args ->
                new ToolResult(null, null, "echoed:" + str(args, "message"), false,
                        Collections.<String, Object>emptyMap())));
        toolkit.register(Toolkit.of("risky", "高危操作", schema("cmd"), args ->
                Confirmations.alreadyConfirmed(args)
                        ? new ToolResult(null, null, "risk-accepted", false,
                        Collections.<String, Object>emptyMap())
                        : Confirmations.needsConfirmation("高危操作")));

        File config = tmp.newFolder("zbot-home" + ns);
        BotConfig cfg = BotConfig.load(config);
        SessionManager sessions = new SessionManager(tmp.newFolder("sessions" + ns));
        File cronDir = tmp.newFolder("cron" + ns);
        CronScheduler cron = new CronScheduler(cronDir, 3600, prompt -> "cron-ok");
        File skills = tmp.newFolder("skills" + ns);
        Sandbox sandbox = new Sandbox(tmp.newFolder("sandbox" + ns).getAbsolutePath());

        BotAgent agent = BotAgent.builder(cfg)
                .provider(llm)
                .providerCode("p28stub")
                .toolkit(toolkit)
                .sandbox(sandbox)
                .sessionManager(sessions)
                .cronScheduler(cron)
                .skillsRoot(skills)
                .model("p28-test-model")
                .withoutCenter()
                .withoutMcp()
                .withoutBuiltinTools()
                .build();

        HttpChannel channel = new HttpChannel(agent, 0, host);
        channel.start();
        return new P28HttpFixture(channel, agent, llm, config,
                sessions.getSessionDir(), skills, cronDir);
    }

    public int port() {
        return channel.getPort();
    }

    @Override
    public void close() {
        channel.stop();
    }

    // ===== 打请求 =====

    public Response call(String method, String path) throws IOException {
        return call(method, path, null);
    }

    public Response call(String method, String path, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(20000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response r = new Response();
        r.url = method + " " + base + path;
        r.code = conn.getResponseCode();
        r.contentType = conn.getContentType();
        r.allow = conn.getHeaderField("Allow");
        InputStream is = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        r.body = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
        conn.disconnect();
        return r;
    }

    /** 状态码先行 —— 别把别人的应答当自己的（curl 对 404 也返回 0）。 */
    public Response expectStatus(String method, String path, String body, int expected) throws IOException {
        Response r = call(method, path, body);
        if (r.code != expected) {
            throw new AssertionError(r + " 期望 " + expected + " 实到 " + r.code);
        }
        return r;
    }

    public static byte[] readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            return baos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static Map<String, Object> schema(String... propertyNames) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (String name : propertyNames) {
            properties.put(name, Collections.<String, Object>singletonMap("type", "string"));
        }
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }

    /** 一个响：状态码 + 形状 + 来源 URL 三件一起带上，红了能直接看出是谁应答的。 */
    public static final class Response {
        public int code;
        public String contentType;
        public String allow;
        public String body;
        public String url;

        @Override
        public String toString() {
            String snippet = body == null ? "" : body.replace('\n', ' ');
            if (snippet.length() > 240) {
                snippet = snippet.substring(0, 240) + "…";
            }
            return url + " -> " + code + " ct=" + contentType + " body=" + snippet;
        }
    }

    /**
     * 脚本 LLM：按队列吐响应；队列空了回一条 {@code P28-STUB-REPLY-<n>}。
     * {@code sleepMillis} 用来把 SSE 帧拉开，好让"客户端断开"这一面量得到。
     */
    public static final class StubProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        private int callCount;
        private volatile long sleepMillis;

        public void sleepMillis(long ms) {
            this.sleepMillis = ms;
        }

        public StubProvider script(String content) {
            scripted.add(text(content));
            return this;
        }

        public StubProvider scriptToolCall(String toolName, String argsJson) {
            scripted.add(new ChatCompletionsResponse("id", "p28-test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, "",
                            Collections.singletonList(new ToolCall("tc-1", toolName, argsJson)), "tool_calls")),
                    new TokenUsage(11L, 7L, 18L), "tool_calls", null));
            return this;
        }

        public int calls() {
            return callCount;
        }

        public void reset() {
            scripted.clear();
            callCount = 0;
        }

        private static ChatCompletionsResponse text(String content) {
            return new ChatCompletionsResponse("id", "p28-test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, content,
                            Collections.<ToolCall>emptyList(), "stop")),
                    new TokenUsage(11L, 7L, 18L), "stop", null);
        }

        @Override
        public String name() {
            return "p28stub";
        }

        @Override
        public List<Model> listModels() {
            return Arrays.asList(
                    new Model(STUB_MODEL_IDS[0], "P28 模型A", "p28stub",
                            Arrays.asList(Model.Capability.CHAT, Model.Capability.TOOLS), 8192L, 2048L),
                    new Model(STUB_MODEL_IDS[1], "P28 模型B", "p28stub",
                            Collections.singletonList(Model.Capability.CHAT), 32768L, 4096L));
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            callCount++;
            long sleep = sleepMillis;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!scripted.isEmpty()) {
                return scripted.remove(0);
            }
            return text("P28-STUB-REPLY-" + callCount);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }

}
