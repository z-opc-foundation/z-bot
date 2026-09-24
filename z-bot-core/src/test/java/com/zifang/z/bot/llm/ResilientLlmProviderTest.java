package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.TokenUsage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ResilientLlmProvider} 重试分类 / 退避 / 模型降级单测。
 * 替身 provider 可控地抛异常并记录每次请求的 model，退避设为 1ms 保证测试瞬时完成。
 */
public class ResilientLlmProviderTest {

    @Test
    public void retryable429IsRetriedThenSucceeds() {
        final RecordingProvider delegate = new RecordingProvider();
        delegate.failures.add("HTTP 429 Too Many Requests");
        delegate.failures.add("HTTP 429 rate limit");
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 2, 1, null);

        ChatCompletionsResponse r = p.chat(request("m1"));

        assertEquals("ok", r.getChoices().get(0).getContent());
        assertEquals(3, delegate.requests.size());
    }

    @Test
    public void nonRetryable401ThrowsImmediately() {
        final RecordingProvider delegate = new RecordingProvider();
        delegate.failures.add("HTTP 401 Unauthorized");
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 3, 1, null);

        try {
            p.chat(request("m1"));
            fail("401 不应重试，应直接上抛");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("401"));
        }
        assertEquals(1, delegate.requests.size());
    }

    @Test
    public void fallbackModelUsedAfterPrimaryRetriesExhausted() {
        final RecordingProvider delegate = new RecordingProvider();
        delegate.failures.addAll(Arrays.asList("HTTP 429", "HTTP 429", "HTTP 429"));
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 2, 1,
                Collections.singletonList("fallback-model"));

        ChatCompletionsResponse r = p.chat(request("primary"));

        assertEquals("ok", r.getChoices().get(0).getContent());
        List<String> models = new ArrayList<String>();
        for (ChatCompletionsRequest req : delegate.requests) {
            models.add(req.getModel());
        }
        // primary 重试 3 次 + 降级 1 次
        assertEquals(Arrays.asList("primary", "primary", "primary", "fallback-model"), models);
    }

    @Test
    public void allAttemptsExhaustedThrowsLast() {
        final RecordingProvider delegate = new RecordingProvider();
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 1, 1, null);
        delegate.alwaysFail = "HTTP 503 Service Unavailable";

        try {
            p.chat(request("m1"));
            fail("全部失败应上抛");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("503"));
        }
        assertEquals(2, delegate.requests.size());
    }

    @Test
    public void classificationTable() {
        assertTrue(ResilientLlmProvider.isRetryable(new RuntimeException("HTTP 429 Too Many Requests")));
        assertTrue(ResilientLlmProvider.isRetryable(new RuntimeException("HTTP 503 unavailable")));
        assertTrue(ResilientLlmProvider.isRetryable(new RuntimeException("connect timed out")));
        assertTrue(ResilientLlmProvider.isRetryable(new RuntimeException("connection reset")));
        assertFalse(ResilientLlmProvider.isRetryable(new RuntimeException("HTTP 401 Unauthorized")));
        assertFalse(ResilientLlmProvider.isRetryable(new RuntimeException("HTTP 400 Bad Request")));
        assertFalse(ResilientLlmProvider.isRetryable(new RuntimeException("boom")));
        // 429 优先于 4xx 通配：不能因为以 4 开头就被判不可重试
        assertTrue(ResilientLlmProvider.isRetryable(new RuntimeException("HTTP 429 rate limit")));
    }

    @Test
    public void retryAfterHeaderIsParsedFromMessage() {
        assertEquals(2000L, ResilientLlmProvider.retryAfterMs(
                new RuntimeException("HTTP 429, Retry-After: 2")));
        assertEquals(0L, ResilientLlmProvider.retryAfterMs(new RuntimeException("429")));
    }

    @Test
    public void streamChatDelegatesWithoutRetry() {
        final RecordingProvider delegate = new RecordingProvider();
        delegate.failures.add("HTTP 429");
        ResilientLlmProvider p = new ResilientLlmProvider(delegate, 3, 1, null);

        final List<Throwable> errors = new ArrayList<Throwable>();
        p.streamChat(request("m1"), new Consumer<ChatCompletionsResponse>() {
            @Override
            public void accept(ChatCompletionsResponse response) {
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable t) {
                errors.add(t);
            }
        });

        assertEquals(1, errors.size());
        assertEquals(1, delegate.requests.size());
    }

    // ===== helpers =====

    private static ChatCompletionsRequest request(String model) {
        return new ChatCompletionsRequest(model,
                Collections.singletonList(Msg.user("hi")), null, 0.7, null, 128, false, null);
    }

    /** 记录请求的替身 provider：failures 队列决定前 N 次抛什么，alwaysFail 非空则一直抛。 */
    private static final class RecordingProvider implements LlmProvider {
        final List<ChatCompletionsRequest> requests = new ArrayList<ChatCompletionsRequest>();
        final List<String> failures = new ArrayList<String>();
        String alwaysFail;

        @Override
        public String name() {
            return "recording";
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
            String fail = alwaysFail != null ? alwaysFail
                    : (!failures.isEmpty() ? failures.remove(0) : null);
            if (fail != null) {
                throw new RuntimeException(fail);
            }
            return new ChatCompletionsResponse("id", request.getModel(),
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0, "ok",
                            Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                    new TokenUsage(1L, 1L, 2L), "stop", null);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            requests.add(request);
            onError.accept(new RuntimeException("HTTP 429"));
        }
    }
}
