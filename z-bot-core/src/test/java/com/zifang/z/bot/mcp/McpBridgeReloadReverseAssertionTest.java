package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P20 验收点名的那条<b>反向断言</b>：reload 之后 {@code getToolNames()} 里不许再出现旧
 * server 的工具名。
 *
 * <p>这个类刻意只使用 {@link McpBridge} 在"stub 覆盖"旧实现与"真 deregister"新实现里
 * <b>都存在</b>的那点 API（构造 / {@code registerAll} / {@code unregisterAll} /
 * {@code registeredNames}），目的是让"修之前先红一次"能被真的跑出来：
 * 把 {@code McpBridge.java} 换回 main 的版本，这个类照样编译，两条断言必须判红
 * （读数在 {@code .cache/p20b/logs/redfirst_*.log}，运行态不入库）。</p>
 *
 * <p>现场同时挂着第二台<b>没被动过</b>的 server：任何"靠重启进程把名字洗掉"的做法都会把
 * 第二台的工具一起弄没，当场判红。</p>
 */
public class McpBridgeReloadReverseAssertionTest {

    private static McpClient client(String name, InMemoryMcpTransport t) {
        return McpClientFactory.wrap(name, t);
    }

    @Test
    public void oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport dying = new InMemoryMcpTransport("dying")
                .tool("alpha", "旧 alpha").tool("beta", "旧 beta");
        InMemoryMcpTransport staying = new InMemoryMcpTransport("staying").tool("keep", "留下");
        long pidBefore = pid();

        McpBridge a = new McpBridge(client("dying", dying), tk);
        McpBridge b = new McpBridge(client("staying", staying), tk);
        assertEquals(2, a.registerAll());
        assertEquals(1, b.registerAll());
        assertTrue(tk.getToolNames().toString(), tk.getToolNames().contains("mcp-dying-alpha"));

        dying.close();       // server 真死了：连接关了，再问工具清单直接报错
        a.unregisterAll();   // ← 注销必须在同一个 JVM 内完成

        List<String> after = new ArrayList<String>(tk.getToolNames());
        assertFalse("reload 之后 getToolNames 里不许还留着旧 server 的工具名: " + after,
                after.contains("mcp-dying-alpha"));
        assertFalse("另一个旧名字也不许留着: " + after, after.contains("mcp-dying-beta"));
        assertTrue("同一 JVM 里第二台没被动过的 server 必须还在（否则就是拿重启糊过去的）: " + after,
                after.contains("mcp-staying-keep"));
        assertEquals("pid 没换，注销发生在同一个 JVM 内", pidBefore, pid());
        assertEquals(Arrays.asList("mcp-staying-keep"), after);

        // 旧 server 复活并换了工具名：老名字不许从任何角落冒回来
        dying.dropTool("alpha").dropTool("beta").tool("gamma", "新 gamma");
        assertEquals(1, a.registerAll());
        assertEquals(Arrays.asList("mcp-staying-keep", "mcp-dying-gamma"), tk.getToolNames());
        assertFalse(tk.getToolNames().contains("mcp-dying-alpha"));
        b.unregisterAll();
        a.unregisterAll();
        assertEquals(Collections.emptyList(), tk.getToolNames());
    }

    @Test
    public void unregisterLeavesNoPlaceholderBehindInAnyListView() {
        Toolkit tk = new Toolkit();
        InMemoryMcpTransport s = new InMemoryMcpTransport("solo").tool("t1", "只有一个工具");
        McpBridge bridge = new McpBridge(client("solo", s), tk);
        bridge.registerAll();
        assertEquals(1, tk.size());

        bridge.unregisterAll();

        assertEquals("注销完注册表必须是空的，不许留同名占位: " + tk.getToolNames(),
                0, tk.size());
        assertEquals(Collections.emptyList(), tk.getToolNames());
        assertEquals(Collections.emptyList(), tk.getToolNames());
        assertFalse(tk.contains("mcp-solo-t1"));
        assertTrue("schema 清单文本里也不许留着它: " + tk.getToolsDescription(),
                !tk.getToolsDescription().contains("mcp-solo-t1"));
    }

    private static long pid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return at > 0 ? Long.parseLong(name.substring(0, at)) : -1L;
    }
}
