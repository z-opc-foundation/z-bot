package com.zifang.z.bot.llm;

import com.zifang.z.bot.llm.LlmErrorClassifier.FailureClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * P26：抖动退避 + 限流阶梯（对齐 hermes {@code agent/retry_utils.py} 的**语义**，数值自定）。
 *
 * <p>短档（普通瞬时失败）：{@code min(base * 2^(attempt-1), maxDelay)}，再叠
 * {@code jitter ~ uniform[0, jitterRatio * 该值]} —— 与 {@code jittered_backoff} 同式。</p>
 *
 * <p>长档（限流 {@link FailureClass#RATE_LIMIT} 且已走完 {@code shortAttempts} 步短档）：
 * 逐步走 {@code llm.retry.rate-limit-ladder-ms} 阶梯表，越界取末项（不重置），同样叠抖动 ——
 * 对应 hermes 的 {@code _ZAI_CODING_OVERLOAD_LONG_BACKOFF} + {@code _ZAI_CODING_SHORT_ATTEMPTS}。</p>
 *
 * <p>上游给了 {@code Retry-After} 时**优先采信**（已在 {@link LlmErrorClassifier} 侧封顶）。</p>
 *
 * <p>总预算：累计等待超过 {@code totalBudgetMs} 后返回 0 并置 {@link #isBudgetExhausted()}，
 * 由调用方停止重试 ⇒ 一次请求不会把整轮拖爆。</p>
 *
 * <p>抖动源可注入（{@link DoubleSupplier}，值域 [0,1)）⇒ 单测用固定 0.0 钉出确定等待序列，
 * 真实运行用 {@link java.util.Random}。</p>
 */
public final class JitteredBackoff {

    private final RetryPolicyConfig policy;
    private final DoubleSupplier jitterSource;
    private final List<Long> waits = new ArrayList<Long>();
    private long spentMs;

    public JitteredBackoff(RetryPolicyConfig policy) {
        this(policy, defaultSource());
    }

    public JitteredBackoff(RetryPolicyConfig policy, DoubleSupplier jitterSource) {
        this.policy = policy == null ? RetryPolicyConfig.defaults() : policy;
        this.jitterSource = jitterSource == null ? defaultSource() : jitterSource;
    }

    private static DoubleSupplier defaultSource() {
        final Random rnd = new Random();
        return new DoubleSupplier() {
            @Override
            public double getAsDouble() {
                return rnd.nextDouble();
            }
        };
    }

    /**
     * 第 {@code attempt} 次失败之后要等多久（1-based）。返回 0 表示预算耗尽、别再试。
     *
     * @param cls           分类结果（决定短档还是长档）
     * @param retryAfterMs  上游 {@code Retry-After}（毫秒），{@code <=0} 表示没有
     */
    public long nextDelayMs(FailureClass cls, int attempt, long retryAfterMs) {
        long raw;
        if (retryAfterMs > 0) {
            raw = retryAfterMs;
        } else if (cls == FailureClass.RATE_LIMIT && attempt > policy.getShortAttempts()) {
            raw = ladderValue(attempt - policy.getShortAttempts());
        } else {
            raw = exponential(attempt);
        }
        long jitter = (long) (this.policy.getJitterRatio() * raw * clamp01(jitterSource.getAsDouble()));
        long wait = raw + jitter;
        if (policy.getTotalBudgetMs() > 0 && spentMs + wait > policy.getTotalBudgetMs()) {
            long headroom = policy.getTotalBudgetMs() - spentMs;
            wait = Math.max(0L, headroom);
        }
        spentMs += wait;
        waits.add(wait);
        return wait;
    }

    /** 预算是否已耗尽（耗尽后调用方应停止重试）。 */
    public boolean isBudgetExhausted() {
        return policy.getTotalBudgetMs() > 0 && spentMs >= policy.getTotalBudgetMs();
    }

    /** 实测用：本实例记录下来的等待序列（杠③/单测拿它当"真等了多久"的读数，不看墙钟）。 */
    public List<Long> observedWaits() {
        return Collections.unmodifiableList(new ArrayList<Long>(waits));
    }

    /** 纯计算（不记账、不加抖动），供配置校验与单测。base&lt;=0 ⇒ 0（"不许退避"就是 0，不是上限）。 */
    public long exponential(int attempt) {
        int exponent = Math.max(0, attempt - 1);
        long base = policy.getBaseDelayMs();
        if (base <= 0) {
            return 0L;
        }
        if (exponent >= 31) {
            return policy.getMaxDelayMs();
        }
        long v = base * (1L << exponent);
        return v <= 0 || v > policy.getMaxDelayMs() ? policy.getMaxDelayMs() : v;
    }

    /** 限流长档：第 {@code step} 步（1-based），超出表尾取最后一项；整表统一被 {@code max-delay-ms} 封顶。 */
    public long ladderValue(int step) {
        List<Long> ladder = policy.getRateLimitLadderMs();
        if (ladder.isEmpty()) {
            return policy.getMaxDelayMs();
        }
        int i = Math.max(1, step) - 1;
        long v = ladder.get(Math.min(i, ladder.size() - 1));
        long cap = policy.getMaxDelayMs();
        return cap > 0L ? Math.min(v, cap) : v;
    }

    private static double clamp01(double d) {
        if (Double.isNaN(d) || d < 0d) {
            return 0d;
        }
        return d >= 1d ? 0.9999999d : d;
    }

    @Override
    public String toString() {
        return "JitteredBackoff{" + policy + ", spent=" + spentMs + "ms, waits=" + waits + "}";
    }
}
