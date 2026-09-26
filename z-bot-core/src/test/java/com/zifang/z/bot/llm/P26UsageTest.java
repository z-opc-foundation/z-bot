package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.store.StateStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P26 §5：usage 归一（含 cache 读/写命中）与 {@code session_model_usage} 落库。
 *
 * <p>夹具双向钉：同一份数字走"厂商形态 → 归一 → 规范 map → 归一"必须闭合。</p>
 */
public class P26UsageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, Object> openAiUsage() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("cached_tokens", Integer.valueOf(4096));
        details.put("audio_tokens", Integer.valueOf(0));
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("prompt_tokens", Integer.valueOf(10000));
        usage.put("completion_tokens", Integer.valueOf(500));
        usage.put("total_tokens", Integer.valueOf(10500));
        usage.put("prompt_tokens_details", details);
        return usage;
    }

    private static Map<String, Object> anthropicUsage() {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("input_tokens", Integer.valueOf(10000));
        usage.put("output_tokens", Integer.valueOf(500));
        usage.put("cache_read_input_tokens", Integer.valueOf(4096));
        usage.put("cache_creation_input_tokens", Integer.valueOf(2048));
        return usage;
    }

    @Test
    public void normalize_openAiShape_readsCachedTokens() {
        ModelUsage.Record r = ModelUsage.fromOpenAiShape(openAiUsage());
        assertEquals(10000L, r.getPromptTokens());
        assertEquals(500L, r.getCompletionTokens());
        assertEquals(4096L, r.getCacheReadTokens());
        assertEquals(0L, r.getCacheWriteTokens());
        assertEquals(10500L, r.getTotalTokens());
        assertEquals(5904L, r.getBillablePromptTokens());
        assertEquals("openai", r.getSourceFormat());
    }

    @Test
    public void normalize_anthropicShape_readsCacheReadAndWrite() {
        ModelUsage.Record r = ModelUsage.fromAnthropicShape(anthropicUsage());
        assertEquals(10000L, r.getPromptTokens());
        assertEquals(4096L, r.getCacheReadTokens());
        assertEquals(2048L, r.getCacheWriteTokens());
        assertEquals(5904L, r.getBillablePromptTokens());
        assertEquals("anthropic", r.getSourceFormat());
    }

    /** 自动认形态：同一份数字在两种厂商字段名下必须折出**同一份**归一结果。 */
    @Test
    public void normalize_bothProviderShapesAgree() {
        Map<String, Object> anthropicSame = new LinkedHashMap<String, Object>();
        anthropicSame.put("input_tokens", Integer.valueOf(10000));
        anthropicSame.put("output_tokens", Integer.valueOf(500));
        anthropicSame.put("cache_read_input_tokens", Integer.valueOf(4096));
        ModelUsage.Record a = ModelUsage.normalize(openAiUsage());
        ModelUsage.Record b = ModelUsage.normalize(anthropicSame);
        assertEquals(a, b);
        assertEquals("openai", a.getSourceFormat());
        assertEquals("anthropic", b.getSourceFormat());
        // 真 Anthropic 形态还多一个写命中，OpenAI 形态没有
        assertEquals(2048L, ModelUsage.normalize(anthropicUsage()).getCacheWriteTokens());
    }

    /** 双向夹具：厂商形态 → 归一 → 规范 map → 再归一，必须逐字段闭合。 */
    @Test
    public void normalize_canonicalRoundTripIsLossless() {
        for (Map<String, Object> fixture : Arrays.asList(openAiUsage(), anthropicUsage())) {
            ModelUsage.Record one = ModelUsage.normalize(fixture);
            Map<String, Object> canonical = ModelUsage.toCanonicalMap(one);
            ModelUsage.Record two = ModelUsage.fromCanonicalMap(canonical);
            assertEquals(one, two);
            assertEquals(canonical, ModelUsage.toCanonicalMap(two));
            assertEquals(one.getCacheReadTokens(), ((Number) canonical.get("cache_read_tokens")).longValue());
            assertEquals(one.getCacheWriteTokens(), ((Number) canonical.get("cache_write_tokens")).longValue());
        }
    }

    @Test
    public void normalize_cacheReadNeverExceedsPrompt() {
        Map<String, Object> usage = openAiUsage();
        ((Map<String, Object>) usage.get("prompt_tokens_details")).put("cached_tokens", Integer.valueOf(999999));
        ModelUsage.Record r = ModelUsage.normalize(usage);
        assertEquals(10000L, r.getCacheReadTokens());
        assertEquals(0L, r.getBillablePromptTokens());
    }

    @Test
    public void normalize_missingUsageIsEmpty() {
        assertTrue(ModelUsage.normalize(null).isEmpty());
        assertTrue(ModelUsage.normalize(Collections.<String, Object>emptyMap()).isEmpty());
    }

    /** 从 kernel 响应归一：cache 字段走 providerMetadata.usage（kernel 目前恒空 ⇒ 0，见 EVIDENCE §0.8）。 */
    @Test
    public void normalize_fromResponse_usesProviderMetadataWhenPresent() {
        ChatCompletionsResponse bare = new ChatCompletionsResponse("id", "m",
                Collections.<ChatCompletionsResponse.Choice>emptyList(), new TokenUsage(10000L, 500L, 10500L),
                null, Collections.<String, Object>emptyMap());
        ModelUsage.Record r = ModelUsage.fromResponse(bare);
        assertEquals(10000L, r.getPromptTokens());
        assertEquals(0L, r.getCacheReadTokens());

        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("usage", anthropicUsage());
        ChatCompletionsResponse withMeta = new ChatCompletionsResponse("id", "m",
                Collections.<ChatCompletionsResponse.Choice>emptyList(), new TokenUsage(10000L, 500L, 10500L),
                null, meta);
        ModelUsage.Record r2 = ModelUsage.fromResponse(withMeta);
        assertEquals(4096L, r2.getCacheReadTokens());
        assertEquals(2048L, r2.getCacheWriteTokens());
    }

    // ===== 落库 =====

    @Test
    public void store_withoutCacheColumns_degradesToLegacyWriteAndReportsZero() throws Exception {
        File db = new File(tmp.newFolder("db1"), "state.db");
        StateStore store = new StateStore(db);
        assertFalse("schema 未迁移 ⇒ 不该假装有两列", store.hasCacheColumns());
        store.recordUsage("s1", "m1", 1000, 20, 3, Long.valueOf(400L), Long.valueOf(50L));
        long[] totals = store.usageTotals("s1");
        assertEquals(1000L, totals[0]);
        assertEquals(20L, totals[1]);
        assertEquals(3L, totals[2]);
        assertEquals(0L, totals[3]);
        assertEquals(0L, totals[4]);
    }

    /** 迁移落地后（测试里手工 ALTER 模拟）：cache 数字必须真的进库、并读得回来。 */
    @Test
    public void store_withCacheColumns_writtenAndReadBack() throws Exception {
        File db = new File(tmp.newFolder("db2"), "state.db");
        StateStore store = new StateStore(db);
        store.recordUsage("s1", "m1", 10, 5, 1);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
        Statement st = c.createStatement();
        st.executeUpdate("ALTER TABLE session_model_usage ADD COLUMN cache_read_tokens INTEGER");
        st.executeUpdate("ALTER TABLE session_model_usage ADD COLUMN cache_write_tokens INTEGER");
        st.close();
        c.close();
        store.invalidateCacheColumnProbe();
        assertTrue(store.hasCacheColumns());

        store.recordUsage("s1", "m1", 1000, 20, 3, Long.valueOf(400L), Long.valueOf(50L));
        store.recordUsage("s1", "m1", 2000, 40, 2, Long.valueOf(800L), Long.valueOf(0L));
        store.recordUsage("s2", "m2", 1, 1, 1, Long.valueOf(9L), Long.valueOf(9L));
        long[] t1 = store.usageTotals("s1");
        // 3000 = 迁移后两笔；外加迁移前那笔 legacy 行 (10/5/1)，其 cache 列是 NULL ⇒ 按 0 聚合
        assertEquals(3010L, t1[0]);
        assertEquals(65L, t1[1]);
        assertEquals(6L, t1[2]);
        assertEquals(1200L, t1[3]);
        assertEquals(50L, t1[4]);
        long[] t2 = store.usageTotals("s2");
        assertEquals(9L, t2[3]);
    }

    /** 旧 5 参入口必须还在（别的写手/老代码在调），且行为不变。 */
    @Test
    public void store_legacyFiveArgStillWorks() throws Exception {
        File db = new File(tmp.newFolder("db3"), "state.db");
        StateStore store = new StateStore(db);
        store.recordUsage("s1", "bench", 100, 20, 1);
        store.recordUsage("s1", "bench", 150, 30, 2);
        StateStore.Stats stats = store.stats();
        assertEquals(2L, stats.usageByModel.get("bench").longValue());
        assertTrue(store.usageTotals("s1")[3] == 0L);
    }
}
