package com.zifang.z.bot.llm;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P26：流式陈旧看门狗（对齐 hermes {@code reasoning_timeouts.py} 的**语义**：
 * 阈值是 {@code max(default, floor)}，且**用户显式配置永远优先、floor 永不压低现状**）。
 *
 * <p>它盯的是"两次进度之间隔了多久"（{@link #touch()}）：一旦超过阈值，
 * 只触发**一次** {@code onStale}（由 {@link ResilientLlmProvider} 用来端掉这条流并抛错），
 * 然后自杀式关闭自己的定时器线程 ⇒ 不留挂死线程。</p>
 */
public final class StreamStaleWatchdog {

    /** 看门狗触发时抛出的异常类型（供上层与 E2E 断言识别）。 */
    public static final class StreamStaleException extends RuntimeException {
        public StreamStaleException(String message) {
            super(message);
        }
    }

    private static final AtomicLong INSTANCE_SEQ = new AtomicLong();

    private final long timeoutMs;
    private final long checkIntervalMs;
    private final Runnable onStale;
    private final AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> task;

    private StreamStaleWatchdog(long timeoutMs, long checkIntervalMs, Runnable onStale) {
        this.timeoutMs = timeoutMs;
        this.checkIntervalMs = checkIntervalMs;
        this.onStale = onStale;
        this.timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "z-llm-stale-watchdog-" + INSTANCE_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * 阈值解析：显式配置 &gt; {@code max(default, floor(model))}。
     * 返回 0 表示关闭看门狗（显式配 0/负数）。
     */
    public static long resolveTimeoutMs(RetryPolicyConfig policy, String modelId) {
        if (policy == null) {
            return RetryPolicyConfig.DEFAULT_STREAM_STALE_MS;
        }
        if (policy.isStreamStaleTimeoutExplicit()) {
            return Math.max(0L, policy.getStreamStaleTimeoutMs());
        }
        long floor = 0L;
        List<RetryPolicyConfig.StaleFloor> floors = policy.getStaleFloors();
        for (RetryPolicyConfig.StaleFloor f : floors) {
            floor = Math.max(floor, f.floorMsFor(modelId));
        }
        return Math.max(policy.getStreamStaleTimeoutMs(), floor);
    }

    /** 起一条看门狗；{@code timeoutMs <= 0} 时返回一个永不触发的空实现（零线程）。 */
    public static StreamStaleWatchdog start(long timeoutMs, long checkIntervalMs, Runnable onStale) {
        return new StreamStaleWatchdog(timeoutMs, Math.max(10L, checkIntervalMs), onStale);
    }

    /** 起看门狗并按 {@code max(default, floor)} 解析阈值。 */
    public static StreamStaleWatchdog startForModel(RetryPolicyConfig policy, String modelId, Runnable onStale) {
        long timeout = resolveTimeoutMs(policy, modelId);
        long interval = policy == null ? RetryPolicyConfig.DEFAULT_STALE_CHECK_INTERVAL_MS
                : policy.getStaleCheckIntervalMs();
        return start(timeout, interval, onStale);
    }

    /** 报告一次进度（收到 chunk / 收到 done）。 */
    public void touch() {
        lastProgressNanos.set(System.nanoTime());
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isTripped() {
        return tripped.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 开始巡检（{@link #touch()} 之前也计时，起点即 start 时刻）。 */
    public StreamStaleWatchdog arm() {
        if (timeoutMs <= 0L) {
            return this;
        }
        final long limit = timeoutMs;
        task = timer.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    return;
                }
                long idle = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastProgressNanos.get());
                if (idle >= limit && tripped.compareAndSet(false, true)) {
                    try {
                        if (onStale != null) {
                            onStale.run();
                        }
                    } catch (RuntimeException ignored) {
                        // 回调炸了也不能让定时器线程活着
                    } finally {
                        close();
                    }
                }
            }
        }, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        return this;
    }

    /** 停表并释放线程（幂等）。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (task != null) {
            task.cancel(false);
        }
        timer.shutdownNow();
    }

    /** 等自己的定时器线程真退出（用于"不留挂死线程"取证）。 */
    public boolean awaitTermination(long ms) {
        try {
            return timer.awaitTermination(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
