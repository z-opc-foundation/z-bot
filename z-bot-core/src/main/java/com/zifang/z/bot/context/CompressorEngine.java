package com.zifang.z.bot.context;

import com.zifang.z.agent.kernel.agent.ContextEngine;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * kernel {@link ContextEngine} SPI 的默认实现 — hermes 式上下文压缩。
 *
 * <p><b>触发口径（P14 重做）</b>：可用窗口 = 模型窗口 − 留给回答的输出额度，
 * 观测到的上下文 ≥ 可用窗口 × {@code pct}（默认 0.50）才压。旧的 0.85 是「按预算上限」
 * 判的，等它响的时候请求本身已经快放不下了。</p>
 *
 * <p><b>三个评估位点</b>（不是一个）：轮首 {@link #evaluateAtTurnStart()}、
 * 建好请求但还没发出去时的粗估 {@link #evaluateBeforeApiCall(long)}、
 * 工具批跑完拿到真实 usage 后的 {@link #evaluateAfterToolBatch(int, int)}。
 * 每个位点各自计数（{@link #siteHits(int)}），所以「这个位点确实被调到了」是能被单测钉住的事实。</p>
 *
 * <p><b>不重复踩</b>：压了但没省下多少（{@link #MIN_EFFECTIVE_SAVING_TOKENS} 与
 * {@link #MIN_EFFECTIVE_SAVING_RATIO} 都不达标）算一次无效，连续 {@code maxIneffective}
 * 次就停手；摘要器抛异常/返空算失败，失败后进冷却。无效计数与冷却截止时刻都过
 * {@link CompressionGate} 落库 ⇒ 杀掉进程重启，冷却与防抖仍然生效。</p>
 *
 * <p><b>抢锁</b>：同会话（按血统根记）跨线程、跨进程只允许一个在压，锁的权威是
 * {@code compression_locks} 表；进程内的 {@code compressing} 只是省一次 sqlite 往返回的快速闸，
 * 它单独不构成正确性（真抢锁断言见 {@code CompressionEngineConcurrencyTest}）。</p>
 */
public class CompressorEngine implements ContextEngine {

    /** 默认触发比例：可用窗口的 50%（hermes 量级）。 */
    public static final double DEFAULT_THRESHOLD = 0.50;
    /** 压缩时原样保留的最近消息条数。 */
    public static final int DEFAULT_KEEP_RECENT = 8;
    /** 压缩失败后的冷却时长。 */
    public static final long DEFAULT_COOLDOWN_MILLIS = 120_000L;
    /** 连续无效压缩多少次之后停手。 */
    public static final int DEFAULT_MAX_INEFFECTIVE = 2;
    /** DB 压缩锁的 TTL：拿不到就是别的在飞，到点自动失效，不怕进程被 kill。 */
    public static final long DEFAULT_LOCK_TTL_MILLIS = 90_000L;
    /** 一次压缩至少要省下这么多 token 才算有效。 */
    public static final long MIN_EFFECTIVE_SAVING_TOKENS = 64L;
    /** 或者至少省下可用窗口的这个比例才算有效。 */
    public static final double MIN_EFFECTIVE_SAVING_RATIO = 0.05D;
    /** 估算口径：字符数 / 2（与 BotAgent 里请求体估算一致）。 */
    public static final int CHARS_PER_TOKEN = 2;

    /** 评估位点：轮首。 */
    public static final int SITE_TURN_START = 0;
    /** 评估位点：API 调用前（只有请求体粗估）。 */
    public static final int SITE_BEFORE_API_CALL = 1;
    /** 评估位点：工具批之后（拿到真实 usage）。 */
    public static final int SITE_AFTER_TOOL_BATCH = 2;
    /** 位点个数。 */
    public static final int SITE_COUNT = 3;
    /** 位点名（日志/自证用）。 */
    public static final String[] SITE_NAMES = {"turn-start", "pre-api-estimate", "post-tool-usage"};

    /** 摘要 prompt 的保留约束 — 路径/命令/代码/决策/承诺丢一条都不行。 */
    public static final String SUMMARY_PROMPT_PREFIX =
            "请把以下早前对话压缩成一段简洁摘要（要点式），必须完整保留：\n"
                    + "1) 出现过的所有文件路径、URL、命令；\n"
                    + "2) 关键代码片段、配置名、参数值；\n"
                    + "3) 已经做出的决策和对用户的承诺；\n"
                    + "4) 未完成的任务与下一步计划。\n"
                    + "只输出摘要本身，不要任何解释。\n\n=== 早前对话 ===\n";

    /** 跳过压缩的原因。 */
    public static final String SKIP_UNDER_LIMIT = "under-limit";
    public static final String SKIP_DISABLED = "disabled";
    public static final String SKIP_COOLDOWN = "cooldown";
    public static final String SKIP_DEBOUNCE = "debounce";
    public static final String SKIP_LOCK = "lock";
    public static final String SKIP_SHORT_HISTORY = "short-history";
    public static final String SKIP_EMPTY_SUMMARY = "empty-summary";
    public static final String SKIP_SUMMARIZER_ERROR = "summarizer-error";

    private final long contextWindow;
    private final int maxOutputTokens;
    private final double threshold;
    private final int keepRecent;
    private final long cooldownMillis;
    private final int maxIneffective;
    private final long lockTtlMillis;

    private final AtomicBoolean compressing = new AtomicBoolean(false);
    private final AtomicInteger compressCount = new AtomicInteger();
    private final AtomicInteger ineffectiveStreak = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicInteger[] siteHits = new AtomicInteger[SITE_COUNT];
    private final long[] observationAtSite = new long[SITE_COUNT];
    private final AtomicInteger lastSite = new AtomicInteger(-1);

    /** 最近一次真实 prompt tokens（LLM usage.promptTokens）。 */
    private volatile long contextTokens;
    /** 最近一次请求体粗估（只有真实 usage 之前的位点用它）。 */
    private volatile long coarseTokens;
    private volatile long cooldownUntilMillis;
    private volatile String lastSummary;
    private volatile int lastFromCount;
    private volatile int lastToCount;
    private volatile String lastSkipReason = SKIP_UNDER_LIMIT;
    private volatile boolean enabled = true;

    private volatile CompressionGate gate;
    private volatile Supplier<String> sessionIdSupplier;
    private volatile LongSupplier clock = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };

    public CompressorEngine(long contextWindow) {
        this(contextWindow, 0, DEFAULT_THRESHOLD, DEFAULT_KEEP_RECENT,
                DEFAULT_COOLDOWN_MILLIS, DEFAULT_MAX_INEFFECTIVE, DEFAULT_LOCK_TTL_MILLIS);
    }

    /** 兼容旧调用点的三参构造：把「预算上限」当窗口、输出额度记 0。 */
    public CompressorEngine(long contextWindow, double threshold, int keepRecent) {
        this(contextWindow, 0, threshold, keepRecent, DEFAULT_COOLDOWN_MILLIS,
                DEFAULT_MAX_INEFFECTIVE, DEFAULT_LOCK_TTL_MILLIS);
    }

    public CompressorEngine(long contextWindow, int maxOutputTokens, double threshold, int keepRecent,
                            long cooldownMillis, int maxIneffective, long lockTtlMillis) {
        this.contextWindow = Math.max(1L, contextWindow);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
        this.threshold = threshold <= 0D ? DEFAULT_THRESHOLD : Math.min(threshold, 1D);
        this.keepRecent = Math.max(2, keepRecent);
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.maxIneffective = Math.max(1, maxIneffective);
        this.lockTtlMillis = Math.max(1L, lockTtlMillis);
        for (int i = 0; i < SITE_COUNT; i++) {
            siteHits[i] = new AtomicInteger();
            observationAtSite[i] = -1L;
        }
    }

    // ===== 外部件接线 =====

    /** 挂上落盘侧（DB 抢锁 + 冷却/防抖入库），并把它已有的状态读回内存副本。 */
    public CompressorEngine withGate(CompressionGate gate, Supplier<String> sessionIdSupplier) {
        this.gate = gate;
        this.sessionIdSupplier = sessionIdSupplier;
        if (gate != null) {
            String sid = currentSessionId();
            this.cooldownUntilMillis = gate.loadCooldownUntilMillis(sid);
            this.ineffectiveStreak.set(gate.loadIneffectiveStreak(sid));
        }
        return this;
    }

    /** 测试用：换掉时钟以模拟冷却到点。 */
    public CompressorEngine withClock(LongSupplier clock) {
        if (clock != null) {
            this.clock = clock;
        }
        return this;
    }

    public CompressorEngine withEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ===== 阈值语义 =====

    /** 可用窗口 = 模型窗口 − 留给输出的额度（至少留 1，防配置成 0 时除不尽式地永远触发）。 */
    public long availableWindow() {
        return Math.max(1L, contextWindow - maxOutputTokens);
    }

    /** 触发线：可用窗口 × pct，向下取整且至少 1。这就是唯一的那道闸门公式。 */
    public long compressionLimit() {
        return Math.max(1L, (long) (availableWindow() * threshold));
    }

    /** 当前观测到的上下文规模：真实 usage 与请求体粗估里取更大的那个。 */
    public long observedTokens() {
        return Math.max(contextTokens, coarseTokens);
    }

    @Override
    public void onSessionStart() {
        contextTokens = 0;
        coarseTokens = 0;
    }

    /** kernel 约定：update(promptTokens, completionTokens)。prompt 即当前上下文规模的真实观测。 */
    @Override
    public void update(int promptTokens, int completionTokens) {
        if (promptTokens > 0) {
            contextTokens = promptTokens;
            coarseTokens = 0L;
        }
    }

    /** 按请求体字符数粗估（网关不回 usage 时的兜底口径）。 */
    public long estimateFromRequestChars(long requestChars) {
        return Math.max(0L, requestChars) / CHARS_PER_TOKEN;
    }

    @Override
    public boolean shouldCompress() {
        return decide(SKIP_UNDER_LIMIT);
    }

    // ===== 三个评估位点 =====

    /** 位点 1（轮首）：拿上一轮留下来的真实观测判，压不压由调用方决定。 */
    public boolean evaluateAtTurnStart() {
        return evaluate(SITE_TURN_START, observedTokens(), false);
    }

    /**
     * 位点 2（API 调用前）：请求已经拼好、还没发出去，只有字符数可估。
     * 粗估一旦越过触发线就必须压 —— 这一位点的作用正是「真实 usage 还没回来之前也要拦一道」。
     */
    public boolean evaluateBeforeApiCall(long requestChars) {
        long est = estimateFromRequestChars(requestChars);
        return evaluate(SITE_BEFORE_API_CALL, est, true);
    }

    /** 位点 3（工具批之后）：这一批工具往上下文里灌了多少真实 tokens。 */
    public boolean evaluateAfterToolBatch(int promptTokens, int completionTokens) {
        update(promptTokens, completionTokens);
        return evaluate(SITE_AFTER_TOOL_BATCH, observedTokens(), false);
    }

    /** 某个位点被调到了没有（>0 = 调到过）。 */
    public int siteHits(int site) {
        return site >= 0 && site < SITE_COUNT ? siteHits[site].get() : 0;
    }

    /**
     * 压缩的产物短到没有意义（省下 token 低于 {@link #effectivenessFloor()}）时返回原历史：
     * 换出去只会让血统里多一条「其实没省下来」的分叉，白搭一次摘要。
     */
    static boolean ineffectiveSaving(long saved, long floor) {
        return saved < floor;
    }

    /** 某个位点最近一次评估时看到的上下文规模（-1 = 还没看过）。 */
    public long observationAtSite(int site) {
        return site >= 0 && site < SITE_COUNT ? observationAtSite[site] : -1L;
    }

    public int lastEvaluatedSite() {
        return lastSite.get();
    }

    private boolean evaluate(int site, long observation, boolean coarseOnly) {
        siteHits[site].incrementAndGet();
        observationAtSite[site] = observation;
        lastSite.set(site);
        if (coarseOnly) {
            coarseTokens = Math.max(coarseTokens, observation);
        }
        return decide(SKIP_UNDER_LIMIT);
    }

    private boolean decide(String reasonIfUnder) {
        if (!enabled) {
            lastSkipReason = SKIP_DISABLED;
            return false;
        }
        if (coolingDown()) {
            lastSkipReason = SKIP_COOLDOWN;
            return false;
        }
        if (ineffectiveStreak.get() >= maxIneffective) {
            lastSkipReason = SKIP_DEBOUNCE;
            return false;
        }
        if (observedTokens() < compressionLimit()) {
            lastSkipReason = reasonIfUnder;
            return false;
        }
        lastSkipReason = null;
        return true;
    }

    // ===== 压缩本体 =====

    @Override
    public List<Msg> compress(List<Msg> history, Summarizer summarizer) {
        if (history == null) {
            return null;
        }
        if (!shouldCompress()) {
            return history;
        }
        return doCompress(history, summarizer, true);
    }

    /** 手动 /compress：绕过阈值与防抖/冷却（用户就是要压），但抢锁与最短长度约束仍然生效。 */
    public List<Msg> forceCompress(List<Msg> history, Summarizer summarizer) {
        if (history == null) {
            return null;
        }
        return doCompress(history, summarizer, false);
    }

    private List<Msg> doCompress(List<Msg> history, Summarizer summarizer, boolean gated) {
        // 进程内快速闸：同实例并发的第二个请求连 sqlite 都不用碰
        if (!compressing.compareAndSet(false, true)) {
            lastSkipReason = SKIP_LOCK;
            return history;
        }
        String sid = currentSessionId();
        CompressionGate g = this.gate;
        boolean holdsDbLock = false;
        try {
            if (gated && cooldownMillis > 0L && g != null) {
                cooldownUntilMillis = g.loadCooldownUntilMillis(sid);
            }
            if (gated && cooldownMillis > 0L && coolingDown()) {
                lastSkipReason = SKIP_COOLDOWN;
                return history;
            }
            if (gated && g != null && maxIneffective > 0) {
                ineffectiveStreak.set(g.loadIneffectiveStreak(sid));
                if (ineffectiveStreak.get() >= maxIneffective) {
                    lastSkipReason = SKIP_DEBOUNCE;
                    return history;
                }
            }
            // DB 抢锁（权威）：多进程/多线程只有拿到这把锁的才真压
            if (g != null) {
                holdsDbLock = g.tryAcquire(sid, null, lockTtlMillis);
                if (!holdsDbLock) {
                    lastSkipReason = SKIP_LOCK;
                    return history;
                }
            }
            if (history.size() <= keepRecent + 1) {
                lastSkipReason = SKIP_SHORT_HISTORY;
                return history;
            }
            int cut = history.size() - keepRecent;
            List<Msg> middle = new ArrayList<Msg>(history.subList(0, cut));
            List<Msg> recent = new ArrayList<Msg>(history.subList(cut, history.size()));

            String summary;
            try {
                summary = summarizer.summarize(middle);
            } catch (RuntimeException e) {
                registerFailure(gated);
                lastSkipReason = SKIP_SUMMARIZER_ERROR;
                return history;
            }
            if (summary == null || summary.trim().isEmpty()) {
                registerFailure(gated);
                lastSkipReason = SKIP_EMPTY_SUMMARY;
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

            long saved = Math.max(0L, tokensOf(history) - tokensOf(out));
            compressCount.incrementAndGet();
            lastSummary = summary.trim();
            lastFromCount = history.size();
            lastToCount = out.size();
            // 压完就是压完了：观测值当场落到压缩后的水位，
            // 否则下一轮还会按压缩前的规模再判一次「该压」，白烧一次摘要调用。
            contextTokens = Math.max(0L, observedTokens() - saved);
            coarseTokens = 0L;
            boolean ineffective = ineffectiveSaving(saved, effectivenessFloor());
            if (ineffective) {
                ineffectiveStreak.incrementAndGet();
            } else {
                ineffectiveStreak.set(0);
                cooldownUntilMillis = 0L;
            }
            if (g != null) {
                g.storeIneffectiveStreak(sid, ineffectiveStreak.get());
                g.storeCooldownUntilMillis(sid, 0L);
            }
            lastSkipReason = null;
            return ineffective ? history : out;
        } finally {
            if (holdsDbLock && g != null) {
                g.release(sid, null);
            }
            compressing.set(false);
        }
    }

    /** 有效性的下限：绝对值与「可用窗口 × pct 的比例」取更大的那个。 */
    public long effectivenessFloor() {
        return Math.max(MIN_EFFECTIVE_SAVING_TOKENS, (long) (compressionLimit() * MIN_EFFECTIVE_SAVING_RATIO));
    }

    private void registerFailure(boolean gated) {
        failureCount.incrementAndGet();
        if (cooldownMillis > 0L) {
            long until = clock.getAsLong() + cooldownMillis;
            if (!gated || until > cooldownUntilMillis) {
                cooldownUntilMillis = until;
            }
            CompressionGate g = this.gate;
            if (g != null) {
                g.storeCooldownUntilMillis(currentSessionId(), cooldownUntilMillis);
            }
        }
    }

    /** 还在冷却里吗（冷却截止时刻来自库，重启也认）。 */
    public boolean coolingDown() {
        return cooldownUntilMillis > clock.getAsLong();
    }

    /** 冷却还剩多少毫秒（0 = 没在冷却）。 */
    public long cooldownRemainingMillis() {
        long left = cooldownUntilMillis - clock.getAsLong();
        return left <= 0L ? 0L : left;
    }

    private static long tokensOf(List<Msg> msgs) {
        long chars = 0L;
        for (Msg m : msgs) {
            if (m != null && m.getContent() != null) {
                chars += m.getContent().length();
            }
        }
        return chars / CHARS_PER_TOKEN;
    }

    private String currentSessionId() {
        Supplier<String> s = sessionIdSupplier;
        String id = s == null ? null : s.get();
        return id == null ? "zbot-compression-default" : id;
    }

    /** 自证面：当前生效的口径全打出来（pct 是否真从配置流进来、锁是不是走库、冷却还剩多久）。 */
    public String describe() {
        CompressionGate g = this.gate;
        return "contextWindow=" + contextWindow + " maxOutputTokens=" + maxOutputTokens
                + " availableWindow=" + availableWindow() + " pct=" + threshold
                + " limit=" + compressionLimit() + " observed=" + observedTokens()
                + " (real=" + contextTokens + ", coarse=" + coarseTokens + ")"
                + " keepRecent=" + keepRecent + " enabled=" + enabled
                + " lockBackend=" + (g == null ? "memory-only" : "compression_locks")
                + " cooldownMillis=" + cooldownMillis + " cooldownLeft=" + cooldownRemainingMillis()
                + " ineffectiveStreak=" + ineffectiveStreak.get() + "/" + maxIneffective
                + " compressCount=" + compressCount.get() + " failures=" + failureCount.get()
                + " lastSkip=" + lastSkipReason
                + " siteHits=" + siteHits[SITE_TURN_START].get() + "/"
                + siteHits[SITE_BEFORE_API_CALL].get() + "/" + siteHits[SITE_AFTER_TOOL_BATCH].get();
    }

    public long getContextTokens() {
        return contextTokens;
    }

    public int getCompressCount() {
        return compressCount.get();
    }

    public int getIneffectiveStreak() {
        return ineffectiveStreak.get();
    }

    public int getFailureCount() {
        return failureCount.get();
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
        return contextWindow;
    }

    public long getThreshold() {
        return contextWindow;
    }

    public double getPct() {
        return threshold;
    }

    public String getLastSkipReason() {
        return lastSkipReason;
    }
}
