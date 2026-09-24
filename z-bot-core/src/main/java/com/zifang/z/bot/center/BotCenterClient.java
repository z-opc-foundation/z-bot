package com.zifang.z.bot.center;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * z-bot → z-agent-center HTTP 客户端。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启动时向 center 注册（POST /api/agent/skill/register），拿 instanceCode + authToken</li>
 *   <li>每 30s 心跳（POST /api/agent/skill/heartbeat）</li>
 *   <li>每次 chat 后 fire postChat 事件（POST /api/bot/event）让 center 侧 hook 跑</li>
 *   <li>拉取 pending skills 并写入 {@code ~/.zbot/skills/<instanceCode>/<skillCode>/SKILL.md}</li>
 * </ul>
 *
 * <p>所有调用都是 best-effort：center 挂了 / 没起，z-bot 继续按本地模式工作。</p>
 */
public class BotCenterClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String centerBaseUrl;
    private final File zbotDir;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 来自 /register 的 instanceCode（存 ~/.zbot/instance.json 以便跨重启复用）。 */
    private String instanceCode;
    private String authToken;
    private String callbackUrl;

    public BotCenterClient(String centerBaseUrl) {
        this(centerBaseUrl, new File(System.getProperty("user.home") + "/.zbot"));
    }

    public BotCenterClient(String centerBaseUrl, File zbotDir) {
        this.centerBaseUrl = centerBaseUrl == null ? null : centerBaseUrl.replaceAll("/$", "");
        this.zbotDir = zbotDir;
    }

    public boolean isEnabled() {
        return centerBaseUrl != null && !centerBaseUrl.isEmpty();
    }

    public String getCenterBaseUrl() {
        return centerBaseUrl;
    }

    /**
     * 启动注册（首次或重启时复用 ~/.zbot/instance.json）。
     *
     * @param appCode     应用编码
     * @param userId      用户 ID
     * @param userName    用户名
     * @param callbackUrl 本机回调（PUSH 模式 / 健康检查用；纯 PULL 传 null）
     * @param version     z-bot 版本
     * @return 注册成功返回 true
     */
    @SuppressWarnings("unchecked")
    public synchronized boolean register(String appCode, String userId, String userName,
                                         String callbackUrl, String version) {
        if (!isEnabled()) {
            return false;
        }

        InstanceId saved = loadSavedInstance();
        if (saved != null) {
            this.instanceCode = saved.instanceCode;
            this.authToken = saved.authToken;
            this.callbackUrl = callbackUrl != null ? callbackUrl : saved.callbackUrl;
            if (sendHeartbeatInternal("ACTIVE", version)) {
                log("复用已存在的注册: instanceCode=" + instanceCode);
                return true;
            }
            log("已存 token 被拒，重新注册…");
        }

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("appCode", appCode);
        body.put("userId", userId == null ? "bot" : userId);
        body.put("userName", userName == null ? "z-bot" : userName);
        body.put("agentType", "ZBOT");
        body.put("callbackUrl", callbackUrl);
        body.put("agentVersion", version);
        body.put("capabilities", "{\"tools\":[\"read_file\",\"exec\"],\"skillSlots\":50}");

        try {
            String json = objectMapper.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(centerBaseUrl + "/api/agent/skill/register")
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    warn("注册失败: HTTP " + resp.code());
                    return false;
                }
                Map<String, Object> respBody = objectMapper.readValue(resp.body().bytes(), Map.class);
                Object data = respBody.get("data");
                if (!(data instanceof Map)) {
                    return false;
                }
                Map<String, Object> dataMap = (Map<String, Object>) data;
                this.instanceCode = (String) dataMap.get("instanceCode");
                this.authToken = (String) dataMap.get("authToken");
                this.callbackUrl = callbackUrl != null ? callbackUrl : (String) dataMap.get("skillRootPath");
                saveInstance();
                log("注册成功: instanceCode=" + instanceCode + " skillRootPath=" + dataMap.get("skillRootPath"));
                return true;
            }
        } catch (IOException e) {
            warn("注册失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送一次心跳（best-effort）。
     */
    public boolean heartbeat(String status, String version) {
        if (!isEnabled() || instanceCode == null) {
            return false;
        }
        return sendHeartbeatInternal(status, version);
    }

    private boolean sendHeartbeatInternal(String status, String version) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("instanceCode", instanceCode);
        body.put("status", status);
        body.put("agentVersion", version);
        try {
            String json = objectMapper.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(centerBaseUrl + "/api/agent/skill/heartbeat")
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (IOException e) {
            debug("heartbeat 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 拉取本实例 pending skills 的内容，写入 {@code ~/.zbot/skills/<instanceCode>/<skillCode>/SKILL.md}。
     *
     * @return 成功写入的 skill 数
     */
    public int syncSkills() {
        if (!isEnabled() || instanceCode == null) {
            return 0;
        }
        List<String> pending = fetchPendingSkills();
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int wrote = 0;
        for (String skillCode : pending) {
            String content = fetchSkillContent(skillCode);
            if (content == null) {
                continue;
            }
            try {
                File skillFile = resolveSkillPath(skillCode);
                skillFile.getParentFile().mkdirs();
                try (java.io.FileWriter fw = new java.io.FileWriter(skillFile)) {
                    fw.write(content);
                }
                wrote++;
                log("Skill 已落盘: " + skillCode + " -> " + skillFile);
            } catch (IOException e) {
                warn("写 skill " + skillCode + " 失败: " + e.getMessage());
            }
        }
        return wrote;
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchPendingSkills() {
        try {
            Request req = new Request.Builder()
                    .url(centerBaseUrl + "/api/agent/skill/pull?instanceCode=" + instanceCode)
                    .header("Authorization", "Bearer " + authToken)
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return null;
                }
                Map<String, Object> body = objectMapper.readValue(resp.body().bytes(), Map.class);
                Object data = body.get("data");
                if (!(data instanceof Map)) {
                    return null;
                }
                Object skillCodes = ((Map<String, Object>) data).get("skillCodes");
                if (skillCodes instanceof List) {
                    return (List<String>) skillCodes;
                }
                return Collections.emptyList();
            }
        } catch (IOException e) {
            debug("fetchPendingSkills 失败: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchSkillContent(String skillCode) {
        try {
            Request req = new Request.Builder()
                    .url(centerBaseUrl + "/api/skill/content?skillCode=" + skillCode)
                    .get()
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return null;
                }
                Map<String, Object> body = objectMapper.readValue(resp.body().bytes(), Map.class);
                Object data = body.get("data");
                return data == null ? null : data.toString();
            }
        } catch (IOException e) {
            debug("fetchSkillContent(" + skillCode + ") 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 拉取长期记忆段落（拼到 system prompt 开头）。
     *
     * <p>center 侧 BotMemoryService 存在但没有对外 REST 端点，所以这里恒返回 null —
     * 与 z-opc 老 bot 行为一致，等 center 补端点后再接。</p>
     */
    public String recallMemory() {
        if (!isEnabled() || instanceCode == null) {
            return null;
        }
        return null;
    }

    /**
     * 发送 postChat 事件给 center（让 center 的 BotHook 跑）。
     */
    public void firePostChat(String userMessage, String reply, String skillCodes) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("instanceCode", instanceCode);
        body.put("userId", "bot");
        body.put("message", userMessage);
        body.put("reply", reply);
        body.put("skillCodes", skillCodes);
        body.put("eventType", "postChat");
        postJson("/api/bot/event", body, "firePostChat");
    }

    /**
     * 提交用户反馈（thumbs up/down）给 center。
     */
    public void submitFeedback(int rating, String comment, String appCode, String skillCodes) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("instanceCode", instanceCode);
        body.put("rating", rating);
        body.put("comment", comment);
        body.put("appCode", appCode);
        body.put("skillCodes", skillCodes);
        postJson("/api/bot/feedback", body, "submitFeedback");
    }

    private void postJson(String path, Map<String, Object> body, String what) {
        if (!isEnabled() || instanceCode == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(centerBaseUrl + path)
                    .header("Authorization", "Bearer " + authToken)
                    .post(RequestBody.create(json, JSON))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    debug(what + " 非 2xx: " + resp.code());
                }
            }
        } catch (IOException e) {
            debug(what + " 失败: " + e.getMessage());
        }
    }

    public String getInstanceCode() {
        return instanceCode;
    }

    /**
     * SKILL.md 落盘路径：{@code ~/.zbot/skills/<instanceCode>/<skillCode>/SKILL.md}
     */
    public File resolveSkillPath(String skillCode) {
        return new File(zbotDir, "skills/" + instanceCode + "/" + skillCode + "/SKILL.md");
    }

    public File skillsRoot() {
        return new File(zbotDir, "skills/" + instanceCode);
    }

    private void saveInstance() {
        if (instanceCode == null) {
            return;
        }
        try {
            File f = new File(zbotDir, "instance.json");
            f.getParentFile().mkdirs();
            Map<String, Object> body = new HashMap<String, Object>();
            body.put("instanceCode", instanceCode);
            body.put("authToken", authToken);
            body.put("callbackUrl", callbackUrl);
            try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
                objectMapper.writeValue(fw, body);
            }
        } catch (Exception e) {
            warn("saveInstance 失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private InstanceId loadSavedInstance() {
        try {
            File f = new File(zbotDir, "instance.json");
            if (!f.exists()) {
                return null;
            }
            Map<String, Object> body = objectMapper.readValue(f, Map.class);
            String code = (String) body.get("instanceCode");
            String token = (String) body.get("authToken");
            String url = (String) body.get("callbackUrl");
            if (code == null || token == null) {
                return null;
            }
            return new InstanceId(code, token, url);
        } catch (Exception e) {
            return null;
        }
    }

    private static void log(String msg) {
        System.out.println("[bot-center] " + msg);
    }

    private static void warn(String msg) {
        System.err.println("[bot-center] " + msg);
    }

    private static void debug(String msg) {
        if (Boolean.getBoolean("zbot.debug")) {
            System.out.println("[bot-center] " + msg);
        }
    }

    private static class InstanceId {
        final String instanceCode;
        final String authToken;
        final String callbackUrl;

        InstanceId(String c, String t, String u) {
            this.instanceCode = c;
            this.authToken = t;
            this.callbackUrl = u;
        }
    }
}
