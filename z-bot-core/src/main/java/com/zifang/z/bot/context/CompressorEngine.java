package com.zifang.z.bot.context;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.message.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * kernel {@link ContextEngine} SPI 的默认实现 — hermes 式上下文压缩。
 *
 * <p>真实 prompt tokens（每次 LLM 响应回传的 usage）达到预算 85% 时触发：
 * 保住最近 N 条原文，中段交给 {@link Summarizer}（辅助模型）压成一条带
 * 「事实承诺」约束的摘要消息。同会话并发只允许一次压缩（压缩锁）；
 * 压缩血统（次数/最近摘要/前后条数）留在本实例上供 /compress 查看。</p>
 */
public class CompressorEngine implements ContextEngine {

    /** 触发阈值：真实上下文 tokens / 预算上限。 */
    public static final double DEFAULT_THRESHOLD = 0.85;
    /** 压缩时原样保留的最近消息条数。 */
    public static final int DEFAULT_KEEP_RECENT = 8;

    /** 摘要 prompt 的保留约束 — 路径/命令/代码/决策/承诺丢一条都不行。 */
    public static final String SUMMARY_PROMPT_PREFIX =
            "请把以下早前对话压缩成一段简洁摘要（要点式），必须完整保留：\n"
                    + "1) 出现过的所有文件路径、URL、命令；\n"
                    + "2) 关键代码片段、配置名、参数值；\n"
                    + "3) 已经做出的决策和对用户的承诺；\n"
                    + "4) 未完成的任务与下一步计划。\n"
                    + "只输出摘要本身，不要任何解释。\n\n=== 早前对话 ===\n";

    private final long maxTokens;
    private final double threshold;
    private final int keepRecent;

    private final AtomicBoolean compressing = new AtomicBoolean(false);
    /** 最近一次请求的真实上下文 tokens（来自 LLM usage.promptTokens）。 */
    private volatile long contextTokens;
    private final AtomicInteger compressCount = new AtomicInteger();
    private volatile String lastSummary;
    private volatile int lastFromCount;
    private volatile int lastToCount;

    public CompressorEngine(long maxTokens) {
        this(maxTokens, DEFAULT_THRESHOLD, DEFAULT_KEEP_RECENT);
    }

    public CompressorEngine(long maxTokens, double threshold, int keepRecent) {
        this.maxTokens = maxTokens;
        this.threshold = threshold;
        this.keepRecent = Math.max(2, keepRecent);
    }

    @Override
    public void onSessionStart() {
        contextTokens = 0;
    }

    /** kernel 约定：update(promptTokens, completionTokens)。prompt 即当前上下文规模的真实观测。 */
    @Override
    public void update(int promptTokens, int completionTokens) {
        if (promptTokens > 0) {
            contextTokens = promptTokens;
        }
    }

    @Override
    public boolean shouldCompress() {
        return contextTokens >= Math.max(1L, (long) (maxTokens * threshold));
    }

    @Override
    public List<Msg> compress(List<Msg> history, Summarizer summarizer) {
        if (history == null || !shouldCompress()) {
            return history;
        }
        // 压缩锁：同会话并发请求只有第一个真正压缩，其余拿原样历史
        if (!compressing.compareAndSet(false, true)) {
            return history;
        }
        try {
            if (history.size() <= keepRecent + 1) {
                return history;
            }
            int cut = history.size() - keepRecent;
            List<Msg> middle = new ArrayList<Msg>(history.subList(0, cut));
            List<Msg> recent = new ArrayList<Msg>(history.subList(cut, history.size()));

            String summary = summarizer.summarize(middle);
            if (summary == null || summary.trim().isEmpty()) {
                return history;
            }

            List<Msg> out = new ArrayList<Msg>();
            // 中段的 system 角色消息（若有）原样置顶，摘要跟在其后
            for (Msg m : middle) {
                if (m.getRole() == MessageRole.SYSTEM) {
                    out.add(m);
                }
            }
            out.add(Msg.user("[context summary] 以下是早前对话的压缩摘要：\n" + summary.trim()));
            out.addAll(recent);

            compressCount.incrementAndGet();
            lastSummary = summary.trim();
            lastFromCount = history.size();
            lastToCount = out.size();
            return out;
        } finally {
            compressing.set(false);
        }
    }

    /** 手动 /compress：绕过 shouldCompress 阈值直接压一次；锁与最短长度约束仍然生效。 */
    public List<Msg> forceCompress(List<Msg> history, Summarizer summarizer) {
        long saved = contextTokens;
        contextTokens = Math.max(contextTokens, maxTokens);
        try {
            return compress(history, summarizer);
        } finally {
            contextTokens = saved;
        }
    }

    public long getContextTokens() {
        return contextTokens;
    }

    public int getCompressCount() {
        return compressCount.get();
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public int getLastFromCount() {
        return lastFromCount;
    }

    public int getLastToCount() {
        return lastToCount;
    }

    public long getMaxTokens() {
        return maxTokens;
    }
}
