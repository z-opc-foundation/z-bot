package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话内短期记忆：一条 system prompt + 按序的 kernel {@link Msg}。
 *
 * <p>engine 老 InMemoryMemory 的等价物；system prompt 不进 messages 列表，
 * 由 {@link BotAgent} 在每次请求时前置，避免落盘后重复堆积。</p>
 */
public final class ConversationMemory {

    private final List<Msg> messages = new ArrayList<Msg>();
    private String systemPrompt;

    public ConversationMemory() {
    }

    public ConversationMemory(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public synchronized void add(Msg msg) {
        if (msg != null) {
            messages.add(msg);
        }
    }

    public synchronized List<Msg> getMessages() {
        return Collections.unmodifiableList(new ArrayList<Msg>(messages));
    }

    /**
     * 只取对话消息（user / assistant / tool），用于落盘与会话恢复。
     */
    public synchronized List<Msg> getConversationMessages() {
        List<Msg> out = new ArrayList<Msg>();
        for (Msg m : messages) {
            if (m.getRole() != MessageRole.SYSTEM) {
                out.add(m);
            }
        }
        return out;
    }

    public synchronized void load(List<Msg> msgs) {
        messages.clear();
        if (msgs != null) {
            messages.addAll(msgs);
        }
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
