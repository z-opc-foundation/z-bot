package com.zifang.z.bot.cliwrap.dto;

import com.zifang.util.core.lang.RandomUtil;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.util.*;

/**
 * OpenAI {@code POST /v1/chat/completions} 非流式响应（子集）。
 */
public class ChatCompletionResponse {

    private String id;
    private String object = "chat.completion";
    private long created;
    private String model;
    private List<Choice> choices = new ArrayList<Choice>();

    public static ChatCompletionResponse of(String model, String content) {
        ChatCompletionResponse resp = new ChatCompletionResponse();
        resp.setId("chatcmpl-local-" + RandomUtil.uuidCompact());
        resp.setCreated(System.currentTimeMillis() / 1000L);
        resp.setModel(model != null ? model : "z-team-local");

        Choice choice = new Choice();
        choice.setIndex(0);
        choice.setFinishReason("stop");

        Choice.AssistantMessage msg = new Choice.AssistantMessage();
        msg.setRole("assistant");
        msg.setContent(content != null ? content : "");
        choice.setMessage(msg);

        resp.setChoices(Collections.singletonList(choice));
        return resp;
    }

    /**
     * 构造 SSE 流式 chunk: {@code data: {"choices":[{"delta":{"content":"..."}}]}}
     */
    public static String sseChunk(String completionId, String model, long created, String deltaContent) {
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("content", deltaContent);

        Map<String, Object> choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", completionId);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", model != null ? model : "z-team-local");
        body.put("choices", Collections.singletonList(choice));

        return "data: " + JsonUtil.toJson(body) + "\n\n";
    }

    /**
     * SSE 思考增量 chunk: {@code data: {"choices":[{"delta":{"reasoning_content":"..."}}]}}
     */
    public static String sseReasoningChunk(String completionId, String model, long created, String deltaThinking) {
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("reasoning_content", deltaThinking);

        Map<String, Object> choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", completionId);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", model != null ? model : "z-team-local");
        body.put("choices", Collections.singletonList(choice));

        return "data: " + JsonUtil.toJson(body) + "\n\n";
    }

    /**
     * SSE 结构化事件 chunk（工具调用生命周期等非文本事件）:
     * {@code data: {"id":...,"choices":[{"index":0,"delta":{}}],"z_team_event":{...}}}
     *
     * <p>事件负载约定（z_team_event.kind）：
     * <ul>
     *   <li>{@code tool_call}  — 工具开始执行：{id, name}（input 未知时省略）</li>
     *   <li>{@code tool_end}   — 工具入参收集完毕：{id, input}</li>
     *   <li>{@code tool_result}— 工具执行结果回传：{id, content}</li>
     * </ul>
     */
    public static String sseEvent(String completionId, String model, long created, JsonObject eventPayload) {
        Map<String, Object> delta = new LinkedHashMap<String, Object>();

        Map<String, Object> choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("id", completionId);
        body.put("object", "chat.completion.chunk");
        body.put("created", created);
        body.put("model", model != null ? model : "z-team-local");
        body.put("choices", Collections.singletonList(choice));
        body.put("z_team_event", toPlainMap(eventPayload));

        return "data: " + JsonUtil.toJson(body) + "\n\n";
    }

    /**
     * SSE 结束标记。
     */
    public static String sseDone() {
        return "data: [DONE]\n\n";
    }

    /**
     * SSE 错误标记。
     */
    public static String sseError(String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("error", message);
        return "data: " + JsonUtil.toJson(error) + "\n\n";
    }

    /**
     * JsonObject 深度转普通 Map：z-util 的 toJson 对 JsonObject 走 beautify 多行输出，
     * SSE 帧必须单行，故先摊平成 Map 再交给 solveMap 紧凑序列化。
     */
    private static Map<String, Object> toPlainMap(JsonObject obj) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : obj.getAllKeyValue()) {
            Object v = e.getValue();
            if (v instanceof JsonObject) {
                v = toPlainMap((JsonObject) v);
            } else if (v instanceof JsonArray) {
                JsonArray arr = (JsonArray) v;
                List<Object> list = new ArrayList<Object>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    list.add(item instanceof JsonObject ? toPlainMap((JsonObject) item) : item);
                }
                v = list;
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    // ──────────── SSE 流式 chunk 构建 ────────────

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public String toJson() {
        return JsonUtil.toJson(this);
    }

    public static class Choice {
        private int index;
        private AssistantMessage message;
        private String finishReason;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public AssistantMessage getMessage() {
            return message;
        }

        public void setMessage(AssistantMessage message) {
            this.message = message;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }

        public static class AssistantMessage {
            private String role = "assistant";
            private String content;

            public String getRole() {
                return role;
            }

            public void setRole(String role) {
                this.role = role;
            }

            public String getContent() {
                return content;
            }

            public void setContent(String content) {
                this.content = content;
            }
        }
    }
}
