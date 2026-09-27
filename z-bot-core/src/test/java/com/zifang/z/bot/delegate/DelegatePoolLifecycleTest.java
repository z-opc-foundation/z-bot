package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P27c 第三条：异步委托的线程池必须跟着 agent 走。
 *
 * <p>修之前它是 {@code DelegateManager} 里的 {@code private static final}：全进程共用一口，
 * {@code BotAgent.shutdown()} 一个字都没提它 —— 每开一个带委托能力的 agent 就多一条活路线，
 * 收尾要么靠 daemon 线程被强杀，要么泄漏到下一次。roadmap §W5 P27 把这条记成"未做"，
 * 是这批台账里唯一一条<b>写进了文档却没写进代码</b>的（另两条"未做"当时已经做了）。</p>
 *
 * <p>顺带一条同族的漏：异步那条路的 {@code finally} 里没有 {@code child.shutdown()}
 * （同步 :234 一直有，cron 的子代理也有）⇒ 子 agent 自带的调度线程全留在进程里。
 * 这一条用 {@link BotAgent#isShutDown()} 观测。</p>
 */
public class DelegatePoolLifecycleTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cfgDir;

    @Before
    public void setUp() throws Exception {
        cfgDir = tmp.newFolder("cfg");
        try (FileWriter w = new FileWriter(new File(cfgDir, "config.properties"))) {
            w.write("llm.provider=glm\n");
            w.write("glm.type=openai\n");
            w.write("glm.api.key=test-key\n");
            w.write("glm.base.url=http://127.0.0.1:1/v1\n");
            w.write("glm.model=glm-4\n");
        }
    }

    /** 结构守卫：这口池不许再变回 {@code static}（阳性对照：非 static 的那个字段必须真的存在）。 */
    @Test
    public void poolFieldIsAnInstanceFieldNotAStatic() {
        List<String> statics = new ArrayList<String>();
        int instancePools = 0;
        for (Field f : DelegateManager.class.getDeclaredFields()) {
            if (!ExecutorService.class.isAssignableFrom(f.getType())) {
                continue;
            }
            if (Modifier.isStatic(f.getModifiers())) {
                statics.add(f.getName());
            } else {
                instancePools++;
            }
        }
        assertEquals("又变回进程共用一口池: " + statics, 0, statics.size());
        assertEquals("守卫本身得有猎物：池字段被删掉时这条也该红", 1, instancePools);
    }

    /** 每口池只随它自己的 agent 收 —— 另一个 agent 的池必须还活着。 */
    @Test
    public void shutdownClosesThisAgentsPoolOnly() throws Exception {
        BotAgent a = agent("s-a");
        BotAgent b = agent("s-b");
        assertFalse(a.getDelegation().asyncPoolShutdown());
        assertFalse(b.getDelegation().asyncPoolShutdown());

        a.shutdown();

        assertTrue("agent.shutdown() 没把委托池交下去", a.getDelegation().asyncPoolShutdown());
        assertFalse("收掉一个 agent 不该把别人的池一起关掉", b.getDelegation().asyncPoolShutdown());
        b.shutdown();
    }

    /** 池收掉之后再派：必须当场拒、并把它记成 FAILED —— 留在 QUEUED 会永久占着宽度闸门。 */
    @Test
    public void submitAfterShutdownRefusesAndDoesNotLeakASlot() throws Exception {
        BotAgent a = agent("s-c");
        a.shutdown();

        String first = a.submitBackground("不该被接下的任务");
        assertTrue(first, first.contains("异步委托未提交"));
        assertTrue(first, first.contains("委托池已关闭"));
        assertTrue("台账留了一条永远取不到结果的现场: " + a.describeAgents(),
                a.describeAgents().contains("FAILED"));

        // 第二次仍走"拒"这一支，而不是"并发已满" ⇒ 第一条没有占住槽位
        String second = a.submitBackground("再来一次");
        assertTrue(second, second.contains("异步委托未提交"));
        assertFalse(second, second.contains("并发已满"));
    }

    /** 异步子代理收工要真的被收口（这条 {@code finally} 里的 {@code child.shutdown()} 是新增的）。 */
    @Test
    public void asyncChildIsShutDownWhenItFinishes() throws Exception {
        BotAgent a = agent("s-d", textReply("done-child"));
        String submitted = a.submitBackground("background task");
        String id = submitted.replaceFirst(".*已提交异步委托 (bg[0-9]+-[0-9]+).*", "$1");

        String result = waitFor(a, id, 8000);
        assertTrue(result, result.contains("DONE"));
        BotAgent child = a.getDelegation().lastChild;
        assertTrue("异步子代理收工后没被 shutdown（它的调度线程还留在进程里）", child.isShutDown());
    }

    /** 同步那条路一直是收口的 —— 留着当对照，防有人把 :234 那句删了还全绿。 */
    @Test
    public void syncChildIsStillShutDownWhenItReturns() throws Exception {
        BotAgent a = agent("s-e",
                toolReply(call("p1", "delegate_task", "{\"task\":\"sync task\"}")),
                textReply("child-done"),
                textReply("parent-done"));
        assertEquals("parent-done", a.chat("go", com.zifang.z.bot.agent.StreamListener.NOOP));
        BotAgent child = a.getDelegation().lastChild;
        assertTrue("同步子代理没被 shutdown", child.isShutDown());
    }

    // ===== helpers =====

    private BotAgent agent(String sub) throws Exception {
        return agent(sub, textReply("x"));
    }

    private BotAgent agent(String sub, ChatCompletionsResponse... scripted) throws Exception {
        File root = tmp.newFolder(sub);
        BotConfig config = BotConfig.load(cfgDir);
        BotAgent.Builder b = BotAgent.builder(config)
                .provider(new Scripted(scripted))
                .sandbox(new Sandbox(new File(root, "sandbox").getAbsolutePath()))
                .sessionManager(new SessionManager(new File(root, "sessions")))
                .model("test-model")
                .delegateDepth(0);
        return b.build();
    }

    private static String waitFor(BotAgent agent, String id, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String result = agent.backgroundResult(id);
        while (result.contains("QUEUED") || result.contains("RUNNING")) {
            if (System.currentTimeMillis() > deadline) {
                return result;
            }
            Thread.sleep(50);
            result = agent.backgroundResult(id);
        }
        return result;
    }

    private static ChatCompletionsResponse textReply(String content) {
        return response(content, Collections.<ToolCall>emptyList());
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return response("", java.util.Arrays.asList(calls));
    }

    private static ChatCompletionsResponse response(String content, List<ToolCall> calls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, calls, "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    private static final class Scripted implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

        Scripted(ChatCompletionsResponse... responses) {
            Collections.addAll(scripted, responses);
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
