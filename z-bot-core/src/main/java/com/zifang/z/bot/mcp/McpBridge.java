package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.tool.BaseTool;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
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
 * <p>每个 server 独占一个 toolset（{@code mcp-<server>}）与 owner（{@code mcp:<server>}），
 * 于是 {@link #unregisterAll()} 能按 toolset <b>真注销</b> —— 对齐 hermes
 * {@code registry.deregister}（registry.py:459，注释明写这是给 MCP dynamic discovery
 * 的 "nuke-and-repave" 用的，且 {@code mcp-*} 前缀豁免插件覆写闸门）。
 * 早前这里没有 deregister API，只能用同名 stub 覆盖收尾，reload 后旧工具名仍占着
 * schema（roadmap §1#8 的旧账），现在没有了。</p>
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

    /** 该 server 在注册表里占的 toolset 名。 */
    public String toolset() {
        return "mcp-" + client.name();
    }

    /** 该 server 的 owner 标识：只有本 bridge 能注销自己 server 的工具。 */
    public String owner() {
        return "mcp:" + client.name();
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
            // 网络工具的副作用不可知 → parallelSafe=false，交给派发侧串行
            toolkit.register(toKernelTool(name, t), new ToolDescriptor(toolset(), false, null, owner()));
            registered.add(name);
            LOG.info("[mcp:{}] 注册工具 {} — {}", client.name(), name, abbreviate(t.description));
        }
        return registered.size();
    }

    /**
     * 关闭连接 + <b>按 toolset 整体注销</b>该 server 的所有工具（真删槽位，不是同名覆盖）。
     *
     * <p>整体注销比"逐个删记住的名字"硬：server 侧改了工具名时，老名字也一并清掉。
     */
    public void unregisterAll() {
        List<String> removed = toolkit.deregisterToolset(toolset(), owner());
        for (String name : registered) {
            if (!removed.contains(name)) {
                LOG.warn("[mcp:{}] 注销后 {} 不在被删清单里 —— 注册表状态与 bridge 记账不一致",
                        client.name(), name);
            }
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