package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Toolkit;
import com.zifang.z.bot.tool.Toolsets;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpBridge} 的<b>真注销</b>守卫（P20）。
 *
 * <p>核心是那条反向断言：reload 之后旧 server 的工具名必须从 {@code getToolNames()} 里消失。
 * 全部断言都跑在<b>同一个 JVM、同一个 Toolkit 实例</b>里，并且现场留了一把"没被动过的第二
 * server"—— 真要是靠重启进程来"清掉"名字，第二 server 的工具也会一起不见，当场判红。</p>
 */
public class McpBridgeDeregisterTest {

    private static final long PID = currentPid();

    private static McpClient client(String name, InMemoryMcpTransport t) {
        return McpClientFactory.wrap(name, t);
    }

    private static McpManager manager(Toolkit tk, McpClient... clients) {
        return McpManager.fromClients(tk, Arrays.asList(clients));
    }

    private static List<String> exposedNames(Toolkit tk) {
        List<String> out = new ArrayList<String>();
        for (com.zifang.z.agent.kernel.tool.Tool t : tk.getAllTools()) {
            out.add(t.getName());
        }
        return out;
    }

    // ===== 本期最要紧的那条反向断言 =====

    @Test
    public void reloadDropsTheDeadServersToolNamesFromGetToolNames() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport legacy = new InMemoryMcpTransport("legacy")
                .tool("alpha", "旧工具 alpha").tool("beta", "旧工具 beta");
        McpManager mgr = manager(tk, client("legacy", legacy));

        mgr.startAll();
        assertTrue(tk.getToolNames().toString(), tk.getToolNames().contains("mcp-legacy-alpha"));
        assertTrue(exposedNames(tk).toString(), exposedNames(tk).contains("mcp-legacy-beta"));
        assertEquals(2, tk.size());

        // server 死了（连接关掉 = transport 不再应答），然后 reload
        legacy.close();
        mgr.stopAll();
        assertFalse("注销后 getToolNames 里不许留着旧名字: " + tk.getToolNames(),
                tk.getToolNames().contains("mcp-legacy-alpha"));
        assertEquals("槽位必须真没了，不是被同名 stub 占着", 0, tk.size());

        // 同一 JVM 里重新拉起：server 这一轮把 alpha 改名成了 gamma
        legacy.tool("gamma", "新工具 gamma").dropTool("alpha").dropTool("beta");
        int total = mgr.reload();
        assertEquals(1, total);
        assertEquals(Arrays.asList("mcp-legacy-gamma"), tk.getToolNames());
        assertFalse("改名前的老名字不许占 schema 名额",
                tk.getToolNames().contains("mcp-legacy-alpha"));
        assertFalse(tk.getToolNames().contains("mcp-legacy-beta"));
        assertFalse(tk.contains("mcp-legacy-alpha"));
        assertNull("get() 也拿不到旧工具", tk.get("mcp-legacy-alpha"));
        assertFalse("旧名字不许残留在 system prompt 的工具清单里",
                tk.getToolsDescription().contains("mcp-legacy-alpha"));
    }

    @Test
    public void unregisteringIsNotStubOverwrite() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport s = new InMemoryMcpTransport("s").tool("t1", "工具一");
        McpBridge bridge = new McpBridge(client("s", s), tk);
        assertEquals(1, bridge.registerAll());
        assertEquals("桥接名要带 mcp-<server>- 前缀", Arrays.asList("mcp-s-t1"), tk.getToolNames());
        String fpBefore = tk.schemaFingerprint();

        bridge.unregisterAll();

        assertFalse("真注销不许留任何同名 stub", tk.contains("mcp-s-t1"));
        assertEquals("槽位数量必须掉到 0，而不是被 stub 顶着", 0, tk.size());
        assertTrue("清单文本里不许残留 [unregistered] 之类的占位工具",
                !tk.getToolsDescription().contains("unregistered"));
        assertFalse("指纹必须换（schema 真的少了东西）", fpBefore.equals(tk.schemaFingerprint()));
        assertEquals(Collections.emptyList(), exposedNames(tk));
    }

    @Test
    public void deregistrationHappensInsideThisJvmNotByRestartingIt() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport dying = new InMemoryMcpTransport("dying").tool("one", "会消失");
        InMemoryMcpTransport staying = new InMemoryMcpTransport("staying").tool("two", "留下来");
        McpBridge a = new McpBridge(client("dying", dying), tk);
        McpBridge b = new McpBridge(client("staying", staying), tk);
        long pidAtStart = currentPid();
        a.registerAll();
        b.registerAll();
        assertEquals(2, tk.size());

        a.unregisterAll();

        assertEquals("同一个 JVM 从头跑到尾（pid 没换）", pidAtStart, PID);
        assertEquals("当前进程 pid 读数", 0L, currentPid() - pidAtStart);
        assertFalse("死掉那台的名字要没了", tk.contains("mcp-dying-one"));
        assertTrue("没被动过的那台必须还在 —— 否则就是重启了进程糊过去的",
                tk.contains("mcp-staying-two"));
        assertEquals(Arrays.asList("mcp-staying-two"), tk.getToolNames());
        assertTrue("B 的工具仍可执行",
                !tk.execute("mcp-staying-two", null).isError());
        assertTrue("A 的工具已消失，执行要报未找到",
                tk.execute("mcp-dying-one", null).isError());
        b.unregisterAll();
        assertEquals(0, tk.size());
    }

    // ===== owner / toolset 边界 =====

    @Test
    public void bridgeOnlyNukesItsOwnToolset() {
        Toolkit tk = new Toolkit();
        McpBridge mine = new McpBridge(client("mine",
                new InMemoryMcpTransport("mine").tool("x", "x")), tk);
        McpBridge theirs = new McpBridge(client("theirs",
                new InMemoryMcpTransport("theirs").tool("y", "y")), tk);
        mine.registerAll();
        theirs.registerAll();
        assertEquals(Toolsets.MCP_PREFIX + "mine", mine.toolset());
        assertEquals("mcp:mine", mine.owner());

        List<String> removed = mine.unregisterAllReturningNames();
        assertEquals(Arrays.asList("mcp-mine-x"), removed);
        assertTrue("别人的 toolset 不许被顺手清掉", tk.contains("mcp-theirs-y"));
        assertEquals(1, tk.size());
    }

    @Test
    public void aBridgeCannotDeregisterAnotherOwnersToolByToolsetName() {
        Toolkit tk = new Toolkit();
        tk.register(Toolkit.of("builtin-thing", "内建", null,
                args -> com.zifang.z.agent.kernel.tool.ToolResult.text("ok")),
                Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        try {
            tk.deregisterToolset(Toolsets.CORE, "mcp:sneaky");
            fail("拿别人的 owner 去整组注销必须被拒");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不能注销"));
        }
        assertTrue("被拒之后内建工具还在", tk.contains("builtin-thing"));
    }

    @Test
    public void repavingWithTheSameToolNameWorksAfterUnregister() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport s = new InMemoryMcpTransport("repave").tool("same", "第一版");
        McpBridge bridge = new McpBridge(client("repave", s), tk);
        bridge.registerAll();
        assertEquals("第一版", tk.get("mcp-repave-same").getDescription());
        bridge.unregisterAll();
        s.tool("same", "第二版");
        assertEquals(1, bridge.registerAll());
        assertEquals("第二版", tk.get("mcp-repave-same").getDescription());
        assertEquals(Arrays.asList("mcp-repave-same"), tk.getToolNames());
    }

    @Test
    public void serverNamesWithDashesGetUnambiguousToolsets() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport s = new InMemoryMcpTransport("deep-kb").tool("ask", "问答");
        McpBridge bridge = new McpBridge(client("deep-kb", s), tk);
        bridge.registerAll();
        assertEquals("mcp-deep_kb", bridge.toolset());
        assertEquals("mcp:deep-kb", bridge.owner());
        assertEquals("mcp-deep-kb-ask", bridge.registeredNames().get(0));
        assertEquals(Toolsets.MCP_PREFIX + "deep_kb", tk.toolsetOf("mcp-deep-kb-ask"));
        assertEquals(Arrays.asList("mcp-deep-kb-ask"), tk.namesOfToolset("mcp-deep_kb"));
        bridge.unregisterAll();
        assertEquals(Collections.emptyList(), tk.namesOfToolset("mcp-deep_kb"));
    }

    /**
     * 杠② <b>MB2</b> 的活猎物（上一棒记"单测层没有活的猎物"：看着最像猎物的
     * {@code deregisterToolsetCleansNamesTheBridgeNoLongerKnowsAbout} 是<b>注册表层</b>的用例，
     * 不经过 {@link McpBridge}）。本条把那个分岔真造出来：<b>注销这一刻，注册表里有的是
     * "桥没记住的名字"</b>，而结论必须来自注册表。
     *
     * <p>两个现场都是真路径：</p>
     * <ol>
     *   <li>同名 server 的<b>第二个桥实例</b>（reload/重连会造出来）—— toolset 与 owner 与第一个
     *       桥逐字相同，可两个桥的 {@code registered} 各只记着自己那批名字；</li>
     *   <li>上一轮注册留下、这一轮 server 不再发的<b>僵尸槽</b>（她 {@code registry.py:459}
     *       注释里 nuke-and-repave 就是为这个）—— 同一个 toolset/owner，桥的记忆里永远没有它。</li>
     * </ol>
     *
     * <p>反向钉住活的猎物：注销<b>之前</b>先用 {@code registeredNames()} 与 {@code getToolNames()}
     * 两个读数各点一次名 —— 没有这两行，下面的"必须全没了"就是空跑。旁边留一台没被动过的
     * 第三 server，防止"顺手清空整张注册表"这种修法蒙过去。</p>
     */
    @Test
    public void unregisterAllCleansSlotsTheBridgeNeverRecorded() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport first = new InMemoryMcpTransport("twice").tool("one", "上一轮的名字");
        McpBridge remembered = new McpBridge(client("twice", first), tk);
        assertEquals(1, remembered.registerAll());

        // 现场一：同名 server 的第二个桥实例（toolset/owner 与第一个完全一样）
        InMemoryMcpTransport again = new InMemoryMcpTransport("twice").tool("two", "这一轮的名字");
        McpBridge second = new McpBridge(client("twice", again), tk);
        assertEquals(1, second.registerAll());
        assertEquals("两个桥拼出来的 toolset/owner 必须逐字相同，否则这根本不是一个命名空间",
                remembered.toolset() + "/" + remembered.owner(), second.toolset() + "/" + second.owner());

        // 现场二：上一轮留下、server 这一轮不再发的僵尸槽（同一个 toolset + 同一个 owner）
        tk.register(Toolkit.of("mcp-twice-legacy", "上一轮的老名字", null,
                args -> ToolResult.text("ghost")), "mcp-twice", "mcp:twice", false);

        // 第三台：全程没被动过 ⇒ "整组注销"不许扩大成"清空注册表"
        InMemoryMcpTransport bystander = new InMemoryMcpTransport("bystander").tool("keep", "旁观");
        McpBridge third = new McpBridge(client("bystander", bystander), tk);
        assertEquals(1, third.registerAll());

        // ===== 活的猎物先点名（这两行不成立的话，下面的反向断言全是空跑）=====
        assertEquals("这个桥自己记住的只有 mcp-twice-two: " + second.registeredNames(),
                Collections.singletonList("mcp-twice-two"), second.registeredNames());
        assertFalse("注册表里有、桥没记住（现场一）",
                second.registeredNames().contains("mcp-twice-one"));
        assertFalse("注册表里有、桥没记住（现场二）",
                second.registeredNames().contains("mcp-twice-legacy"));
        assertEquals("注销前注册表里这三个名字都真挂着",
                Arrays.asList("mcp-twice-one", "mcp-twice-two", "mcp-twice-legacy"),
                new ArrayList<String>(tk.namesOfToolset("mcp-twice")));

        List<String> removed = second.unregisterAllReturningNames();

        assertEquals("结论必须来自注册表（toolset+owner 整组），不是来自桥的记忆: " + removed,
                Arrays.asList("mcp-twice-one", "mcp-twice-two", "mcp-twice-legacy"), removed);
        assertEquals("注销后这个 toolset 必须真空", Collections.emptyList(), tk.namesOfToolset("mcp-twice"));
        assertFalse("僵尸槽不许占着 schema 名额: " + tk.getToolNames(),
                tk.getToolNames().contains("mcp-twice-legacy"));
        assertFalse(tk.contains("mcp-twice-one"));
        assertFalse("外发清单里也不许留着桥没记住的名字: " + exposedNames(tk),
                exposedNames(tk).contains("mcp-twice-legacy"));
        assertEquals("第三台 server 一个字节都不许被顺带清掉: " + tk.getToolNames(),
                Arrays.asList("mcp-bystander-keep"), tk.getToolNames());
        assertEquals(1, tk.size());
        assertEquals("注销完桥的记忆也要跟着清空（否则下一次注册拿的是旧账）",
                Collections.emptyList(), second.registeredNames());
        third.unregisterAll();
        assertEquals(0, tk.size());
    }

    // ===== check_fn 接的是真连接状态 =====

    @Test
    public void bridgeRegistersAnAvailabilityProbeBackedByTheConnection() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport s = new InMemoryMcpTransport("live").tool("ping", "心跳");
        McpBridge bridge = new McpBridge(client("live", s), tk);
        bridge.registerAll();
        assertEquals("注册阶段不该探（探的是 schema 取用时刻）", 0L, bridge.probeInvocations());

        assertTrue(exposedNames(tk).contains("mcp-live-ping"));
        long afterFirstProbe = bridge.probeInvocations();
        assertEquals("第一次取 schema 必须真去探一次", 1L, afterFirstProbe);
        for (int i = 0; i < 6; i++) {
            exposedNames(tk);
            tk.getToolsDescription();
        }
        assertEquals("TTL（30s）窗内一律吃缓存，不许每次都探",
                afterFirstProbe, bridge.probeInvocations());

        // 另一台：注册完立刻断连，再取 schema ⇒ 探到的是真连接状态，工具不再外发但槽位保留
        InMemoryMcpTransport dying = new InMemoryMcpTransport("dying2").tool("ping", "心跳");
        McpBridge b2 = new McpBridge(client("dying2", dying), tk);
        b2.registerAll();
        dying.close();
        assertFalse(b2.client().isConnected());
        List<String> exposed = exposedNames(tk);
        assertFalse("连接断了就不许再把这批 schema 发给模型", exposed.contains("mcp-dying2-ping"));
        assertTrue("槽位仍在（派发侧不过滤），注册名清单要看得见",
                tk.getToolNames().contains("mcp-dying2-ping"));
        assertEquals(1L, b2.probeInvocations());
        ToolResult called = tk.execute("mcp-dying2-ping", null);
        assertTrue("掉线工具的调用要回错误", called.isError());
        assertTrue("槽位仍在：报的是调用失败，不是\"未找到工具\"（派发侧不按可用性过滤）: "
                        + called.getContent(),
                called.getContent().contains("transport closed")
                        && !called.getContent().contains("未找到工具"));
        bridge.unregisterAll();
        b2.unregisterAll();
        assertEquals(0, tk.size());
    }

    @Test
    public void managerStopAllClearsEveryBridgeToolsetAndReloadRepaves() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport a = new InMemoryMcpTransport("a").tool("t", "t");
        InMemoryMcpTransport b = new InMemoryMcpTransport("b").tool("u", "u");
        McpManager mgr = manager(tk, client("a", a), client("b", b));
        mgr.startAll();
        assertEquals(2, tk.size());
        long gen = tk.generation();

        mgr.stopAll();
        assertEquals(Collections.emptyList(), tk.getToolNames());
        assertTrue(tk.generation() > gen);

        a.tool("t2", "t2");
        assertEquals(3, mgr.reload());
        assertEquals(Arrays.asList("mcp-a-t", "mcp-a-t2", "mcp-b-u"), tk.getToolNames());
        assertFalse(tk.getToolNames().contains("mcp-ghost"));
        mgr.stopAll();
        assertEquals(0, tk.size());
    }

    @Test
    public void failedRegisterAllDoesNotLeaveHalfRegisteredTools() {
        Toolkit tk = new Toolkit();
        // 真拉不起来的子进程：connect 就炸，不该留下任何半成品工具
        McpClient broken = McpClientFactory.createStdio(
                new com.zifang.z.bot.config.BotConfig.McpServerEntry("broken",
                        Arrays.asList("/nonexistent/command")));
        McpManager mgr = manager(tk, broken, client("good",
                new InMemoryMcpTransport("good").tool("p", "p")));
        mgr.startAll();
        assertEquals(Arrays.asList("mcp-good-p"), tk.getToolNames());
        assertFalse(tk.getToolNames().contains("mcp-broken-p"));
        assertEquals(2, mgr.snapshot().size());
        mgr.stopAll();
        assertEquals(Collections.emptyList(), tk.getToolNames());
    }

    private static long currentPid() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
                    .contains("@") ? Long.parseLong(
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
                            .split("@")[0]) : -1L;
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
