package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带重试与模型降级的 {@link LlmProvider} 装饰器（对齐 hermes provider 重试语义）。
 *
 * <p>错误分类：</p>
 * <ul>
 *   <li>可重试 — 429 / 5xx / 超时 / 连接类失败；退避按 {@code Retry-After} 优先，
 *       否则 backoffMs * (尝试序号)，重试 {@code maxRetries} 次</li>
 *   <li>不可重试 — 其他 4xx（鉴权/参数错），立即上抛</li>
 * </ul>
 *
 * <p>主模型重试全部耗尽后，按 {@code fallbackModels} 顺序逐个换模型重试一整轮；
 * 全部失败时抛最后一次的异常。流式接口不做重试，直接透传。</p>
 */
public final class ResilientLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientLlmProvider.class);

    private static final Pattern RETRYABLE = Pattern.compile(
            "(?i)(429|http\\s*5\\d\\d|\\b5\\d\\d\\b|rate.?limit|too many requests|timeout|timed out"
                    + "|connect(ion)?\\s?(refused|reset|closed|timed)|broken pipe|unavailable|bad gateway|overloaded)");
    /** 4xx 白名单式列举（不能笼统 4\\d\\d，否则把 429 也吞了）。 */
    private static final Pattern NON_RETRYABLE = Pattern.compile(
            "(?i)(\\b(?:400|401|402|403|404|405|406|407|409|410|418|422|423|424|428|431|451)\\b"
                    + "|unauthorized|forbidden|invalid api key|bad request)");
    private static final Pattern RETRY_AFTER = Pattern.compile("(?i)retry.?after\\D{0,4}(\\d{1,5})");

    private final LlmProvider delegate;
    private final int maxRetries;
    private final long backoffMs;
    private final List<String> fallbackModels;

    public ResilientLlmProvider(LlmProvider delegate, int maxRetries, long backoffMs,
                                List<String> fallbackModels) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate provider 不能为空");
        }
        this.delegate = delegate;
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffMs = Math.max(0, backoffMs);
        this.fallbackModels = fallbackModels == null
                ? Collections.<String>emptyList() : new ArrayList<String>(fallbackModels);
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

        RuntimeException last = null;
        for (int m = 0; m < models.size(); m++) {
            ChatCompletionsRequest req = m == 0 ? request : withModel(request, models.get(m));
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    return delegate.chat(req);
                } catch (RuntimeException e) {
                    if (!isRetryable(e)) {
                        throw e;
                    }
                    last = e;
                    if (attempt < maxRetries) {
                        long wait = retryAfterMs(e) > 0 ? retryAfterMs(e) : backoffMs * (attempt + 1);
                        LOG.warn("[ResilientLlm] {} 调用失败(第 {} 次)，{}ms 后重试: {}",
                                req.getModel(), attempt + 1, wait, e.getMessage());
                        sleep(wait);
                    }
                }
            }
            if (m < models.size() - 1) {
                LOG.warn("[ResilientLlm] 模型 {} 重试耗尽，降级到 {}: {}",
                        req.getModel(), models.get(m + 1), last.getMessage());
            }
        }
        throw last;
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                           Consumer<Throwable> onError) {
        delegate.streamChat(request, onChunk, onError);
    }

    @Override
    public java.util.Map<String, Object> providerParams() {
        return delegate.providerParams();
    }

    /** 消息级错误分类：命中可重试特征且未命中明确 4xx 才重试。 */
    static boolean isRetryable(Throwable e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (NON_RETRYABLE.matcher(msg).find()) {
            return false;
        }
        return RETRYABLE.matcher(msg).find();
    }

    /** 从异常消息里解析 Retry-After（秒）；解析不到返回 0。 */
    static long retryAfterMs(Throwable e) {
        if (e == null || e.getMessage() == null) {
            return 0;
        }
        Matcher m = RETRY_AFTER.matcher(e.getMessage());
        return m.find() ? Long.parseLong(m.group(1)) * 1000L : 0;
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
                + ", backoffMs=" + backoffMs + ", fallbacks=" + Arrays.toString(fallbackModels.toArray()) + "}";
    }
}
