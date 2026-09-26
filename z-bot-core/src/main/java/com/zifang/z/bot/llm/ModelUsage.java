package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.types.TokenUsage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P26：usage 归一 —— 把不同 provider 形态的用量口径折成一份带 cache 维度的记录。
 *
 * <p>口径（本棒定死，杠② 用夹具双向钉）：</p>
 * <ul>
 *   <li>{@code promptTokens} = 上游报的**输入总量**（含 cache 读命中，与 OpenAI/Anthropic/DashScope 一致）</li>
 *   <li>{@code cacheReadTokens} = 命中缓存被**读**的那部分输入（OpenAI {@code prompt_tokens_details.cached_tokens}
 *       / Anthropic {@code cache_read_input_tokens} / DashScope {@code prompt_tokens_details.cached_tokens}）</li>
 *   <li>{@code cacheWriteTokens} = 为写缓存**多付**的那部分（Anthropic {@code cache_creation_input_tokens}
 *       / OpenAI {@code cache_write_tokens}）；没有该字段 ⇒ 0</li>
 *   <li>{@code billablePromptTokens} = {@code max(0, promptTokens - cacheReadTokens)} —— 折后计价口径</li>
 * </ul>
 *
 * <p>{@link #toCanonicalMap()} 与 {@link #fromCanonicalMap(Map)} 互为逆元，
 * {@link #fromOpenAiShape(Map)} / {@link #fromAnthropicShape(Map)} 覆盖厂商形态 ⇒ 单测可对同一份数字
 * 走"厂商形态 → 归一 → 厂商形态"双向回环（夹具不许只测一个方向）。</p>
 *
 * <p><b>现状约束（EVIDENCE §0.8）</b>：kernel 0.2.1 的 provider 解析时不读 cache 字段，
 * {@code ChatCompletionsResponse.getProviderMetadata()} 恒为 {@code emptyMap()} ⇒
 * 走真 provider 时 cache 维度只能取到 0。本类把"能读到就读"的口子留在
 * {@code providerMetadata} 的 {@code usage} / {@code raw_usage} 两键上，kernel 一侧补透传后即自动生效。</p>
 */
public final class ModelUsage {

    /** 归一后的用量记录（含 cache 读/写）。 */
    public static final class Record {
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;
        private final long cacheReadTokens;
        private final long cacheWriteTokens;
        private final String sourceFormat;

        Record(long promptTokens, long completionTokens, long totalTokens, long cacheReadTokens,
               long cacheWriteTokens, String sourceFormat) {
            this.promptTokens = Math.max(0L, promptTokens);
            this.completionTokens = Math.max(0L, completionTokens);
            this.totalTokens = totalTokens > 0L ? totalTokens
                    : this.promptTokens + this.completionTokens;
            this.cacheReadTokens = clampToPrompt(cacheReadTokens, this.promptTokens);
            this.cacheWriteTokens = Math.max(0L, cacheWriteTokens);
            this.sourceFormat = sourceFormat;
        }

        private static long clampToPrompt(long cache, long prompt) {
            long v = Math.max(0L, cache);
            return prompt > 0L ? Math.min(v, prompt) : v;
        }

        public long getPromptTokens() {
            return promptTokens;
        }

        public long getCompletionTokens() {
            return completionTokens;
        }

        public long getTotalTokens() {
            return totalTokens;
        }

        public long getCacheReadTokens() {
            return cacheReadTokens;
        }

        public long getCacheWriteTokens() {
            return cacheWriteTokens;
        }

        public String getSourceFormat() {
            return sourceFormat;
        }

        /** 折掉 cache 读命中后的计价输入量。 */
        public long getBillablePromptTokens() {
            return Math.max(0L, promptTokens - cacheReadTokens);
        }

        public boolean isEmpty() {
            return promptTokens == 0L && completionTokens == 0L && cacheReadTokens == 0L
                    && cacheWriteTokens == 0L;
        }

        Map<String, Object> toCanonicalMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("prompt_tokens", Long.valueOf(promptTokens));
            m.put("completion_tokens", Long.valueOf(completionTokens));
            m.put("total_tokens", Long.valueOf(totalTokens));
            m.put("cache_read_tokens", Long.valueOf(cacheReadTokens));
            m.put("cache_write_tokens", Long.valueOf(cacheWriteTokens));
            m.put("source_format", sourceFormat);
            return m;
        }

        @Override
        public String toString() {
            return "ModelUsage{p=" + promptTokens + ", c=" + completionTokens + ", t=" + totalTokens
                    + ", cacheRead=" + cacheReadTokens + ", cacheWrite=" + cacheWriteTokens
                    + ", fmt=" + sourceFormat + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Record)) {
                return false;
            }
            Record r = (Record) o;
            return promptTokens == r.promptTokens && completionTokens == r.completionTokens
                    && totalTokens == r.totalTokens && cacheReadTokens == r.cacheReadTokens
                    && cacheWriteTokens == r.cacheWriteTokens;
        }

        @Override
        public int hashCode() {
            long h = promptTokens * 31 + completionTokens;
            h = h * 31 + totalTokens;
            h = h * 31 + cacheReadTokens;
            h = h * 31 + cacheWriteTokens;
            return (int) (h ^ (h >>> 32));
        }
    }

    private ModelUsage() {
    }

    /** 从 kernel 响应归一：先取结构化 {@link TokenUsage}，再从 {@code providerMetadata} 里捞 cache 字段。 */
    public static Record fromResponse(ChatCompletionsResponse response) {
        if (response == null) {
            return new Record(0, 0, 0, 0, 0, "none");
        }
        TokenUsage u = response.getUsage();
        long prompt = u == null ? 0L : u.getPromptTokens();
        long completion = u == null ? 0L : u.getCompletionTokens();
        long total = u == null ? 0L : u.getTotalTokens();
        Map<String, Object> meta = response.getProviderMetadata();
        long read = 0L;
        long write = 0L;
        String fmt = "kernel-token-usage";
        if (meta != null && !meta.isEmpty()) {
            Map<String, Object> raw = usagePayloadOf(meta);
            if (raw != null) {
                read = firstLong(raw, "cache_read_input_tokens", "cache_read_tokens",
                        "cached_tokens", "cacheReadTokens");
                write = firstLong(raw, "cache_creation_input_tokens", "cache_write_tokens", "cacheWriteTokens");
                Map<String, Object> details = subMap(raw, "prompt_tokens_details", "input_tokens_details");
                if (read == 0L && details != null) {
                    read = firstLong(details, "cached_tokens", "cache_read_tokens");
                }
                fmt = details != null || raw.containsKey("cache_read_input_tokens")
                        ? "provider-metadata" : fmt;
                prompt = maxZero(prompt, firstLong(raw, "prompt_tokens", "input_tokens"));
                completion = maxZero(completion, firstLong(raw, "completion_tokens", "output_tokens"));
            }
        }
        return new Record(prompt, completion, total, read, write, fmt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usagePayloadOf(Map<String, Object> meta) {
        Object direct = meta.get("usage");
        if (direct instanceof Map) {
            return (Map<String, Object>) direct;
        }
        Object raw = meta.get("raw_usage");
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        return meta.containsKey("prompt_tokens") || meta.containsKey("cache_read_input_tokens") ? meta : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> parent, String... keys) {
        if (parent == null) {
            return null;
        }
        for (String k : keys) {
            Object v = parent.get(k);
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
        }
        return null;
    }

    private static long firstLong(Map<String, Object> m, String... keys) {
        if (m == null) {
            return 0L;
        }
        for (String k : keys) {
            Long v = asLong(m.get(k));
            if (v != null) {
                return v.longValue();
            }
        }
        return 0L;
    }

    private static long maxZero(long a, long b) {
        return b > a ? b : a;
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Long.valueOf(((Number) v).longValue());
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** OpenAI/DashScope 形态：{@code prompt_tokens_details.cached_tokens}。 */
    public static Record fromOpenAiShape(Map<String, Object> usage) {
        Map<String, Object> details = subMap(usage, "prompt_tokens_details", "input_tokens_details");
        Map<String, Object> cDetails = subMap(usage, "completion_tokens_details");
        long read = firstLong(usage, "cached_tokens");
        if (read == 0L) {
            read = firstLong(details, "cached_tokens", "cache_read_tokens");
        }
        long write = firstLong(usage, "cache_write_tokens");
        if (write == 0L) {
            write = firstLong(details, "cache_write_tokens");
        }
        long completion = firstLong(usage, "completion_tokens", "output_tokens");
        if (completion == 0L) {
            completion = firstLong(cDetails, "reasoning_tokens");
        }
        return new Record(firstLong(usage, "prompt_tokens", "input_tokens"), completion,
                firstLong(usage, "total_tokens"), read, write, "openai");
    }

    /** Anthropic 形态：{@code cache_read_input_tokens} / {@code cache_creation_input_tokens}。 */
    public static Record fromAnthropicShape(Map<String, Object> usage) {
        return new Record(firstLong(usage, "input_tokens"), firstLong(usage, "output_tokens"),
                firstLong(usage, "total_tokens"),
                firstLong(usage, "cache_read_input_tokens", "cache_read_tokens"),
                firstLong(usage, "cache_creation_input_tokens", "cache_write_tokens"), "anthropic");
    }

    /** 归一（自动认形态）：给了 {@code input_tokens} 且有 cache_*_input_tokens ⇒ Anthropic，否则 OpenAI。 */
    public static Record normalize(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return new Record(0, 0, 0, 0, 0, "none");
        }
        boolean anthropic = usage.containsKey("input_tokens")
                || usage.containsKey("cache_read_input_tokens")
                || usage.containsKey("cache_creation_input_tokens");
        return anthropic ? fromAnthropicShape(usage) : fromOpenAiShape(usage);
    }

    /** 归一结果 → 规范 map（回环用）。 */
    public static Map<String, Object> toCanonicalMap(Record r) {
        return r == null ? Collections.<String, Object>emptyMap() : r.toCanonicalMap();
    }

    /** 规范 map → 归一结果（与 {@link #toCanonicalMap(Record)} 互逆）。 */
    public static Record fromCanonicalMap(Map<String, Object> m) {
        Object fmt = m == null ? null : m.get("source_format");
        return new Record(firstLong(m, "prompt_tokens"), firstLong(m, "completion_tokens"),
                firstLong(m, "total_tokens"), firstLong(m, "cache_read_tokens"),
                firstLong(m, "cache_write_tokens"), fmt == null ? "canonical" : String.valueOf(fmt));
    }
}
