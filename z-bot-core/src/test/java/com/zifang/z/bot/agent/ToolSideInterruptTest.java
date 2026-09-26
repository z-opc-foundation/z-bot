package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.InterruptFlag;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P12「工具侧中断」单测：旗子按执行线程定向、工具在飞时真能断、断的时候子进程真没了。
 *
 * <p>判「断了」不看日志、也不只看抛了什么异常 —— 用 {@link ProcessHandle} 直接查进程表：
 * 被 {@code exec} 拉起来的那个 {@code sleep}（以及 bash 名下会变孤儿的那个）必须在置位后
 * 从进程表里消失。只杀 bash 不杀后代的话，这一条必红。</p>
 */
public class ToolSideInterruptTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File sandboxDir;

    @Before
    public void setUp() throws Exception {
        sandboxDir = tmp.newFolder("sandbox");
    }

    // ===== 线程作用域 =====

    @Test
    public void checkpointIsNoOpWithoutFlagAndThrowsAfterRequest() {
        InterruptScope.checkpoint();                                    // 没绑定：不许抛
        assertNull(InterruptScope.current());

        InterruptFlag flag = new InterruptFlag();
        InterruptFlag previous = InterruptScope.bind(flag);
        try {
            assertNull(previous);
            assertEquals(flag, InterruptScope.current());
            InterruptScope.checkpoint();                                 // 还没置位
            flag.request("用户请求停止");
            assertTrue(InterruptScope.isInterrupted());
            try {
                InterruptScope.checkpoint();
                fail("置位后检查点必须抛");
            } catch (InterruptFlag.AgentInterruptedException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("用户请求停止"));
            }
        } finally {
            InterruptScope.restore(previous);
        }
        assertNull("还原后必须真的解绑，否则复用的线程会把旗子带给下一个会话",
                InterruptScope.current());
        InterruptScope.checkpoint();                                     // 又成空操作
    }

    @Test
    public void flagsArePerThreadSoConcurrentSessionsDoNotCrossFire() throws Exception {
        final InterruptFlag flagA = new InterruptFlag();
        final InterruptFlag flagB = new InterruptFlag();

        // B 会话在自己的执行线程上按了停止
        Thread b = new Thread(new Runnable() {
            @Override
            public void run() {
                InterruptScope.bind(flagB);
                flagB.request("B 会话按了停止");
                InterruptScope.restore(null);
            }
        }, "p12-session-b");
        b.start();
        b.join(10_000);
        assertTrue(flagB.isInterrupted());

        // A 会话随后跑：绑的是自己那面旗子 —— B 的停止不许把 A 一起打断
        final AtomicReference<Boolean> aSawInterrupted = new AtomicReference<Boolean>();
        final AtomicReference<Throwable> aThrewUnexpectedly = new AtomicReference<Throwable>();
        boolean aThrew = false;
        InterruptScope.bind(flagA);
        try {
            InterruptScope.checkpoint();
            aSawInterrupted.set(InterruptScope.isInterrupted());
            flagA.request("A 会话按了停止");
            InterruptScope.checkpoint();
        } catch (InterruptFlag.AgentInterruptedException expected) {
            aThrew = true;
        } catch (Throwable t) {
            aThrewUnexpectedly.set(t);
        } finally {
            InterruptScope.restore(null);
        }
        assertNull("A 会话抛了不该抛的异常：" + aThrewUnexpectedly.get(), aThrewUnexpectedly.get());
        assertEquals("B 的停不能串到 A", Boolean.FALSE, aSawInterrupted.get());
        assertTrue("A 自己置位后必须断", aThrew);
    }

    // ===== 工具在飞时真能断 =====

    @Test
    public void execAbortsInFlightAndTakesTheWholeProcessTreeDown() throws Exception {
        // 一个不可能被别的进程撞上的时长当指纹：按参数在进程表里定位它
        final String token = "9" + (1_000_000 + (int) (System.nanoTime() % 8_000_000L));
        final InterruptFlag flag = new InterruptFlag();
        final Tool exec = BuiltinTools.exec(new Sandbox(sandboxDir.getAbsolutePath()), "off");
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                InterruptScope.bind(flag);
                try {
                    // 后台 + wait：这样 bash 一定留着当爹，睡进程是它名下的后代 ——
                    // 「只 destroy 直接子进程」的写法会把后代漏成孤儿，进程表清不干净
                    String command = "sleep " + token + " & wait";
                    ToolResult r = exec.execute(
                            Collections.<String, Object>singletonMap("command", command));
                    thrown.set(new AssertionError("exec 居然正常返回了：" + r.getContent()));
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    InterruptScope.restore(null);
                }
            }
        }, "p12-exec-worker");
        worker.start();

        long appearedMs = waitForProcessCount(token, 2, 15_000);
        assertTrue("没看到 bash + sleep 两个进程（实测 " + countProcessesMatching(token)
                + "），这一条测不到任何东西", appearedMs >= 0);

        long t0 = System.currentTimeMillis();
        flag.request("用户请求停止");
        worker.join(10_000);
        long tThrow = System.currentTimeMillis();
        assertTrue("exec 线程 10s 没退出：工具在飞时断不掉", !worker.isAlive());
        Throwable t = thrown.get();
        assertTrue("工具侧必须抛 kernel 的中断异常，实测 " + t,
                t instanceof InterruptFlag.AgentInterruptedException);

        // 真断了的判据在进程表里，不在日志里：等到匹配进程数为 0，再量耗时
        while (System.currentTimeMillis() - t0 < 5_000 && countProcessesMatching(token) > 0) {
            sleep(20);
        }
        long tGone = System.currentTimeMillis();
        int leftovers = countProcessesMatching(token);
        assertEquals("子进程没被收干净（只杀 bash 不杀后代就是这个数）", 0, leftovers);
        System.out.println("[P12] exec 中断实测: 置位→工具抛出=" + (tThrow - t0)
                + "ms, 置位→进程表干净=" + (tGone - t0) + "ms");
        assertTrue("从置位到进程表干净应小于 2s，实测 " + (tGone - t0) + "ms", tGone - t0 < 2_000);
    }

    @Test
    public void readFileAbortsInsteadOfReadingTheWholeFile() throws Exception {
        File big = new File(sandboxDir, "big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("line ").append(i).append('\n');
        }
        try (FileWriter fw = new FileWriter(big)) {
            fw.write(sb.toString());
        }
        InterruptFlag flag = new InterruptFlag();
        flag.request("用户请求停止");
        InterruptFlag previous = InterruptScope.bind(flag);
        try {
            Tool read = BuiltinTools.readFile(new Sandbox(sandboxDir.getAbsolutePath()));
            try {
                read.execute(Collections.<String, Object>singletonMap("path", "big.txt"));
                fail("已置位时读文件必须断，不能把整份内容读完再返回");
            } catch (InterruptFlag.AgentInterruptedException expected) {
                // ok
            }
        } finally {
            InterruptScope.restore(previous);
        }
    }

    @Test
    public void writeFileAbortsBeforeTouchingTheSandbox() throws Exception {
        InterruptFlag flag = new InterruptFlag();
        flag.request("用户请求停止");
        InterruptFlag previous = InterruptScope.bind(flag);
        try {
            Tool write = BuiltinTools.writeFile(new Sandbox(sandboxDir.getAbsolutePath()));
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("path", "never.txt");
            args.put("content", "x");
            try {
                write.execute(args);
                fail("已置位时不许落盘");
            } catch (InterruptFlag.AgentInterruptedException expected) {
                // ok
            }
            assertTrue("中止的一刻不该留下文件", !new File(sandboxDir, "never.txt").exists());
        } finally {
            InterruptScope.restore(previous);
        }
    }

    // ===== 工具入口：置位之后连进程都不该起 =====

    @Test
    public void execWithFlagAlreadySetStartsNoChildProcessAtAll() throws Exception {
        final String token = "9" + (1_000_000 + (int) (System.nanoTime() % 8_000_000L));
        final Tool exec = BuiltinTools.exec(new Sandbox(sandboxDir.getAbsolutePath()), "off");
        final String command = "sleep " + token + " & wait";

        InterruptFlag flag = new InterruptFlag();
        flag.request("用户已按停止");
        InterruptFlag previous = InterruptScope.bind(flag);
        try {
            try {
                exec.execute(Collections.<String, Object>singletonMap("command", command));
                fail("已置位时 exec 不许把子进程拉起来");
            } catch (InterruptFlag.AgentInterruptedException expected) {
                // ok
            }
        } finally {
            InterruptScope.restore(previous);
        }
        assertEquals("入口检查点没拦住：进程表里已经出现了匹配 " + token + " 的进程",
                0, countProcessesMatching(token));

        // 反空跑对照：同一句命令、同一套代码，旗子没置位时必须在进程表里数得到 ——
        // 没有这一半，上面那句 assertEquals(0, …) 可以是"命令根本没跑起来"的空跑。
        final AtomicReference<Throwable> thrown = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                InterruptScope.bind(new InterruptFlag());
                try {
                    exec.execute(Collections.<String, Object>singletonMap("command", command));
                    thrown.set(null);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    InterruptScope.restore(null);
                }
            }
        }, "p12-control-worker");
        worker.start();
        try {
            long appeared = waitForProcessCount(token, 2, 15_000);
            assertTrue("对照组里 bash + sleep 两个进程没出现（实测 "
                    + countProcessesMatching(token) + "），这条对照测不到任何东西", appeared >= 0);
        } finally {
            killAllMatching(token);
            worker.join(15_000);
        }
        assertTrue("对照线程没收工，说明 pkill 没生效", !worker.isAlive());
    }

    // ===== 主循环接线 =====

    @Test
    public void interruptInsideParallelToolBatchAbortsRunNotFeedsModelError() throws Exception {
        final BotAgent[] holder = new BotAgent[1];
        ScriptedLlm llm = new ScriptedLlm();
        Toolkit toolkit = new Toolkit();
        // parallel-safe 才进并发批次（跑在池线程上）。每条都先请停止、再走检查点：
        // 置位发生在自己的检查点之前，所以两条线程都会抛 —— 判定不依赖调度顺序。
        toolkit.register(Toolkit.of("stopper", "自停工具", stringSchema("message"), args -> {
            holder[0].stop();
            for (int i = 0; i < 50; i++) {
                InterruptScope.checkpoint();
                sleep(10);
            }
            return result("不该到这里");
        }), true);
        llm.script(toolReply(call("p1", "stopper", "{\"message\":\"a\"}"),
                call("p2", "stopper", "{\"message\":\"b\"}")))
                .script(textReply("第二轮还在跑"));

        BotAgent agent = BotAgent.builder(null)
                .provider(llm)
                .toolkit(toolkit)
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions")))
                .maxSteps(5)
                .model("test-model")
                .withoutBuiltinTools()
                .build();
        holder[0] = agent;

        String reply = agent.chat("并行批次", StreamListener.NOOP);

        assertTrue("并行批次里的中断必须走中止分支，实测回复: " + reply, reply.startsWith("已中止"));
        assertEquals("中止后不许再打第二次 LLM", 1, llm.callCount);
    }

    // ===== 量具：直接查进程表，不信日志 =====

    private static int countProcessesMatching(String token) {
        final int[] n = {0};
        ProcessHandle.allProcesses().forEach(p -> {
            String cmdline = p.info().commandLine().orElse("");
            if (cmdline.contains(token)) {
                n[0]++;
            }
        });
        return n[0];
    }

    /**
     * 等到进程表里匹配 {@code token} 的进程数变成 {@code wanted}。
     *
     * @return 等到时的耗时 ms；超时返回 -1
     */
    private static long waitForProcessCount(String token, int wanted, long timeoutMs) {
        long start = System.nanoTime();
        while ((System.nanoTime() - start) / 1_000_000L < timeoutMs) {
            if (countProcessesMatching(token) == wanted) {
                return (System.nanoTime() - start) / 1_000_000L;
            }
            sleep(20);
        }
        return -1;
    }

    /** 收尾：把对照组拉起来的进程真清掉（pkill 不存在也不能让测试挂死）。 */
    private static void killAllMatching(String token) {
        try {
            new ProcessBuilder("pkill", "-f", token).start().waitFor();
        } catch (Exception ignored) {
            // 清不掉由后面的 join 超时兜住
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== helpers =====

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

    private static ToolResult result(String content) {
        return new ToolResult(null, null, content, false, Collections.<String, Object>emptyMap());
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

    /** 脚本化 LLM 替身：按序吐响应并记录请求。 */
    private static final class ScriptedLlm implements LlmProvider {
        private final List<ChatCompletionsResponse> scripted = new ArrayList<ChatCompletionsResponse>();
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        int callCount;

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
            callCount++;
            return scripted.isEmpty() ? textReply("脚本用尽") : scripted.remove(0);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }
}
