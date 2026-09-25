package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.cron.CronJob;
import com.zifang.z.bot.cron.CronScheduler;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.slash.SlashCommand;
import com.zifang.z.bot.slash.SlashRegistry;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * cron 调度接线测试：脚本 LLM 让 agent 调 cronjob.add，
 * 再注入短 tick 的 scheduler，验证 jobs.json 持久化 + tick 真执行子任务。
 */
public class BotAgentCronTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ScriptedProvider llm;
    private File sandboxDir;
    private File sessionDir;
    private File configDir;
    private BotAgent agent;
    private AtomicInteger ran;

    @Before
    public void setUp() throws Exception {
        llm = new ScriptedProvider();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
        configDir = tmp.newFolder("config");
        ran = new AtomicInteger(0);

        // 注入短 tick 的 scheduler：3 秒一拍，自定义 runner 只跑 prompt → "ran-<n>"
        File cronDir = new File(configDir, "cron");
        CronScheduler scheduler = new CronScheduler(cronDir, 3, prompt -> {
            int n = ran.incrementAndGet();
            return "ran-" + n + " prompt=" + prompt;
        });

        BotConfig cfg = BotConfig.load(configDir);
        agent = BotAgent.builder(cfg)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model")
                .withoutCenter()
                .cronScheduler(scheduler)
                .build();
    }

    @Test
    public void slashCronListInitiallyEmpty() {
        SlashRegistry reg = SlashRegistry.withBuiltinCommands();
        SlashCommand cron = reg.find("/cron");
        assertNotNull("/cron 未注册", cron);
        String out = cron.execute(agent, "list");
        assertTrue("应提示暂无任务，但实际: " + out, out.contains("暂无定时任务"));
    }

    @Test
    public void slashCronAddPersistsJobToJson() throws Exception {
        SlashCommand cron = SlashRegistry.withBuiltinCommands().find("/cron");
        // schedule | name | prompt 三段
        String out = cron.execute(agent, "add every 5s | heartbeat | ping");
        assertTrue(out, out.startsWith("已创建定时任务 cron_"));
        File jobs = new File(configDir, "cron/jobs.json");
        assertTrue("jobs.json 应已落盘", jobs.exists());
        String json = new String(java.nio.file.Files.readAllBytes(jobs.toPath()), "UTF-8");
        assertTrue(json, json.contains("heartbeat"));
        assertTrue(json, json.contains("ping"));
        assertTrue(json, json.contains("every 5s"));
    }

    @Test
    public void cronSchedulerActuallyRunsAddedJob() throws Exception {
        // 先 add 一条短间隔任务
        agent.cronManage("add every 5s | t1 | do-thing");
        CronScheduler scheduler = agent.getCronScheduler();
        assertEquals(1, scheduler.list().size());

        // 不等后台 daemon，手动调 tick + 喂 lastOffset 让 every 5s 必触发
        scheduler.tickWithOffset(java.time.ZonedDateTime.now().minusSeconds(30));

        assertEquals("runner 应被调用一次", 1, ran.get());
        CronJob only = scheduler.list().get(0);
        assertEquals("ran-1 prompt=do-thing", only.lastResult);
        assertNotNull(only.lastRun);
        // 持久化里 lastResult 也写回了
        File jobs = new File(configDir, "cron/jobs.json");
        String json = new String(java.nio.file.Files.readAllBytes(jobs.toPath()), "UTF-8");
        assertTrue(json, json.contains("ran-1"));
    }

    @Test
    public void slashCronPauseAndResumeFlipsEnabled() throws Exception {
        agent.cronManage("add every 10s | t1 | ping");
        String id = agent.getCronScheduler().list().get(0).id;
        agent.cronManage("pause " + id);
        assertEquals(false, agent.getCronScheduler().list().get(0).enabled);
        agent.cronManage("resume " + id);
        assertEquals(true, agent.getCronScheduler().list().get(0).enabled);
        agent.cronManage("remove " + id);
        assertEquals(0, agent.getCronScheduler().list().size());
    }

    @Test
    public void agentInvokesCronToolAndReportsBack() {
        // 让 LLM 先调 cronjob.add，最后吐回 "ok"
        llm.script(toolReply(call("c1", "cronjob",
                        "{\"action\":\"add\",\"name\":\"heartbeat\","
                                + "\"prompt\":\"ping\","
                                + "\"schedule\":\"every 30s\"}"),
                        call("c2", "echo", "{\"message\":\"hi\"}")))
                .script(textReply("ok"));
        agent.chat("set heartbeat", StreamListener.NOOP);
        assertEquals(1, agent.getCronScheduler().list().size());
        CronJob j = agent.getCronScheduler().list().get(0);
        assertEquals("heartbeat", j.name);
        assertEquals("ping", j.prompt);
        assertEquals("every 30s", j.schedule);
    }

    @Test
    public void slashRegistryExposesCronCommand() {
        SlashCommand cron = SlashRegistry.withBuiltinCommands().find("/cron");
        assertNotNull(cron);
        assertEquals("/cron", cron.name());
    }

    // ===== helpers =====

    private static ChatCompletionsResponse textReply(String content) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content,
                        Collections.<ToolCall>emptyList(), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, "",
                        Arrays.asList(calls), "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    /** 脚本化 LLM 替身：按序吐出预设响应。 */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

        ScriptedProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

        @Override
        public String name() {
            return "scripted";
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
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}