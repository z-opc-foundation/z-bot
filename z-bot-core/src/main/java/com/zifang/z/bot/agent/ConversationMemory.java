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

    /**
     * 把文本追加到<b>最后一条 tool 结果</b>尾部（P12 的 steer 注入点）。
     *
     * <p>kernel {@link Msg} 是不可变的，所以这里是「就地替换那一条」而不是「改那一条」：
     * 同 id、同 role、同 toolCallId，只有 content 变长。</p>
     *
     * @return 命中并改写返回 {@code true}；对话里还没有 tool 结果时返回 {@code false}（调用方决定退路）
     */
    public synchronized boolean appendToLastToolResult(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MessageRole.TOOL) {
                String content = m.getContent() == null ? "" : m.getContent();
                messages.set(i, Msg.toolResult(m.getToolCallId(), content + suffix));
                return true;
            }
        }
        return false;
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
