package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.credential.CredentialPool;
import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.bot.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 多 key 凭据池（Hermes credential_pool 语义）：同一 provider 配多个 api key 时轮换使用。
 *
 * <p>轮换规则：</p>
 * <ul>
 *   <li>key 作用域失败（401/403 鉴权失效、402/quota 配额耗尽、429 限流）→
 *       {@code reportFailure} 冷却该 key，立刻换下一个 key 重试</li>
 *   <li>成功 → {@code reportSuccess} 清零失败计数</li>
 *   <li>非 key 作用域错误（5xx/超时/参数错）→ 直接上抛，交给外层
 *       {@link ResilientLlmProvider} 做退避重试与模型降级</li>
 * </ul>
 *
 * <p>分层：Resilient（模型降级+瞬时重试）→ KeyPool（key 轮换）→ 单 key kernel provider。</p>
 */
public final class KeyPoolLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(KeyPoolLlmProvider.class);

    /** 鉴权失效 key 的冷却时长（不是永远拉黑 — 配额充值/密钥修复后要能自动恢复）。 */
    private static final long AUTH_COOLDOWN_MS = 10 * 60_000L;
    /** 限流默认冷却；有 Retry-After 时优先用它。 */
    private static final long RATE_COOLDOWN_MS = 60_000L;

    /** key 作用域失败特征：鉴权 / 配额 / 限流。5xx、超时、400 参数错都不算。 */
    private static final Pattern KEY_SCOPED = Pattern.compile(
            "(?i)(\\b(?:401|402|403|429)\\b|unauthorized|forbidden|invalid\\s+api\\s*key"
                    + "|api\\s*key\\s*(?:not|is\\s*not)\\s*valid|quota|rate.?limit"
                    + "|too\\s+many\\s+requests|exceeded)");

    private final CredentialPool pool;
    private final List<String> keys;
    private final BotConfig.Provider template;
    /** key → 单 key provider 构造（测试可注入桩工厂）。 */
    private final java.util.function.Function<String, LlmProvider> factory;
    /** key → 已构造的单 key provider 实例（首 key 复用调用方传入的实例）。 */
    private final ConcurrentMap<String, LlmProvider> cache = new ConcurrentHashMap<String, LlmProvider>();

    private KeyPoolLlmProvider(LlmProvider primary, List<String> keys, BotConfig.Provider template,
                               java.util.function.Function<String, LlmProvider> factory) {
        this.pool = new CredentialPool(keys);
        this.keys = Collections.unmodifiableList(new ArrayList<String>(keys));
        this.template = template;
        this.factory = factory;
        this.cache.put(keys.get(0), primary);
    }

    /**
     * 单 key 或无配置时原样返回，多 key 时包一层轮换。
     *
     * @param primary 已构造好的 provider（作为首 key 的实例复用）
     * @param template 提供 type/baseUrl/model，用于按需构造其余 key 的实例
     */
    public static LlmProvider wrap(LlmProvider primary, BotConfig.Provider template) {
        if (primary == null || template == null) {
            return primary;
        }
        List<String> keys = template.getApiKeys();
        if (keys.size() <= 1) {
            return primary;
        }
        return new KeyPoolLlmProvider(primary, keys, template, null);
    }

    /** 测试注入：自定义 key → provider 工厂（绕开真实 LlmRouter 构造）。 */
    static LlmProvider wrapForTest(LlmProvider primary, List<String> keys,
                                   java.util.function.Function<String, LlmProvider> factory) {
        BotConfig.Provider template = new BotConfig.Provider("test", "openai",
                String.join(",", keys), null, "test-model");
        return new KeyPoolLlmProvider(primary, keys, template, factory);
    }

    @Override
    public String name() {
        return primary().name();
    }

    @Override
    public List<Model> listModels() {
        return primary().listModels();
    }

    @Override
    public boolean supportsModel(String modelId) {
        return primary().supportsModel(modelId);
    }

    @Override
    public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
        Set<String> tried = new HashSet<String>();
        RuntimeException last = null;
        while (tried.size() < keys.size()) {
            String key = pickUntried(tried);
            if (key == null) {
                break;
            }
            tried.add(key);
            try {
                ChatCompletionsResponse resp = providerFor(key).chat(request);
                pool.reportSuccess(key);
                return resp;
            } catch (RuntimeException e) {
                if (!isKeyScoped(e)) {
                    throw e;
                }
                long cooldown = cooldownMs(e);
                pool.reportFailure(key, cooldown);
                LOG.warn("[KeyPool] key#{} 失败进入冷却 {}ms，换下一个 key: {}",
                        indexOf(key), cooldown, e.getMessage());
                last = e;
            }
        }
        if (last == null) {
            throw new IllegalStateException("credential pool 为空");
        }
        throw last;
    }

    @Override
    public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                           Consumer<Throwable> onError) {
        // 流式不做轮换（半途换 key 会拼出两段回复）；失败照样记冷却，下次调用自然换 key
        String key = pickUntried(Collections.<String>emptySet());
        try {
            providerFor(key).streamChat(request, onChunk, onError);
        } catch (RuntimeException e) {
            if (isKeyScoped(e)) {
                pool.reportFailure(key, cooldownMs(e));
            }
            onError.accept(e);
        }
    }

    @Override
    public java.util.Map<String, Object> providerParams() {
        return primary().providerParams();
    }

    /** 当前未冷却的首选 key；已试过的跳过（pool.pick 在全冷却时返回最早恢复者，可能重复）。 */
    private String pickUntried(Set<String> tried) {
        String picked = pool.pick();
        if (!tried.contains(picked)) {
            return picked;
        }
        for (String k : keys) {
            if (!tried.contains(k)) {
                return k;
            }
        }
        return null;
    }

    private LlmProvider providerFor(String key) {
        LlmProvider p = cache.get(key);
        if (p != null) {
            return p;
        }
        // ConcurrentHashMap.putIfAbsent 保证并发下同一 key 只构造一个实例
        LlmProvider created = factory != null
                ? factory.apply(key)
                : LlmRouter.create(template.withApiKey(key));
        LlmProvider prev = cache.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    private LlmProvider primary() {
        LlmProvider p = cache.get(keys.get(0));
        if (p != null) {
            return p;
        }
        return factory != null ? factory.apply(keys.get(0))
                : LlmRouter.create(template.withApiKey(keys.get(0)));
    }

    /** key 作用域判定。 */
    static boolean isKeyScoped(Throwable e) {
        if (e == null) {
            return false;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return KEY_SCOPED.matcher(msg).find();
    }

    /** 冷却时长：429 优先 Retry-After，鉴权类给固定长冷却。 */
    private long cooldownMs(Throwable e) {
        if (e != null && e.getMessage() != null
                && Pattern.compile("(?i)\\b429\\b|rate.?limit|too many requests")
                        .matcher(e.getMessage()).find()) {
            long after = ResilientLlmProvider.retryAfterMs(e);
            if (after > 0) {
                return after;
            }
            return RATE_COOLDOWN_MS;
        }
        return AUTH_COOLDOWN_MS;
    }

    private int indexOf(String key) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** 测试观察点：key 列表（只读）。 */
    public List<String> keys() {
        return keys;
    }

    @Override
    public String toString() {
        return "KeyPoolLlmProvider{keys=" + keys.size() + ", provider='" + template.getType() + "'}";
    }
}
