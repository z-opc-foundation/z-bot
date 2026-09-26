package com.zifang.z.bot.memory;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Confirmations;
import com.zifang.z.bot.tool.Toolkit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * memory 工具：agent 主动读写长期记忆三层。
 *
 * <p>action = read / append / replace / remove / batch / rewrite / forget；
 * read 无害直接执行，条目级写（append/replace/remove/batch）走
 * {@link MemoryWriteGate} + {@link MemoryDriftGuard} 那套真门禁（内容扫描、
 * old_text 必填、预算、漂移拒写），rewrite（整页覆盖）与 forget（清空）
 * 属破坏性写 —— 走 {@link Confirmations} 审批：首次调用返回 needsConfirmation，
 * agent 循环挂起等 /confirm，确认后 {@code confirmTool} 带章重放才真正落盘。</p>
 *
 * <p>被门禁拒掉时回给模型的不是"失败"两个字，而是
 * {@code [code=…] + 原因 + 当前条目清单}：她 {@code _missing_old_text_error} 那段注释的理由
 * —— 只回"old_text is required"是个死胡同，模型拿不到现场就只会把同一个调用再发一遍。</p>
 */
public final class MemoryTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MemoryTools() {
    }

    public static Tool memoryTool(final MemoryStore store) {
        return Toolkit.of("memory",
                "读写长期记忆（MEMORY.md）与用户画像（USER.md）、人格（SOUL.md 只读）。"
                        + "action: read | append | replace | remove | batch | rewrite | forget；"
                        + "section: memory(默认) | user；replace/remove 必须给 old_text"
                        + "（要改/删那条里的一段唯一子串），batch 给 operations(JSON 数组)；"
                        + "rewrite/forget 需要用户确认",
                new com.zifang.z.agent.kernel.tool.ToolSchemaBuilder()
                        .string("action", "read | append | replace | remove | batch | rewrite | forget")
                        .string("section", "memory | user，默认 memory", false)
                        .string("content", "append/replace/rewrite 的内容", false)
                        .string("old_text", "replace/remove：定位已有条目用的唯一子串（缺则报错，不会新建）", false)
                        .string("operations", "batch 用：JSON 数组，元素形如 "
                                + "{\"action\":\"add|replace|remove\",\"content\":\"…\",\"old_text\":\"…\"}"
                                + "；整组要么全落要么全不落", false)
                        .build(),
                args -> {
                    String action = str(args, "action").toLowerCase();
                    MemorySection section = MemorySection.ofTargetName(str(args, "section"));
                    if (section == null) {
                        return ToolResult.error("参数错误：section 只能是 memory | user（不是 "
                                + str(args, "section") + "）");
                    }
                    String content = str(args, "content");
                    String oldText = str(args, "old_text");
                    try {
                        switch (action) {
                            case "read":
                                return ToolResult.text(readBlock(store, section));
                            case "append":
                                return receipt(store.appendEntry(section, content), section, "追加");
                            case "replace":
                                return receipt(store.replaceEntry(section, oldText, content),
                                        section, "替换");
                            case "remove":
                                return receipt(store.removeEntry(section, oldText), section, "删除");
                            case "batch":
                                return batch(store, section, args);
                            case "rewrite":
                                if (content.trim().isEmpty()) {
                                    return ToolResult.error("参数错误：rewrite 的 content 不能为空");
                                }
                                if (!Confirmations.alreadyConfirmed(args)) {
                                    return Confirmations.needsConfirmation(
                                            "整页重写 " + section.fileName() + "（覆盖现有内容）");
                                }
                                return receipt(store.rewritePage(section, content), section, "重写");
                            case "forget":
                                if (!Confirmations.alreadyConfirmed(args)) {
                                    return Confirmations.needsConfirmation(
                                            "清空 " + section.fileName());
                                }
                                return receipt(store.rewritePage(section, ""), section, "清空");
                            default:
                                return ToolResult.error("参数错误：action 必须是 "
                                        + "read/append/replace/remove/batch/rewrite/forget");
                        }
                    } catch (MemoryWriteRejectedException e) {
                        return rejected(e);
                    } catch (Exception e) {
                        return ToolResult.error("记忆操作失败: " + e.getMessage());
                    }
                });
    }

    /** 三份一并回读：SOUL 在 read 里看得见（它是身份），但条目级写不接受它。 */
    private static String readBlock(MemoryStore store, MemorySection section) {
        StringBuilder sb = new StringBuilder();
        sb.append(section.fileName()).append(":\n");
        String page = store.readPage(section);
        sb.append(page.isEmpty() ? "（空）" : page).append('\n');
        if (section.isEntryList()) {
            sb.append("（").append(section.fileName()).append(" 条目 ")
                    .append(store.entries(section).size()).append(" 条，占 ")
                    .append(store.charCount(section)).append('/').append(store.charLimit(section))
                    .append(" 字符）");
        }
        return sb.toString();
    }

    private static ToolResult receipt(MemoryReceipt r, MemorySection section, String verb) {
        String head = "已" + verb + " " + section.fileName() + "：" + r.usage();
        if (r.duplicateSkipped() && r.opsApplied() == 0) {
            return ToolResult.text(head + "（同正文条目已存在，本次未重复写入）");
        }
        return ToolResult.text(head);
    }

    private static ToolResult batch(MemoryStore store, MemorySection section,
                                    Map<String, Object> args) throws Exception {
        List<MemoryOp> ops = parseOps(args.get("operations"));
        if (ops.isEmpty()) {
            return ToolResult.error("参数错误：batch 需要非空的 operations（JSON 数组）");
        }
        return receipt(store.applyBatch(section, ops), section,
                "批量应用 " + ops.size() + " 条操作于");
    }

    /**
     * operations 支持两种来法：模型给的 JSON 字符串，或已被上游解析成的 List/Map 结构。
     * 解析不动 ⇒ 抛 {@link MemoryWriteRejectedException}（BAD_OPERATION），
     * 不静默当空批量。
     */
    static List<MemoryOp> parseOps(Object raw) throws MemoryWriteRejectedException {
        List<MemoryOp> out = new ArrayList<MemoryOp>();
        if (raw == null) {
            return out;
        }
        JsonNode node;
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) {
                return out;
            }
            try {
                node = JSON.readTree(s);
            } catch (Exception e) {
                throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                        "operations 不是合法 JSON：" + e.getMessage());
            }
        } else if (raw instanceof List || raw instanceof Map) {
            node = JSON.convertValue(raw, JsonNode.class);
        } else {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                    "operations 类型不支持：" + raw.getClass().getName());
        }
        if (!node.isArray()) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                    "operations 必须是 JSON 数组，实际是 " + node.getNodeType());
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            JsonNode el = it.next();
            if (!el.isObject()) {
                throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                        "operations 第 " + (out.size() + 1) + " 个元素不是对象。");
            }
            MemoryOp.Kind kind = MemoryOp.Kind.of(text(el, "action"));
            if (kind == null) {
                throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                        "operations 第 " + (out.size() + 1) + " 个元素的 action 不认识："
                                + text(el, "action") + "（只能是 add/replace/remove）");
            }
            out.add(new MemoryOp(kind, text(el, "content"), text(el, "old_text")));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** 门禁拒绝 → 带机读码 + 现场的回执。 */
    static ToolResult rejected(MemoryWriteRejectedException e) {
        StringBuilder sb = new StringBuilder();
        sb.append("记忆写入被门禁拒绝 [code=").append(e.code()).append(']');
        if (e.opIndex() > 0) {
            sb.append(" [op=").append(e.opIndex()).append(']');
        }
        sb.append("：").append(e.getMessage());
        if (e.bakPath() != null) {
            sb.append(" [drift_backup=").append(e.bakPath()).append(']');
        }
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<String, Object>();
        meta.put("memoryGateCode", e.code());
        if (e.opIndex() > 0) {
            meta.put("memoryGateOpIndex", Integer.valueOf(e.opIndex()));
        }
        if (e.bakPath() != null) {
            meta.put("memoryDriftBackup", e.bakPath());
        }
        return new ToolResult(null, null, sb.toString(), true,
                java.util.Collections.unmodifiableMap(meta));
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : v.toString();
    }
}
