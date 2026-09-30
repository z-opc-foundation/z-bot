package com.zifang.z.bot.llm;

import com.zifang.z.bot.config.BotConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * P26：重试/退避/看门狗的策略配置（不可变值对象）。
 *
 * <p>为什么不进 {@code config/BotConfig.java}：另有两支写手在改 BotConfig，
 * 本棒的键先在自家落地，等主线并网后再统一收口（见 _doc/005_testing/acceptance/p26/EVIDENCE.md §11）。</p>
 *
 * <p>读取顺序：显式 {@link Properties} &gt; {@code <configDir>/config.properties} &gt;
 * {@link BotConfig} 既有 getter（{@code retry.max.attempts} / {@code retry.backoff.ms} 复用，不抄第二份）&gt; 内置默认。</p>
 *
 * <p>键表（全）：</p>
 * <ul>
 *   <li>{@code llm.retry.base-delay-ms}（默认 500）— 指数档首值</li>
 *   <li>{@code llm.retry.max-delay-ms}（默认 120000）— 单次等待绝对上限：短档指数封顶，也是限流长档的封顶</li>
 *   <li>{@code llm.retry.jitter-ratio}（默认 0.5）— jitter ~ uniform[0, ratio*delay]</li>
 *   <li>{@code llm.retry.short-attempts}（默认 3）— 走几步短档后切限流长档</li>
 *   <li>{@code llm.retry.rate-limit-ladder-ms}（默认 "30000,60000,90000,120000"）— 限流长档表</li>
 *   <li>{@code llm.retry.retry-after-cap-ms}（默认 120000）— 上游 Retry-After 的采信上限</li>
 *   <li>{@code llm.retry.total-budget-ms}（默认 90000）— 单请求重试总预算，超了不再退避</li>
 *   <li>{@code llm.stream.stale-timeout-ms}（默认 180000）— 流式陈旧看门狗默认值</li>
 *   <li>{@code llm.stream.stale-timeout-floors}（默认见 {@link #DEFAULT_STALE_FLOORS}）— 分档下限表</li>
 *   <li>{@code llm.stream.stale-check-interval-ms}（默认 500）— 看门狗巡检周期</li>
 * </ul>
 */
public final class RetryPolicyConfig {

    /** 内置默认（EVIDENCE §2 给出这些数的实测依据）。 */
    public static final long DEFAULT_BASE_DELAY_MS = 500L;
    /** 单次等待的绝对上限：短档指数封顶 + 限流长档封顶（对齐 hermes {@code max_delay=120s}）。 */
    public static final long DEFAULT_MAX_DELAY_MS = 120_000L;
    public static final double DEFAULT_JITTER_RATIO = 0.5d;
    public static final int DEFAULT_SHORT_ATTEMPTS = 3;
    public static final String DEFAULT_RATE_LADDER = "30000,60000,90000,120000";
    public static final long DEFAULT_RETRY_AFTER_CAP_MS = 120_000L;
    public static final long DEFAULT_TOTAL_BUDGET_MS = 90_000L;
    public static final long DEFAULT_STREAM_STALE_MS = 180_000L;
    public static final long DEFAULT_STALE_CHECK_INTERVAL_MS = 500L;
    /** {@code slug 正则=秒} 逗号分隔；命中即作为**下限**（{@code max(default, floor)}），永不压低。 */
    public static final String DEFAULT_STALE_FLOORS =
            "^(?:o1|o3|qwq|r1|deepseek-r1|glm-z1|qwen3|nemotron)(?:[-_.\\d].*)?=300,"
                    + ".*(?:thinking|reasoner|nemo|preview).*|=240";

    private static final String[] MIRROR_ENV = new String[] {"ZBOT_HOME"};

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterRatio;
    private final int shortAttempts;
    private final List<Long> rateLimitLadderMs;
    private final long retryAfterCapMs;
    private final long totalBudgetMs;
    private final long streamStaleTimeoutMs;
    private final long staleCheckIntervalMs;
    private final List<StaleFloor> staleFloors;
    /** 用户是否**显式**配了 {@code llm.stream.stale-timeout-ms}：显式配了 ⇒ floor 不得覆盖（hermes 语义）。 */
    private boolean streamStaleTimeoutExplicit;

    private RetryPolicyConfig(int maxAttempts, long baseDelayMs, long maxDelayMs, double jitterRatio,
                             int shortAttempts, List<Long> ladder, long retryAfterCapMs, long totalBudgetMs,
                             long streamStaleMs, long checkIntervalMs, List<StaleFloor> floors) {
        this.maxAttempts = Math.max(0, maxAttempts);
        this.baseDelayMs = Math.max(0L, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.jitterRatio = clampRatio(jitterRatio);
        this.shortAttempts = Math.max(1, shortAttempts);
        this.rateLimitLadderMs = Collections.unmodifiableList(new ArrayList<Long>(ladder));
        this.retryAfterCapMs = Math.max(0L, retryAfterCapMs);
        this.totalBudgetMs = Math.max(0L, totalBudgetMs);
        this.streamStaleTimeoutMs = Math.max(0L, streamStaleMs);
        this.staleCheckIntervalMs = Math.max(10L, checkIntervalMs);
        this.staleFloors = Collections.unmodifiableList(new ArrayList<StaleFloor>(floors));
    }

    private static double clampRatio(double r) {
        if (Double.isNaN(r) || r < 0d) {
            return 0d;
        }
        return r > 1d ? 1d : r;
    }

    /** 只读默认：{@code ~/.zbot}（或 {@code ZBOT_HOME}）下的 config.properties；读不到就全默认。 */
    public static RetryPolicyConfig defaults() {
        return of(null, null, null);
    }

    /** 从 BotConfig（可空）+ 显式 Properties（可空）构造。 */
    public static RetryPolicyConfig of(BotConfig config, Properties props) {
        return of(config, props, null);
    }

    /** 从 BotConfig + 显式 Properties + 配置目录构造。 */
    public static RetryPolicyConfig of(BotConfig config, Properties props, File configDir) {
        Properties p = props != null ? props : readFromFile(configDir);
        int attempts = config != null ? config.getRetryMaxAttempts() : 0;
        long backoff = config != null ? config.getRetryBackoffMs() : DEFAULT_BASE_DELAY_MS;
        RetryPolicyConfig out = new RetryPolicyConfig(
                intOf(p, "llm.retry.max.attempts", attempts),
                longOf(p, "llm.retry.base-delay-ms", backoff <= 0 ? DEFAULT_BASE_DELAY_MS : backoff),
                longOf(p, "llm.retry.max-delay-ms", DEFAULT_MAX_DELAY_MS),
                doubleOf(p, "llm.retry.jitter-ratio", DEFAULT_JITTER_RATIO),
                intOf(p, "llm.retry.short-attempts", DEFAULT_SHORT_ATTEMPTS),
                longListOf(p, "llm.retry.rate-limit-ladder-ms", DEFAULT_RATE_LADDER),
                longOf(p, "llm.retry.retry-after-cap-ms", DEFAULT_RETRY_AFTER_CAP_MS),
                longOf(p, "llm.retry.total-budget-ms", DEFAULT_TOTAL_BUDGET_MS),
                longOf(p, "llm.stream.stale-timeout-ms", DEFAULT_STREAM_STALE_MS),
                longOf(p, "llm.stream.stale-check-interval-ms", DEFAULT_STALE_CHECK_INTERVAL_MS),
                StaleFloor.parse(stringOf(p, "llm.stream.stale-timeout-floors", DEFAULT_STALE_FLOORS)));
        String explicitStale = p == null ? null : p.getProperty("llm.stream.stale-timeout-ms");
        out.streamStaleTimeoutExplicit = explicitStale != null && !explicitStale.trim().isEmpty();
        return out;
    }

    /** 用户有没有显式配过 {@code llm.stream.stale-timeout-ms}。 */
    public boolean isStreamStaleTimeoutExplicit() {
        return streamStaleTimeoutExplicit;
    }

    private static Properties readFromFile(File configDir) {
        Properties props = new Properties();
        File dir = configDir;
        if (dir == null) {
            for (String env : MIRROR_ENV) {
                String v = System.getenv(env);
                if (v != null && !v.trim().isEmpty()) {
                    dir = new File(v.trim());
                    break;
                }
            }
        }
        File file = dir == null ? null : new File(dir, "config.properties");
        if (file == null || !file.isFile()) {
            return props;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            props.load(in);
        } catch (IOException ignored) {
            // 读不到配置 ⇒ 全默认，绝不因为策略配置缺失把主流程打挂。
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        return props;
    }

    private static String stringOf(Properties p, String key, String def) {
        String v = p == null ? null : p.getProperty(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static long longOf(Properties p, String key, long def) {
        String v = stringOf(p, key, null);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intOf(Properties p, String key, int def) {
        long v = longOf(p, key, def);
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    private static double doubleOf(Properties p, String key, double def) {
        String v = stringOf(p, key, null);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<Long> longListOf(Properties p, String key, String def) {
        String v = stringOf(p, key, def);
        List<Long> out = new ArrayList<Long>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.valueOf(Long.parseLong(t)));
            } catch (NumberFormatException e) {
                // 一个坏项不影响其余档位
            }
        }
        return out;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public double getJitterRatio() {
        return jitterRatio;
    }

    public int getShortAttempts() {
        return shortAttempts;
    }

    public List<Long> getRateLimitLadderMs() {
        return rateLimitLadderMs;
    }

    public long getRetryAfterCapMs() {
        return retryAfterCapMs;
    }

    public long getTotalBudgetMs() {
        return totalBudgetMs;
    }

    public long getStreamStaleTimeoutMs() {
        return streamStaleTimeoutMs;
    }

    public long getStaleCheckIntervalMs() {
        return staleCheckIntervalMs;
    }

    public List<StaleFloor> getStaleFloors() {
        return staleFloors;
    }

    /** 覆盖总预算，用于把阶梯压成常数做确定性测试（杠①/杠③ 用它设上限时间）。 */
    public RetryPolicyConfig withTotalBudgetMs(long budget) {
        return new RetryPolicyConfig(maxAttempts, baseDelayMs, maxDelayMs, jitterRatio, shortAttempts,
                rateLimitLadderMs, retryAfterCapMs, budget, streamStaleTimeoutMs, staleCheckIntervalMs,
                staleFloors);
    }

    /**
     * 兼容层：老的 4 参构造只给了一个 {@code backoffMs}，语义是"所有等待都在这个量级内"。
     * 用它构造时把指数上限、限流长档、Retry-After 采信一并压到 {@code 4*backoffMs} 以内，
     * 这样既有单测不会被 30s/60s 的限流长档拖爆（EVIDENCE §2 记这条"怎么把阶梯压成常数"）。
     */
    public RetryPolicyConfig withBaseDelayMs(long base) {
        long cap = Math.max(1L, base) * 4L;
        RetryPolicyConfig out = new RetryPolicyConfig(maxAttempts, base, cap, jitterRatio, shortAttempts,
                rateLimitLadderMs, cap, totalBudgetMs, streamStaleTimeoutMs, staleCheckIntervalMs, staleFloors);
        return out;
    }

    public RetryPolicyConfig withJitterRatio(double ratio) {
        return new RetryPolicyConfig(maxAttempts, baseDelayMs, maxDelayMs, ratio, shortAttempts,
                rateLimitLadderMs, retryAfterCapMs, totalBudgetMs, streamStaleTimeoutMs,
                staleCheckIntervalMs, staleFloors);
    }

    /** 显式设流式陈旧阈值：一旦走这个口子就等于"用户配过"，floor 不得再覆盖。 */
    public RetryPolicyConfig withStreamStaleTimeoutMsExplicit(long ms) {
        RetryPolicyConfig out = withStreamStaleTimeoutMs(ms);
        out.streamStaleTimeoutExplicit = true;
        return out;
    }

    public RetryPolicyConfig withStreamStaleTimeoutMs(long ms) {
        return new RetryPolicyConfig(maxAttempts, baseDelayMs, maxDelayMs, jitterRatio, shortAttempts,
                rateLimitLadderMs, retryAfterCapMs, totalBudgetMs, ms, staleCheckIntervalMs, staleFloors);
    }

    @Override
    public String toString() {
        return "RetryPolicyConfig{attempts=" + maxAttempts + ", base=" + baseDelayMs + "ms, max=" + maxDelayMs
                + "ms, jitter=" + jitterRatio + ", short=" + shortAttempts + ", ladder=" + rateLimitLadderMs
                + ", retryAfterCap=" + retryAfterCapMs + "ms, budget=" + totalBudgetMs + "ms, streamStale="
                + streamStaleTimeoutMs + "ms, floors=" + staleFloors.size() + "}";
    }

    /** 一条分档下限：slug 正则 → 秒。仅用于 {@code max(default, floor)} 的**下限**侧。 */
    public static final class StaleFloor {
        private final Pattern slug;
        private final long seconds;

        StaleFloor(Pattern slug, long seconds) {
            this.slug = slug;
            this.seconds = seconds;
        }

        public long getSeconds() {
            return seconds;
        }

        public String pattern() {
            return slug.pattern();
        }

        /** 命中返回下限毫秒，否则返回 0（0 = 不生效，交给 default）。 */
        public long floorMsFor(String modelSlug) {
            return modelSlug != null && slug.matcher(slugStripPrefix(modelSlug)).matches() ? seconds * 1000L : 0L;
        }

        /** 剥掉聚合器前缀（{@code openai/o3-mini} ⇒ {@code o3-mini}），与 hermes 的 slug-only 匹配同义。 */
        static String slugStripPrefix(String model) {
            int i = model.lastIndexOf('/');
            return i >= 0 && i + 1 < model.length() ? model.substring(i + 1) : model;
        }

        static List<StaleFloor> parse(String spec) {
            List<StaleFloor> out = new ArrayList<StaleFloor>();
            for (String item : spec.split(",")) {
                String t = item.trim();
                if (t.isEmpty()) {
                    continue;
                }
                int eq = t.lastIndexOf('=');
                if (eq <= 0 || eq == t.length() - 1) {
                    continue;
                }
                try {
                    long sec = Long.parseLong(t.substring(eq + 1).trim());
                    out.add(new StaleFloor(Pattern.compile(t.substring(0, eq).trim()), sec));
                } catch (RuntimeException e) {
                    // 坏项跳过：正则炸了不能让看门狗失效
                }
            }
            return out;
        }

        @Override
        public String toString() {
            return slug.pattern() + "=" + seconds + "s";
        }
    }
}
