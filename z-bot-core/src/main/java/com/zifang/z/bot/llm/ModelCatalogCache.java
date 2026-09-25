package com.zifang.z.bot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zifang.z.agent.kernel.llm.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型目录缓存（Hermes models_dev 语义）：provider 的 listModels 结果按 provider 分桶缓存，
 * TTL 内直接命中，过期走 supplier 重拉并落盘（tmp + ATOMIC_MOVE）跨进程复用。
 *
 * <p>当前 kernel provider 的 listModels 是静态表（不打网络），缓存的价值在于：
 * ① 启动/HTTP 首屏不阻塞在 provider 构造上；② 未来接动态目录（/v1/models）时零改动；
 * ③ 多进程（serve/gateway/cron）共享一份目录文件。</p>
 */
public final class ModelCatalogCache {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalogCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认 TTL 5 分钟。 */
    public static final long DEFAULT_TTL_MS = 5 * 60_000L;

    /** key → 模型列表；value[0] 为拉取时间戳。 */
    private final Map<String, CacheEntry> entries = new LinkedHashMap<String, CacheEntry>();
    private final File file;
    private final long ttlMs;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-catalog-refresh");
        t.setDaemon(true);
        return t;
    });

    private static final class CacheEntry {
        final List<Model> models;
        final long fetchedAt;

        CacheEntry(List<Model> models, long fetchedAt) {
            this.models = models;
            this.fetchedAt = fetchedAt;
        }
    }

    public ModelCatalogCache(File file) {
        this(file, DEFAULT_TTL_MS);
    }

    public ModelCatalogCache(File file, long ttlMs) {
        this.file = file;
        this.ttlMs = ttlMs;
        loadFromDisk();
    }

    /**
     * 取某 provider 的模型目录：TTL 内命中缓存，否则同步调 supplier 重拉。
     *
     * @param providerCode provider 代码（openai/minimax/…）
     * @param supplier     重拉函数（TTL 命中时不会被调用）
     */
    public synchronized List<Model> catalog(String providerCode, java.util.function.Supplier<List<Model>> supplier) {
        CacheEntry e = entries.get(providerCode);
        long now = System.currentTimeMillis();
        if (e != null && now - e.fetchedAt < ttlMs) {
            return e.models;
        }
        List<Model> fresh = null;
        if (supplier != null) {
            try {
                fresh = supplier.get();
            } catch (RuntimeException ex) {
                // 重拉失败 → 用旧缓存顶着（宁可目录旧也不空/不炸）
                LOG.warn("[ModelCatalog] 重拉 {} 失败: {}", providerCode, ex.getMessage());
            }
        }
        if (fresh == null) {
            return e == null ? Collections.<Model>emptyList() : e.models;
        }
        List<Model> copy = Collections.unmodifiableList(new ArrayList<Model>(fresh));
        entries.put(providerCode, new CacheEntry(copy, now));
        saveToDisk();
        return copy;
    }

    /** 异步强制刷新（HTTP /api/models?refresh=1 用，立刻返回当前缓存）。 */
    public List<Model> refreshAsync(String providerCode, java.util.function.Supplier<List<Model>> supplier) {
        CacheEntry e = entries.get(providerCode);
        if (refreshing.compareAndSet(false, true)) {
            executor.submit(() -> {
                try {
                    catalog(providerCode, supplier);
                } catch (RuntimeException ex) {
                    LOG.warn("[ModelCatalog] 异步刷新 {} 失败: {}", providerCode, ex.getMessage());
                } finally {
                    refreshing.set(false);
                }
            });
        }
        return e == null ? Collections.<Model>emptyList() : e.models;
    }

    /** 缓存是否已过期（供调用方决定展示"缓存于 X 分钟前"）。 */
    public boolean isStale(String providerCode) {
        CacheEntry e = entries.get(providerCode);
        return e == null || System.currentTimeMillis() - e.fetchedAt >= ttlMs;
    }

    /** 最近一次拉取时间；无记录返回 0。 */
    public long fetchedAt(String providerCode) {
        CacheEntry e = entries.get(providerCode);
        return e == null ? 0L : e.fetchedAt;
    }

    public synchronized void invalidate(String providerCode) {
        entries.remove(providerCode);
        saveToDisk();
    }

    public synchronized void invalidateAll() {
        entries.clear();
        saveToDisk();
    }

    /** 关后台刷新线程（agent shutdown 调）。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ===== 落盘: {code: {fetchedAt, models:[…]}} =====

    private void loadFromDisk() {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(file);
            synchronized (this) {
                for (java.util.Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> en = it.next();
                    JsonNode v = en.getValue();
                    if (!v.has("models")) {
                        continue;
                    }
                    List<Model> models = new ArrayList<Model>();
                    for (JsonNode m : v.get("models")) {
                        models.add(fromJson(m));
                    }
                    entries.put(en.getKey(), new CacheEntry(
                            Collections.unmodifiableList(models), v.path("fetchedAt").asLong(0L)));
                }
            }
        } catch (Exception e) {
            LOG.warn("[ModelCatalog] 读缓存文件失败(忽略): {}", e.getMessage());
        }
    }

    private void saveToDisk() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            for (Map.Entry<String, CacheEntry> en : entries.entrySet()) {
                ObjectNode v = root.putObject(en.getKey());
                v.put("fetchedAt", en.getValue().fetchedAt);
                ArrayNode arr = v.putArray("models");
                for (Model m : en.getValue().models) {
                    arr.add(toJson(m));
                }
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp, root);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOG.warn("[ModelCatalog] 写缓存文件失败(忽略): {}", e.getMessage());
        }
    }

    private static ObjectNode toJson(Model m) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", m.getId());
        n.put("displayName", m.getDisplayName());
        n.put("provider", m.getProvider());
        n.put("contextWindow", m.getContextWindow());
        n.put("maxOutputTokens", m.getMaxOutputTokens());
        ArrayNode caps = n.putArray("capabilities");
        for (Model.Capability c : m.getCapabilities()) {
            caps.add(c.name());
        }
        return n;
    }

    private static Model fromJson(JsonNode n) {
        List<Model.Capability> caps = new ArrayList<Model.Capability>();
        for (JsonNode c : n.path("capabilities")) {
            try {
                caps.add(Model.Capability.valueOf(c.asText()));
            } catch (IllegalArgumentException ignored) {
                // 未来新增的 capability 枚举值，旧缓存文件里出现时跳过
            }
        }
        return new Model(n.path("id").asText(), n.path("displayName").asText(),
                n.path("provider").asText(), caps,
                n.path("contextWindow").asLong(0L), n.path("maxOutputTokens").asLong(0L));
    }
}
