package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.config.BotConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P10a 多 key 凭据池单测 — 轮换/冷却/分层语义全用桩 provider, 不打真实网络。
 */
public class KeyPoolLlmProviderTest {

    // ===== 基础接线 =====

    @Test
    public void singleKeyReturnsPrimaryUnwrapped() {
        LlmProvider primary = new StubProvider("ok", null);
        BotConfig.Provider template = new BotConfig.Provider("t", "openai", "k1", null, "m");
        assertSame(primary, KeyPoolLlmProvider.wrap(primary, template));
    }

    @Test
    public void nullTemplateReturnsPrimary() {
        LlmProvider primary = new StubProvider("ok", null);
        assertSame(primary, KeyPoolLlmProvider.wrap(primary, null));
    }

    @Test
    public void multiKeyParsesCommaSeparated() {
        BotConfig.Provider p = new BotConfig.Provider("t", "openai", " k1 , k2 ,,k3 ", null, "m");
        assertEquals(Arrays.asList("k1", "k2", "k3"), p.getApiKeys());
    }

    @Test
    public void singleKeyListForm() {
        BotConfig.Provider p = new BotConfig.Provider("t", "openai", "sk-only", null, "m");
        assertEquals(Collections.singletonList("sk-only"), p.getApiKeys());
    }

    @Test
    public void nullApiKeyGivesEmptyList() {
        BotConfig.Provider p = new BotConfig.Provider("t", "openai", null, null, "m");
        assertTrue(p.getApiKeys().isEmpty());
    }

    // ===== 轮换行为 =====

    @Test
    public void rotatesToSecondKeyOnAuthFailure() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider first = new StubProvider(null, new RuntimeException("401 Unauthorized: invalid api key"));
        StubProvider second = new StubProvider("ok", null);
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(first, keys, key ->
                "k1".equals(key) ? first : second);

        ChatCompletionsResponse resp = pool.chat(req());
        assertEquals("ok", resp.getChoices().get(0).getContent());
        assertEquals(1, first.attempts);
        assertEquals(1, second.attempts);
    }

    @Test
    public void allKeysFailThrowsLastError() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider(null, new RuntimeException("401 Unauthorized"));
        StubProvider p2 = new StubProvider(null, new RuntimeException("401 Unauthorized"));
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);

        try {
            pool.chat(req());
            fail("应当抛出最后一次 key 失败");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("401"));
        }
        // 两个 key 都被试过
        assertEquals(1, p1.attempts);
        assertEquals(1, p2.attempts);
    }

    @Test
    public void nonKeyErrorPropagatesWithoutRotation() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider(null, new RuntimeException("500 Internal Server Error"));
        StubProvider p2 = new StubProvider("should-not-be-used", null);
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);

        try {
            pool.chat(req());
            fail("5xx 应当直接上抛");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("500"));
        }
        assertEquals(0, p2.attempts);
    }

    @Test
    public void secondCallSkipsCooledDownKey() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider(null, new RuntimeException("429 Too Many Requests"));
        StubProvider p2 = new StubProvider("ok", null);
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);

        // 第一次: k1 429 → 冷却 → k2 成功
        pool.chat(req());
        assertEquals(1, p1.attempts);
        assertEquals(1, p2.attempts);

        // 第二次: k1 仍在 60s 冷却中 → 直接走 k2, k1 不再被调用
        pool.chat(req());
        assertEquals(1, p1.attempts);
        assertEquals(2, p2.attempts);
    }

    @Test
    public void successSticksToFirstKey() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider("ok", null);
        StubProvider p2 = new StubProvider("ok", null);
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);

        pool.chat(req());
        pool.chat(req());
        // 首 key 一直成功 → 一直用首 key
        assertEquals(2, p1.attempts);
        assertEquals(0, p2.attempts);
    }

    // ===== key 作用域判定 =====

    @Test
    public void keyScopedClassification() {
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("401 Unauthorized")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("403 Forbidden")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("402 Payment Required")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("429 rate limit exceeded")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("quota exceeded for this key")));
        assertTrue(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("invalid api key provided")));
        assertFalse(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("500 internal error")));
        assertFalse(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("connection timed out")));
        assertFalse(KeyPoolLlmProvider.isKeyScoped(new RuntimeException("400 bad request: missing field")));
        assertFalse(KeyPoolLlmProvider.isKeyScoped(null));
    }

    // ===== 分层: Resilient 外层 + KeyPool 内层 =====

    @Test
    public void layeredWithResilientRetriesAcrossKeys() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider(null, new RuntimeException("401 Unauthorized"));
        StubProvider p2 = new StubProvider("ok", null);
        LlmProvider pooled = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);
        // Resilient 重试 0 次 → 只看 KeyPool 的一轮轮换
        LlmProvider layered = new ResilientLlmProvider(pooled, 0, 0, Collections.<String>emptyList());

        assertEquals("ok", layered.chat(req()).getChoices().get(0).getContent());
        assertEquals(1, p1.attempts);
        assertEquals(1, p2.attempts);
    }

    @Test
    public void wrapIsIdempotentForSingleKeyInAgentWiring() {
        // BotAgent.wrapResilient 的内层调用: 单 key 时 wrap 返回原对象, 不产生额外层
        BotConfig.Provider one = new BotConfig.Provider("t", "openai", "only", null, "m");
        LlmProvider raw = new StubProvider("ok", null);
        assertSame(raw, KeyPoolLlmProvider.wrap(raw, one));
    }

    // ===== 转发语义 =====

    @Test
    public void nameAndListModelsDelegateToPrimary() {
        List<String> keys = Arrays.asList("k1", "k2");
        StubProvider p1 = new StubProvider("ok", null);
        StubProvider p2 = new StubProvider("ok", null);
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(p1, keys, key ->
                "k1".equals(key) ? p1 : p2);

        assertEquals("stub", pool.name());
        assertEquals(1, pool.listModels().size());
        assertTrue(pool.supportsModel("any"));
    }

    @Test
    public void keysExposesList() {
        LlmProvider pool = KeyPoolLlmProvider.wrapForTest(
                new StubProvider("ok", null), Arrays.asList("k1", "k2"), key -> null);
        assertTrue(pool instanceof KeyPoolLlmProvider);
        assertEquals(2, ((KeyPoolLlmProvider) pool).keys().size());
    }

    // ===== helpers =====

    private static ChatCompletionsRequest req() {
        return new ChatCompletionsRequest("test-model", Collections.<Msg>emptyList());
    }

    private static ChatCompletionsResponse ok(String content) {
        return new ChatCompletionsResponse("id", "test-model",
                Collections.singletonList(new ChatCompletionsResponse.Choice(0, content,
                        Collections.<ToolCall>emptyList(), "stop")),
                TokenUsage.empty(), "stop", null);
    }

    /** 记录尝试次数的桩 provider。 */
    static final class StubProvider implements LlmProvider {
        final String okContent;
        final RuntimeException failure;
        int attempts;

        StubProvider(String okContent, RuntimeException failure) {
            this.okContent = okContent;
            this.failure = failure;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public List<Model> listModels() {
            return Collections.singletonList(
                    new Model("test-model", "Test", "stub",
                            Collections.<Model.Capability>emptyList(), 0, 0));
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            attempts++;
            if (failure != null) {
                throw failure;
            }
            return ok(okContent);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            attempts++;
            if (failure != null) {
                onError.accept(failure);
                return;
            }
            onChunk.accept(ok(okContent));
        }

        @Override
        public Map<String, Object> providerParams() {
            return Collections.emptyMap();
        }
    }
}
