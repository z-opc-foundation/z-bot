package com.zifang.z.bot.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.mcp.McpTransport;
import com.zifang.z.agent.kernel.mcp.StdioMcpTransport;
import com.zifang.z.bot.config.BotConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client 工厂 — 把 {@link BotConfig.McpServerEntry} 转成 {@link McpClient}.
 *
 * <p>目前只支持 stdio transport（启动子进程跑 MCP server）。HTTP+SSE 留到后续版本。</p>
 */
public final class McpClientFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpClientFactory() {
    }

    /**
     * 创建一个 stdio MCP client（不会立即 connect，调用方按需触发）。
     */
    public static McpClient createStdio(BotConfig.McpServerEntry entry) {
        return new StdIoMcpClient(entry.getName(), new StdioMcpTransport(new ArrayList<String>(entry.getCommand())));
    }

    /** 基于自定义 transport 的 client（测试用）。 */
    public static McpClient wrap(String name, McpTransport transport) {
        return new StdIoMcpClient(name, transport);
    }

    /** 内部 client 实现：connect 时立刻初始化，listTools 走 JSON-RPC tools/list，call 走 tools/call。 */
    static final class StdIoMcpClient implements McpClient {

        private final String name;
        private final McpTransport transport;
        private final AtomicLong nextId = new AtomicLong();
        private volatile boolean connected;

        StdIoMcpClient(String name, McpTransport transport) {
            this.name = name;
            this.transport = transport;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void connect() {
            if (connected) {
                return;
            }
            try {
                transport.open();
                connected = true;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("mcp connect failed: " + e.getMessage(), e);
            }
        }

        @Override
        public List<McpClient.McpTool> listTools() {
            ensureConnected();
            long id = nextId.incrementAndGet();
            String req = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"method\":\"tools/list\",\"params\":{}}";
            try {
                String resp = transport.request(req);
                return parseTools(resp);
            } catch (Exception e) {
                throw new RuntimeException("mcp tools/list failed: " + e.getMessage(), e);
            }
        }

        @Override
        public McpClient.McpResult call(String toolName, java.util.Map<String, Object> arguments) {
            ensureConnected();
            long id = nextId.incrementAndGet();
            String argJson;
            try {
                argJson = JSON.writeValueAsString(arguments == null
                        ? java.util.Collections.<String, Object>emptyMap() : arguments);
            } catch (Exception e) {
                return McpClient.McpResult.error("参数序列化失败: " + e.getMessage());
            }
            String req = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"method\":\"tools/call\",\"params\":"
                + "{\"name\":" + JSON.valueToTree(toolName)
                + ",\"arguments\":" + argJson + "}}";
            try {
                String resp = transport.request(req);
                return parseCall(resp);
            } catch (Exception e) {
                return McpClient.McpResult.error("mcp tools/call 失败: " + e.getMessage());
            }
        }

        @Override
        public void disconnect() {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected && transport.isOpen();
        }

        private void ensureConnected() {
            if (!isConnected()) {
                connect();
            }
        }

        @SuppressWarnings("unchecked")
        private List<McpClient.McpTool> parseTools(String resp) {
            try {
                java.util.Map<String, Object> root = JSON.readValue(resp,
                        new TypeReference<java.util.Map<String, Object>>() {
                        });
                Object result = root.get("result");
                if (!(result instanceof java.util.Map)) {
                    return java.util.Collections.emptyList();
                }
                Object tools = ((java.util.Map<String, Object>) result).get("tools");
                if (!(tools instanceof List)) {
                    return java.util.Collections.emptyList();
                }
                List<McpClient.McpTool> out = new ArrayList<McpClient.McpTool>();
                for (Object o : (List<Object>) tools) {
                    if (!(o instanceof java.util.Map)) {
                        continue;
                    }
                    java.util.Map<String, Object> t = (java.util.Map<String, Object>) o;
                    String n = String.valueOf(t.get("name"));
                    String d = t.get("description") == null ? "" : t.get("description").toString();
                    Object schema = t.get("inputSchema");
                    java.util.Map<String, Object> schemaMap = schema instanceof java.util.Map
                            ? (java.util.Map<String, Object>) schema
                            : java.util.Collections.<String, Object>emptyMap();
                    out.add(new McpClient.McpTool(n, d, schemaMap));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("解析 tools/list 响应失败: " + e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private McpClient.McpResult parseCall(String resp) {
            try {
                java.util.Map<String, Object> root = JSON.readValue(resp,
                        new TypeReference<java.util.Map<String, Object>>() {
                        });
                if (root.containsKey("error")) {
                    Object err = root.get("error");
                    return McpClient.McpResult.error(err == null ? "未知错误" : err.toString());
                }
                Object result = root.get("result");
                if (result instanceof java.util.Map) {
                    java.util.Map<String, Object> r = (java.util.Map<String, Object>) result;
                    if (Boolean.TRUE.equals(r.get("isError"))) {
                        Object content = r.get("content");
                        return McpClient.McpResult.error(content == null ? "tools/call 失败" : content.toString());
                    }
                    return McpClient.McpResult.ok(r.get("content"));
                }
                return McpClient.McpResult.ok(result);
            } catch (Exception e) {
                return McpClient.McpResult.error("解析 tools/call 响应失败: " + e.getMessage());
            }
        }
    }
}