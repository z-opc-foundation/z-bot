package com.zifang.z.bot.local;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面端本地执行模式的项目目录（{@code configDir/projects.json}）。
 *
 * <p>一个本地项目 = 一个任务分组：可绑定一个工作文件夹（也可以不绑，落到默认沙箱根），
 * 名下的会话 id 列表由聊天路由在首条消息时写入。会话正文仍统一存共享会话库
 * （state.db），这里只持「项目 → 会话 id」的归属关系。</p>
 */
public final class LocalProjectStore {

    private static final Logger LOG = LoggerFactory.getLogger(LocalProjectStore.class);

    private final File file;

    public LocalProjectStore(File configDir) {
        File dir = configDir != null ? configDir : com.zifang.z.bot.config.BotConfig.defaultConfigDir();
        this.file = new File(dir, "projects.json");
    }

    public synchronized List<Map<String, Object>> list() {
        return load();
    }

    public synchronized Map<String, Object> get(String id) {
        for (Map<String, Object> p : load()) {
            if (id.equals(String.valueOf(p.get("id")))) {
                return p;
            }
        }
        return null;
    }

    public synchronized Map<String, Object> create(String name, String dir) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String d = dir == null ? "" : dir.trim();
        if (!d.isEmpty()) {
            File f = new File(d);
            if (!f.exists() || !f.isDirectory()) {
                throw new IllegalArgumentException("文件夹不存在或不是目录: " + d);
            }
            d = f.getAbsolutePath();
        }
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("id", "lp-" + RandomUtil.uuidCompact().substring(0, 8));
        p.put("name", name.trim());
        if (!d.isEmpty()) {
            p.put("dir", d);
        }
        p.put("createdAt", System.currentTimeMillis());
        p.put("sessions", new ArrayList<String>());
        List<Map<String, Object>> all = load();
        all.add(p);
        save(all);
        return p;
    }

    public synchronized boolean delete(String id) {
        List<Map<String, Object>> all = load();
        for (Iterator<Map<String, Object>> it = all.iterator(); it.hasNext(); ) {
            if (id.equals(String.valueOf(it.next().get("id")))) {
                it.remove();
                try {
                    save(all);
                } catch (IOException e) {
                    throw new IllegalStateException("projects.json 写失败: " + e.getMessage(), e);
                }
                return true;
            }
        }
        return false;
    }

    /** 把会话 id 归到项目名下；已归属（含别的项目）则不动。 */
    public synchronized void bindSession(String projectId, String sessionId) {
        if (projectId == null || projectId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            return;
        }
        List<Map<String, Object>> all = load();
        for (Map<String, Object> p : all) {
            List<String> sessions = sessionsOf(p);
            if (sessions.contains(sessionId)) {
                return;
            }
        }
        for (Map<String, Object> p : all) {
            if (projectId.equals(String.valueOf(p.get("id")))) {
                sessionsOf(p).add(sessionId);
                try {
                    save(all);
                } catch (IOException e) {
                    LOG.warn("projects.json 绑定会话写失败(忽略): {}", e.getMessage());
                }
                return;
            }
        }
    }

    /** 会话被删时摘掉归属关系。 */
    public synchronized void unbindSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        List<Map<String, Object>> all = load();
        boolean dirty = false;
        for (Map<String, Object> p : all) {
            List<String> sessions = sessionsOf(p);
            if (sessions.remove(sessionId)) {
                dirty = true;
            }
        }
        if (dirty) {
            try {
                save(all);
            } catch (IOException e) {
                LOG.warn("projects.json 解绑会话写失败(忽略): {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> sessionsOf(Map<String, Object> p) {
        Object raw = p.get("sessions");
        if (raw instanceof List) {
            return (List<String>) raw;
        }
        List<String> l = new ArrayList<String>();
        p.put("sessions", l);
        return l;
    }

    private List<Map<String, Object>> load() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!file.exists()) {
            return out;
        }
        try {
            JsonObject root = JsonUtil.parseObject(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            if (!root.containsKey("projects")) {
                return out;
            }
            JsonArray arr = root.getJsonArray("projects");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.getJsonObject(i);
                if (o == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", o.getString("id"));
                m.put("name", o.getString("name"));
                if (o.getString("dir") != null && !o.getString("dir").isEmpty()) {
                    m.put("dir", o.getString("dir"));
                }
                m.put("createdAt", o.getLong("createdAt") == null ? 0L : o.getLong("createdAt"));
                List<String> sessions = new ArrayList<String>();
                if (o.get("sessions") instanceof JsonArray) {
                    JsonArray sa = o.getJsonArray("sessions");
                    for (int j = 0; j < sa.size(); j++) {
                        String sid = sa.getString(j);
                        if (sid != null && !sid.isEmpty()) {
                            sessions.add(sid);
                        }
                    }
                }
                m.put("sessions", sessions);
                out.add(m);
            }
        } catch (Exception e) {
            LOG.warn("projects.json 解析失败，按空目录继续: {} ({})", file, e.toString());
            return new ArrayList<Map<String, Object>>();
        }
        return out;
    }

    private void save(List<Map<String, Object>> all) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        root.put("projects", arr);
        for (Map<String, Object> p : all) {
            JsonObject o = new JsonObject();
            o.put("id", str(p.get("id")));
            o.put("name", str(p.get("name")));
            String dir = str(p.get("dir"));
            if (!dir.isEmpty()) {
                o.put("dir", dir);
            }
            o.put("createdAt", p.get("createdAt") == null ? 0L : p.get("createdAt"));
            JsonArray sa = new JsonArray();
            for (String sid : sessionsOf(p)) {
                sa.add(sid);
            }
            o.put("sessions", sa);
            arr.add(o);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".tmp");
        Files.write(tmp.toPath(),
                JsonUtil.toJson(JsonUtil.toPlainValue(root)).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
