package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.ExecGuard;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P27c 第一条：子代理的审批缝必须<b>当场</b>裁决，不能把"等人来批"这件事留给一个
 * 没有人在等的队列。
 *
 * <p>修之前的形状（一次性取证，见 {@code _doc/acceptance/p27c/EVIDENCE.md}）：
 * 子代理的 exec 撞上 {@code Confirmations.isRequired} ⇒ {@code emitToolResult} 照旧抛
 * {@link com.zifang.z.bot.agent.ToolConfirmationNeeded}，{@code chat()} 把它揉成
 * {@code WAIT_CONFIRM:tool|args|reason} <b>当最终回复返回</b>，
 * {@code DelegateManager} 再把那串字符记成 {@code TASK_COMPLETED}。父模型于是收到一次
 * "看起来成功完成"的委托：一个字节都没执行，也没任何人被问过。同一时刻子 agent 私有的
 * {@code ApprovalService} 队列里还留着一条永远 pending 的申请（父的 {@code /confirm}
 * 结构上读不到它）。</p>
 *
 * <p>对标：hermes 用 {@code ThreadPoolExecutor(initializer=…)} 给子代理工作线程装非交互
 * 裁决回调，缺省 {@code _subagent_auto_deny}（{@code delegate_tool.py:57-85}，本体 :74-85）。
 * 我们等价物是 {@code BotAgent.Builder.nonInteractive} + {@code emitToolResult} 里的 deny 分支。</p>
 */
public class SubagentApprovalDenialTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private RecordingProvider llm;
    private File sandboxDir;
    private File sessionDir;

    @Before
    public void setUp() throws Exception {
        llm = new RecordingProvider();
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
    }

    /** ① 同步委托里：申请被当场 deny，回合继续走，交给父的那份回复不是 WAIT_CONFIRM。 */
    @Test
    public void childApprovalIsDeniedInlineAndTurnKeepsGoing() throws Exception {
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"run it\",\"label\":\"probe\"}")))
                .script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf ./target-cache\"}")))
                .script(textReply("child-kept-going-after-denial"))
                .script(textReply("parent-done"));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        String reply = agent.chat("go", StreamListener.NOOP);
        assertEquals("parent-done", reply);

        BotAgent child = agent.getDelegation().lastChild;
        assertTrue("子代理必须被声明成 non-interactive", child.isNonInteractive());
        assertEquals("恰好一次自动 deny", 1, child.subagentAutoDeniedApprovals());

        // 子模型第二次被问到时，看到的是那句拒绝 —— 这是"它能从中改道"的全部依据。
        List<String> toolRows = toolContents(llm.requests.get(2));
        assertTrue("子代理记忆里没有 auto-denied 回执: " + toolRows,
                containsPrefix(toolRows, "[auto-denied]"));
        assertTrue("拒绝里要带上闸门给的原因: " + toolRows,
                containsContaining(toolRows, ExecGuard.APPROVAL_PREFIX));
        assertFalse("deny 之后不该再有多余一次申请: " + toolRows,
                child.subagentAutoDeniedApprovals() > 1);

        // 交给父模型的那份（delegate_task 的工具结果）绝不能是 WAIT_CONFIRM 串。
        List<String> parentToolRows = toolContents(llm.requests.get(3));
        assertTrue("父代理没收到子代理回复: " + parentToolRows,
                containsContaining(parentToolRows, "child-kept-going-after-denial"));
        assertFalse("父模型收到的是 WAIT_CONFIRM 假成功: " + parentToolRows,
                containsContaining(parentToolRows, BotAgent.WAIT_CONFIRM_PREFIX));
        // 回执头部那句"自动deny审批=N"必须真的进到父上下文：父模型只有从这一句才知道
        // 子代理被拦过，否则它会把一次"少执行了一条命令"的委托当成完整结果。
        assertTrue("父侧回执没带上自动 deny 次数: " + parentToolRows,
                containsContaining(parentToolRows, "自动deny审批=1"));
    }

    /**
     * ①b 拒绝要**当场结清**队列：exec 闸门替这次调用在本 agent 私有的 {@code ApprovalService}
     * 里落了一条 pending，deny 分支必须把它取走，否则每撞一次闸门就攒一条永不消费的待批
     * （红线 8 的账实分离）。
     *
     * <p>{@code pendingApprovals()} 这个读数不是空跑的：同一个访问器在
     * {@link #interactiveParentStillAsksAndQueues()} 里必须数得出 1 条 —— 交互父代理那条
     * 就是它的阳性对照（这里断 0，那里断 1，"恒空"和"恒不空"都过不了这一族）。</p>
     */
    @Test
    public void deniedApprovalLeavesNoPendingRowInThePrivateQueue() throws Exception {
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"run it\"}")))
                .script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf ./target-cache\"}")))
                .script(textReply("child-kept-going-after-denial"))
                .script(textReply("parent-done"));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        agent.chat("go", StreamListener.NOOP);
        BotAgent child = agent.getDelegation().lastChild;

        // 先证这条真的走过闸门（否则"队列是空的"只是因为什么都没发生）
        assertEquals("这一支要有猎物：闸门必须真的被撞到一次", 1, child.subagentAutoDeniedApprovals());
        assertEquals("子代理私有队列里留了一条永远没人消费的待批", 0, child.pendingApprovals().size());
        assertEquals(0, agent.pendingApprovals().size());
    }

    /** ①c 接线层：{@code buildChild} 必须真的把子代理声明成非交互（机制在≠接上了）。 */
    @Test
    public void delegatedChildIsDeclaredNonInteractive() throws Exception {
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"run it\"}")))
                .script(textReply("child-done"))
                .script(textReply("parent-done"));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        agent.chat("go", StreamListener.NOOP);
        BotAgent child = agent.getDelegation().lastChild;
        assertNotNull("委托没建出子代理，下面那条断言是空跑", child);
        assertTrue("子代理没被声明成 non-interactive ⇒ deny 机制接了个空",
                child.isNonInteractive());
        assertFalse("交互父代理自己不能被顺手关掉申请", agent.isNonInteractive());
    }

    /** ② 阳性对照（被拒的那一支得真的能红）：交互父代理同一形状照旧中断、照旧入队。 */
    @Test
    public void interactiveParentStillAsksAndQueues() throws Exception {
        llm.script(toolReply(call("p1", "exec", "{\"command\":\"rm -rf ./target-cache\"}")));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        String reply = agent.chat("go", StreamListener.NOOP);

        assertTrue("交互路径必须仍然回抛待批: " + reply,
                reply.startsWith(BotAgent.WAIT_CONFIRM_PREFIX));
        assertTrue(reply, reply.contains(ExecGuard.APPROVAL_PREFIX));
        assertEquals(1, agent.pendingApprovals().size());
        assertEquals("rm -rf ./target-cache", agent.pendingApprovals().get(0).command());
        assertEquals("交互 agent 不记账自动 deny", 0, agent.subagentAutoDeniedApprovals());
    }

    /** ③ 硬线与 deny 分支正交：{@code nonInteractive} 既不能把硬线放成"可批"，也不该计一次 deny。 */
    @Test
    public void hardlineStillRefusesInsideChildRegardlessOfNonInteractive() throws Exception {
        llm.script(toolReply(call("p1", "delegate_task", "{\"task\":\"evil\",\"label\":\"probe\"}")))
                .script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf /\"}")))
                .script(textReply("child-saw-hardline"))
                .script(textReply("parent-done"));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        agent.chat("go", StreamListener.NOOP);
        BotAgent child = agent.getDelegation().lastChild;

        assertEquals("硬线是拒绝执行，不是申请人工确认", 0, child.subagentAutoDeniedApprovals());
        List<String> toolRows = toolContents(llm.requests.get(2));
        assertTrue("硬线回执没回到子模型: " + toolRows,
                containsContaining(toolRows, ExecGuard.HARDLINE_PREFIX));
        assertFalse("硬线不该被 auto-denied 文案盖掉: " + toolRows,
                containsPrefix(toolRows, "[auto-denied]"));
    }

    /** ④ 异步委托跑在池线程上，更没有人可以等 ⇒ 同一支 deny 也必须生效。 */
    @Test
    public void asyncChildAlsoDeniesInsteadOfStalling() throws Exception {
        llm.script(toolReply(call("c1", "exec", "{\"command\":\"rm -rf ./target-cache\"}")))
                .script(textReply("async-child-recovered"));
        BotAgent agent = builder(configWith()).delegateDepth(0).build();

        String submitted = agent.submitBackground("background dangerous task");
        assertTrue(submitted, submitted.contains("已提交异步委托"));
        String id = submitted.replaceFirst(".*已提交异步委托 (bg[0-9]+-[0-9]+).*", "$1");

        String result = waitFor(agent, id, 8000);
        assertTrue("异步子代理没收工: " + result, result.contains("DONE"));
        assertTrue("取回的回复不是子模型的下一句: " + result,
                result.contains("async-child-recovered"));
        assertFalse("异步委托也把 WAIT_CONFIRM 当成完成: " + result,
                result.contains(BotAgent.WAIT_CONFIRM_PREFIX));
        assertEquals(1, agent.getDelegation().lastChild.subagentAutoDeniedApprovals());
        assertEquals(0, agent.getDelegation().lastChild.pendingApprovals().size());
    }

    // ===== helpers =====

    private BotConfig configWith(String... extraLines) throws Exception {
        File dir = tmp.newFolder("cfg");
        try (FileWriter w = new FileWriter(new File(dir, "config.properties"))) {
            w.write("llm.provider=glm\n");
            w.write("glm.type=openai\n");
            w.write("glm.api.key=test-key\n");
            w.write("glm.base.url=http://127.0.0.1:1/v1\n");
            w.write("glm.model=glm-4\n");
            for (String line : extraLines) {
                w.write(line + "\n");
            }
        }
        return BotConfig.load(dir);
    }

    private BotAgent.Builder builder(BotConfig config) {
        return BotAgent.builder(config)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("test-model");
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

    private static List<String> toolContents(ChatCompletionsRequest request) {
        List<String> out = new ArrayList<String>();
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.TOOL) {
                out.add(m.getContent() == null ? "" : m.getContent());
            }
        }
        return out;
    }

    private static boolean containsPrefix(List<String> rows, String prefix) {
        for (String r : rows) {
            if (r.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsContaining(List<String> rows, String needle) {
        for (String r : rows) {
            if (r.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static ChatCompletionsResponse textReply(String content) {
        return response(content, Collections.<ToolCall>emptyList());
    }

    private static ChatCompletionsResponse toolReply(ToolCall... calls) {
        return response("", Arrays.asList(calls));
    }

    private static ChatCompletionsResponse response(String content, List<ToolCall> toolCalls) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, toolCalls, "stop")),
                new TokenUsage(11L, 7L, 18L), "stop", null);
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    /** 脚本化 LLM 替身，并把每一次请求原样留下 —— 断言"模型看见了什么"只能从请求里读。 */
    private static final class RecordingProvider implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();

        RecordingProvider script(ChatCompletionsResponse response) {
            scripted.add(response);
            return this;
        }

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
            return scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
