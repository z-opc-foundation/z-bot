package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.bot.llm.LlmErrorClassifier.Decision;
import com.zifang.z.bot.llm.LlmErrorClassifier.FailureClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * 带重试与模型降级的 {@link LlmProvider} 装饰器（P26 起对齐 hermes 的**分类表 + 抖动退避 + 限流阶梯
 * + 流式陈旧看门狗**语义，不再是"两条正则 + 线性退避"）。
 *
 * <p>错误分类走 {@link LlmErrorClassifier}（结构化 HTTP 状态码 &gt; 异常类型 &gt; 收窄消息），四类语义：</p>
 * <ul>
 *   <li>{@link FailureClass#RETRY_SAME_KEY} / {@link FailureClass#RATE_LIMIT} —— 退避重试；
 *       退避由 {@link JitteredBackoff} 给出（指数 + jitter，限流走长档表，{@code Retry-After} 优先）</li>
 *   <li>{@link FailureClass#ROTATE_KEY} —— 本层不重试（换 key 是 {@link KeyPoolLlmProvider} 的活），直接上抛</li>
 *   <li>{@link FailureClass#FALLBACK_MODEL} —— 立刻跳出本模型的剩余重试，进降级链下一个模型</li>
 *   <li>{@link FailureClass#FATAL} —— 直接上抛</li>
 * </ul>
 *
 * <p>换模型时通过 {@link ModelFallbackHook} 让上层重建在飞的 system 上下文并重置压缩计数
 * （没注册 hook 时是空操作 —— 那正是 P26 之前的行为，见 EVIDENCE §3）。</p>
 *
 * <p>流式不做重试，但有 {@link StreamStaleWatchdog} 盯陈旧：阈值
 * {@code max(default, floor(model))}，用户显式配置优先。</p>
 */
public final class ResilientLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientLlmProvider.class);

    private final LlmProvider delegate;
    private final int maxRetries;
    private final long backoffMs;
    private final List<String> fallbackModels;
    private final RetryPolicyConfig policy;
    private final DoubleSupplier jitterSource;
    private final AtomicReference<ModelFallbackHook> hook = new AtomicReference<ModelFallbackHook>();
    private final AtomicReference<RetryObserver> observer = new AtomicReference<RetryObserver>();
    private final List<Long> lastWaits = Collections.synchronizedList(new ArrayList<Long>());
    private final AtomicReference<Decision> lastDecision = new AtomicReference<Decision>();

    public ResilientLlmProvider(LlmProvider delegate, int maxRetries, long backoffMs,
                                List<String> fallbackModels) {
        this(delegate, maxRetries, backoffMs, fallbackModels, null, null);
    }

    /**
     * @param policy        退避策略；{@code null} ⇒ 内置默认（base={@code backoffMs}，抖动 0.5，限流长档）
     * @param jitterSource  抖动源 [0,1)；{@code null} ⇒ {@link java.util.Random}。测试注 0.0 ⇒ 等待序列可预期
     */
    public ResilientLlmProvider(LlmProvider delegate, int maxRetries, long backoffMs,
                                List<String> fallbackModels, RetryPolicyConfig policy,
                                DoubleSupplier jitterSource) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate provider 不能为空");
        }
        this.delegate = delegate;
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffMs = Math.max(0, backoffMs);
        this.fallbackModels = fallbackModels == null
                ? Collections.<String>emptyList() : new ArrayList<String>(fallbackModels);
        this.jitterSource = jitterSource;
        this.policy = policy != null ? policy : RetryPolicyConfig.of(null, null)
                .withBaseDelayMs(this.backoffMs);
    }

    /** 换模型时回调：重建在飞上下文 + 重置压缩计数。 */
    public interface ModelFallbackHook {
        /** 用新模型重建请求（system 上下文、maxTokens 等按新模型口径），返回null表示原样透传。 */
        ChatCompletionsRequest rebuildContext(String fromModel, String toModel, ChatCompletionsRequest inFlight);

        /** 换模型后重置上层压缩计数（避免带着旧模型的 token 账继续判窗口）。 */
        void resetCompressionState(String fromModel, String toModel);
    }

    /** 每次退避的观测点（EVIDENCE 拿它取"真等了多久"的等待序列，不看墙钟）。 */
    public interface RetryObserver {
        void onRetry(String model, int attempt, Decision decision, long waitMs);
    }

    public void setModelFallbackHook(ModelFallbackHook hook) {
        this.hook.set(hook);
    }

    public void setRetryObserver(RetryObserver observer) {
        this.observer.set(observer);
    }

    /** 最近一次 {@code chat()} 实际发生的等待序列（毫秒）。 */
    public List<Long> lastObservedWaits() {
        synchronized (lastWaits) {
            return new ArrayList<Long>(lastWaits);
        }
    }

    /** 最近一次失败分类结果（审计用）。 */
    public Decision lastDecision() {
        return lastDecision.get();
    }

    public RetryPolicyConfig policy() {
        return policy;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public List<Model> listModels() {
        return delegate.listModels();
    }

    @Override
    public boolean supportsModel(String modelId) {
        return delegate.supportsModel(modelId);
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        List<String> models = new ArrayList<String>();
        models.add(request.getModel());
        models.addAll(fallbackModels);
        lastWaits.clear();

        JitteredBackoff backoff = new JitteredBackoff(policy, jitterSource);
        RuntimeException last = null;
        ChatCompletionsRequest req = request;
        for (int m = 0; m < models.size(); m++) {
            if (m > 0) {
                req = switchModel(models.get(m - 1), models.get(m), request);
            }
            for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
                try {
                    return delegate.chat(req);
                } catch (RuntimeException e) {
                    Decision d = LlmErrorClassifier.classify(e, policy);
                    lastDecision.set(d);
                    last = e;
                    if (d.requiresModelFallback()) {
                        LOG.warn("[ResilientLlm] {} 判为换模型({})，立刻降级: {}", req.getModel(), d.getRule(),
                                e.getMessage());
                        break;
                    }
                    if (!d.isRetryable() || backoff.isBudgetExhausted()) {
                        throw e;
                    }
                    if (attempt >= maxRetries + 1) {
                        // 本模型最后一次尝试已经用完：不要再空等一轮退避
                        break;
                    }
                    long wait = backoff.nextDelayMs(d.getFailureClass(), attempt, d.getRetryAfterMs());
                    if (wait <= 0L) {
                        throw e;
                    }
                    lastWaits.add(Long.valueOf(wait));
                    RetryObserver ob = observer.get();
                    if (ob != null) {
                        ob.onRetry(req.getModel(), attempt, d, wait);
                    }
                    LOG.warn("[ResilientLlm] {} 调用失败(第 {} 次，{} / {})，{}ms 后重试: {}", req.getModel(),
                            attempt, d.getFailureClass(), d.getRule(), wait, e.getMessage());
                    sleep(wait);
                }
            }
            if (m < models.size() - 1) {
                LOG.warn("[ResilientLlm] 模型 {} 重试耗尽/判为不可用，降级到 {}: {}",
                        req.getModel(), models.get(m + 1), last == null ? "?" : last.getMessage());
            }
        }
        throw last;
    }

    /** 换模型：先按新模型重建上下文，再通知上层重置压缩计数。 */
    private ChatCompletionsRequest switchModel(String from, String to, ChatCompletionsRequest origin) {
        ChatCompletionsRequest rebuilt = null;
        ModelFallbackHook h = hook.get();
        if (h != null) {
            try {
                rebuilt = h.rebuildContext(from, to, origin);
            } catch (RuntimeException e) {
                LOG.warn("[ResilientLlm] 重建 {} 上下文失败，退回原请求: {}", to, e.toString());
            }
            try {
                h.resetCompressionState(from, to);
            } catch (RuntimeException e) {
                LOG.warn("[ResilientLlm] 重置压缩计数失败: {}", e.toString());
            }
        }
        return rebuilt != null ? rebuilt : withModel(origin, to);
    }

    @Override
    public void streamChat(final ChatCompletionsRequest request, final Consumer<ChatCompletionsResponse> onChunk,
                           final Consumer<Throwable> onError) {
        final long timeoutMs = StreamStaleWatchdog.resolveTimeoutMs(policy, request.getModel());
        if (timeoutMs <= 0L) {
            delegate.streamChat(request, onChunk, onError);
            return;
        }
        final java.util.concurrent.atomic.AtomicReference<StreamStaleWatchdog> watchdogRef =
                new java.util.concurrent.atomic.AtomicReference<StreamStaleWatchdog>();
        final Consumer<Throwable> errorOnce = new Consumer<Throwable>() {
            private final java.util.concurrent.atomic.AtomicBoolean done =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            @Override
            public void accept(Throwable t) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                StreamStaleWatchdog w = watchdogRef.get();
                if (w != null) {
                    w.close();
                }
                if (onError != null) {
                    onError.accept(t);
                }
            }
        };
        final StreamStaleWatchdog watchdog = StreamStaleWatchdog
                .start(timeoutMs, policy.getStaleCheckIntervalMs(), new Runnable() {
                    @Override
                    public void run() {
                        errorOnce.accept(new StreamStaleWatchdog.StreamStaleException(
                                "[stream-stale] model=" + request.getModel() + " idle>" + timeoutMs + "ms"));
                    }
                });
        watchdogRef.set(watchdog);
        watchdog.arm();
        delegate.streamChat(request, new Consumer<ChatCompletionsResponse>() {
            @Override
            public void accept(ChatCompletionsResponse chunk) {
                watchdog.touch();
                if (watchdog.isTripped()) {
                    return;
                }
                if (chunk != null && chunk.getFinishReason() != null) {
                    // 收尾块到了：流已经自己走完，看门狗必须停表，不留线程
                    watchdog.close();
                }
                if (onChunk != null) {
                    onChunk.accept(chunk);
                }
            }
        }, errorOnce);
        if (watchdog.isTripped()) {
            watchdog.close();
        }
    }

    private static void closeWatchdog(StreamStaleWatchdog w) {
        if (w != null) {
            w.close();
        }
    }

    @Override
    public java.util.Map<String, Object> providerParams() {
        return delegate.providerParams();
    }

    /** 消息级错误分类（保留给既有调用点/测试）：走新表，等价于"会不会被再试一次"。 */
    static boolean isRetryable(Throwable e) {
        Decision d = LlmErrorClassifier.classify(e, null);
        return d.isRetryable();
    }

    /** 从异常里解析 Retry-After（毫秒）；解析不到返回 0。 */
    static long retryAfterMs(Throwable e) {
        return LlmErrorClassifier.retryAfterMs(e == null ? null : e.getMessage(), null);
    }

    private static ChatCompletionsRequest withModel(ChatCompletionsRequest request, String model) {
        return new ChatCompletionsRequest(model, request.getMessages(), request.getTools(),
                request.getTemperature(), request.getTopP(), request.getMaxTokens(),
                request.isStream(), request.getProviderParams());
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", e);
        }
    }

    /** 供测试/调试展示降级链。 */
    List<String> modelChain(String primaryModel) {
        List<String> out = new ArrayList<String>(fallbackModels.size() + 1);
        out.add(primaryModel);
        out.addAll(fallbackModels);
        return Collections.unmodifiableList(out);
    }

    @Override
    public String toString() {
        return "ResilientLlmProvider{" + delegate.name() + ", retries=" + maxRetries
                + ", backoffMs=" + backoffMs + ", fallbacks=" + Arrays.toString(fallbackModels.toArray())
                + ", policy=" + policy + "}";
    }
}
