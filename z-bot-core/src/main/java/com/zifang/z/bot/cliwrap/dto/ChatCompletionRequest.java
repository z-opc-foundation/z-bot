package com.zifang.z.bot.cliwrap.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI {@code POST /v1/chat/completions} 请求体（子集，与 OpenAI 兼容）。
 */
public class ChatCompletionRequest {

    private String model;
    private List<ChatMessage> messages = new ArrayList<ChatMessage>();
    private Boolean stream;
    /**
     * 自定义扩展字段：本地会话 ID
     */
    private String sessionId;
    /**
     * 自定义扩展字段：Agent 类型（jar/hermes/claude-code），为空则默认 jar
     */
    private String agentType;
    /**
     * 自定义扩展字段：CLI Agent ID（agentType 非 jar 时使用）
     */
    private String agentId;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public Boolean getStream() {
        return stream;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isStream() {
        return Boolean.TRUE.equals(stream);
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * 判断是否为 CLI Agent 类型（hermes / claude-code）。
     */
    public boolean isCliAgent() {
        return agentType != null
                && ("hermes".equals(agentType) || "claude-code".equals(agentType));
    }

    public static class ChatMessage {
        private String role;
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
