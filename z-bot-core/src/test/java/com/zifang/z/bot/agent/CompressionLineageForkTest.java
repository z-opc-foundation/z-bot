package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.context.CompressionLedger;
import com.zifang.z.bot.context.CompressorEngine;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P14 §5 的 agent 侧：压缩一旦发生就是<b>一次真分叉</b> —— 写 {@code sessions.parent_session_id}、
 * 子会话标题按 {@code base #N} 派号、原文留在父会话里、重启后从库里仍然看得见这条链。
 */
public class CompressionLineageForkTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class ScriptedLlm implements LlmProvider {
        private final List<ChatCompletionsResponse> script = new ArrayList<ChatCompletionsResponse>();

        void addToolCall() {
            script.add(response("先看一眼", Collections.singletonList(new ToolCall("c1", "noop_tool", "{}"))));
        }

        void addText(String text) {
            script.add(response(text, Collections.<ToolCall>emptyList()));
        }

        private static ChatCompletionsResponse response(String content, List<ToolCall> calls) {
            return new ChatCompletionsResponse("id", "test-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, content, calls, "stop")),
                    new TokenUsage(9000L, 5L, 9005L), calls.isEmpty() ? "stop" : "tool_calls", null);
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
            return script.isEmpty() ? addTextFallback() : script.remove(0);
        }

        private ChatCompletionsResponse addTextFallback() {
            return response("兜底", Collections.<ToolCall>emptyList());
        }

        @Override
        public void streamChat(ChatCompletionsRequest request,
                               java.util.function.Consumer<ChatCompletionsResponse> onChunk,
                               java.util.function.Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }

    private static final class NoopTool implements Tool {
        @Override
        public String getName() {
            return "noop_tool";
        }

        @Override
        public String getDescription() {
            return "往上下文里灌一大段结果";
        }

        @Override
        public Map<String, Object> getSchema() {
            return Collections.emptyMap();
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                sb.append("工具输出填充段落 ").append(i).append("。\n");
            }
            return ToolResult.text(sb.toString());
        }
    }

    private static final ContextEngine.Summarizer SUMMARIZER =
            msgs -> "摘要：保留了路径、命令与决策。";

    @Test
    public void compressionForksSessionAndWritesParentAndBaseOrdinal() throws Exception {
        java.io.File dbFile = new java.io.File(tmp.newFolder("fork"), "state.db");
        StateStore store = new StateStore(dbFile);
        SessionManager sm = new SessionManager(tmp.newFolder("sessions-fork"), store);
        String first = sm.createSession();
        CompressorEngine e = new CompressorEngine(20_000L, 0, 0.50D, 1,
                CompressorEngine.DEFAULT_COOLDOWN_MILLIS, 2, 60_000L);
        ScriptedLlm llm = new ScriptedLlm();
        llm.addToolCall();
        llm.addText("工具跑完了");
        Toolkit tk = new Toolkit();
        tk.register(new NoopTool());
        BotAgent a = BotAgent.builder((com.zifang.z.bot.config.BotConfig) null)
                .provider(llm)
                .sandbox(new Sandbox(tmp.newFolder("sandbox-fork").getAbsolutePath()))
                .sessionManager(sm)
                .toolkit(tk)
                .contextEngine(e, SUMMARIZER)
                .model("test-model")
                .maxSteps(4)
                .build();
        try {
            seed(a, 5);
            a.chat("用一下工具");
            assertTrue("这一轮必须真压过（lastSkip=" + e.getLastSkipReason() + "）",
                    e.getCompressCount() >= 1);
            String child = sm.getCurrentSessionId();
            assertTrue("压缩之后当前会话应该换人", !first.equals(child));
            assertEquals("血统里第一代分叉的标题", "base #1", find(store, child).title);
            assertEquals(first, store.parentOf(child));
            assertTrue("父会话要标成已结束（end_reason=compressed）", store.sessionEnded(first));
            assertTrue("压缩后的历史落在子会话里", store.messageCount(child) >= 1);
            assertTrue("父会话的原文不丢", store.messageCount(first) >= 1);
            CompressionLedger l = new CompressionLedger(store);
            assertEquals("base #1 的父就是最初的会话", 1, l.lineageDepth(child));
            // 之后再落一次盘（SessionManager 会拿「首条消息猜的标题」覆盖）⇒ 派号必须还在
            a.chat("再聊一轮");
            assertEquals("后续落盘不得把 base #N 冲掉", "base #1", find(store, child).title);
        } finally {
            a.shutdown();
        }
    }

    /** 「入库」而不是「内存口径」：换一个 StateStore 实例重开，链还在。 */
    @Test
    public void forkChainIsVisibleAfterReopeningTheStore() throws Exception {
        java.io.File dbFile = new java.io.File(tmp.newFolder("reopen"), "state.db");
        StateStore store = new StateStore(dbFile);
        store.upsertSession("p1", "最初会话", null, null, 1, 10, 1);
        store.upsertSession("c1", "新会话", null, null, 1, 0, 0);
        CompressionLedger l = new CompressionLedger(store);
        assertEquals("base #1", l.forkForCompression("p1", "c1"));

        StateStore reopened = new StateStore(dbFile);
        CompressionLedger l2 = new CompressionLedger(reopened);
        assertEquals("c1", find(reopened, "c1").id);
        assertEquals("base #1", find(reopened, "c1").title);
        assertEquals("p1", reopened.parentOf("c1"));
        assertEquals(1, l2.lineageDepth("c1"));
        assertTrue("父会话在库里仍是已结束状态", reopened.sessionEnded("p1"));
    }

    /** 垫 5 条 2000 字符的历史：中段真的有大块内容可压（否则「省不出下限」会被判无效并原样退回）。 */
    private static void seed(BotAgent a, int n) {
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder("垫底历史 " + i + "：");
            while (sb.length() < 2000) {
                sb.append("填充填充填充填充填充填充。");
            }
            a.getMemory().add(Msg.user(sb.toString()));
        }
    }

    private static StateStore.SessionRow find(StateStore store, String id) {
        for (StateStore.SessionRow r : store.listSessions(true)) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }
}
