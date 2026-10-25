package com.zifang.z.bot.cliwrap;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionRequest;
import com.zifang.z.bot.cliwrap.dto.ChatCompletionResponse;
import com.zifang.z.bot.cliwrap.CliAgentConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hermes CLI agent wrapper：接口形式与 {@link CliAgentWrapper} 一致，
 * 内部通过 hermes gateway HTTP API 实现真流式（逐 token SSE）。
 *
 * <p>gateway 提供 OpenAI 兼容的 {@code POST /v1/chat/completions}，
 * 通过 {@code stream_delta_callback} 逐 token 写 SSE。
 */
public class HermesProcessWrapper implements CliAgentWrapper {

    private static final Logger LOG = Logger.getLogger(HermesProcessWrapper.class.getName());
    private static final String GATEWAY_URL = "http://127.0.0.1:8642/v1/chat/completions";
    private static final String API_KEY_FILE = "/tmp/hermes_api_key.txt";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10 * 60_000;

    @Override
    public boolean supports(String type) {
        return "hermes".equals(type);
    }

    @Override
    public String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        String completionId = "chatcmpl-cli-" + RandomUtil.uuidCompact();
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();
        StringBuilder fullReply = new StringBuilder();

        HttpURLConnection conn = openGatewayConnection(request, true);
        try {
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String payload = line.substring(6).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String delta = StreamJsonParser.extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    fullReply.append(delta);
                    String sseChunk = ChatCompletionResponse.sseChunk(completionId, model, created, delta);
                    sseOut.write(sseChunk.getBytes(StandardCharsets.UTF_8));
                    sseOut.flush();
                }
            }

            sseOut.write(ChatCompletionResponse.sseDone().getBytes(StandardCharsets.UTF_8));
            sseOut.flush();

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Gateway stream error: " + e.getMessage(), e);
            if (fullReply.length() == 0) {
                sseOut.write(ChatCompletionResponse.sseError("Gateway error: " + e.getMessage())
                        .getBytes(StandardCharsets.UTF_8));
                sseOut.flush();
            }
        } finally {
            conn.disconnect();
        }

        return fullReply.toString();
    }

    @Override
    public String syncChat(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        HttpURLConnection conn = openGatewayConnection(request, false);
        StringBuilder fullReply = new StringBuilder();

        try {
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String payload = line.substring(6).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String delta = StreamJsonParser.extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    fullReply.append(delta);
                }
            }

        } catch (IOException e) {
            throw new IOException("Gateway sync error: " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }

        return fullReply.toString();
    }

    // ──────────── 内部方法 ────────────

    /**
     * 建立 gateway HTTP 连接，发送 OpenAI 兼容请求。
     */
    private HttpURLConnection openGatewayConnection(ChatCompletionRequest request, boolean stream)
            throws IOException {
        String apiKey = readApiKey();
        URL url = new URL(GATEWAY_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        // 构建请求体：直接透传 messages 数组
        StringBuilder body = new StringBuilder();
        body.append("{\"messages\":[");
        if (request.getMessages() != null) {
            for (int i = 0; i < request.getMessages().size(); i++) {
                ChatCompletionRequest.ChatMessage msg = request.getMessages().get(i);
                if (i > 0) {
                    body.append(",");
                }
                String role = msg.getRole() != null ? msg.getRole() : "user";
                String content = msg.getContent() != null ? msg.getContent() : "";
                body.append("{\"role\":\"")
                        .append(escapeJson(role))
                        .append("\",\"content\":\"")
                        .append(escapeJson(content))
                        .append("\"}");
            }
        }
        body.append("],\"stream\":").append(stream).append("}");

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readErrorStream(conn);
            throw new IOException("Gateway returned HTTP " + code
                    + (errBody != null && !errBody.isEmpty() ? ": " + errBody : ""));
        }

        LOG.info("Gateway connected: stream=" + stream + ", model=" + request.getModel());
        return conn;
    }

    /**
     * 从文件读取 API key。
     */
    private String readApiKey() {
        try {
            String key = new String(Files.readAllBytes(Paths.get(API_KEY_FILE)), StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                throw new IOException("API key file is empty: " + API_KEY_FILE);
            }
            return key;
        } catch (IOException e) {
            LOG.warning("Failed to read API key from " + API_KEY_FILE + ": " + e.getMessage());
            throw new RuntimeException("Hermes gateway API key not found: " + e.getMessage(), e);
        }
    }

    /**
     * 读取错误响应体。
     */
    private String readErrorStream(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            if (es == null) {
                return "";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
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
