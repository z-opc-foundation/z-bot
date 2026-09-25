package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.Model;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P10b 模型目录缓存单测 — TTL 命中/过期重拉/落盘复用/supplier 失败降级, 不打真实网络。
 */
public class ModelCatalogCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Model model(String id) {
        return new Model(id, id.toUpperCase(), "openai",
                Collections.singletonList(Model.Capability.CHAT), 128000, 16384);
    }

    private static List<Model> models(String... ids) {
        List<Model> out = new ArrayList<Model>();
        for (String id : ids) {
            out.add(model(id));
        }
        return out;
    }

    @Test
    public void firstCallInvokesSupplierAndCaches() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f);
        AtomicInteger calls = new AtomicInteger();

        List<Model> first = cache.catalog("openai", () -> {
            calls.incrementAndGet();
            return models("gpt-4o", "gpt-4o-mini");
        });
        assertEquals(2, first.size());
        assertEquals(1, calls.get());
        assertFalse(cache.isStale("openai"));

        // TTL 内第二次 → 不再调 supplier
        List<Model> second = cache.catalog("openai", () -> {
            calls.incrementAndGet();
            return models("should-not-appear");
        });
        assertEquals(2, second.size());
        assertEquals(1, calls.get());
    }

    @Test
    public void expiredTtlRefetches() throws Exception {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f, 50); // 50ms TTL
        AtomicInteger calls = new AtomicInteger();

        cache.catalog("openai", () -> {
            calls.incrementAndGet();
            return models("v1");
        });
        Thread.sleep(120);

        assertTrue(cache.isStale("openai"));
        List<Model> refetched = cache.catalog("openai", () -> {
            calls.incrementAndGet();
            return models("v2");
        });
        assertEquals(2, calls.get());
        assertEquals("v2", refetched.get(0).getId());
    }

    @Test
    public void diskCacheReusedByNewInstance() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache c1 = new ModelCatalogCache(f);
        c1.catalog("openai", () -> models("gpt-4o"));

        // 新实例（模拟另一进程）→ 从文件读到, TTL 内不再调 supplier
        ModelCatalogCache c2 = new ModelCatalogCache(f);
        AtomicInteger calls = new AtomicInteger();
        List<Model> got = c2.catalog("openai", () -> {
            calls.incrementAndGet();
            return models("should-not-be-called");
        });
        assertEquals(0, calls.get());
        assertEquals("gpt-4o", got.get(0).getId());
        assertEquals("GPT-4O", got.get(0).getDisplayName());
        assertEquals(128000, got.get(0).getContextWindow());
        assertTrue(got.get(0).getCapabilities().contains(Model.Capability.CHAT));
        assertFalse(c2.isStale("openai"));
    }

    @Test
    public void supplierFailureKeepsStaleCache() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f, 1); // 立即过期
        cache.catalog("openai", () -> models("old"));

        // 过期后 supplier 挂了 → 返回旧缓存兜底, 不抛
        List<Model> fallback = cache.catalog("openai", () -> {
            throw new RuntimeException("network down");
        });
        assertEquals(1, fallback.size());
        assertEquals("old", fallback.get(0).getId());
    }

    @Test
    public void supplierFailureWithNoCacheGivesEmpty() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f);
        List<Model> empty = cache.catalog("openai", () -> {
            throw new RuntimeException("network down");
        });
        assertTrue(empty.isEmpty());
    }

    @Test
    public void providerBucketsAreIndependent() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f);
        cache.catalog("openai", () -> models("gpt-4o"));
        cache.catalog("anthropic", () -> models("claude"));

        assertEquals(1, cache.catalog("openai", null).size());
        assertEquals(1, cache.catalog("anthropic", null).size());
        assertEquals("gpt-4o", cache.catalog("openai", null).get(0).getId());
        assertEquals("claude", cache.catalog("anthropic", null).get(0).getId());
    }

    @Test
    public void invalidateForcesRefetch() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f);
        cache.catalog("openai", () -> models("old"));
        cache.invalidate("openai");

        assertTrue(cache.isStale("openai"));
        List<Model> fresh = cache.catalog("openai", () -> models("new"));
        assertEquals("new", fresh.get(0).getId());
    }

    @Test
    public void fetchedAtTimestampRecorded() {
        File f = new File(tmp.getRoot(), "models-cache.json");
        ModelCatalogCache cache = new ModelCatalogCache(f);
        assertEquals(0, cache.fetchedAt("openai"));

        long before = System.currentTimeMillis();
        cache.catalog("openai", () -> models("gpt-4o"));
        long after = System.currentTimeMillis();

        long at = cache.fetchedAt("openai");
        assertTrue(at >= before && at <= after);
    }

    @Test
    public void corruptCacheFileIsIgnored() throws Exception {
        File f = new File(tmp.getRoot(), "models-cache.json");
        java.nio.file.Files.write(f.toPath(),
                "{ this is not json !!!".getBytes("UTF-8"));

        // 坏文件不炸, 正常当无缓存用
        ModelCatalogCache cache = new ModelCatalogCache(f);
        List<Model> got = cache.catalog("openai", () -> models("gpt-4o"));
        assertEquals(1, got.size());
    }

    @Test
    public void unknownCapabilityInCacheIsSkipped() throws Exception {
        File f = new File(tmp.getRoot(), "models-cache.json");
        java.nio.file.Files.write(f.toPath(), ("{\n"
                + "  \"openai\": {\n"
                + "    \"fetchedAt\": 1000,\n"
                + "    \"models\": [{\"id\":\"m1\",\"displayName\":\"M1\",\"provider\":\"openai\","
                + "\"contextWindow\":1,\"maxOutputTokens\":1,"
                + "\"capabilities\":[\"CHAT\",\"FUTURE_CAP\"]}]\n"
                + "  }\n"
                + "}").getBytes("UTF-8"));

        ModelCatalogCache cache = new ModelCatalogCache(f, 60_000);
        List<Model> got = cache.catalog("openai", null);
        assertEquals(1, got.size());
        assertEquals(Arrays.asList(Model.Capability.CHAT), got.get(0).getCapabilities());
    }
}
