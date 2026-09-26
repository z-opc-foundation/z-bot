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
 * <p>按 {@link BotConfig.McpServerEntry#getTransport()} 派发两条真实通道：
 * {@code stdio} → {@link ZBotStdioMcpTransport}（起子进程），{@code http} →
 * {@link StreamableHttpMcpTransport}（POST JSON-RPC + {@code mcp-session-id}，吃 JSON 与 SSE 两种响应）。
 * 未知 transport 或缺必需字段一律抛，不静默降级成 stdio。</p>
 */
public final class McpClientFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpClientFactory() {
    }

    /**
     * 创建一个 stdio MCP client（不会立即 connect，调用方按需触发）。
     *
     * <p><b>走的是内核 {@code StdioMcpTransport}</b>，保留它是为了当对照组：
     * P21 §2 要用它实测"内核 stdio 收不到 server 主动推的 notification"这条推论。
     * 生产路径已换到 {@link #createStdioZBot}。</p>
     */
    public static McpClient createStdio(BotConfig.McpServerEntry entry) {
        return new StdIoMcpClient(entry.getName(), new StdioMcpTransport(new ArrayList<String>(entry.getCommand())));
    }

    /**
     * z-bot 侧 stdio transport：实现同一个公开接口 {@link McpTransport}，
     * 但修掉内核三个已知未修缺陷（字符串包含配对端 / 不生效的 deadline / 写死的自报版本），
     * 并补上常驻读线程 ⇒ 能收 {@code notifications/tools/list_changed}。
     */
    public static McpClient createStdioZBot(BotConfig.McpServerEntry entry) {
        ZBotStdioMcpTransport.Options o = new ZBotStdioMcpTransport.Options()
                .serverName(entry.getName())
                .command(entry.getCommand())
                .clientVersion(clientVersion());
        if (entry.getTimeoutMillis() > 0) {
            o.timeoutMillis(entry.getTimeoutMillis());
        }
        return new StdIoMcpClient(entry.getName(), new ZBotStdioMcpTransport(o));
    }

    /** StreamableHTTP transport（POST JSON-RPC + mcp-session-id + 双形态响应）。 */
    public static McpClient createHttp(BotConfig.McpServerEntry entry) {
        StreamableHttpMcpTransport.Options o = new StreamableHttpMcpTransport.Options()
                .serverName(entry.getName())
                .url(entry.getUrl())
                .headers(entry.getHeaders())
                .clientVersion(clientVersion());
        if (entry.getTimeoutMillis() > 0) {
            o.timeoutMillis(entry.getTimeoutMillis());
        }
        return new StdIoMcpClient(entry.getName(), new StreamableHttpMcpTransport(o));
    }

    /**
     * 按配置里的 transport 判别位派发。生产路径唯一入口。
     *
     * @throws IllegalArgumentException 未知 transport，或 http 条目缺 url（不静默降级成 stdio：
     *                                  那会把一个连不上的 server 报成"起来了但 0 工具"）
     */
    public static McpClient create(BotConfig.McpServerEntry entry) {
        String t = entry.getTransport();
        if (BotConfig.McpServerEntry.TRANSPORT_HTTP.equals(t)) {
            if (entry.getUrl().isEmpty()) {
                throw new IllegalArgumentException("mcp server '" + entry.getName()
                        + "' transport=http 但没有 url（mcp.server." + entry.getName() + ".url=…）");
            }
            return createHttp(entry);
        }
        if (BotConfig.McpServerEntry.TRANSPORT_STDIO.equals(t)) {
            if (entry.getCommand().isEmpty()) {
                throw new IllegalArgumentException("mcp server '" + entry.getName()
                        + "' transport=stdio 但没有命令行");
            }
            return createStdioZBot(entry);
        }
        throw new IllegalArgumentException("mcp server '" + entry.getName()
                + "' 的 transport 不认识: " + t + "（可用值: stdio / http）");
    }

    /** 自报版本：跟 jar 走，不再像内核那样把 "0.2.0" 焊死在源码里。 */
    static String clientVersion() {
        String v = McpClientFactory.class.getPackage().getImplementationVersion();
        return v == null || v.isEmpty() ? "0.2.0-dev" : v;
    }

    /**
     * 取一个 client 底下的 transport；不是本工厂实现的返回 null。
     *
     * <p>{@link McpBridge} 用它判"这条通道能不能收 server 主动通知"，
     * 收不到就不广告 {@code listChanged}（广告出去的字段就是承诺）。</p>
     */
    public static McpTransport transportOf(McpClient client) {
        if (client instanceof StdIoMcpClient) {
            return ((StdIoMcpClient) client).transportHandle();
        }
        return null;
    }

    /** 基于自定义 transport 的 client（测试用，也是 P21 新 transport 的注入缝）。 */
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

        /** 给 {@link McpBridge} 看的底层通道（判 {@link McpNotificationSource} 用）。 */
        McpTransport transportHandle() {
            return transport;
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