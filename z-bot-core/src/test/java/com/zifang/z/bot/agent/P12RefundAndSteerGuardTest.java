package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.InterruptFlag;
import com.zifang.z.agent.kernel.agent.IterationBudget;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P12 里最容易"写了但没接线"的两条守卫的回归：
 *
 * <ul>
 *   <li><b>压缩后的预算 refund</b>：kernel 的 {@link IterationBudget} 只认累计 token，
 *       压缩省下来的空间一秒都没还回来。这里钉两半 —— 账面上要有退款记录，
 *       <b>且</b>累计值已经顶过上限时主循环还得继续跑（不退款就只能吃 kernel 的 grace 通道，
 *       而 grace 通道会掩盖"没退款"这件事，所以显式把 graceCalls 设成 0 把它隔离掉）。</li>
 *   <li><b>硬中断丢弃 pending steer</b>：用户已经按了停止，那条还没注入的插话就没有落点了；
 *       留着它会在下一轮开头以 {@code [User steer]} 冒出来。</li>
 *   <li><b>/queue 多条 vs /steer 单槽</b>：两者共用 kernel 的 {@code SteerQueue}，
 *       差别只在写入端是否覆盖。写错了就是"前一条排队消息被后一条悄悄吃掉"。</li>
 * </ul>
 */
public class P12RefundAndSteerGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;
    private File sessionDir;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
        sessionDir = tmp.newFolder("sessions");
    }

    // ===== 预算 refund =====

    @Test
    public void compressionRefundsBudgetAndKeepsTheLoopRunning() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        // token 上限 1000、graceCalls 0：生产默认是 1，这里设 0 是为了把"退款"和
        // "收尾机会"两个通道分开 —— 留着 grace 通道，不退款也能多打一次，就量不出来了。
        IterationBudget budget = new IterationBudget(10, 1000L, 0);
        CompressorEngine engine = new CompressorEngine(1000L, 0.5d, 2);

        BotAgent agent = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .toolkit(toolkitWithBigEcho())
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .contextEngine(engine, middle -> "摘要：早前 " + middle.size() + " 条已压缩")
                .budget(budget)
                .model("test-model")
                .maxSteps(10)
                .withoutBuiltinTools()
                .build();
        try {
            // 两轮工具调用把累计 token 顶到 1200（>1000）；中段那条 3000 字的用户消息
            // 会在第 3 轮开始前被压成一行摘要 —— 省下来的空间必须当场回到预算里。
            llm.script(toolReply(600, call("c1", "echo", "{\"message\":\"a\"}"),
                    call("c2", "echo", "{\"message\":\"b\"}")));
            llm.script(toolReply(600, call("c3", "echo", "{\"message\":\"c\"}")));
            llm.script(textReply(600, "第三轮还在跑"));

            StringBuilder big = new StringBuilder("问题：");
            for (int i = 0; i < 600; i++) {
                big.append("填充内容用于制造可压缩的中段历史。");
            }
            String reply = agent.chat(big.toString(), StreamListener.NOOP);

            assertTrue("压缩应当至少发生过一次，实得 " + engine.getCompressCount() + " 次",
                    engine.getCompressCount() >= 1);
            assertTrue("压缩省下来的 token 必须退还进预算，实得 refunded="
                            + agent.budgetLedger().refundedTokens(),
                    agent.budgetLedger().refundedTokens() > 0L);
            assertEquals("净占用 = 累计 - 退还（口径要对得上）",
                    agent.budgetLedger().tokensUsed() - agent.budgetLedger().refundedTokens(),
                    agent.budgetLedger().effectiveTokensUsed());
            assertFalse("退了款就不该报预算耗尽，实得回复：" + reply,
                    reply.startsWith("已达到预算上限"));
            assertEquals("第三轮还在跑", reply);
            assertEquals("三次调用都要真打出去（没退款的话第 3 轮根本进不来）",
                    3, llm.requests.size());
        } finally {
            agent.shutdown();
        }
    }

    @Test
    public void manualCompressRefundsIntoTheLedger() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        CompressorEngine engine = new CompressorEngine(1000L, 0.99d, 2);
        BotAgent agent = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .contextEngine(engine, middle -> "一行摘要")
                .budget(new IterationBudget(10, 100_000L))
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        try {
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                big.append("这段历史在手动压缩时会整段消失。");
            }
            llm.script(textReply(500, "第一轮"));
            agent.chat(big.toString(), StreamListener.NOOP);
            llm.script(textReply(500, "第二轮"));
            agent.chat(big.toString(), StreamListener.NOOP);
            assertEquals("阈值没到就不该自动压缩、更不该有退款",
                    0L, agent.budgetLedger().refundedTokens());

            String out = agent.compressNow("");

            assertTrue(out, out.contains("已压缩"));
            assertTrue("手动 /compress 也要退款，实得 " + out
                            + " / refunded=" + agent.budgetLedger().refundedTokens(),
                    agent.budgetLedger().refundedTokens() > 0L);
            assertTrue("回复文案要把退款数额报出来（用户看的就是这行）：" + out,
                    out.contains("退还预算"));
        } finally {
            agent.shutdown();
        }
    }

    // ===== steer 语义 =====

    @Test
    public void hardStopDropsPendingSteerSoItCannotResurfaceNextTurn() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        BotAgent agent = plainAgent(llm);
        try {
            agent.steer("改道：后面全部用中文");
            assertTrue("steer 写入后应处于待注入状态", agent.hasPendingSteer());

            agent.stop();

            assertFalse("硬中断必须丢弃尚未注入的 steer", agent.hasPendingSteer());
            assertTrue("旗子确实被置位过（否则上面那句是空跑）", agent.isStopRequested());

            llm.script(textReply(50, "下一轮"));
            agent.chat("继续", StreamListener.NOOP);
            String user = lastUserText(llm.requests.get(0));
            assertFalse("被叫停的插话不许在下一轮以 [User steer] 冒出来：" + user,
                    user.contains("[User steer]"));
            assertFalse(user, user.contains("改道：后面全部用中文"));
        } finally {
            agent.shutdown();
        }
    }

    @Test
    public void queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        BotAgent agent = plainAgent(llm);
        try {
            agent.enqueue("排队甲");
            agent.enqueue("排队乙");
            assertEquals("/queue 是多条按序保留，前一条不许被后一条吃掉",
                    Arrays.asList("排队甲", "排队乙"), agent.context().steer().drain());

            agent.steer("插话甲");
            agent.steer("插话乙");
            assertEquals("/steer 是单槽后到盖先到（hermes 的 _pending_steer 同此语义）",
                    Collections.singletonList("插话乙"), agent.context().steer().drain());
        } finally {
            agent.shutdown();
        }
    }

    // ===== helpers =====

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BotAgent plainAgent(ScriptedLlm llm) throws Exception {
        return BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .budget(new IterationBudget(10, 100_000L))
                .model("test-model")
                .withoutBuiltinTools()
                .build();
    }

    /** echo 工具：结果 3000 字，用来把"最近 N 条"撑大，让中段压缩省下的字符肉眼可见。 */
    private Toolkit toolkitWithBigEcho() {
        Toolkit toolkit = new Toolkit();
        final String big = pad("echoed:");
        toolkit.register(Toolkit.of("echo", "回显", stringSchema("message"),
                args -> new ToolResult(null, null, big, false,
                        Collections.<String, Object>emptyMap())));
        return toolkit;
    }

    private static String pad(String head) {
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < 300; i++) {
            sb.append("工具输出填充。");
        }
        return sb.toString();
    }

    private static String lastUserText(ChatCompletionsRequest request) {
        String last = null;
        for (Msg m : request.getMessages()) {
            if (m.getRole() == MessageRole.USER) {
                last = m.getContent();
            }
        }
        assertTrue("请求里没有 user 消息", last != null);
        return last;
    }

    private static Map<String, Object> stringSchema(String... propertyNames) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (String name : propertyNames) {
            properties.put(name, Collections.<String, Object>singletonMap("type", "string"));
        }
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static ToolCall call(String id, String name, String argsJson) {
        return new ToolCall(id, name, argsJson);
    }

    private static ChatCompletionsResponse textReply(int promptTokens, String content) {
        return response(content, Collections.<ToolCall>emptyList(), promptTokens);
    }

    private static ChatCompletionsResponse toolReply(int promptTokens, ToolCall... calls) {
        return response("", Arrays.asList(calls), promptTokens);
    }

    private static ChatCompletionsResponse response(String content, List<ToolCall> calls,
                                                    int promptTokens) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, calls, "stop")),
                new TokenUsage((long) promptTokens, 0L, (long) promptTokens), "stop", null);
    }

    /** 脚本化替身：按序吐响应，并把每一次实际发出的请求记下来。 */
    private static final class ScriptedLlm implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();

        ScriptedLlm script(ChatCompletionsResponse response) {
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
            requests.add(request);
            return scripted.isEmpty() ? textReply(50, "脚本用尽") : scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
