import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.llm.KeyPoolLlmProvider;
import com.zifang.z.bot.llm.LlmErrorClassifier;
import com.zifang.z.bot.llm.LlmRouter;
import com.zifang.z.bot.llm.ModelUsage;
import com.zifang.z.bot.llm.ResilientLlmProvider;
import com.zifang.z.bot.llm.RetryPolicyConfig;
import com.zifang.z.bot.llm.StreamStaleWatchdog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * p26a 杠③：在**真进程 + 真 jar + 真 okhttp** 里驱动 {@link ResilientLlmProvider}，
 * 打的是 `p26_e2e.py` 起在 127.0.0.1 上的假端点（**绝不打厂商线上 API**）。
 *
 * <p>策略配置全部从 {@code <configDir>/config.properties} 读（{@code llm.retry.*} / {@code llm.stream.*}），
 * 数值由 python 侧按场景写进去 ⇒ 这里没有第二个默认值实现。</p>
 *
 * <p>用法：{@code java -cp z-bot-core.jar:p26-driver P26E2eDriver <mode> <configDir> [model]}</p>
 * <ul>
 *   <li>{@code chat} —— 非流式，跑完打印分类/等待序列/异常类型</li>
 *   <li>{@code stream} —— 流式，等到 onError 或 25s 超时；打印看门狗线程数前后差</li>
 * </ul>
 */
public final class P26E2eDriver {

    private P26E2eDriver() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "chat";
        String dir = args.length > 1 ? args[1] : ".";
        String model = args.length > 2 ? args[2] : null;

        BotConfig cfg = BotConfig.load(new File(dir));
        // ===== 出网卫兵（红线：只许打 127.0.0.1）===============================
        // BotConfig 只认 LEGACY_PROVIDERS(minimax/spark) 或显式 providers= 声明的 code；
        // 配错时 activeProvider() 会返回 baseUrl=null 的合成对象，kernel 随即用它自己的
        // 默认域名 https://api.openai.com/v1 —— 那就是真出网。这里在**任何 HTTP 之前**掐掉。
        String baseUrl = cfg.activeProvider().getBaseUrl();
        int keyCount = cfg.activeProvider().getApiKeys().size();
        boolean loopback = baseUrl != null
                && (baseUrl.startsWith("http://127.0.0.1:") || baseUrl.startsWith("http://localhost:"));
        System.out.println("P26 GUARD baseUrl=" + baseUrl + " keys=" + keyCount + " loopback=" + loopback);
        if (!loopback || keyCount == 0) {
            System.out.println("P26 GUARD ABORT 端点不是 127.0.0.1 或无 key —— 拒绝发任何请求");
            System.exit(4);
        }
        LlmProvider base = LlmRouter.create(cfg.activeProvider());
        RetryPolicyConfig pol = RetryPolicyConfig.of(cfg, null, new File(dir));
        LlmProvider pooled = KeyPoolLlmProvider.wrap(base, cfg.activeProvider());
        final ResilientLlmProvider p = new ResilientLlmProvider(pooled, cfg.getRetryMaxAttempts(),
                cfg.getRetryBackoffMs(), cfg.getFallbackModels(), pol, null);

        final String useModel = model != null ? model : cfg.getModel();
        System.out.println("P26 CFG provider=" + cfg.activeProvider().getType()
                + " url=" + cfg.activeProvider().getBaseUrl()
                + " model=" + useModel + " keys=" + cfg.activeProvider().getApiKeys().size()
                + " retries=" + cfg.getRetryMaxAttempts() + " backoff=" + cfg.getRetryBackoffMs());
        System.out.println("P26 POLICY " + pol);

        if ("chat".equals(mode)) {
            final List<String> seen = new ArrayList<String>();
            p.setRetryObserver(new ResilientLlmProvider.RetryObserver() {
                @Override
                public void onRetry(String m, int attempt, LlmErrorClassifier.Decision d, long waitMs) {
                    seen.add(m + "#" + attempt + "/" + waitMs + "ms");
                }
            });
            long t0 = System.currentTimeMillis();
            try {
                ChatCompletionsResponse r = p.chat(request(useModel, false));
                System.out.println("P26 RESULT ok content=" + r.getChoices().get(0).getContent()
                        + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");
                System.out.println("P26 USAGE raw=" + r.getUsage()
                        + " metaKeys=" + (r.getProviderMetadata() == null ? "NULL" : r.getProviderMetadata().keySet())
                        + " norm=" + ModelUsage.fromResponse(r));
            } catch (Throwable e) {
                System.out.println("P26 RESULT err type=" + e.getClass().getName()
                        + " msg=" + String.valueOf(e.getMessage()).replace('\n', ' ')
                        + " elapsed=" + (System.currentTimeMillis() - t0) + "ms");
            }
            System.out.println("P26 WAITS " + p.lastObservedWaits());
            System.out.println("P26 RETRIES " + seen);
            System.out.println("P26 DECISION " + p.lastDecision());
            System.exit(0);
        }

        // ===== stream =====
        final int before = watchdogThreads();
        final long t0 = System.currentTimeMillis();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger chunks = new AtomicInteger();
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        p.streamChat(request(useModel, true), new java.util.function.Consumer<ChatCompletionsResponse>() {
            @Override
            public void accept(ChatCompletionsResponse chunk) {
                chunks.incrementAndGet();
                System.out.println("P26 CHUNK n=" + chunks.get() + " t=" + (System.currentTimeMillis() - t0) + "ms"
                        + " text=" + chunk.getChoices().get(0).getContent()
                        + " finish=" + chunk.getFinishReason());
                if (chunk.getFinishReason() != null) {
                    latch.countDown();
                }
            }
        }, new java.util.function.Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
                err.compareAndSet(null, t);
                System.out.println("P26 ONERROR t=" + (System.currentTimeMillis() - t0) + "ms class="
                        + t.getClass().getName() + " msg=" + String.valueOf(t.getMessage()).replace('\n', ' '));
                latch.countDown();
            }
        });
        boolean done = latch.await(25, TimeUnit.SECONDS);
        System.out.println("P26 STREAM done=" + done + " chunks=" + chunks.get()
                + " waitedMs=" + (System.currentTimeMillis() - t0)
                + " err=" + (err.get() == null ? "NONE" : err.get().getClass().getName())
                + " errMsg=" + (err.get() == null ? "" : String.valueOf(err.get().getMessage()).replace('\n', ' ')));
        Thread.sleep(1500L);
        int after = watchdogThreads();
        System.out.println("P26 THREADS watchdog before=" + before + " after=" + after
                + " leaked=" + (after - before));
        System.exit(after > before ? 3 : 0);
    }

    private static ChatCompletionsRequest request(String model, boolean stream) {
        List<Msg> msgs = new ArrayList<Msg>();
        msgs.add(Msg.system("p26 e2e"));
        msgs.add(Msg.user("say pong"));
        return new ChatCompletionsRequest(model, msgs, null, 0.7, null, 64, stream, null);
    }

    private static int watchdogThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("z-llm-stale-watchdog")) {
                n++;
            }
        }
        return n;
    }
}
