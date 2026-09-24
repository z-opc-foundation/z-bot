package com.zifang.z.bot.context;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** CompressorEngine：阈值触发 / 头尾保护 / 压缩锁 / 事实承诺保留 prompt / 血统记录。 */
public class CompressorEngineTest {

    private static Msg user(String text) {
        return new Msg(MessageRole.USER, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    private static List<Msg> history(int n) {
        List<Msg> out = new ArrayList<Msg>();
        for (int i = 1; i <= n; i++) {
            out.add(user("历史消息 " + i));
        }
        return out;
    }

    private static CompressorEngine engine(long maxTokens) {
        return new CompressorEngine(maxTokens, 0.85, 4);
    }

    private static ContextEngine.Summarizer sum(final String result) {
        return msgs -> result;
    }

    @Test
    public void shouldCompressFollowsRealPromptTokensAgainstThreshold() {
        CompressorEngine e = engine(1000);
        assertFalse("未到 85% 不触发", e.shouldCompress());
        e.update(800, 50);
        assertFalse("800/1000 < 85%", e.shouldCompress());
        e.update(900, 50);
        assertTrue("900/1000 >= 85% 触发", e.shouldCompress());
        // completion 不参与判定：prompt 才是上下文规模
        e.update(100, 5000);
        assertFalse("新 prompt 变小后回到阈值下", e.shouldCompress());
    }

    @Test
    public void compressKeepsRecentVerbatimAndSummarizesMiddle() {
        CompressorEngine e = engine(1000);
        e.update(999, 0);
        List<Msg> history = history(10);
        List<Msg> out = e.compress(history, sum("这是中段摘要"));
        assertNotNull(out);
        assertEquals("1 条摘要 + 4 条最近原文", 5, out.size());
        assertTrue(out.get(0).getContent().startsWith("[context summary]"));
        assertTrue(out.get(0).getContent().contains("这是中段摘要"));
        assertEquals("最近 4 条原样保留", "历史消息 7", out.get(1).getContent());
        assertEquals("历史消息 10", out.get(4).getContent());
        assertEquals(1, e.getCompressCount());
        assertEquals(10, e.getLastFromCount());
        assertEquals(5, e.getLastToCount());
        assertEquals("这是中段摘要", e.getLastSummary());
    }

    @Test
    public void compressSkippedWhenUnderThresholdOrHistoryTooShort() {
        CompressorEngine e = engine(1000);
        e.update(100, 0);
        List<Msg> history = history(20);
        assertSame("低于阈值原样返回", history, e.compress(history, sum("摘要")));

        CompressorEngine e2 = engine(1000);
        e2.update(999, 0);
        List<Msg> shortHistory = history(4);
        assertSame("条数 <= keepRecent+1 不值得压", shortHistory, e2.compress(shortHistory, sum("摘要")));
        assertEquals(0, e2.getCompressCount());
    }

    @Test
    public void emptySummaryLeavesHistoryUntouched() {
        CompressorEngine e = engine(1000);
        e.update(999, 0);
        List<Msg> history = history(12);
        assertSame("摘要为空（aux 模型失败）时不替换", history, e.compress(history, sum("  ")));
        assertNull(e.getLastSummary());
    }

    @Test
    public void compressLockLetsOnlyOneThreadThrough() throws Exception {
        final CompressorEngine e = engine(1000);
        e.update(999, 0);
        final AtomicInteger realRuns = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        ContextEngine.Summarizer slowSummarizer = msgs -> {            realRuns.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "锁内摘要";
        };
        List<Msg> history = history(20);
        Thread first = new Thread(() -> e.compress(history, slowSummarizer));
        first.start();
        started.await();
        // 第一个还在压缩时第二个进来 → 拿原样历史，不阻塞、不重复压缩
        List<Msg> second = e.compress(history, sum("不该被用到"));
        assertSame(history, second);
        release.countDown();
        first.join(5000);
        assertEquals("真实摘要只跑一次", 1, realRuns.get());
    }

    @Test
    public void forceCompressBypassesThresholdButNotLockOrMinLength() {
        CompressorEngine e = engine(1000);
        e.update(10, 0);
        List<Msg> history = history(15);
        List<Msg> out = e.forceCompress(history, sum("手动压缩"));
        assertEquals(5, out.size());
        assertEquals(1, e.getCompressCount());
        // force 不改变正常阈值观测
        assertFalse(e.shouldCompress());
        List<Msg> shortHistory = history(3);
        assertSame(shortHistory, e.forceCompress(shortHistory, sum("太短")));
    }

    @Test
    public void summarizerPromptDemandsFactsRetention() {
        final List<Msg> captured = new ArrayList<Msg>();
        CompressorEngine e = engine(1000);
        e.update(999, 0);
        List<Msg> history = new ArrayList<Msg>();
        history.add(user("把配置写到 /etc/app/config.yml"));
        history.add(user("答应用户周五前交付"));
        history.addAll(history(15));
        // 借 summarize 调用捕获 middle 内容：用一个包装引擎直接调 compress 并截获 prompt 组装前的输入
        e.compress(history, msgs -> {
            captured.addAll(msgs);
            return "ok";
        });
        assertEquals("middle = 除最近 4 条外的全部", 13, captured.size());
        assertEquals("最早的排最前", "把配置写到 /etc/app/config.yml", captured.get(0).getContent());
        // BotAgent.summarizeWithProvider 会把 SUMMARY_PROMPT_PREFIX 拼在最前 — 常量本身必须包含保留条款
        assertTrue(CompressorEngine.SUMMARY_PROMPT_PREFIX.contains("文件路径"));
        assertTrue(CompressorEngine.SUMMARY_PROMPT_PREFIX.contains("决策"));
        assertTrue(CompressorEngine.SUMMARY_PROMPT_PREFIX.contains("承诺"));
    }
}
