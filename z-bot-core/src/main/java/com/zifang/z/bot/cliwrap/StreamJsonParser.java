package com.zifang.z.bot.cliwrap;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

/**
 * 统一流式 JSON 行解析器：从 CLI stdout 逐行提取 text delta。
 *
 * <p>支持多种格式（按实测）：
 * <ul>
 *   <li><b>stream_event</b>（claude code --include-partial-messages 真流式）：
 *       {@code {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}}
 *       → 提取 event.delta.text ★关键</li>
 *   <li><b>content_block_delta</b>：delta.text（兼容）</li>
 *   <li><b>assistant</b>：message.content[].text（兜底完整块）</li>
 *   <li><b>OpenAI choices</b>：choices[0].delta.content</li>
 *   <li><b>简单格式</b>：text / content / output 字段</li>
 * </ul>
 *
 * <p>过滤 CLI 元信息行（hermes session_id/Duration 等前缀）。
 */
public final class StreamJsonParser {

    private StreamJsonParser() {
    }

    /**
     * 从一行 stdout 提取文本增量。返回 null 表示该行无有效 delta。
     */
    public static String extractDelta(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String trimmed = line.trim();

        // 过滤 CLI 元信息行（hermes 等）
        if (trimmed.startsWith("session_id:")
                || trimmed.startsWith("Session:")
                || trimmed.startsWith("Duration:")
                || trimmed.startsWith("Messages:")
                || trimmed.startsWith("Resume this session")
                || trimmed.startsWith("hermes --resume")
                || trimmed.startsWith("Query:")) {
            return null;
        }

        // 非 JSON 行直接当文本处理，不进 parseObject——
        // 宽松语义与原 catch 兜底一致，但避免 z-util-json 对每条噪音行打 ParseException 栈
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }

        try {
            JsonObject json = JsonUtil.parseObject(line);
            String type = json.getString("type");

            // ★ stream_event（claude code --include-partial-messages 真流式）
            // {"type":"stream_event", "event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}
            if ("stream_event".equals(type)) {
                JsonObject event = json.getJsonObject("event");
                if (event != null) {
                    String eventType = event.getString("type");
                    if ("content_block_delta".equals(eventType) || "content_delta".equals(eventType)) {
                        JsonObject delta = event.getJsonObject("delta");
                        if (delta != null) {
                            // 只取 text_delta，跳过 thinking_delta/signature_delta
                            if ("text_delta".equals(delta.getString("type"))) {
                                String text = delta.getString("text");
                                if (text != null && !text.isEmpty()) {
                                    return text;
                                }
                            }
                        }
                    }
                }
                // stream_event 的其他子类型（message_start/content_block_start 等）不产生 text
                return null;
            }

            // content_block_delta（非嵌套形式，兼容）
            if ("content_block_delta".equals(type)) {
                JsonObject delta = json.getJsonObject("delta");
                if (delta != null) {
                    String text = delta.getString("text");
                    if (text != null) {
                        return text;
                    }
                }
            }

            // ★ claude code stream-json 的 assistant/result 是「完整快照」而非增量：
            // stream_event 的 content_block_delta 已提供全部 text_delta，
            // assistant(message.content[].text) 和 result 是同一内容的完整镜像。
            // 若当 delta 返回，会与 stream_event 重复累加（实测 "你好" 被输出 3 次）。
            // 故此处返回 null，仅依赖 stream_event 提供增量。
            // （ClaudeCodeWrapper 强制 --include-partial-messages，必有 stream_event）
            if ("assistant".equals(type) || "result".equals(type)) {
                return null;
            }

            // OpenAI 兼容: choices[0].delta.content
            JsonArray choices = json.getJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject choice = choices.getJsonObject(0);
                if (choice != null) {
                    JsonObject delta = choice.getJsonObject("delta");
                    if (delta != null) {
                        String content = delta.getString("content");
                        if (content != null) {
                            return content;
                        }
                    }
                    JsonObject message = choice.getJsonObject("message");
                    if (message != null) {
                        String content = message.getString("content");
                        if (content != null) {
                            return content;
                        }
                    }
                }
            }

            // 简单格式: text / content / output
            String text = json.getString("text");
            if (text != null) {
                return text;
            }
            String content = json.getString("content");
            if (content != null) {
                return content;
            }
            String output = json.getString("output");
            if (output != null) {
                return output;
            }

        } catch (Exception e) {
            // 形似 JSON 但解析失败（残缺行），不产生 delta
            return null;
        }

        return null;
    }
}
