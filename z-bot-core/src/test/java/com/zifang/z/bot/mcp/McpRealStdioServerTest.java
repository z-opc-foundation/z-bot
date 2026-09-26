package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.mcp.StdioMcpTransport;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 杠③(a) + (c) 与 §1/§2 取证：对着<b>官方 MCP Python SDK 1.27.1 起的真 stdio server</b>，
 * 把 z-bot 侧 transport 与内核 transport 放在<b>同一条被中继观测的通道</b>上做对照。
 *
 * <p>中继是关键：要证"client 没收到"，必须先有一个 client 之外的观察点证
 * "server 真推了、这行字节真到了 client 的 stdout"。否则负向断言只是空跑。</p>
 */
public class McpRealStdioServerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SERVER = "ref";

    private File serverLog;
    private File relayLog;
    private final List<Process> strays = new ArrayList<Process>();

    @Before
    public void setUp() throws Exception {
        RealMcpHarness.requireOfficialSdk();
        serverLog = tmp.newFile("ref-server.log");
        relayLog = tmp.newFile("relay.log");
    }

    @After
    public void tearDown() {
        for (Process p : strays) {
            RealMcpHarness.destroyQuietly(p);
        }
        strays.clear();
    }

    /** {@code z-bot transport → /bin/sh 监护 → python 中继 → python 官方 server} 的 argv。 */
    private List<String> relayedServerCommand() {
        return new ArrayList<String>(Arrays.asList(
                RealMcpHarness.python(), "-u",
                RealMcpHarness.file(RealMcpHarness.RELAY).getAbsolutePath(),
                "--log", relayLog.getAbsolutePath(), "--",
                RealMcpHarness.python(), "-u",
                RealMcpHarness.file(RealMcpHarness.REF_SERVER).getAbsolutePath(),
                "--transport", "stdio",
                "--log-file", serverLog.getAbsolutePath()));
    }

    private McpClient zbotClient(long timeoutMillis) {
        ZBotStdioMcpTransport.Options o = new ZBotStdioMcpTransport.Options()
                .serverName(SERVER)
                .command(relayedServerCommand())
                .timeoutMillis(timeoutMillis)
                .clientVersion(McpClientFactory.clientVersion());
        return McpClientFactory.wrap(SERVER, new ZBotStdioMcpTransport(o));
    }

    /** 同一份命令，换成内核 transport —— §2 的对照组。 */
    private McpClient kernelClient() {
        return McpClientFactory.wrap(SERVER, new StdioMcpTransport(relayedServerCommand()));
    }

    // ------------------------------------------------------------------ (a) 真 server 跑通

    @Test
    public void zbotTransportHandshakesListsAndCallsAgainstOfficialSdk() throws Exception {
        Toolkit tk = new Toolkit();
        McpBridge bridge = new McpBridge(zbotClient(20_000L), tk);
        try {
            int registered = bridge.registerAll();
            assertTrue("官方 server 至少该有 5 个工具，实得 " + registered
                    + " (" + bridge.registeredNames() + ")", registered >= 5);
            for (String n : new String[] {"p21_echo", "p21_alpha", "p21_beta",
                    "p21_stall", "p21_apply_table"}) {
                assertTrue("缺工具 " + n + "，实到 " + bridge.registeredNames(),
                        tk.contains(McpBridge.bridgeName(SERVER, n)));
            }
            ZBotStdioMcpTransport t =
                    (ZBotStdioMcpTransport) McpClientFactory.transportOf(bridge.client());
            assertEquals("2025-06-18", t.serverProtocolVersion());
            assertEquals("p21-ref-server", t.serverInfoName());
            assertEquals("官方 SDK 版本一抬这条读数就该红，逼着人回来看: " + t.serverInfoVersion(),
                    RealMcpHarness.mcpSdkVersion(), t.serverInfoVersion());
            assertFalse("自报版本不许还是内核那个焊死在源码里的 0.2.0",
                    "0.2.0".equals(t.selfReportedVersion()));
            assertTrue("对端该广告 tools.listChanged=true", t.peerAdvertisesListChanged());

            McpClient.McpResult r = bridge.client().call("p21_echo",
                    Collections.<String, Object>singletonMap("text", "你好 p21"));
            assertFalse("tools/call 报错: " + r.errorMessage, r.isError);
            assertTrue("原始响应该带 echo 前缀，实得 " + r.content,
                    String.valueOf(r.content).contains("echo:你好 p21"));
            assertTrue("零参数工具该能调", String.valueOf(
                    bridge.client().call("p21_alpha", null).content).contains("alpha-ok"));

            // 阳性对照：通道真的通过中继（两个方向都有字节），不是各自起了个空进程
            assertTrue("中继里没有 client→server 的字节",
                    RealMcpHarness.relayLogContains(relayLog, "C2S", "\"method\":\"initialize\""));
            assertTrue("中继里没有 server→client 的字节",
                    RealMcpHarness.relayLogContains(relayLog, "S2C", "p21-ref-server"));
            read("a-handshake",
                    "transport", "zbot-stdio",
                    "registered", registered,
                    "serverProtocolVersion", t.serverProtocolVersion(),
                    "serverInfoName", t.serverInfoName(),
                    "serverInfoVersion", t.serverInfoVersion(),
                    "clientSelfReported", t.selfReportedVersion(),
                    "peerAdvertisesListChanged", t.peerAdvertisesListChanged(),
                    "notificationCapable", bridge.notificationCapable(),
                    "toolNames", bridge.registeredNames(),
                    "relayC2S", RealMcpHarness.countLines(relayLog, "C2S"),
                    "relayS2C", RealMcpHarness.countLines(relayLog, "S2C"),
                    "relayFile", relayLog.getAbsolutePath());
        } finally {
            bridge.unregisterAll();
        }
    }

    // ------------------------------------------------------------------ §1.1 工单假设的证伪

    /**
     * 工单说"任何非紧凑 JSON 的真 server 永远配不上 ⇒ 永久挂死"。
     * 对<b>官方 SDK</b> 这一条<b>不成立</b>：它 server→client 走 pydantic
     * {@code model_dump_json()}，是紧凑的，内核那个 {@code contains("\"id\":N")} 恰好配得上。
     * 两边都量：内核跑官方 server 通（≤20s），跑 P20b 的 {@code STUB_PRETTY=1} 桩 6s 不返回。
     */
    @Test
    public void kernelTransportRoundTripsOnOfficialSdkButHangsOnNonCompactJson() throws Exception {
        long t0 = System.currentTimeMillis();
        McpClient kernel = kernelClient();
        kernel.connect();
        List<McpClient.McpTool> tools = kernel.listTools();
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse("官方 SDK 是紧凑 JSON ⇒ 内核这一路应当能配上（工单的'任何真 server'过宽）",
                tools.isEmpty());
        assertTrue("内核对紧凑 JSON 的耗时读数异常: " + elapsed + "ms", elapsed < 20_000L);
        read("1.1-kernel-vs-official-compact-json",
                "transport", "kernel-stdio",
                "officialSdkToolsListed", tools.size(),
                "elapsedMillis", elapsed);
        kernel.disconnect();

        String stub = stubServerPath();
        assertTrue("找不到 P20b 留下的桩 " + stub, new File(stub).isFile());
        // 这个桩的命令行契约是 `mcp_stub_server.py <pid 文件> <工具清单文件>`（见其 docstring）
        File pidFile = new File(tmp.getRoot(), "pretty.pid");
        File toolsFile = new File(tmp.getRoot(), "pretty.tools");
        writeJson(toolsFile, "pretty_a:带空格 JSON 的桩工具\n");
        List<String> pretty = Arrays.asList("/bin/sh", "-c",
                "STUB_PRETTY=1 exec " + McpWire.shellQuote(RealMcpHarness.python())
                        + " -u " + McpWire.shellQuote(stub)
                        + " " + McpWire.shellQuote(pidFile.getAbsolutePath())
                        + " " + McpWire.shellQuote(toolsFile.getAbsolutePath()));
        final McpClient kernel2 =
                McpClientFactory.wrap("pretty", new StdioMcpTransport(pretty));
        final Exception[] failure = new Exception[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    kernel2.connect();
                    kernel2.listTools();
                } catch (Exception e) {
                    failure[0] = e;
                }
            }
        }, "kernel-pretty-probe");
        worker.setDaemon(true);
        worker.start();
        worker.join(6_000L);
        if (failure[0] != null) {
            fail("内核在 STUB_PRETTY=1 下抛了而不是挂住: " + failure[0]);
        }
        assertTrue("内核在 6s 内返回了 ⇒ §1.1 的'永久挂死'复现失败，得重看源码", worker.isAlive());
        // 刻意不调 kernel2.disconnect()：内核的 request()/close() 共用同一把 monitor，
        // 而 worker 正阻塞在 readLine() 里持着它 —— 一调 close() 就把测试线程也钉死。
        // 这个"关不掉"本身就是缺陷的一部分，写在这儿免得下一个人再踩。
        // 收尾用桩自己写的 pid 文件（副作用回读），不用日志行当扳机。
        long stubPid = readPid(pidFile, 8_000L);
        assertTrue("桩没起来，pid 文件里读不到: " + stubPid, stubPid > 0L);
        new ProcessBuilder("/bin/sh", "-c", "kill -9 " + stubPid + " 2>/dev/null").start();
        worker.interrupt();
    }

    private static long readPid(File pidFile, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (pidFile.isFile() && pidFile.length() > 0) {
                try {
                    return Long.parseLong(RealMcpHarness.slurp(pidFile).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            RealMcpHarness.sleep(50L);
        }
        return -1L;
    }

    private static String stubServerPath() {
        File dir = new File(RealMcpHarness.acceptanceDir().getParentFile(), "p20b");
        return new File(dir, "mcp_stub_server.py").getAbsolutePath();
    }

    // ------------------------------------------------------------------ (c) 真换血

    @Test
    public void officialServerPushesListChangedAndZbotTransportReallySwapsToolkit()
            throws Exception {
        Toolkit tk = new Toolkit();
        McpBridge bridge = new McpBridge(zbotClient(20_000L), tk);
        String toolset = null;
        int sizeBefore = 0;
        int baseline = -1;
        try {
            assertTrue("z-bot transport 必须自证收得到通知，否则外面不许广告 listChanged",
                    bridge.notificationCapable());
            bridge.registerAll();
            toolset = bridge.toolset();
            List<String> before = tk.namesOfToolset(bridge.toolset());
            sizeBefore = tk.size();
            baseline = sizeBefore - before.size();

            File ctl = new File(tmp.getRoot(), "add-only.json");
            writeJson(ctl, "{\"add\": [\"p21_gamma\", \"p21_delta\"], \"drop\": []}");
            McpClient.McpResult r = bridge.client().call("p21_apply_table",
                    Collections.<String, Object>singletonMap("control_file", ctl.getAbsolutePath()));
            assertFalse("apply_table 失败: " + r.errorMessage, r.isError);

            assertTrue("阳性对照没猎物：官方 server 根本没推 notifications/tools/list_changed",
                    waitFor(new Condition() {
                        @Override
                        public boolean holds() throws Exception {
                            return RealMcpHarness.relayLogContains(relayLog, "S2C",
                                    "\"method\":\"notifications/tools/list_changed\"");
                        }
                    }, 8_000L));
            assertTrue("字节到了但 transport 没收到：notificationsSeen="
                            + bridge.notificationsSeen(),
                    waitFor(new Condition() {
                        @Override
                        public boolean holds() {
                            return bridge.notificationsSeen() >= 1;
                        }
                    }, 8_000L));
            assertTrue("注册表里没长出 p21_gamma，toolset 前=" + before + " 后="
                            + tk.namesOfToolset(bridge.toolset()),
                    waitFor(new Condition() {
                        @Override
                        public boolean holds() {
                            return tk.contains(McpBridge.bridgeName(SERVER, "p21_gamma"));
                        }
                    }, 8_000L));
            assertEquals("换血只该发生一轮", 1L, bridge.refreshCount());
            assertTrue("p21_delta 也该长出来",
                    tk.contains(McpBridge.bridgeName(SERVER, "p21_delta")));
            assertEquals("Toolkit 条目数增量必须正好等于新增工具数",
                    sizeBefore + 2, tk.size());

            // 第二轮：外部 actor 改表是删 ⇒ 真注销（槽位消失，不是同名覆盖）
            File ctl2 = new File(tmp.getRoot(), "drop.json");
            writeJson(ctl2, "{\"add\": [], \"drop\": [\"p21_beta\", \"p21_gamma\"]}");
            bridge.client().call("p21_apply_table",
                    Collections.<String, Object>singletonMap("control_file", ctl2.getAbsolutePath()));
            assertTrue("p21_beta 该消失", waitFor(new Condition() {
                @Override
                public boolean holds() {
                    return !tk.contains(McpBridge.bridgeName(SERVER, "p21_beta"));
                }
            }, 8_000L));
            assertFalse("p21_gamma 该消失", tk.contains(McpBridge.bridgeName(SERVER, "p21_gamma")));
            assertEquals("第二轮换血没发生", 2L, bridge.refreshCount());
            assertEquals("注销后条目数该正好回落", sizeBefore, tk.size());
            List<String> after = tk.namesOfToolset(bridge.toolset());
            assertTrue("delta 里该留下 p21_delta: " + after, after.contains("mcp-ref-p21_delta"));
            read("c-swap",
                    "relayFile", relayLog.getAbsolutePath(),
                    "relayLines", RealMcpHarness.slurp(relayLog).split("\n").length,
                    "toolset", bridge.toolset(),
                    "round1Tools", before.size(),
                    "sizeAfterRound1", sizeBefore,
                    "round2Tools", after.size(),
                    "notificationsSeen", bridge.notificationsSeen(),
                    "refreshCount", bridge.refreshCount());
        } finally {
            bridge.unregisterAll();
        }
        // 判的是<b>同一个</b> tk 实例（不是新 new 一个空表来充数）：
        // unregisterAll 走的是 Toolkit#deregisterToolset，整条 toolset 的槽位必须真没了，
        // 且总数正好回到桥注册之前的基线。
        read("c-unregisterAll",
                "toolset", toolset,
                "leftoverSlots", tk.namesOfToolset(toolset).size(),
                "size", tk.size(),
                "baseline", baseline);
        assertEquals("bridge 注销后该 toolset 仍占着槽位",
                Collections.emptyList(), tk.namesOfToolset(toolset));
        assertEquals("bridge 注销后总数没回到基线", baseline, tk.size());
    }

    // ------------------------------------------------------------------ §2 判定

    /**
     * 同一支真 server、同一条推送，内核 transport 收不到。
     * 内核的读循环只在"有人等某个 id"时才 {@code readLine()}，且不匹配 {@code "id":N} 的行
     * 直接 {@code continue} 丢弃 ⇒ 这条推送被它当垃圾吞了（中继日志证明它到了 stdout）。
     */
    @Test
    public void kernelTransportCannotSeeTheSamePushedNotification() throws Exception {
        Toolkit tk = new Toolkit();
        McpBridge bridge = new McpBridge(kernelClient(), tk);
        try {
            assertFalse("内核 transport 不许自证能收通知（它收不到）⇒ 外面也不许广告 listChanged",
                    bridge.notificationCapable());
            bridge.registerAll();
            int sizeBefore = tk.size();

            File ctl = new File(tmp.getRoot(), "kernel-add.json");
            writeJson(ctl, "{\"add\": [\"p21_epsilon\"], \"drop\": []}");
            McpClient.McpResult r = bridge.client().call("p21_apply_table",
                    Collections.<String, Object>singletonMap("control_file", ctl.getAbsolutePath()));
            assertFalse("内核这一路 tools/call 都没成: " + r.errorMessage, r.isError);
            assertTrue("阳性对照没猎物：server 没推", waitFor(new Condition() {
                @Override
                public boolean holds() throws Exception {
                    return RealMcpHarness.relayLogContains(relayLog, "S2C",
                            "\"method\":\"notifications/tools/list_changed\"");
                }
            }, 8_000L));
            // 给足 2s：若内核只是"慢"，这 2s 里也该刷出来
            RealMcpHarness.sleep(2_000L);

            assertFalse("内核竟然刷出了新工具 ⇒ §2 的推论被推翻，设计要改写",
                    tk.contains(McpBridge.bridgeName(SERVER, "p21_epsilon")));
            assertEquals("内核 transport 下注册表不该有任何变化", sizeBefore, tk.size());
            assertEquals("内核 transport 下 refreshCount 必须为 0", 0L, bridge.refreshCount());
        } finally {
            bridge.unregisterAll();
        }
    }

    // ------------------------------------------------------------------ §1.2

    @Test
    public void zbotTransportHonoursItsOwnDeadlineWhileKernelBlocksInReadLine() throws Exception {
        McpClient mine = zbotClient(900L);
        mine.connect();
        long t0 = System.currentTimeMillis();
        // 注意口径：kernel 的 McpClient#call 会把 transport 抛的 TimeoutException 包成
        // McpResult.error(...)，不往外抛。所以这里判"结果是错误 + 用时贴着自己的 deadline"，
        // 而不是判异常。
        McpClient.McpResult stalled =
                mine.call("p21_stall", Collections.<String, Object>singletonMap("seconds", 6.0));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("stall 6s 却拿到了成功结果 ⇒ 超时没触发，用了 " + elapsed + "ms", stalled.isError);
        assertTrue("超时读数 <700ms 说明根本没发出去: " + elapsed, elapsed >= 700L);
        assertTrue("900ms 的超时用了 " + elapsed + "ms ⇒ deadline 不成立", elapsed < 4_000L);
        assertTrue("错误里该带上是哪个方法超时，实得: " + stalled.errorMessage,
                String.valueOf(stalled.errorMessage).contains("p21_stall"));
        mine.disconnect();

        final McpClient kernel = kernelClient();
        kernel.connect();
        final boolean[] returned = new boolean[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                kernel.call("p21_stall", Collections.<String, Object>singletonMap("seconds", 6.0));
                returned[0] = true;
            }
        }, "kernel-stall-probe");
        worker.setDaemon(true);
        worker.start();
        worker.join(3_000L);
        assertFalse("内核 3s 内就返回了 ⇒ §1.2 的'阻塞在 readLine 里、deadline 不检查'不成立",
                returned[0]);
        worker.join(20_000L);
        kernel.disconnect();
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 把关键读数原样打进 stdout —— surefire 会把它带进 maven 日志，
     * 于是 EVIDENCE.md 里那些数不是手抄，而是"跑一条命令就能重新长出来"的。
     * 值里绝不放 headers/参数原文（安全红线），只放计数与版本这类无害字段。
     */
    private static void read(String tag, Object... kv) {
        StringBuilder sb = new StringBuilder("P21-READ ").append(tag);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
        }
        System.out.println(sb);
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static boolean waitFor(Condition c, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (c.holds()) {
                    return true;
                }
                RealMcpHarness.sleep(50L);
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static void writeJson(File f, String json) throws Exception {
        java.nio.file.Files.write(f.toPath(), json.getBytes("UTF-8"));
    }
}
