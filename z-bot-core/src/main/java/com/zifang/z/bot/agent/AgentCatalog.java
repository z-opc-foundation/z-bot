package com.zifang.z.bot.agent;

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
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面端 {@code /v1/agents} 面背后的 agent 目录。
 *
 * <p>内置 {@code default}（type=jar，即 z-bot 内置 ReAct）恒在：不落盘、不可删。
 * 自定义条目（CLI 引擎的 command/args/workingDir）存 {@code configDir/agents.json}。</p>
 */
public final class AgentCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(AgentCatalog.class);

    public static final String DEFAULT_ID = "default";

    private static final Set<String> TYPES =
            new LinkedHashSet<String>(Arrays.asList("jar", "claude-code", "hermes"));

    private final File file;

    public AgentCatalog(File configDir) {
        File dir = configDir != null ? configDir : com.zifang.z.bot.config.BotConfig.defaultConfigDir();
        this.file = new File(dir, "agents.json");
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.add(builtin());
        out.addAll(load());
        return out;
    }

    public synchronized Map<String, Object> get(String id) {
        if (DEFAULT_ID.equals(id)) {
            return builtin();
        }
        for (Map<String, Object> a : load()) {
            if (id.equals(String.valueOf(a.get("id")))) {
                return a;
            }
        }
        return null;
    }

    public synchronized Map<String, Object> create(Map<String, Object> req) throws IOException {
        String name = str(req.get("name"));
        String type = str(req.get("type"));
        if (type.isEmpty()) {
            type = "jar";
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的 agent type: " + type + "（可选 jar / claude-code / hermes）");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("id", type + "-" + RandomUtil.uuidCompact().substring(0, 8));
        a.put("name", name);
        a.put("type", type);
        a.put("status", "stopped");
        a.put("createdAt", System.currentTimeMillis());
        String command = str(req.get("command"));
        if (!command.isEmpty()) {
            a.put("command", command);
        }
        Object args = req.get("args");
        if (args instanceof List) {
            a.put("args", args);
        }
        String workingDir = str(req.get("workingDir"));
        if (workingDir.isEmpty()) {
            // 原 z-team-agent 的 POST /v1/agents 用 snake_case；desk 用 camelCase，两套都收
            workingDir = str(req.get("working_dir"));
        }
        if (!workingDir.isEmpty()) {
            a.put("workingDir", workingDir);
        }
        List<Map<String, Object>> all = load();
        all.add(a);
        save(all);
        return a;
    }

    /** 删掉自定义条目；内置 default 与未知 id 一律 false。 */
    public synchronized boolean delete(String id) {
        if (DEFAULT_ID.equals(id)) {
            return false;
        }
        List<Map<String, Object>> all = load();
        for (Iterator<Map<String, Object>> it = all.iterator(); it.hasNext(); ) {
            if (id.equals(String.valueOf(it.next().get("id")))) {
                it.remove();
                try {
                    save(all);
                } catch (IOException e) {
                    throw new IllegalStateException("agents.json 写失败: " + e.getMessage(), e);
                }
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> builtin() {
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("id", DEFAULT_ID);
        a.put("name", "Z-Bot 自研");
        a.put("type", "jar");
        a.put("status", "running");
        a.put("createdAt", 0L);
        return a;
    }

    private List<Map<String, Object>> load() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!file.exists()) {
            return out;
        }
        try {
            JsonObject root = JsonUtil.parseObject(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            if (!root.containsKey("agents")) {
                return out;
            }
            JsonArray arr = root.getJsonArray("agents");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.getJsonObject(i);
                if (o == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", o.getString("id"));
                m.put("name", o.getString("name"));
                m.put("type", o.getString("type"));
                m.put("status", o.getString("status") == null ? "stopped" : o.getString("status"));
                m.put("createdAt", o.getLong("createdAt") == null ? 0L : o.getLong("createdAt"));
                for (String k : new String[]{"command", "workingDir"}) {
                    if (o.getString(k) != null && !o.getString(k).isEmpty()) {
                        m.put(k, o.getString(k));
                    }
                }
                // 只在真有 args 时才取：JsonObject.getJsonArray 对缺失键抛 IllegalArgumentException
                if (o.get("args") instanceof JsonArray) {
                    JsonArray args = o.getJsonArray("args");
                    List<String> l = new ArrayList<String>();
                    for (int j = 0; j < args.size(); j++) {
                        l.add(args.getString(j));
                    }
                    m.put("args", l);
                }
                out.add(m);
            }
        } catch (Exception e) {
            // 目录文件坏掉不该把整个 /v1/agents 面打死：空目录继续，丢的只是自定义条目
            LOG.warn("agents.json 解析失败，按空目录继续: {} ({})", file, e.toString());
            return new ArrayList<Map<String, Object>>();
        }
        return out;
    }

    private void save(List<Map<String, Object>> all) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        root.put("agents", arr);
        for (Map<String, Object> a : all) {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Object> en : a.entrySet()) {
                o.put(en.getKey(), en.getValue());
            }
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
