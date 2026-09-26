package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.mcp.StdioMcpTransport;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P21 §7：{@code BotConfig.McpServerEntry} 的 <b>transport 判别位</b> 与
 * <b>老写法回归</b>，以及 {@link McpClientFactory#create(BotConfig.McpServerEntry)} 的派发。
 *
 * <p>老写法回归是这一节的主要风险面：本期给 {@code mcp.servers} 加了两种新形态
 * （值直接写 URL、逐 server 覆盖键），但 {@code mcp.servers = "fs=node /x.js,git=uvx mcp-git"}
 * 这类既有配置的解析结果<b>一个字段都不许动</b> —— 这里逐字段钉住，而不只是钉"能解析出 2 条"。</p>
 *
 * <p>派发侧钉的是"不许静默降级"：{@code transport} 不认识、或 {@code http} 缺 url 时，
 * 必须当场报错，而不是退成 stdio 去拉一个不存在的子进程 —— 后者会被报成
 * "这个 server 起来了但 0 工具"，那是最难查的一类错法（{@link McpManager#startOne} 里
 * 为这个场景专门留了一条 warn）。</p>
 */
public class McpTransportConfigDiscriminationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private int folderSeq;

    private BotConfig loadWith(String... lines) throws IOException {
        folderSeq++;
        File dir = tmp.newFolder("cfg" + folderSeq);
        FileWriter w = new FileWriter(new File(dir, "config.properties"));
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        w.write(sb.toString());
        w.close();
        return BotConfig.load(dir);
    }

    private static BotConfig.McpServerEntry only(BotConfig cfg, String name) {
        for (BotConfig.McpServerEntry e : cfg.getMcpServers()) {
            if (name.equals(e.getName())) {
                return e;
            }
        }
        assertNotNull("没有解析出 server " + name + "，实际: " + cfg.getMcpServers(), null);
        throw new IllegalStateException("unreachable");
    }

    // ------------------------------------------------------------ 老写法回归

    @Test
    public void legacyCommandLineFormStillParsesFieldByFieldAsStdio() throws Exception {
        BotConfig cfg = loadWith("mcp.servers=fs=node /usr/local/bin/mcp-fs.js,git=uvx mcp-git --foo");
        assertEquals(2, cfg.getMcpServers().size());

        BotConfig.McpServerEntry fs = cfg.getMcpServers().get(0);
        assertEquals("fs", fs.getName());
        assertEquals(Arrays.asList("node", "/usr/local/bin/mcp-fs.js"), fs.getCommand());
        assertEquals(BotConfig.McpServerEntry.TRANSPORT_STDIO, fs.getTransport());
        assertEquals("", fs.getUrl());
        assertTrue(fs.getHeaders().isEmpty());
        assertEquals(0L, fs.getTimeoutMillis());
        assertFalse(fs.isHttp());

        BotConfig.McpServerEntry git = cfg.getMcpServers().get(1);
        assertEquals("git", git.getName());
        assertEquals(Arrays.asList("uvx", "mcp-git", "--foo"), git.getCommand());
        assertEquals(BotConfig.McpServerEntry.TRANSPORT_STDIO, git.getTransport());

        // 生产路径：老写法现在走 z-bot 自己的 stdio transport，但线级行为口径不变
        McpClient client = McpClientFactory.create(fs);
        assertEquals("fs", client.name());
        Object transport = McpClientFactory.transportOf(client);
        assertTrue("老写法必须走 ZBotStdioMcpTransport，实际: " + transport,
                transport instanceof ZBotStdioMcpTransport);
        assertFalse("不许还留在内核那条有缺陷的 transport 上",
                transport instanceof StdioMcpTransport);
    }

    @Test
    public void legacyTwoArgConstructorKeepsStdioDefaults() {
        BotConfig.McpServerEntry e = new BotConfig.McpServerEntry("old",
                Arrays.asList("node", "x.js"));
        assertEquals(BotConfig.McpServerEntry.TRANSPORT_STDIO, e.getTransport());
        assertEquals("", e.getUrl());
        assertTrue(e.getHeaders().isEmpty());
        assertEquals(0L, e.getTimeoutMillis());
        Map<String, Object> safe = e.toSafeMap();
        assertEquals("应当带 command 视图", e.getCommand(), safe.get("command"));
        assertNull("stdio 条目不该出现 url 视图", safe.get("url"));
        assertNull("stdio 条目不该出现 headerNames", safe.get("headerNames"));
        assertTrue(e.toString().contains("node"));
    }

    @Test
    public void emptyAndBlankMcpServersStayEmpty() throws Exception {
        assertEquals(0, loadWith("mcp.servers=").getMcpServers().size());
        assertEquals(0, loadWith("mcp.servers= , , ").getMcpServers().size());
        // 老行为：有名字但没有任何命令行 token ⇒ 这条不成立，丢弃而不是留一个空 stdio
        assertEquals(0, loadWith("mcp.servers=bad=").getMcpServers().size());
    }

    // ------------------------------------------------------------ 新判别位

    @Test
    public void urlAsValueIsDiscriminatedAsHttpWithoutAnyOverrideKey() throws Exception {
        BotConfig cfg = loadWith("mcp.servers=corp=https://mcp.example.com/mcp");
        BotConfig.McpServerEntry corp = cfg.getMcpServers().get(0);
        assertEquals(BotConfig.McpServerEntry.TRANSPORT_HTTP, corp.getTransport());
        assertEquals("https://mcp.example.com/mcp", corp.getUrl());
        assertTrue(corp.getCommand().isEmpty());
        assertTrue(corp.isHttp());
        Object t = McpClientFactory.transportOf(McpClientFactory.create(corp));
        assertTrue("http 判别位必须建出 StreamableHTTP transport，实际: " + t,
                t instanceof StreamableHttpMcpTransport);
    }

    @Test
    public void perServerOverrideKeysSetTransportUrlHeadersAndTimeout() throws Exception {
        BotConfig cfg = loadWith(
                "mcp.servers=corp=node /srv/corp.js",
                "mcp.server.corp.transport=http",
                "mcp.server.corp.url=http://127.0.0.1:8877/mcp",
                "mcp.server.corp.headers=Authorization=Bearer aaa;X-API-Key=bbb",
                "mcp.server.corp.timeout=2500");
        BotConfig.McpServerEntry corp = only(cfg, "corp");
        assertEquals(BotConfig.McpServerEntry.TRANSPORT_HTTP, corp.getTransport());
        assertEquals("http://127.0.0.1:8877/mcp", corp.getUrl());
        assertEquals(2, corp.getHeaders().size());
        assertEquals("Bearer aaa", corp.getHeaders().get("Authorization"));
        // value 里可以再含 =：只按第一个 = 切
        assertEquals(2500L, corp.getTimeoutMillis());
        Map<String, Object> safe = corp.toSafeMap();
        assertEquals(Arrays.asList("Authorization", "X-API-Key"), safe.get("headerNames"));
        assertEquals("http://127.0.0.1:8877/mcp", safe.get("url"));
    }

    @Test
    public void headerValueWithSecondEqualsKeepsTheTail() throws Exception {
        BotConfig cfg = loadWith(
                "mcp.servers=corp=http://127.0.0.1:1/mcp",
                "mcp.server.corp.headers=X-Blob=a=b=c;Empty=;NoKey");
        BotConfig.McpServerEntry corp = only(cfg, "corp");
        assertEquals("a=b=c", corp.getHeaders().get("X-Blob"));
        assertEquals("= 之后为空也留着这个 key", "", corp.getHeaders().get("Empty"));
        assertFalse("没有 = 的片段不该变成 header", corp.getHeaders().containsKey("NoKey"));
    }

    @Test
    public void transportAliasesNormaliseUnknownOnesStayRaw() throws Exception {
        String[][] cases = {
                {"streamable-http", BotConfig.McpServerEntry.TRANSPORT_HTTP},
                {"StreamableHTTP", BotConfig.McpServerEntry.TRANSPORT_HTTP},
                {"https", BotConfig.McpServerEntry.TRANSPORT_HTTP},
                {"http+sse", BotConfig.McpServerEntry.TRANSPORT_HTTP},
                {"SSE", BotConfig.McpServerEntry.TRANSPORT_HTTP},
                {"stdio+", BotConfig.McpServerEntry.TRANSPORT_STDIO},
                {"WEBSOCKET", "websocket"},
        };
        for (String[] c : cases) {
            BotConfig cfg = loadWith(
                    "mcp.servers=a=http://127.0.0.1:1/mcp",
                    "mcp.server.a.transport=" + c[0]);
            assertEquals("别名 " + c[0] + " 归一化错了", c[1], only(cfg, "a").getTransport());
        }
    }

    // ------------------------------------------------------------ 不许静默降级

    @Test
    public void unknownTransportFailsLoudlyInsteadOfFallingBackToStdio() throws Exception {
        BotConfig cfg = loadWith(
                "mcp.servers=ws=http://127.0.0.1:1/mcp",
                "mcp.server.ws.transport=websocket");
        BotConfig.McpServerEntry ws = only(cfg, "ws");
        try {
            McpClientFactory.create(ws);
            fail("未知 transport 必须当场报错，不许退成 stdio");
        } catch (IllegalArgumentException e) {
            assertTrue("报错里要点名是哪个 server: " + e.getMessage(),
                    e.getMessage().contains("ws"));
            assertTrue("报错里要带上那个不认知的值: " + e.getMessage(),
                    e.getMessage().contains("websocket"));
        }
    }

    @Test
    public void httpWithoutUrlAndStdioWithoutCommandBothFailWithTheirOwnMessage() throws Exception {
        BotConfig noUrl = loadWith("mcp.servers=corp=whatever",
                "mcp.server.corp.transport=http");
        try {
            McpClientFactory.create(only(noUrl, "corp"));
            fail("transport=http 却没 url 必须报错");
        } catch (IllegalArgumentException e) {
            assertTrue("要给出补配置的键名提示: " + e.getMessage(),
                    e.getMessage().contains("mcp.server.corp.url="));
        }

        BotConfig noCmd = loadWith("mcp.servers=corp=https://x.example/mcp",
                "mcp.server.corp.transport=stdio");
        try {
            McpClientFactory.create(only(noCmd, "corp"));
            fail("transport=stdio 却没命令行必须报错（不许拿 url 当命令跑）");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("stdio"));
            assertTrue(e.getMessage(), e.getMessage().contains("命令行"));
        }
    }

    /**
     * 配置错在 {@link McpManager} 里的兑现：坏配置只脏自己的状态行，
     * 不注册任何工具、也不退化成 stdio 去拉子进程（隔离性本身由
     * {@code McpManagerTest#isolatedServerFailureDoesNotPoisonOthers} 钉）。
     */
    @Test
    public void managerKeepsConfigErrorInItsOwnStatusRow() throws Exception {
        BotConfig cfg = loadWith(
                "mcp.servers=ws=http://127.0.0.1:1/mcp",
                "mcp.server.ws.transport=websocket");
        Toolkit tk = new Toolkit();
        McpManager mgr = new McpManager(tk, cfg.getMcpServers());
        mgr.startAll();
        List<Map<String, Object>> rows = mgr.toMapList();
        assertEquals(1, rows.size());
        Map<String, Object> ws = rows.get(0);
        assertEquals("ws", ws.get("name"));
        assertEquals(Boolean.FALSE, ws.get("ok"));
        // 配置阶段就抛的错走的是 2 参 error()，transport 位记 "unknown"（只有进了
        // startOne 的路径才知道 transport）—— 这一条是现状读数，不是静默降级的证据
        assertEquals("unknown", ws.get("transport"));
        assertEquals(0, ((List<?>) ws.get("tools")).size());
        assertTrue(String.valueOf(ws.get("error")).contains("websocket"));
        assertFalse("坏 server 不许顺手注册工具", tk.contains("mcp-ws-anything"));
        assertNull("配置就没过的 server 不该有 bridge", mgr.bridge("ws"));
        mgr.stopAll();
    }

    /**
     * {@code timeout} 写非数字 ⇒ 配置层记 0，而 0 的含义是"由 transport 实现兜默认"，
     * 不在配置层编第二个默认值（那样两处默认值迟早分叉）。
     */
    @Test
    public void nonNumericTimeoutMeansUseTheTransportDefault() throws Exception {
        BotConfig cfg = loadWith("mcp.servers=corp=http://127.0.0.1:1/mcp",
                "mcp.server.corp.timeout=soon");
        BotConfig.McpServerEntry corp = only(cfg, "corp");
        assertEquals(0L, corp.getTimeoutMillis());

        ZBotStdioMcpTransport.Options stdio = new ZBotStdioMcpTransport.Options()
                .command(Arrays.asList("true")).timeoutMillis(corp.getTimeoutMillis());
        assertEquals("0 必须回落到实现默认值",
                ZBotStdioMcpTransport.DEFAULT_TIMEOUT_MILLIS, stdio.timeoutMillis);
        StreamableHttpMcpTransport.Options http = new StreamableHttpMcpTransport.Options()
                .url("http://127.0.0.1:1/mcp").timeoutMillis(corp.getTimeoutMillis());
        assertEquals(StreamableHttpMcpTransport.DEFAULT_TIMEOUT_MILLIS, http.timeoutMillis);
        assertEquals(15_000L, ZBotStdioMcpTransport.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(15_000L, StreamableHttpMcpTransport.DEFAULT_TIMEOUT_MILLIS);
    }
}

