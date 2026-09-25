package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.tool.BaseTool;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个 {@link McpClient} 上的所有工具桥接成 {@link Toolkit} 里的 kernel 工具，
 * 命名统一为 {@code mcp-<server>-<tool>}（中划线避免被工具调用解析歧义）。
 *
 * <p>每次注册会覆盖同名工具；关闭时统一 unregister。</p>
 */
public final class McpBridge {

    private static final Logger LOG = LoggerFactory.getLogger(McpBridge.class);

    private final McpClient client;
    private final Toolkit toolkit;
    private final List<String> registered = new ArrayList<String>();

    public McpBridge(McpClient client, Toolkit toolkit) {
        this.client = client;
        this.toolkit = toolkit;
    }

    public String serverName() {
        return client.name();
    }

    /** 连接并把 tools 全部注册进 toolkit。失败抛 RuntimeException 不静默。 */
    public int registerAll() {
        client.connect();
        List<McpClient.McpTool> tools;
        try {
            tools = client.listTools();
        } catch (RuntimeException e) {
            client.disconnect();
            throw e;
        }
        for (McpClient.McpTool t : tools) {
            String name = bridgeName(client.name(), t.name);
            if (toolkit.contains(name)) {
                LOG.warn("[mcp:{}] 工具 {} 已存在，覆盖旧实现", client.name(), name);
            }
            toolkit.register(toKernelTool(name, t));
            registered.add(name);
            LOG.info("[mcp:{}] 注册工具 {} — {}", client.name(), name, abbreviate(t.description));
        }
        return registered.size();
    }

    /** 关闭连接 + 反注册所有工具。 */
    public void unregisterAll() {
        // 反注册：toolkit 当前 API 没 unregister, 用覆盖一个空工具的方式收尾
        for (String name : registered) {
            toolkit.register(stubTool(name));
        }
        registered.clear();
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
    }

    public List<String> registeredNames() {
        return new ArrayList<String>(registered);
    }

    public McpClient client() {
        return client;
    }

    public static String bridgeName(String server, String tool) {
        return "mcp-" + server + "-" + tool;
    }

    private Tool toKernelTool(String name, McpClient.McpTool t) {
        Map<String, Object> schema = t.inputSchema == null
                ? emptyObjectSchema()
                : t.inputSchema;
        return new BaseTool(name, t.description == null ? "" : t.description, schema) {
            @Override
            protected ToolResult doExecute(Map<String, Object> args) {
                McpClient.McpResult r;
                try {
                    r = client.call(t.name, args);
                } catch (Exception e) {
                    return ToolResult.error("MCP 调用失败: " + e.getMessage());
                }
                if (r == null) {
                    return ToolResult.error("MCP 返回 null");
                }
                if (r.isError) {
                    return ToolResult.error(r.errorMessage == null ? "未知错误" : r.errorMessage);
                }
                Object content = r.content;
                if (content == null) {
                    return ToolResult.text("");
                }
                return ToolResult.text(content.toString());
            }
        };
    }

    private static Tool stubTool(String name) {
        return new BaseTool(name, "[unregistered]", emptyObjectSchema()) {
            @Override
            protected ToolResult doExecute(Map<String, Object> args) {
                return ToolResult.error("MCP server 已断开，工具已注销");
            }
        };
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}