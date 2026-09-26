package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.channel.HttpChannel;
import com.zifang.z.bot.memory.MemoryStore;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P12 遗留硬账（派单 p12e 任务一）：{@code BotAgent.chat()} 把动态上下文拼进 user 消息之后，
 * 会不会 ① 同一会话第 N 轮请求里堆出 N 块互相矛盾的「当前时间」，② 被 {@code persistSession()}
 * 原样落盘，③ 经 {@code HttpChannel} 的 {@code content} 字段流到用户视野。
 *
 * <p><b>实验形态刻意做成有猎物的</b>：同一个 {@link BotAgent}、同一个 session_id、连跑 3 轮，
 * 第 1 轮之后再写一次 MEMORY.md 并新装一个技能（派单要求的差异），且每轮之间真过一秒
 * ⇒ 三块时钟时间戳互不相同，"堆叠"不是量具误差而是模型真会读到 N 个「当前时间」。</p>
 *
 * <p>每个面一条具名测试（一面红不许遮住另一面的读数），且都带<b>阳性对照</b>：
 * 负向断言之外必须证明那块东西真会进请求（第 1 轮就要看到 {@code 当前时间}）。</p>
 */
public class VolatileContextPersistenceTest {

    private static final String HEADER = "[z-bot 运行时上下文]";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;
    private File sessionDir;
    private File memoryDir;
    private File skillsDir;
    private MemoryStore memoryStore;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        memoryDir = tmp.newFolder("memories");
        skillsDir = new File(tmp.getRoot(), "skills");
        skillsDir.mkdirs();
        memoryStore = new MemoryStore(memoryDir);
    }

    /** 一次 3 轮实验的全部产物。 */
    private static final class Run {
        final RecordingLlm llm = new RecordingLlm();
        BotAgent agent;
        String sessionId;
    }

    /** 同一 agent、同一会话连跑 3 轮；第 1 轮后改写记忆 + 新装技能；轮间真过一秒。 */
    private Run threeTurns() throws Exception {
        write("MEMORY.md", "第一轮的记忆-X1");
        Run run = new Run();
        run.agent = newAgent(run.llm);
        run.sessionId = run.agent.currentSessionId();
        assertTrue("三轮必须共用一个 session_id", run.sessionId != null && !run.sessionId.isEmpty());

        run.llm.reply = "回合一";
        assertEquals("回合一", run.agent.chat("第一句原话", StreamListener.NOOP));

        Thread.sleep(1100);
        write("MEMORY.md", "第二轮才写入的记忆-X2");
        writeSkill("deploy", "第二轮才装的技能正文-X3");

        run.llm.reply = "回合二";
        assertEquals("回合二", run.agent.chat("第二句原话", StreamListener.NOOP));
        Thread.sleep(1100);

        run.llm.reply = "回合三";
        assertEquals("回合三", run.agent.chat("第三句原话", StreamListener.NOOP));

        assertEquals("3 轮各一步 ⇒ 3 次 provider 请求", 3, run.llm.requests.size());
        for (int i = 0; i < run.llm.requests.size(); i++) {
            ChatCompletionsRequest r = run.llm.requests.get(i);
            System.out.println("[p12e-forensic] request#" + (i + 1)
                    + " totalMsgs=" + r.getMessages().size()
                    + " userRows=" + textsOf(r.getMessages(), MessageRole.USER).size()
                    + " clockBearingUserRows=" + countContains(textsOf(r.getMessages(), MessageRole.USER), "当前时间")
                    + " headerBearingUserRows=" + countContains(textsOf(r.getMessages(), MessageRole.USER), HEADER));
        }
        return run;
    }

    // ===== 取证面 1：第 3 次请求里堆了几块时钟 =====

    @Test
    public void thirdRequestInOneSessionStacksContradictoryClockBlocks() throws Exception {
        Run run = threeTurns();
        ChatCompletionsRequest third = run.llm.requests.get(2);
        List<String> users = textsOf(third.getMessages(), MessageRole.USER);
        int i = 0;
        for (String u : users) {
            System.out.println("[p12e-forensic] req#3 user#" + (++i) + " len=" + u.length()
                    + " >>>\n" + u + "\n<<<");
        }

        // 阳性对照（缺猎物则下面的负向断言全是空跑）
        String first = onlyUser(run.llm.requests.get(0));
        assertTrue("阳性对照：第 1 轮请求里必须真看得到时钟块（否则「只 1 块」是空跑）",
                first.contains("当前时间") && first.contains(HEADER));
        assertTrue("阳性对照：第 1 轮的记忆行随块到了模型", first.contains("第一轮的记忆-X1"));
        assertFalse("第 1 轮请求不许含第二轮才写入的记忆", first.contains("第二轮才写入的记忆-X2"));

        assertEquals("第 3 次请求里的 user 行数 = 3 轮历史", 3, users.size());
        assertEquals("第 3 次请求里含「当前时间」的 user 行数必须 = 1；实测 "
                + countContains(users, "当前时间") + "（>1 ⇒ 模型每轮读到 N 个互相矛盾的时钟）",
                1, countContains(users, "当前时间"));
        assertEquals("第 3 次请求里含抬头的 user 行数同样 = 1",
                1, countContains(users, HEADER));
        assertEquals("第 3 次请求里含上一轮记忆行 X1 的 user 行数必须 = 0；实测 "
                + countContains(users, "第一轮的记忆-X1"),
                0, countContains(users, "第一轮的记忆-X1"));
        assertTrue("本轮（第 3 轮）的记忆 X2 必须看得见",
                latestUser(third).contains("第二轮才写入的记忆-X2"));
        assertTrue("本轮的技能指引必须看得见", latestUser(third).contains("第二轮才装的技能正文-X3"));
        assertTrue("用户原话保持在最后", latestUser(third).endsWith("第三句原话"));
        assertEquals("历史第 1 条 user 行必须逐字是用户原话、不带模板",
                "第一句原话", users.get(0));
    }

    // ===== 取证面 2：落盘 transcript =====

    @Test
    public void persistedTranscriptHoldsPlainUserWordsNotTheRuntimeBlock() throws Exception {
        Run run = threeTurns();
        try {
            File transcript = new File(sessionDir, run.sessionId + ".json");
            assertTrue("transcript 应落在 " + transcript, transcript.isFile());
            String onDisk = new String(Files.readAllBytes(transcript.toPath()), StandardCharsets.UTF_8);
            System.out.println("[p12e-forensic] transcript=" + transcript.getAbsolutePath()
                    + " bytes=" + transcript.length()
                    + " headerHits=" + hits(onDisk, HEADER)
                    + " clockHits=" + hits(onDisk, "当前时间"));
            System.out.println("[p12e-forensic] transcript 原文：\n" + onDisk);
            List<Msg> reloaded = new SessionManager(sessionDir).loadMessages(run.sessionId);
            System.out.println("[p12e-forensic] 重新载入的 user 行："
                    + textsOf(reloaded, MessageRole.USER));

            // 阳性对照：这三句原话必须真在盘上，否则 0 命中只是没落盘
            assertTrue("阳性对照：原话必须真落了盘", onDisk.contains("第一句原话")
                    && onDisk.contains("第二句原话") && onDisk.contains("第三句原话"));
            assertEquals("盘上 user 行数 = 3", 3, textsOf(reloaded, MessageRole.USER).size());
            assertEquals("落盘 transcript 里运行时上下文抬头命中数必须 = 0；实测 "
                    + hits(onDisk, HEADER), 0, hits(onDisk, HEADER));
            assertEquals("落盘 transcript 里时钟行命中数必须 = 0；实测 " + hits(onDisk, "当前时间"),
                    0, hits(onDisk, "当前时间"));
            assertEquals("重载后第 1 条 user 行仍是原话", "第一句原话",
                    textsOf(reloaded, MessageRole.USER).get(0));

            // 第 4 个面：/sessions 列表的标题（SessionManager.titleFrom 取首条 user 行前 30 字）
            String title = null;
            for (SessionManager.SessionSummary s : new SessionManager(sessionDir).listSessions()) {
                if (run.sessionId.equals(s.id)) {
                    title = s.title;
                }
            }
            System.out.println("[p12e-forensic] /sessions title=" + title);
            assertEquals("会话列表标题必须是用户第一句原话，实测 " + title, "第一句原话", title);
        } finally {
            run.agent.shutdown();
        }
    }

    // ===== 取证面 3：渲染面（真起 HttpChannel 打真请求） =====

    @Test
    public void httpSessionMessagesSurfaceLeaksTheRuntimeBlock() throws Exception {
        Run run = threeTurns();
        HttpChannel channel = new HttpChannel(run.agent, 0);
        channel.start();
        try {
            String base = "http://127.0.0.1:" + channel.getPort();
            String byId = httpGet(base + "/api/session/messages?id=" + run.sessionId);
            String live = httpGet(base + "/api/session/messages");
            System.out.println("[p12e-forensic] GET /api/session/messages?id=" + run.sessionId
                    + " headerHits=" + hits(byId, HEADER) + " body=\n" + byId);
            System.out.println("[p12e-forensic] GET /api/session/messages (活记忆) headerHits="
                    + hits(live, HEADER) + " body=\n" + live);

            // 阳性对照：content 里必须真看得见原话，否则 0 命中是空跑
            assertTrue("阳性对照：web content 里必须真有对话内容",
                    byId.contains("第一句原话") && live.contains("第三句原话")
                            && byId.contains("回合一"));
            assertEquals("按 session_id 读盘那支：web content 不许含抬头；实测 "
                    + hits(byId, HEADER), 0, hits(byId, HEADER));
            assertEquals("读活记忆那支：web content 不许含抬头；实测 "
                    + hits(live, HEADER), 0, hits(live, HEADER));
        } finally {
            channel.stop();
        }
    }

    // ===== helpers =====

    private BotAgent newAgent(RecordingLlm llm) {
        return BotAgent.builder(null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .memoryStore(memoryStore)
                .skillsRoot(skillsDir)
                .maxSteps(5)
                .build();
    }

    private void write(String fileName, String content) throws Exception {
        Files.write(new File(memoryDir, fileName).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeSkill(String name, String body) throws Exception {
        File dir = new File(skillsDir, name);
        dir.mkdirs();
        Files.write(new File(dir, "SKILL.md").toPath(),
                ("---\nname: " + name + "\ndescription: 测试技能\n---\n" + body + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> textsOf(List<Msg> msgs, MessageRole role) {
        List<String> out = new ArrayList<String>();
        for (Msg m : msgs) {
            if (m.getRole() == role) {
                out.add(m.getContent() == null ? "" : m.getContent());
            }
        }
        return out;
    }

    private static int countContains(List<String> rows, String needle) {
        int c = 0;
        for (String s : rows) {
            if (s.contains(needle)) {
                c++;
            }
        }
        return c;
    }

    private static String onlyUser(ChatCompletionsRequest request) {
        List<String> users = textsOf(request.getMessages(), MessageRole.USER);
        assertEquals(1, users.size());
        return users.get(0);
    }

    /** 请求里<b>本轮</b>那条 user 行 = 最后一条 user 行。 */
    private static String latestUser(ChatCompletionsRequest request) {
        List<String> users = textsOf(request.getMessages(), MessageRole.USER);
        assertTrue(!users.isEmpty());
        return users.get(users.size() - 1);
    }

    private static int hits(String haystack, String needle) {
        int c = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return c;
            }
            c++;
            from = at + needle.length();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        assertEquals(200, conn.getResponseCode());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) > 0) {
            bos.write(buf, 0, read);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 每轮一步文本回复的替身；记下每一次实际发出的请求字节。 */
    private static final class RecordingLlm implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        volatile String reply = "ok";

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            requests.add(request);
            return new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, reply,
                            Collections.<ToolCall>emptyList(), "stop")),
                    new TokenUsage(11L, 7L, 18L), "stop", null);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
