package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpTransport;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Toolkit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P21 §4 的<b>进程内</b>证据：{@code notifications/tools/list_changed} 到了 ⇒
 * 注册表里<b>真</b>发生 deregister / register。
 *
 * <p>断言口径按工单钉死：<b>{@link Toolkit} 条目数前后差</b>，不看日志。
 * 与 {@code McpRealStdioServerTest}（真 python server、真进程）的分工是：这一支管
 * "通知进来了，注册表换血对不对"这条内部逻辑；那一支管"线上真能收到通知"。
 * 两支口径不同，不能互相顶替。</p>
 *
 * <p>与 {@code McpBridgeDeregisterTest} 的分工：那一支证 deregister 机制本身
 * （按 toolset 真删槽、不越界、不留 stub）；这一支证<b>通知驱动的</b>那条路径，
 * 包括"收不到通知的通道不许被算进兑现路径"这一半。</p>
 */
public class McpListChangedInProcessTest {

    /** 能收推送的内存 transport：{@link #push} 就是"server 主动推一条"。 */
    private static final class PushTransport implements McpTransport, McpNotificationSource {
        private final Map<String, String> table = new LinkedHashMap<String, String>();
        private final AtomicInteger listCalls = new AtomicInteger();
        private McpNotificationListener listener;
        private boolean capable = true;
        private boolean open;

        PushTransport(String... tools) {
            for (String t : tools) {
                table.put(t, "desc:" + t);
            }
        }

        /** 外部 actor 改表：删一个、加一个（改的是"服务端事实"，不是 bridge 的记账）。 */
        void mutate(List<String> drop, List<String> add) {
            for (String d : drop) {
                table.remove(d);
            }
            for (String a : add) {
                table.put(a, "desc:" + a);
            }
        }

        void push(String method) {
            McpNotificationListener l = listener;
            assertNotNull("listener 没挂上 —— 说明 bridge 认为这条通道收不到通知", l);
            l.onNotification(method, McpNotificationListener.emptyParams());
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public String request(String requestJson) {
            if (requestJson.contains("tools/list")) {
                listCalls.incrementAndGet();
                StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[");
                boolean first = true;
                for (Map.Entry<String, String> e : table.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append("{\"name\":\"").append(e.getKey())
                            .append("\",\"description\":\"").append(e.getValue())
                            .append("\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}");
                }
                return sb.append("]}}").toString();
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":\"ok\"}}";
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void setNotificationListener(McpNotificationListener l) {
            this.listener = l;
        }

        @Override
        public boolean notificationCapable() {
            return capable;
        }
    }

    private static List<String> namesOf(Toolkit tk, String toolset) {
        List<String> n = new ArrayList<String>(tk.namesOfToolset(toolset));
        java.util.Collections.sort(n);
        return n;
    }

    /** 有上限地等一条异步换血做完；超时就把当前状态原样交回，让断言报出差在哪。 */
    private static boolean waitForToolset(Toolkit tk, String toolset, List<String> expected) {
        for (int i = 0; i < 100; i++) {
            if (namesOf(tk, toolset).equals(expected)) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    public void pushedListChangedReallyDeregistersAndReplacesTheToolset() {
        Toolkit tk = new Toolkit();
        tk.register("builtin_keep", "不受影响的内置工具",
                new LinkedHashMap<String, Object>(), args -> ToolResult.text("kept"));
        int builtinBefore = tk.size();

        PushTransport tr = new PushTransport("alpha", "beta", "gamma");
        McpBridge bridge = new McpBridge(McpClientFactory.wrap("push", tr), tk);
        assertEquals("这条通道必须自认收得到通知", true, bridge.notificationCapable());
        assertEquals(3, bridge.registerAll());
        String toolset = bridge.toolset();
        assertEquals(Arrays.asList("mcp-push-alpha", "mcp-push-beta", "mcp-push-gamma"),
                namesOf(tk, toolset));
        int sizeAfterRegister = tk.size();
        assertEquals(builtinBefore + 3, sizeAfterRegister);
        long listsBefore = tr.listCalls.get();
        assertEquals(0L, bridge.refreshCount());

        // 外部 actor 改表：摘掉 beta、加上 delta —— 净条数不变，所以"只看条数"会漏判
        tr.mutate(Arrays.asList("beta"), Arrays.asList("delta"));
        tr.push(McpNotificationListener.TOOLS_LIST_CHANGED);

        List<String> expected = Arrays.asList("mcp-push-alpha", "mcp-push-delta", "mcp-push-gamma");
        assertTrue("换血没在 5s 内完成，当前: " + namesOf(tk, toolset),
                waitForToolset(tk, toolset, expected));
        assertEquals("净条数应当不变（一增一减）", sizeAfterRegister, tk.size());
        assertEquals(1L, bridge.refreshCount());
        assertEquals(1L, bridge.notificationsSeen());
        assertTrue("应当多打了一次 tools/list", tr.listCalls.get() > listsBefore);

        // 真 deregister：不是同名 stub 覆盖
        assertFalse("旧工具名还挂在注册表里", tk.contains("mcp-push-beta"));
        assertNull("旧槽位没被摘掉", tk.get("mcp-push-beta"));
        assertFalse(tk.getToolNames().contains("mcp-push-beta"));
        assertFalse(tk.getExposedToolNames().contains("mcp-push-beta"));
        assertFalse("旧名字还留在给模型看的工具描述里",
                tk.getToolsDescription().contains("mcp-push-beta"));
        List<String> booked = new ArrayList<String>(bridge.registeredNames());
        java.util.Collections.sort(booked);
        assertEquals(expected, booked);
        bridge.unregisterAll();
        assertEquals("整组注销后应回到内置基线", builtinBefore, tk.size());
    }

    @Test
    public void droppedToolsShrinkTheToolkitCountByExactlyTheirNumber() {
        Toolkit tk = new Toolkit();
        PushTransport tr = new PushTransport("one", "two", "three", "four");
        McpBridge bridge = new McpBridge(McpClientFactory.wrap("shrink", tr), tk);
        assertEquals(4, bridge.registerAll());
        int before = tk.size();

        tr.mutate(Arrays.asList("two", "four"), Collections.<String>emptyList());
        tr.push(McpNotificationListener.TOOLS_LIST_CHANGED);
        List<String> expected = Arrays.asList("mcp-shrink-one", "mcp-shrink-three");
        assertTrue("当前: " + namesOf(tk, bridge.toolset()),
                waitForToolset(tk, bridge.toolset(), expected));
        assertEquals("条目数前后差必须正好是被摘掉的 2 条", before - 2, tk.size());
        assertEquals(2, namesOf(tk, bridge.toolset()).size());
        bridge.unregisterAll();
        assertEquals(0, tk.size());
    }

    @Test
    public void otherNotificationMethodsDoNotTouchTheRegistry() {
        Toolkit tk = new Toolkit();
        PushTransport tr = new PushTransport("alpha");
        McpBridge bridge = new McpBridge(McpClientFactory.wrap("other", tr), tk);
        assertEquals(1, bridge.registerAll());
        int before = tk.size();
        long listsBefore = tr.listCalls.get();

        tr.push("notifications/resources/updated");
        // 同一批里钉住猎物：这条推送确实进了回调（计数 +1），只是不该触发换血
        for (int i = 0; i < 40 && bridge.notificationsSeen() < 1L; i++) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(1L, bridge.notificationsSeen());
        assertEquals(0L, bridge.refreshCount());
        assertEquals(before, tk.size());
        assertEquals("无关通知不该多打一次 tools/list", listsBefore, tr.listCalls.get());
        bridge.unregisterAll();
    }

    /**
     * 兑现路径的另一半：收不到通知的通道<b>不许</b>被算进 list_changed 的兑现，
     * 也不许对外广告 {@code listChanged}（广告出去就是承诺）。
     *
     * <p>阳性对照在同一条测试里：把同一套代码的 {@code capable} 翻成 true，
     * 同一次 push 就必须真的换血 —— 否则"没换血"可能只是"我压根没接通"。</p>
     */
    @Test
    public void channelThatCannotReceiveNotificationsIsNeitherAdvertisedNorRefreshed() {
        Toolkit tk = new Toolkit();
        PushTransport deaf = new PushTransport("alpha", "beta");
        deaf.capable = false;
        McpBridge bridge = new McpBridge(McpClientFactory.wrap("deaf", deaf), tk);
        assertEquals(2, bridge.registerAll());
        assertFalse(bridge.notificationCapable());
        assertNull("不该挂监听器", deaf.listener);
        assertEquals("没挂监听器时 push 就是空跑", 0L, bridge.notificationsSeen());
        int sizeBefore = tk.size();
        assertEquals(Arrays.asList("mcp-deaf-alpha", "mcp-deaf-beta"),
                namesOf(tk, bridge.toolset()));

        // 阴性对照成立后，把同一套通道翻成"收得到"：这一次 push 必须真换血
        PushTransport hearing = new PushTransport("alpha", "beta");
        Toolkit tk2 = new Toolkit();
        McpBridge live = new McpBridge(McpClientFactory.wrap("deaf", hearing), tk2);
        assertEquals(2, live.registerAll());
        hearing.mutate(Arrays.asList("beta"), Collections.<String>emptyList());
        hearing.push(McpNotificationListener.TOOLS_LIST_CHANGED);
        assertTrue("阳性对照：能收的通道必须真换血",
                waitForToolset(tk2, live.toolset(), Arrays.asList("mcp-deaf-alpha")));
        assertEquals(1L, live.refreshCount());
        assertEquals(sizeBefore, tk.size());
        bridge.unregisterAll();
        live.unregisterAll();
    }

    @Test
    public void managerReportsListChangedOnlyForChannelsThatHonourIt() {
        Toolkit tk = new Toolkit();
        PushTransport capable = new PushTransport("alpha");
        capable.capable = true;
        PushTransport deaf = new PushTransport("beta");
        deaf.capable = false;
        McpManager mgr = McpManager.fromClients(tk,
                Arrays.<com.zifang.z.agent.kernel.mcp.McpClient>asList(
                        McpClientFactory.wrap("capable", capable),
                        McpClientFactory.wrap("deaf", deaf)));
        mgr.startAll();
        Map<String, Boolean> advertised = new LinkedHashMap<String, Boolean>();
        for (Map<String, Object> m : mgr.toMapList()) {
            advertised.put(String.valueOf(m.get("name")), Boolean.valueOf(String.valueOf(m.get("listChanged"))));
        }
        assertEquals(2, advertised.size());
        assertEquals(Boolean.TRUE, advertised.get("capable"));
        assertEquals(Boolean.FALSE, advertised.get("deaf"));
        assertTrue(mgr.bridge("capable").notificationCapable());
        assertFalse(mgr.bridge("deaf").notificationCapable());
        mgr.stopAll();
        assertEquals(0, tk.size());
    }
}
