package com.zifang.z.bot.memory;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Toolkit;

import java.util.Map;

/**
 * memory 工具：agent 主动读写长期记忆三层。
 *
 * <p>action = read / append / rewrite / forget；
 * read 无害直接执行，append 低风险追加，rewrite（整页覆盖）与 forget（清空）
 * 属破坏性写 — 走 {@link Confirmations} 审批：首次调用返回 needsConfirmation，
 * agent 循环挂起等 /confirm，确认后 {@code confirmTool} 带章重放才真正落盘。</p>
 */
public final class MemoryTools {

    private MemoryTools() {
    }

    public static Tool memoryTool(final MemoryStore store) {
        return Toolkit.of("memory",
                "读写长期记忆（MEMORY.md）与用户画像（USER.md）。"
                        + "action: read | append | rewrite | forget；section: memory(默认) | user；"
                        + "rewrite/forget 需要用户确认",
                new com.zifang.z.agent.kernel.tool.ToolSchemaBuilder()
                        .string("action", "read | append | rewrite | forget")
                        .string("section", "memory | user，默认 memory", false)
                        .string("content", "append/rewrite 的内容", false)
                        .build(),
                args -> {
                    String action = str(args, "action").toLowerCase();
                    boolean user = "user".equalsIgnoreCase(str(args, "section"));
                    String content = str(args, "content");
                    try {
                        switch (action) {
                            case "read":
                                return ToolResult.text(user
                                        ? "USER.md:\n" + store.readUser()
                                        : "MEMORY.md:\n" + store.readMemory());
                            case "append":
                                if (content.trim().isEmpty()) {
                                    return ToolResult.error("参数错误：content 不能为空");
                                }
                                if (user) {
                                    store.appendUser(content);
                                } else {
                                    store.appendMemory(content);
                                }
                                return ToolResult.text("已写入 " + (user ? "USER" : "MEMORY") + ".md");
                            case "rewrite":
                                if (content.trim().isEmpty()) {
                                    return ToolResult.error("参数错误：rewrite 的 content 不能为空");
                                }
                                if (!Confirmations.alreadyConfirmed(args)) {
                                    return Confirmations.needsConfirmation(
                                            "整页重写 " + (user ? "USER" : "MEMORY") + ".md（覆盖现有内容）");
                                }
                                if (user) {
                                    store.rewriteUser(content);
                                } else {
                                    store.rewriteMemory(content);
                                }
                                return ToolResult.text("已重写 " + (user ? "USER" : "MEMORY") + ".md");
                            case "forget":
                                if (!Confirmations.alreadyConfirmed(args)) {
                                    return Confirmations.needsConfirmation(
                                            "清空 " + (user ? "USER" : "MEMORY") + ".md");
                                }
                                if (user) {
                                    store.clearUser();
                                } else {
                                    store.clearMemory();
                                }
                                return ToolResult.text("已清空 " + (user ? "USER" : "MEMORY") + ".md");
                            default:
                                return ToolResult.error("参数错误：action 必须是 read/append/rewrite/forget");
                        }
                    } catch (Exception e) {
                        return ToolResult.error("记忆操作失败: " + e.getMessage());
                    }
                });
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }
}
