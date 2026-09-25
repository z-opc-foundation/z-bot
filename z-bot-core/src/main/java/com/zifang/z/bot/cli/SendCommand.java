package com.zifang.z.bot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.bot.config.BotConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code z-bot send <channel> <conversationId> <text>} — 单条消息脚本化外发到 channel。
 * 当前支持 webhook（POST /webhook/in 让 gateway 收消息并异步回 conversationId）。
 *
 * <p>设计目的：CI / 定时任务 / 告警脚本能直接驱动 z-bot，绕开 TUI/IDE。</p>
 */
@Command(name = "send", description = "脚本化外发：把一条消息送给指定 channel 的会话")
public class SendCommand implements Callable<Integer> {

    @Mixin
    public AgentOptions options = new AgentOptions();

    @Parameters(index = "0", paramLabel = "CHANNEL", description = "目标 channel 名（webhook/http）")
    String channel;

    @Parameters(index = "1", paramLabel = "CONVERSATION", description = "目标会话 id")
    String conversationId;

    @Parameters(index = "2", paramLabel = "TEXT", description = "消息文本")
    String text;

    @Option(names = {"--webhook-url"}, defaultValue = "http://127.0.0.1:8090/webhook/in",
            description = "webhook 通道地址（仅 webhook 模式）")
    String webhookUrl;

    @Override
    public Integer call() throws Exception {
        if ("webhook".equalsIgnoreCase(channel)) {
            return sendWebhook();
        }
        if ("http".equalsIgnoreCase(channel)) {
            return sendHttp();
        }
        System.err.println("暂不支持的 channel: " + channel + "（当前仅 webhook/http）");
        return 2;
    }

    private int sendWebhook() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("conversationId", conversationId);
        body.put("text", text);
        body.put("senderId", System.getProperty("user.name"));
        byte[] payload = json.writeValueAsBytes(body);
        java.net.HttpURLConnection con = (java.net.HttpURLConnection)
                new java.net.URL(webhookUrl).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(3000);
        con.setReadTimeout(5000);
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (java.io.OutputStream os = con.getOutputStream()) {
            os.write(payload);
        }
        int rc = con.getResponseCode();
        try (java.io.InputStream is = rc < 400 ? con.getInputStream() : con.getErrorStream()) {
            byte[] resp = readAll(is);
            System.out.println("[" + rc + "] " + new String(resp, java.nio.charset.StandardCharsets.UTF_8));
        }
        return rc < 400 ? 0 : 4;
    }

    private int sendHttp() throws Exception {
        // 复用 BotAgent 单轮对话
        return ChatCommand.run(options, text, false);
    }

    private static byte[] readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}