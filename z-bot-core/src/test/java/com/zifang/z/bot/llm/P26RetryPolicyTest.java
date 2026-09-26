package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.llm.support.LlmException;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.llm.LlmErrorClassifier.Decision;
import com.zifang.z.bot.llm.LlmErrorClassifier.Evidence;
import com.zifang.z.bot.llm.LlmErrorClassifier.FailureClass;
import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P26 §1/§2/§3/§4：错误分类表、抖动退避与限流阶梯、换模型重建上下文、流式陈旧看门狗。
 *
 * <p>每个 @Test 的名字里带它钉的**规则名/档位名**，杠② 变异按这些名字点期望红的用例。</p>
 *
 * <p>时间纪律：所有退避用例都用 {@code jitterSource=0.0} + 极小的 base，断言的是
 * {@link JitteredBackoff#observedWaits()} 记录的**等待序列**，不是墙钟。</p>
 */
public class P26RetryPolicyTest {

    // ===== §1 分类表 =====

    @Test
    public void classifier_rule_http429_structuredStatusIsRateLimit() {
        Decision d = LlmErrorClassifier.classify(new LlmException("openai", 429, "slow down"));
        assertEquals(FailureClass.RATE_LIMIT, d.getFailureClass());
        assertEquals("http:429", d.getRule());
        assertEquals(Evidence.HTTP_STATUS, d.getEvidence());
        assertEquals(Integer.valueOf(429), d.getHttpStatus());
    }

    @Test
    public void classifier_rule_http401_402_403_isKeyScoped() {
        for (int code : new int[] {401, 402, 403}) {
            Decision d = LlmErrorClassifier.classify(new LlmException("openai", code, "nope"));
            assertEquals("code=" + code, FailureClass.ROTATE_KEY, d.getFailureClass());
            assertEquals("http:401/402/403", d.getRule());
        }
    }

    @Test
    public void classifier_rule_http5xx_isRetrySameKey() {
        Decision d = LlmErrorClassifier.classify(new LlmException("openai", 503, "busy"));
        assertEquals(FailureClass.RETRY_SAME_KEY, d.getFailureClass());
        assertEquals("http:5xx", d.getRule());
    }

    @Test
    public void classifier_rule_http404Model_vs_404Path() {
        assertEquals(FailureClass.FALLBACK_MODEL,
                LlmErrorClassifier.classify(new LlmException("openai", 404,
                        "{\"error\":{\"code\":\"model_not_found\"}}")).getFailureClass());
        assertEquals("http:404-model", LlmErrorClassifier.classify(new LlmException("openai", 404,
                "{\"error\":{\"code\":\"model_not_found\"}}")).getRule());
        Decision path = LlmErrorClassifier.classify(new LlmException("openai", 404, "{\"detail\":\"no route\"}"));
        assertEquals(FailureClass.FATAL, path.getFailureClass());
        assertEquals("http:404-path", path.getRule());
    }

    @Test
    public void classifier_rule_http4xxFatal_badRequest() {
        Decision d = LlmErrorClassifier.classify(new LlmException("openai", 400, "bad json"));
        assertEquals(FailureClass.FATAL, d.getFailureClass());
        assertEquals("http:4xx-fatal", d.getRule());
    }

    /** 结构化字段优先：状态码是 400，正文里写满 "rate limit / 429" 也不能被带偏。 */
    @Test
    public void classifier_structuredStatusBeatsMessageText() {
        Decision d = LlmErrorClassifier.classify(new LlmException("openai", 400,
                "HTTP 429 rate limit too many requests"));
        assertEquals(FailureClass.FATAL, d.getFailureClass());
        assertEquals(Evidence.HTTP_STATUS, d.getEvidence());
        assertEquals(Integer.valueOf(400), d.getHttpStatus());
    }

    /** 老实现的决定性假阳性：正文里出现裸 "429" 就判成限流。新表必须不判。 */
    @Test
    public void classifier_bare429InProseIsNotRateLimit() {
        RuntimeException e = new RuntimeException("请把这份报告归档到工单 429 里，然后回复用户");
        Decision d = LlmErrorClassifier.classify(e);
        assertFalse("裸数字 429 不得判成限流", d.isRetryable());
        assertEquals(FailureClass.FATAL, d.getFailureClass());
        assertEquals("unclassified", d.getRule());
        // 老码的等价断言（证明这条差异真的存在）：(?i)(429|...) 会命中
        assertTrue(java.util.regex.Pattern.compile("(?i)(429|http\\s*5\\d\\d|\\b5\\d\\d\\b)").matcher(
                e.getMessage()).find());
    }

    @Test
    public void classifier_bare500InProseIsNotRetryable() {
        Decision d = LlmErrorClassifier.classify(new RuntimeException("这台机器有 500 个用户"));
        assertEquals(FailureClass.FATAL, d.getFailureClass());
        assertNull(d.getHttpStatus());
    }

    /** 带协议标签的状态码仍然有效（既有测试与真 provider 的消息形态都是这一类）。 */
    @Test
    public void classifier_labeledHttpStatusStillWorks() {
        assertEquals(FailureClass.RATE_LIMIT,
                LlmErrorClassifier.classify(new RuntimeException("HTTP 429 Too Many Requests")).getFailureClass());
        assertEquals(FailureClass.RETRY_SAME_KEY,
                LlmErrorClassifier.classify(new RuntimeException("status=503 upstream")).getFailureClass());
    }

    @Test
    public void classifier_rule_typeSocketTimeout() {
        Decision d = LlmErrorClassifier.classify(new IllegalStateException("wrapped",
                new SocketTimeoutException("read timed out")));
        assertEquals(FailureClass.RETRY_SAME_KEY, d.getFailureClass());
        assertEquals("type:socket-timeout", d.getRule());
        assertEquals(Evidence.EXCEPTION_TYPE, d.getEvidence());
    }

    @Test
    public void classifier_rule_typeConnect() {
        Decision d = LlmErrorClassifier.classify(new LlmException("openai", "postJson I/O failed",
                new ConnectException("Connection refused")));
        assertEquals(FailureClass.RETRY_SAME_KEY, d.getFailureClass());
        assertEquals("type:connect", d.getRule());
    }

    @Test
    public void classifier_rule_msgModelScoped_contextLength() {
        Decision d = LlmErrorClassifier.classify(new RuntimeException(
                "This model's maximum context length is 8192 tokens, your input is too long"));
        assertEquals(FailureClass.FALLBACK_MODEL, d.getFailureClass());
        assertEquals("msg:model-scoped", d.getRule());
    }

    @Test
    public void classifier_rule_unclassifiedIsFatal_andInterruptToo() {
        assertEquals("unclassified", LlmErrorClassifier.classify(new RuntimeException("boom")).getRule());
        assertEquals("interrupt", LlmErrorClassifier.classify(new RuntimeException(new InterruptedException()))
                .getRule());
        assertEquals("unclassified", LlmErrorClassifier.classify(null).getRule());
    }

    @Test
    public void classifier_retryAfter_isParsedOnlyFromLabeledForms_andCapped() {
        java.util.Properties capRaw = new java.util.Properties();
        capRaw.setProperty("llm.retry.retry-after-cap-ms", "20000");
        RetryPolicyConfig cap5s = RetryPolicyConfig.of(null, capRaw);
        assertEquals(2000L, ResilientLlmProvider.retryAfterMs(new RuntimeException("HTTP 429, Retry-After: 2")));
        assertEquals(0L, ResilientLlmProvider.retryAfterMs(new RuntimeException("429")));
        Decision d = LlmErrorClassifier.classify(
                new RuntimeException("HTTP 429 retry after 600 seconds"), cap5s);
        assertEquals(20000L, d.getRetryAfterMs());
    }

    /** KeyPool 侧：老正则里的 {@code exceeded} 会把"上下文超长"误判成 key 失效并冷却好 key。 */
    @Test
    public void classifier_keyPoolDoesNotRotateKeyOnContextLength() {
        assertFalse(KeyPoolLlmProvider.isKeyScoped(new RuntimeException(
                "This model's maximum context length is exceeded")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("401 Unauthorized")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("429 rate limit exceeded")));
        assertFalse(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("500 internal error")));
    }

    // ===== §2 抖动与阶梯 =====

    private static RetryPolicyConfig policy(long base, long max, double jitter, int shortAttempts, String ladder) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("llm.retry.base-delay-ms", String.valueOf(base));
        p.setProperty("llm.retry.max-delay-ms", String.valueOf(max));
        p.setProperty("llm.retry.jitter-ratio", String.valueOf(jitter));
        p.setProperty("llm.retry.short-attempts", String.valueOf(shortAttempts));
        p.setProperty("llm.retry.rate-limit-ladder-ms", ladder);
        return RetryPolicyConfig.of(null, p);
    }

    @Test
    public void backoff_exponentialNotLinear_whenJitterZero() {
        JitteredBackoff b = new JitteredBackoff(policy(100, 100000, 0.0d, 3, "1000,2000"), zero());
        assertEquals(100L, b.exponential(1));
        assertEquals(200L, b.exponential(2));
        assertEquals(400L, b.exponential(3));
        assertEquals(800L, b.exponential(4));
        assertEquals(Arrays.asList(100L, 200L, 400L, 800L), waits(b, FailureClass.RETRY_SAME_KEY, 4));
    }

    @Test
    public void backoff_exponentialIsCappedByMaxDelay() {
        JitteredBackoff b = new JitteredBackoff(policy(100, 350, 0.0d, 3, "1000"), zero());
        assertEquals(350L, b.exponential(3));
        assertEquals(350L, b.exponential(9));
    }

    @Test
    public void backoff_jitterStaysWithinRatio() {
        // ratio=0.5, source=0.4 ⇒ 100 + 0.5*100*0.4 = 120
        JitteredBackoff b = new JitteredBackoff(policy(100, 100000, 0.5d, 3, "5000"), constant(0.4d));
        assertEquals(120L, b.nextDelayMs(FailureClass.RETRY_SAME_KEY, 1, 0L));
        JitteredBackoff hi = new JitteredBackoff(policy(100, 100000, 0.5d, 3, "5000"), constant(0.999d));
        assertEquals(149L, hi.nextDelayMs(FailureClass.RETRY_SAME_KEY, 1, 0L));
        JitteredBackoff lo = new JitteredBackoff(policy(100, 100000, 0.5d, 3, "5000"), constant(0.0d));
        assertEquals(100L, lo.nextDelayMs(FailureClass.RETRY_SAME_KEY, 1, 0L));
    }

    @Test
    public void backoff_rateLimitWalksLongLadderAfterShortTier() {
        JitteredBackoff b = new JitteredBackoff(policy(100, 100000, 0.0d, 3, "1000,2000,3000,4000"), zero());
        assertEquals(Arrays.asList(100L, 200L, 400L, 1000L, 2000L, 3000L, 4000L, 4000L),
                waits(b, FailureClass.RATE_LIMIT, 8));
    }

    @Test
    public void backoff_retryAfterOverridesLadder() {
        JitteredBackoff b = new JitteredBackoff(policy(100, 100000, 0.0d, 3, "9000"), zero());
        assertEquals(2000L, b.nextDelayMs(FailureClass.RATE_LIMIT, 7, 2000L));
    }

    @Test
    public void backoff_totalBudgetStopsGrowth() {
        RetryPolicyConfig p = policy(100, 100000, 0.0d, 3, "1000");
        java.util.Properties raw = new java.util.Properties();
        raw.setProperty("llm.retry.base-delay-ms", "100");
        raw.setProperty("llm.retry.jitter-ratio", "0");
        raw.setProperty("llm.retry.total-budget-ms", "250");
        JitteredBackoff b = new JitteredBackoff(RetryPolicyConfig.of(null, raw), zero());
        assertEquals(Arrays.asList(100L, 150L, 0L, 0L), waits(b, FailureClass.RETRY_SAME_KEY, 4));
        assertTrue(b.isBudgetExhausted());
    }

    @Test
    public void backoff_configKeysAreActuallyRead() {
        RetryPolicyConfig p = RetryPolicyConfig.of(null, null);
        assertEquals(500L, p.getBaseDelayMs());
        assertEquals(120000L, p.getMaxDelayMs());
        assertEquals(0.5d, p.getJitterRatio(), 0.0d);
        assertEquals(3, p.getShortAttempts());
        assertEquals(Arrays.asList(30000L, 60000L, 90000L, 120000L), p.getRateLimitLadderMs());
        assertEquals(120000L, p.getRetryAfterCapMs());
        assertEquals(90000L, p.getTotalBudgetMs());
        assertEquals(180000L, p.getStreamStaleTimeoutMs());
        assertFalse(p.getStaleFloors().isEmpty());
    }

    /** 真 provider 走一遍：等待序列被记进 observer（杠③ (a) 的同一条路）。 */
    @Test
    public void resilient_recordsActualWaitSequenceForRateLimit() {
        Flaky delegate = new Flaky();
        delegate.throwFor(Collections.<Throwable>singletonList(new LlmException("openai", 429, "busy")));
        delegate.always = new LlmException("openai", 429, "busy");
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 2, 100, null,
                policy(100, 100000, 0.0d, 3, "1000"), zero());
        final List<Long> seen = new ArrayList<Long>();
        p.setRetryObserver(new ResilientLlmProvider.RetryObserver() {
            @Override
            public void onRetry(String model, int attempt, Decision d, long waitMs) {
                seen.add(Long.valueOf(waitMs));
            }
        });
        try {
            p.chat(request("m1"));
            fail("应上抛");
        } catch (RuntimeException expected) {
            // ignore
        }
        assertEquals(Arrays.asList(100L, 200L), seen);
        assertEquals(Arrays.asList(100L, 200L), p.lastObservedWaits());
        assertEquals(FailureClass.RATE_LIMIT, p.lastDecision().getFailureClass());
    }

    @Test
    public void resilient_doesNotRetryFatalAndRetriesTransient() {
        Flaky d1 = new Flaky();
        d1.always = new LlmException("openai", 400, "bad param");
        ResilientLlmProvider p1 = new ResilientLlmProvider(d1, 3, 1, null, policy(1, 10, 0.0d, 3, "10"), zero());
        try {
            p1.chat(request("m1"));
            fail("400 不该重试");
        } catch (RuntimeException expected) {
            assertEquals(1, d1.calls.get());
        }
        assertEquals(0, p1.lastObservedWaits().size());

        Flaky d2 = new Flaky();
        d2.throwFor(Arrays.<Throwable>asList(new LlmException("openai", 503, "busy")));
        ResilientLlmProvider p2 = new ResilientLlmProvider(d2, 3, 1, null, policy(1, 10, 0.0d, 3, "10"), zero());
        assertNotNull(p2.chat(request("m1")));
        assertEquals(2, d2.calls.get());
    }

    @Test
    public void resilient_legacyCtorKeepsWaitsTiny() {
        Flaky d = new Flaky();
        d.always = new RuntimeException("HTTP 429 rate limit");
        ResilientLlmProvider p = new ResilientLlmProvider(d, 5, 1, null);
        try {
            p.chat(request("m1"));
        } catch (RuntimeException expected) {
            // ignore
        }
        for (Long w : p.lastObservedWaits()) {
            assertTrue("legacy backoffMs=1 时单次等待必须 <=6ms（4ms 上限 + 50% 抖动），实际 " + w,
                    w.longValue() <= 6L);
        }
        assertEquals(5, p.lastObservedWaits().size());
    }

    // ===== §3 换模型重建上下文 =====

    @Test
    public void modelSwitch_rebuildsContextAndResetsCounters() {
        Flaky d = new Flaky();
        d.always = new LlmException("openai", 404, "{\"error\":{\"code\":\"model_not_found\"}}");
        d.succeedFor = "second-model";
        ResilientLlmProvider p = new ResilientLlmProvider(d, 2, 1,
                Collections.singletonList("second-model"), policy(1, 4, 0.0d, 3, "10"), zero());
        final AtomicInteger resets = new AtomicInteger();
        p.setModelFallbackHook(new ResilientLlmProvider.ModelFallbackHook() {
            @Override
            public ChatCompletionsRequest rebuildContext(String from, String to, ChatCompletionsRequest inFlight) {
                assertEquals("primary", from);
                assertEquals("second-model", to);
                List<Msg> msgs = new ArrayList<Msg>();
                msgs.add(Msg.system("rebuilt-for-" + to));
                msgs.add(Msg.user("hi"));
                return new ChatCompletionsRequest(to, msgs, inFlight.getTools(), inFlight.getTemperature(),
                        inFlight.getTopP(), 999, inFlight.isStream(), inFlight.getProviderParams());
            }

            @Override
            public void resetCompressionState(String from, String to) {
                resets.incrementAndGet();
            }
        });
        assertNotNull(p.chat(request("primary")));
        assertEquals(1, resets.get());
        ChatCompletionsRequest switched = d.requests.get(1);
        assertEquals("second-model", switched.getModel());
        assertEquals(999, switched.getMaxTokens().intValue());
        assertEquals(MessageRole.SYSTEM, switched.getMessages().get(0).getRole());
        assertEquals("rebuilt-for-" + switched.getModel(), switched.getMessages().get(0).getContent());
    }

    @Test
    public void modelSwitch_withoutHookStillSwitchesModelButKeepsStaleSystemPrompt() {
        Flaky d = new Flaky();
        d.always = new LlmException("openai", 404, "{\"error\":{\"code\":\"model_not_found\"}}");
        d.succeedFor = "second-model";
        ResilientLlmProvider p = new ResilientLlmProvider(d, 2, 1,
                Collections.singletonList("second-model"), policy(1, 4, 0.0d, 3, "10"), zero());
        assertNotNull(p.chat(request("primary")));
        assertEquals(2, d.requests.size());
        assertEquals(d.requests.get(0).getMessages().get(0).getContent(),
                d.requests.get(1).getMessages().get(0).getContent());
    }

    // ===== §4 流式陈旧看门狗 =====

    @Test
    public void stale_floorIsMaxOfDefaultAndModelFloor() {
        RetryPolicyConfig p = RetryPolicyConfig.of(null, null);
        // 非分档模型 ⇒ 用 default 180s
        assertEquals(180000L, StreamStaleWatchdog.resolveTimeoutMs(p, "gpt-4o-mini"));
        // reasoning slug ⇒ floor 300s 抬高它
        assertEquals(300000L, StreamStaleWatchdog.resolveTimeoutMs(p, "openai/o3-mini"));
        assertEquals(300000L, StreamStaleWatchdog.resolveTimeoutMs(p, "deepseek-r1"));
    }

    @Test
    public void stale_floorNeverLowersDefault() {
        java.util.Properties raw = new java.util.Properties();
        raw.setProperty("llm.stream.stale-timeout-floors", "^o3.*=5");
        RetryPolicyConfig p = RetryPolicyConfig.of(null, raw);
        assertEquals(180000L, StreamStaleWatchdog.resolveTimeoutMs(p, "o3-mini"));
    }

    @Test
    public void stale_explicitUserConfigBeatsFloor() {
        java.util.Properties raw = new java.util.Properties();
        raw.setProperty("llm.stream.stale-timeout-ms", "7000");
        RetryPolicyConfig p = RetryPolicyConfig.of(null, raw);
        assertTrue(p.isStreamStaleTimeoutExplicit());
        assertEquals(7000L, StreamStaleWatchdog.resolveTimeoutMs(p, "o3-mini"));
    }

    @Test
    public void stale_watchdogTripsAndDeliversErrorOnce() throws Exception {
        StaleStreamProvider delegate = new StaleStreamProvider();
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 1, 1, null,
                RetryPolicyConfig.of(null, null).withStreamStaleTimeoutMsExplicit(120L), zero());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        final List<ChatCompletionsResponse> chunks = new ArrayList<ChatCompletionsResponse>();
        p.streamChat(request("m1"), collector(chunks), new Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
                errors.add(t);
            }
        });
        long deadline = System.currentTimeMillis() + 5000;
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertEquals("只允许一次 onError", 1, errors.size());
        assertTrue(errors.get(0).getClass().getName(),
                errors.get(0) instanceof StreamStaleWatchdog.StreamStaleException);
        assertTrue(delegate.isSilent());
    }

    @Test
    public void stale_noHungThreadAfterTripOrNormalEnd() throws Exception {
        int before = watchdogThreads();
        ResilientLlmProvider p = new ResilientLlmProvider(new StaleStreamProvider(), 1, 1, null,
                RetryPolicyConfig.of(null, null).withStreamStaleTimeoutMsExplicit(80L), zero());
        p.streamChat(request("m1"), collector(new ArrayList<ChatCompletionsResponse>()), null);
        long deadline = System.currentTimeMillis() + 4000;
        while (watchdogThreads() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertEquals("看门狗线程必须收干净", before, watchdogThreads());

        ResilientLlmProvider p2 = new ResilientLlmProvider(new DoneStreamProvider(), 1, 1, null,
                RetryPolicyConfig.of(null, null).withStreamStaleTimeoutMsExplicit(60000L), zero());
        final List<ChatCompletionsResponse> got = new ArrayList<ChatCompletionsResponse>();
        p2.streamChat(request("m1"), collector(got), null);
        assertEquals(1, got.size());
        deadline = System.currentTimeMillis() + 4000;
        while (watchdogThreads() > before && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertEquals("正常收尾也要停表", before, watchdogThreads());
    }

    @Test
    public void stale_zeroThresholdMeansNoWatchdog() {
        java.util.Properties raw = new java.util.Properties();
        raw.setProperty("llm.stream.stale-timeout-ms", "0");
        RetryPolicyConfig p = RetryPolicyConfig.of(null, raw);
        assertEquals(0L, StreamStaleWatchdog.resolveTimeoutMs(p, "m1"));
        Flaky d = new Flaky();
        ResilientLlmProvider r = new ResilientLlmProvider(d, 0, 0, null, p, zero());
        r.streamChat(request("m1"), collector(new ArrayList<ChatCompletionsResponse>()), null);
        assertEquals(1, d.calls.get());
    }

    // ===== helpers =====

    private static int watchdogThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("z-llm-stale-watchdog")) {
                n++;
            }
        }
        return n;
    }

    private static List<Long> waits(JitteredBackoff b, FailureClass cls, int times) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 1; i <= times; i++) {
            out.add(Long.valueOf(b.nextDelayMs(cls, i, 0L)));
        }
        return out;
    }

    private static Consumer<ChatCompletionsResponse> collector(final List<ChatCompletionsResponse> sink) {
        return new Consumer<ChatCompletionsResponse>() {
            @Override
            public void accept(ChatCompletionsResponse chunk) {
                sink.add(chunk);
            }
        };
    }

    private static DoubleSupplier zero() {
        return new DoubleSupplier() {
            @Override
            public double getAsDouble() {
                return 0.0d;
            }
        };
    }

    private static DoubleSupplier constant(final double v) {
        return new DoubleSupplier() {
            @Override
            public double getAsDouble() {
                return v;
            }
        };
    }

    private static ChatCompletionsRequest request(String model) {
        return new ChatCompletionsRequest(model, Arrays.asList(Msg.system("sys"), Msg.user("hi")),
                null, 0.7, null, 128, false, null);
    }

    /** 可控 provider：前 N 次抛指定异常，之后按 always / succeedFor 决定。 */
    private static final class Flaky implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        final AtomicInteger calls = new AtomicInteger();
        final List<Throwable> queue = new ArrayList<Throwable>();
        Throwable always;
        String succeedFor;

        void throwFor(List<Throwable> ts) {
            queue.addAll(ts);
        }

        @Override
        public String name() {
            return "flaky";
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
            calls.incrementAndGet();
            if (!queue.isEmpty()) {
                throw asRuntime(queue.remove(0));
            }
            if (succeedFor != null && succeedFor.equals(request.getModel())) {
                return ok(request.getModel());
            }
            if (always != null) {
                throw asRuntime(always);
            }
            return ok(request.getModel());
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            requests.add(request);
            calls.incrementAndGet();
            if (onError != null) {
                onError.accept(new RuntimeException("HTTP 429 rate limit"));
            }
        }

        private static RuntimeException asRuntime(Throwable t) {
            return t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    /** 流式：开一条永远不吐字的流（用来喂看门狗）。 */
    private static final class StaleStreamProvider implements LlmProvider {
        private volatile boolean silent = true;

        boolean isSilent() {
            return silent;
        }

        @Override
        public String name() {
            return "stale";
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
            return ok(request.getModel());
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(600L);
                        silent = false;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "p26-fake-upstream");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 流式：立刻给一个带 finish_reason 的收尾块。 */
    private static final class DoneStreamProvider implements LlmProvider {
        @Override
        public String name() {
            return "done";
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
            return ok(request.getModel());
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(new ChatCompletionsResponse("id", request.getModel(),
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, "done",
                        null, "stop")),
                    new TokenUsage(1L, 1L, 2L), "stop", Collections.<String, Object>emptyMap()));
        }
    }

    private static ChatCompletionsResponse ok(String model) {
        return new ChatCompletionsResponse("id", model,
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, "ok", null, null)),
                new TokenUsage(1L, 1L, 2L), null, Collections.<String, Object>emptyMap());
    }
}
