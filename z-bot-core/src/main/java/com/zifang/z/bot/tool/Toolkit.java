package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.BaseTool;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具注册表 — 持有 kernel {@link Tool} 实例，按名字派发。
 *
 * <p>同名注册会覆盖（与 z-opc 老 bot 行为一致：ZAgent 先注册裸 read_file/exec，
 * TerminalBot 再用带沙箱的版本盖掉）。</p>
 */
public final class Toolkit {

    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public Toolkit register(Tool tool) {
        if (tool == null || tool.getName() == null || tool.getName().isEmpty()) {
            throw new IllegalArgumentException("tool 及 tool.name 不能为空");
        }
        tools.put(tool.getName(), tool);
        return this;
    }

    /**
     * 用 lambda 快速注册一个工具（内置工具和测试桩都走这里）。
     */
    public Toolkit register(String name, String description, Map<String, Object> schema,
                            Function<Map<String, Object>, ToolResult> handler) {
        return register(of(name, description, schema, handler));
    }

    public static Tool of(String name, String description, Map<String, Object> schema,
                          final Function<Map<String, Object>, ToolResult> handler) {
        return new BaseTool(name, description, schema == null ? emptyObjectSchema() : schema) {
            @Override
            protected ToolResult doExecute(Map<String, Object> args) {
                return handler.apply(args);
            }
        };
    }

    /**
     * 无参工具也要给出合法 JSON Schema：kernel 的 BaseTool 缺省给空 Map，
     * 而 OpenAI 的 {@code tools[].function.parameters} 需要 type/properties 才不被拒。
     */
    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 执行工具。工具不存在 / 抛异常都转成 error {@link ToolResult}，不向外抛。
     */
    public ToolResult execute(String name, Map<String, Object> args) {
        Tool tool = get(name);
        if (tool == null) {
            return ToolResult.failure(null, name, "未找到工具: " + name);
        }
        try {
            ToolResult raw = tool.execute(args == null ? new LinkedHashMap<String, Object>() : args);
            if (raw == null) {
                return ToolResult.failure(null, name, "工具 " + name + " 返回 null");
            }
            return withName(raw, name);
        } catch (Exception e) {
            return ToolResult.failure(null, name, "工具 " + name + " 执行失败: " + e.getMessage());
        }
    }

    public int size() {
        return tools.size();
    }

    public List<String> getToolNames() {
        return Collections.unmodifiableList(new ArrayList<String>(tools.keySet()));
    }

    public List<Tool> getAllTools() {
        return Collections.unmodifiableList(new ArrayList<Tool>(tools.values()));
    }

    /**
     * 供 system prompt 使用的工具清单文本。
     */
    public String getToolsDescription() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.getName()).append(": ")
                    .append(t.getDescription() == null ? "(no description)" : t.getDescription())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * kernel 的 {@code ToolResult.text()/error()} 不带 name，回灌消息前补齐工具名。
     */
    private static ToolResult withName(ToolResult result, String name) {
        if (name.equals(result.getName())) {
            return result;
        }
        return new ToolResult(result.getCallId(), name, result.getContent(),
                result.isError(), result.getMetadata());
    }
}
