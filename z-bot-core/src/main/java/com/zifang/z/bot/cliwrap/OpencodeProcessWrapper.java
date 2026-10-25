package com.zifang.z.bot.cliwrap;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionResponse;
import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * opencode CLI agent wrapper：接口形式与 {@link CliAgentWrapper} 一致，
 * 内部通过 opencode server HTTP API 交互。
 *
 * <p>opencode server 提供 session-based API：
 * <ul>
 *   <li>POST /session → 创建会话</li>
 *   <li>POST /session/{id}/message → 发送消息，获取完整回复</li>
 * </ul>
 *
 * <p>回复 parts 中 type=text 的部分即为文本内容，
 * 按行拆分后逐行推送 SSE chunk（行级流式）。
 */
public class OpencodeProcessWrapper implements CliAgentWrapper {

    private static final Logger LOG = Logger.getLogger(OpencodeProcessWrapper.class.getName());
    private static final String SERVER_URL = "http://127.0.0.1:8643";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10 * 60_000;

    @Override
    public boolean supports(String type) {
        return "opencode".equals(type);
    }

    @Override
    public String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        String completionId = "chatcmpl-cli-" + RandomUtil.uuidCompact();
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();
        StringBuilder fullReply = new StringBuilder();

        try {
            String sessionId = createSession();
            String prompt = buildPrompt(request);
            String responseBody = sendMessage(sessionId, prompt);

            JsonObject json = JsonUtil.parseObject(responseBody);
            JsonArray parts = json.getJsonArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.size(); i++) {
                    JsonObject part = parts.getJsonObject(i);
                    if (part != null && "text".equals(part.getString("type"))) {
                        String text = part.getString("text");
                        if (text != null && !text.isEmpty()) {
                            fullReply.append(text);
                            // 按行拆分推送 SSE chunk
                            String[] lines = text.split("\n");
                            for (String line : lines) {
                                String delta = line + "\n";
                                String sseChunk = ChatCompletionResponse.sseChunk(completionId, model, created, delta);
                                sseOut.write(sseChunk.getBytes(StandardCharsets.UTF_8));
                                sseOut.flush();
                            }
                        }
                    }
                }
            }

            sseOut.write(ChatCompletionResponse.sseDone().getBytes(StandardCharsets.UTF_8));
            sseOut.flush();

        } catch (IOException e) {
            LOG.log(Level.WARNING, "opencode server error: " + e.getMessage(), e);
            if (fullReply.length() == 0) {
                sseOut.write(ChatCompletionResponse.sseError("opencode server error: " + e.getMessage())
                        .getBytes(StandardCharsets.UTF_8));
                sseOut.flush();
            }
        }

        return fullReply.toString();
    }

    @Override
    public String syncChat(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        String sessionId = createSession();
        String prompt = buildPrompt(request);
        String responseBody = sendMessage(sessionId, prompt);
        StringBuilder fullReply = new StringBuilder();

        JsonObject json = JsonUtil.parseObject(responseBody);
        JsonArray parts = json.getJsonArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.getJsonObject(i);
                if (part != null && "text".equals(part.getString("type"))) {
                    String text = part.getString("text");
                    if (text != null && !text.isEmpty()) {
                        fullReply.append(text);
                    }
                }
            }
        }

        return fullReply.toString();
    }

    // ──────────── 内部方法 ────────────

    /**
     * 创建 opencode 会话。
     */
    private String createSession() throws IOException {
        URL url = new URL(SERVER_URL + "/session");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("opencode createSession returned HTTP " + code);
        }

        String body = readStream(conn.getInputStream());
        conn.disconnect();

        JsonObject json = JsonUtil.parseObject(body);
        String sessionId = json.getString("id");
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IOException("opencode createSession: no session id in response");
        }
        LOG.info("opencode session created: " + sessionId);
        return sessionId;
    }

    /**
     * 向会话发送消息，返回完整响应体。
     */
    private String sendMessage(String sessionId, String prompt) throws IOException {
        URL url = new URL(SERVER_URL + "/session/" + sessionId + "/message");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        // opencode message 格式：{"parts":[{"type":"text","text":"prompt"}]}
        String body = "{\"parts\":[{\"type\":\"text\",\"text\":\"" + escapeJson(prompt) + "\"}]}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("opencode sendMessage returned HTTP " + code
                    + (errBody != null && !errBody.isEmpty() ? ": " + errBody : ""));
        }

        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();
        LOG.info("opencode message sent, response received");
        return responseBody;
    }

    /**
     * 从 messages 数组构建 prompt 字符串。
     */
    private String buildPrompt(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.ChatMessage msg : request.getMessages()) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            String role = msg.getRole();
            if (role == null) {
                role = "user";
            }
            if ("system".equalsIgnoreCase(role)) {
                sb.append("[System Instructions]\n").append(msg.getContent()).append("\n\n");
            } else if ("assistant".equalsIgnoreCase(role)) {
                sb.append("[Assistant]\n").append(msg.getContent()).append("\n\n");
            } else {
                sb.append(msg.getContent()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 读取 InputStream 为字符串。
     */
    private String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * JSON 字符串转义。
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
