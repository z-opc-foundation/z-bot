package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.mcp.McpTransport;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpManager} + {@link McpBridge} 单测 — 用内存 transport 桩模拟 MCP server。
 */
public class McpManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void noServersMeansNoBridgesAndNoThrow() {
        Toolkit tk = new Toolkit();
        McpManager mgr = new McpManager(tk, new ArrayList<BotConfig.McpServerEntry>());
        mgr.startAll();
        assertEquals(0, mgr.snapshot().size());
        mgr.stopAll();
    }

    @Test
    public void bridgeRegistersAllToolsWithPrefix() {
        Toolkit tk = new Toolkit();
        FakeMcpServer fs = new FakeMcpServer("fs");
        fs.registerTool("read", "读取文件", "{\"path\":\"/tmp/x\"}", "file content");
        fs.registerTool("write", "写入文件", "{\"path\":\"/tmp/x\",\"data\":\"...\"}", "ok");

        McpManager mgr = McpManager.fromClients(tk,
                java.util.Collections.singletonList(McpClientFactory.wrap("fs", fs)));
        mgr.startAll();

        assertEquals(1, mgr.snapshot().size());
        McpManager.BridgeStatus s = mgr.snapshot().get(0);
        assertTrue(s.ok);
        assertEquals("fs", s.name);
        assertEquals(2, s.toolCount);
        assertTrue(tk.contains("mcp-fs-read"));
        assertTrue(tk.contains("mcp-fs-write"));
        mgr.stopAll();
    }

    @Test
    public void bridgeToolExecutesAndReturnsContent() {
        Toolkit tk = new Toolkit();
        FakeMcpServer fs = new FakeMcpServer("fs");
        fs.registerTool("read", "读取文件", "{\"path\":\"/tmp/x\"}", "hello-world");

        McpManager mgr = McpManager.fromClients(tk,
                java.util.Collections.singletonList(McpClientFactory.wrap("fs", fs)));
        mgr.startAll();

        Map<String, Object> args = new HashMap<String, Object>();
        args.put("path", "/tmp/x");
        com.zifang.z.agent.kernel.tool.ToolResult result = tk.execute("mcp-fs-read", args);
        assertFalse("MCP tool call should succeed", result.isError());
        assertEquals("hello-world", result.getContent());
        mgr.stopAll();
    }

    @Test
    public void bridgeToolReturnsErrorWhenServerFails() {
        Toolkit tk = new Toolkit();
        FakeMcpServer fs = new FakeMcpServer("fs");
        fs.registerToolFailing("read", "读取文件", "boom");

        McpManager mgr = McpManager.fromClients(tk,
                java.util.Collections.singletonList(McpClientFactory.wrap("fs", fs)));
        mgr.startAll();

        com.zifang.z.agent.kernel.tool.ToolResult result =
                tk.execute("mcp-fs-read", new HashMap<String, Object>());
        assertTrue("Failing server should produce error result", result.isError());
        assertTrue("Error message should contain 'boom', was: " + result.getContent(),
                result.getContent().contains("boom"));
        mgr.stopAll();
    }

    @Test
    public void isolatedServerFailureDoesNotPoisonOthers() {
        Toolkit tk = new Toolkit();
        FakeMcpServer good = new FakeMcpServer("good");
        good.registerTool("ping", "心跳", "{}", "pong");

        // 第二个 server 用真实 stdio + 不存在命令 → 必失败
        com.zifang.z.agent.kernel.mcp.McpClient badClient = McpClientFactory.createStdio(
                new BotConfig.McpServerEntry("bad",
                        java.util.Arrays.asList("/nonexistent/command")));
        com.zifang.z.agent.kernel.mcp.McpClient goodClient = McpClientFactory.wrap("good", good);
        McpManager mgr = McpManager.fromClients(tk, java.util.Arrays.asList(badClient, goodClient));
        mgr.startAll();

        assertEquals(2, mgr.snapshot().size());
        // good 是 ok
        McpManager.BridgeStatus okStatus = mgr.snapshot().get(0).ok
                ? mgr.snapshot().get(0) : mgr.snapshot().get(1);
        assertEquals("good", okStatus.name);
        assertTrue(okStatus.ok);
        // bad 是失败
        McpManager.BridgeStatus badStatus = okStatus == mgr.snapshot().get(0)
                ? mgr.snapshot().get(1) : mgr.snapshot().get(0);
        assertEquals("bad", badStatus.name);
        assertFalse(badStatus.ok);
        // good 的工具仍然注册
        assertTrue(tk.contains("mcp-good-ping"));
        mgr.stopAll();
    }

    @Test
    public void reloadReregistersTools() {
        Toolkit tk = new Toolkit();
        FakeMcpServer fs = new FakeMcpServer("fs");
        fs.registerTool("a", "tool a", "{}", "A");

        McpManager mgr = McpManager.fromClients(tk,
                java.util.Collections.singletonList(McpClientFactory.wrap("fs", fs)));
        mgr.startAll();
        assertTrue(tk.contains("mcp-fs-a"));

        fs.registerTool("b", "tool b", "{}", "B");
        int total = mgr.reload();
        assertEquals(2, total);
        assertTrue(tk.contains("mcp-fs-a"));
        assertTrue(tk.contains("mcp-fs-b"));
        mgr.stopAll();
    }

    @Test
    public void bridgeNameFormatsCorrectly() {
        assertEquals("mcp-fs-read", McpBridge.bridgeName("fs", "read"));
        assertEquals("mcp-git-commit", McpBridge.bridgeName("git", "commit"));
    }

    @Test
    public void botConfigParsesMcpServers() throws Exception {
        File cfgDir = tmp.newFolder("cfg");
        java.io.FileWriter w = new java.io.FileWriter(new File(cfgDir, "config.properties"));
        w.write("mcp.servers=fs=node /usr/local/bin/mcp-fs.js,git=uvx mcp-git --foo\n");
        w.close();
        BotConfig cfg = BotConfig.load(cfgDir);
        assertEquals(2, cfg.getMcpServers().size());
        assertEquals("fs", cfg.getMcpServers().get(0).getName());
        // ["node", "/usr/local/bin/mcp-fs.js"] = 2 段
        assertEquals(2, cfg.getMcpServers().get(0).getCommand().size());
        assertEquals("node", cfg.getMcpServers().get(0).getCommand().get(0));
        assertEquals("/usr/local/bin/mcp-fs.js", cfg.getMcpServers().get(0).getCommand().get(1));
        assertEquals("git", cfg.getMcpServers().get(1).getName());
        assertEquals("mcp-git", cfg.getMcpServers().get(1).getCommand().get(1));
        assertEquals("--foo", cfg.getMcpServers().get(1).getCommand().get(2));
    }

    @Test
    public void botConfigEmptyMcpServersIsFine() throws Exception {
        BotConfig cfg = BotConfig.load(tmp.newFolder("cfg"));
        assertNotNull(cfg.getMcpServers());
        assertEquals(0, cfg.getMcpServers().size());
    }

    private static <T> List<T> listOf(T... items) {
        List<T> out = new ArrayList<T>();
        for (T i : items) {
            out.add(i);
        }
        return out;
    }

    // ===== Fake MCP server (in-memory transport) =====

    private static final class FakeMcpServer implements McpTransport {

        private final String name;
        private final Map<String, FakeTool> tools = new java.util.LinkedHashMap<String, FakeTool>();
        private boolean open;
        private long idCounter = 1;

        FakeMcpServer(String name) {
            this.name = name;
        }

        void registerTool(String toolName, String desc, String schemaJson, String result) {
            tools.put(toolName, new FakeTool(toolName, desc, schemaJson, result, false));
        }

        void registerToolFailing(String toolName, String desc, String errorMsg) {
            tools.put(toolName, new FakeTool(toolName, desc, "{}", errorMsg, true));
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public String request(String requestJson) {
            long id = idCounter++;
            if (requestJson.contains("\"method\":\"tools/list\"")) {
                StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                        .append(",\"result\":{\"tools\":[");
                boolean first = true;
                for (FakeTool t : tools.values()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append("{\"name\":\"").append(t.name).append("\",")
                            .append("\"description\":\"").append(t.description).append("\",")
                            .append("\"inputSchema\":").append(t.schemaJson).append("}");
                    first = false;
                }
                sb.append("]}}");
                return sb.toString();
            }
            if (requestJson.contains("\"method\":\"tools/call\"")) {
                // 抽 name 字段（简化）
                String toolName = extractAfter(requestJson, "\"name\":\"", "\"");
                FakeTool t = tools.get(toolName);
                if (t == null) {
                    return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"error\":{\"message\":\"tool not found\"}}";
                }
                if (t.fail) {
                    return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"isError\":true,\"content\":\"" + t.result + "\"}}";
                }
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"content\":\"" + t.result + "\"}}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{}}";
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        private static String extractAfter(String s, String start, String end) {
            int i = s.indexOf(start);
            if (i < 0) {
                return "";
            }
            i += start.length();
            int j = s.indexOf(end, i);
            return j < 0 ? s.substring(i) : s.substring(i, j);
        }

        private static final class FakeTool {
            final String name;
            final String description;
            final String schemaJson;
            final String result;
            final boolean fail;

            FakeTool(String name, String description, String schemaJson, String result, boolean fail) {
                this.name = name;
                this.description = description;
                this.schemaJson = schemaJson;
                this.result = result;
                this.fail = fail;
            }
        }
    }
}