package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 MCP transport（测试替身）：能开关、能改工具清单、能数请求次数。
 *
 * <p>刻意做得像真 stdio 传输一样"断了就报错"，这样 {@link McpBridge} 的可用性探测
 * （{@code client.isConnected()}）与注销语义都能被真判到，而不是被一个永远听话的桩糊过去。</p>
 */
public final class InMemoryMcpTransport implements McpTransport {

    private final String serverName;
    private final Map<String, String> tools = new LinkedHashMap<String, String>();
    private final List<String> calls = new ArrayList<String>();
    private boolean open;
    private int openCount;
    private int closeCount;

    public InMemoryMcpTransport(String serverName) {
        this.serverName = serverName;
    }

    public InMemoryMcpTransport tool(String name, String description) {
        tools.put(name, description);
        return this;
    }

    public InMemoryMcpTransport dropTool(String name) {
        tools.remove(name);
        return this;
    }

    public List<String> toolNames() {
        return new ArrayList<String>(tools.keySet());
    }

    public int openCount() {
        return openCount;
    }

    public int closeCount() {
        return closeCount;
    }

    public List<String> toolCalls() {
        return new ArrayList<String>(calls);
    }

    @Override
    public void open() {
        openCount++;
        open = true;
    }

    @Override
    public String request(String requestJson) throws Exception {
        if (!open) {
            throw new IllegalStateException("transport closed: " + serverName);
        }
        long id = extractId(requestJson);
        if (requestJson.contains("\"method\":\"tools/list\"")) {
            StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                    .append(",\"result\":{\"tools\":[");
            boolean first = true;
            for (Map.Entry<String, String> e : tools.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append("{\"name\":\"").append(e.getKey())
                        .append("\",\"description\":\"").append(e.getValue())
                        .append("\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}");
                first = false;
            }
            return sb.append("]}}").toString();
        }
        if (requestJson.contains("\"method\":\"tools/call\"")) {
            String toolName = between(requestJson, "\"name\":\"", "\"");
            calls.add(toolName);
            if (!tools.containsKey(toolName)) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"message\":\"no such tool: " + toolName + "\"}}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"content\":\"" + serverName + "/" + toolName + " said ok\"}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
    }

    @Override
    public void close() {
        closeCount++;
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    private static long extractId(String json) {
        String key = "\"id\":";
        int i = json.indexOf(key);
        if (i < 0) {
            return 0L;
        }
        int s = i + key.length();
        int e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) {
            e++;
        }
        return e > s ? Long.parseLong(json.substring(s, e)) : 0L;
    }

    private static String between(String s, String left, String right) {
        int i = s.indexOf(left);
        if (i < 0) {
            return "";
        }
        i += left.length();
        int j = s.indexOf(right, i);
        return j < 0 ? s.substring(i) : s.substring(i, j);
    }
}
