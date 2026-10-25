package com.zifang.z.bot.cliwrap.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话元信息，对应 meta.json。
 */
public class SessionInfo {

    private String id;
    private String title;
    private long createdAt;
    private String model;
    private long updatedAt;
    private String agentId;
    private List<ChatMessageInfo> messages = new ArrayList<ChatMessageInfo>();

    public SessionInfo() {
    }

    public SessionInfo(String id, String title, String model) {
        this.id = id;
        this.title = title;
        this.model = model;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<ChatMessageInfo> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessageInfo> messages) {
        this.messages = messages;
    }

    public static class ChatMessageInfo {
        private String role;
        private String content;
        private long timestamp;

        public ChatMessageInfo() {
        }

        public ChatMessageInfo(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

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

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
