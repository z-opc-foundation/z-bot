package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.store.StateStore;
import com.zifang.z.bot.tool.Toolkit;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P21 杠③（真进程 E2E）的 <b>z-bot 侧驱动</b>：由 {@code p21_e2e.py} 用 {@code java -cp}
 * 起成真 JVM，通过 {@code System.out} 上的 {@code FACT<TAB>key<TAB>value} 行交回读数。
 *
 * <p>为什么要有这个类而不是让 python 直接说 MCP 线：杠③ 要测的是
 * <b>z-bot 的 transport 与 bridge</b>，python 自己连一遍只能证明"对端会说规范话"，
 * 证明不了我这侧的实现。所以线上行为由这个驱动走<b>生产装配入口</b>
 * （{@link McpClientFactory#create(BotConfig.McpServerEntry)} —— 配置判别位也在里面派发），
 * 注册表口径由 {@link Toolkit} 条目数给出，python 只做编排与外部观察点。</p>
 *
 * <p>模式：</p>
 * <ul>
 *   <li>{@code stdio}：§11(a) 官方 stdio server —— 握手 / 列表 / 三个工具调用</li>
 *   <li>{@code stdioswap}：§11(c) 外部 actor 改工具表 ⇒ 真 deregister（两轮：一增一减、再纯减）</li>
 *   <li>{@code http}：§11(b) 官方 StreamableHTTP/SSE server —— 会话头、双形态响应</li>
 *   <li>{@code httpswap}：§11(b)+(c) 同一条换血路径走 HTTP 绑定（GET 通知流是唯一落点）</li>
 *   <li>{@code seed}：给 §11(d) 反向 serve 造一个真有数据的 state.db</li>
 * </ul>
 *
 * <p>本类是 test 作用域的<b>编排壳</b>，不含任何被验收的逻辑；所有断言在 python 侧，
 * 所有事实来自产品码。</p>
 */
public final class McpE2eDriver {

    private McpE2eDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: McpE2eDriver <stdio|stdioswap|http|httpswap|seed> [--k v ...]");
            System.exit(2);
            return;
        }
        String mode = args[0];
        Map<String, String> opt = parse(args);
        try {
            if ("seed".equals(mode)) {
                seed(opt);
            } else if ("stdio".equals(mode)) {
                runStdio(opt, false);
            } else if ("stdioswap".equals(mode)) {
                runStdio(opt, true);
            } else if ("http".equals(mode)) {
                runHttp(opt, false);
            } else if ("httpswap".equals(mode)) {
                runHttp(opt, true);
            } else {
                System.err.println("未知模式: " + mode);
                System.exit(2);
            }
        } catch (Throwable t) {
            fact("error", t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
        System.out.flush();
        System.exit(0);
    }

    // ------------------------------------------------------------------ stdio 侧

    private static void runStdio(Map<String, String> opt, boolean swap) throws Exception {
        List<String> cmd = stdioCommand(opt);
        fact("argv", joinQuoted(cmd));
        BotConfig.McpServerEntry entry = new BotConfig.McpServerEntry(
                "e2e", cmd, BotConfig.McpServerEntry.TRANSPORT_STDIO, "", null,
                longOpt(opt, "timeout-ms", 30_000L));
        McpClient client = McpClientFactory.create(entry);
        fact("client_class", client.getClass().getName());
        ZBotStdioMcpTransport tr = transportOf(client, ZBotStdioMcpTransport.class);
        fact("transport_class", tr.getClass().getName());
        fact("self_reported_client_version", tr.selfReportedVersion());

        Toolkit tk = new Toolkit();
        McpBridge bridge = new McpBridge(client, tk);
        int n = bridge.registerAll();
        fact("registered", Integer.valueOf(n));
        fact("toolkit_size", Integer.valueOf(tk.size()));
        fact("toolset_names", join(sorted(tk.namesOfToolset(bridge.toolset()))));
        fact("server_protocol_version", tr.serverProtocolVersion());
        fact("server_info", tr.serverInfoName() + "@" + tr.serverInfoVersion());
        fact("peer_advertises_list_changed", Boolean.valueOf(tr.peerAdvertisesListChanged()));
        fact("notification_capable", Boolean.valueOf(tr.notificationCapable()));
        fact("bridge_notification_capable", Boolean.valueOf(bridge.notificationCapable()));
        callTools(client);
        if (swap) {
            swapTable(client, bridge, tk, opt);
        }
        bridge.unregisterAll();
        fact("toolkit_size_after_unregister", Integer.valueOf(tk.size()));
        fact("transport_notification_count", Long.valueOf(tr.notificationCount()));
        fact("transport_tools_list_changed_count", Long.valueOf(tr.toolsListChangedCount()));
        fact("stderr_tail", String.join(" | ", tr.stderrTail()));
        client.disconnect();
        fact("open_after_disconnect", Boolean.valueOf(tr.isOpen()));
    }

    private static void callTools(McpClient client) {
        Map<String, Object> echoArgs = new LinkedHashMap<String, Object>();
        echoArgs.put("text", "p21-e2e-ping");
        fact("call_p21_echo", stringify(client.call("p21_echo", echoArgs)));
        Map<String, Object> betaArgs = new LinkedHashMap<String, Object>();
        betaArgs.put("query", "q-42");
        fact("call_p21_beta", stringify(client.call("p21_beta", betaArgs)));
        fact("call_p21_alpha", stringify(client.call("p21_alpha", null)));
        Map<String, Object> bad = new LinkedHashMap<String, Object>();
        bad.put("text", 42);
        fact("call_p21_echo_wrong_type", stringify(client.call("p21_echo", bad)));
        fact("call_unknown_tool", stringify(client.call("p21_no_such_tool", null)));
    }

    /**
     * 外部 actor 改表两轮的实据：每轮都取 {@link Toolkit} 的名字集与条目数，
     * 不看任何日志。控制文件由 python 事先写好（"新表是什么"不由 z-bot 侧决定）。
     */
    private static void swapTable(McpClient client, McpBridge bridge, Toolkit tk,
                                  Map<String, String> opt) throws Exception {
        String toolset = bridge.toolset();
        snapshot(bridge, tk, toolset, "base");
        applyRound(client, bridge, tk, toolset, opt.get("control1"), "1");
        applyRound(client, bridge, tk, toolset, opt.get("control2"), "2");
        fact("bridge_notifications_seen", Long.valueOf(bridge.notificationsSeen()));
        fact("bridge_refresh_count", Long.valueOf(bridge.refreshCount()));
    }

    private static void applyRound(McpClient client, McpBridge bridge, Toolkit tk, String toolset,
                                   String controlFile, String tag) throws Exception {
        if (controlFile == null) {
            return;
        }
        long before = bridge.refreshCount();
        long t0 = System.currentTimeMillis();
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("control_file", controlFile);
        fact("apply" + tag + "_server_says", stringify(client.call("p21_apply_table", args)));
        long deadline = t0 + 20_000L;
        while (bridge.refreshCount() <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        fact("apply" + tag + "_refresh_count", Long.valueOf(bridge.refreshCount()));
        fact("apply" + tag + "_waited_ms", Long.valueOf(
                Math.min(20_000L, System.currentTimeMillis() - t0)));
        snapshot(bridge, tk, toolset, "after" + tag);
    }

    private static void snapshot(McpBridge bridge, Toolkit tk, String toolset, String tag) {
        fact("snapshot_" + tag + "_size", Integer.valueOf(tk.size()));
        fact("snapshot_" + tag + "_names", join(sorted(tk.namesOfToolset(toolset))));
    }

    private static List<String> stdioCommand(Map<String, String> opt) {
        String py = opt.get("python");
        String server = opt.get("server");
        String relay = opt.get("relay");
        List<String> serverArgv = new ArrayList<String>(Arrays.asList(
                py, "-u", server, "--transport", "stdio"));
        if (relay == null) {
            return serverArgv;
        }
        List<String> cmd = new ArrayList<String>(Arrays.asList(
                py, "-u", relay, "--"));
        cmd.addAll(serverArgv);
        return cmd;
    }

    // ------------------------------------------------------------------ http 侧

    private static void runHttp(Map<String, String> opt, boolean swap) throws Exception {
        String url = opt.get("url");
        BotConfig.McpServerEntry entry = new BotConfig.McpServerEntry(
                "e2e", Collections.<String>emptyList(), BotConfig.McpServerEntry.TRANSPORT_HTTP,
                url, headersOf(opt), longOpt(opt, "timeout-ms", 30_000L));
        fact("entry_render", entry.toString());
        fact("entry_safe_map", entry.toSafeMap().toString());
        McpClient client = McpClientFactory.create(entry);
        StreamableHttpMcpTransport tr = transportOf(client, StreamableHttpMcpTransport.class);
        fact("transport_class", tr.getClass().getName());
        Toolkit tk = new Toolkit();
        McpBridge bridge = new McpBridge(client, tk);
        fact("registered", Integer.valueOf(bridge.registerAll()));
        fact("session_id_present", Boolean.valueOf(tr.sessionId() != null
                && !tr.sessionId().isEmpty()));
        fact("session_id_prefix", tr.sessionId() == null ? "" : head(tr.sessionId(), 6));
        fact("server_protocol_version", tr.serverProtocolVersion());
        fact("negotiated_protocol_version", tr.negotiatedProtocolVersion());
        fact("server_info", tr.serverInfoName() + "@" + tr.serverInfoVersion());
        fact("peer_advertises_list_changed", Boolean.valueOf(tr.peerAdvertisesListChanged()));
        fact("notification_capable", Boolean.valueOf(tr.notificationCapable()));
        fact("json_shape_responses", Long.valueOf(tr.jsonShapeResponses()));
        fact("sse_shape_responses", Long.valueOf(tr.sseShapeResponses()));
        fact("toolset_names", join(sorted(tk.namesOfToolset(bridge.toolset()))));
        callTools(client);
        if (swap) {
            swapTable(client, bridge, tk, opt);
        }
        fact("json_shape_after", Long.valueOf(tr.jsonShapeResponses()));
        fact("sse_shape_after", Long.valueOf(tr.sseShapeResponses()));
        fact("transport_notification_count", Long.valueOf(tr.notificationCount()));
        bridge.unregisterAll();
        fact("toolkit_size_after_unregister", Integer.valueOf(tk.size()));
        client.disconnect();
        fact("open_after_disconnect", Boolean.valueOf(tr.isOpen()));
        fact("wire_log", join(tr.wireLog()));
    }

    private static Map<String, String> headersOf(Map<String, String> opt) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        String v = opt.get("header");
        if (v != null) {
            int eq = v.indexOf('=');
            h.put(v.substring(0, eq), v.substring(eq + 1));
        }
        return h;
    }

    // ------------------------------------------------------------------ seed

    private static void seed(Map<String, String> opt) {
        File db = new File(opt.get("db"));
        if (db.getParentFile() != null) {
            db.getParentFile().mkdirs();
        }
        StateStore store = new StateStore(db);
        store.upsertSession("e2e-live-1", "E2E 在线会话", "stub-model", "stub", 0, 0L, 0L, "cli");
        store.upsertSession("e2e-live-2", "第二条会话", "stub-model", "stub", 0, 0L, 0L, "cli");
        store.upsertSession("e2e-arch-1", "已归档会话", "stub-model", "stub", 0, 0L, 0L, "cli");
        store.appendMessage("e2e-live-1", msg("第一条用户消息",
                com.zifang.z.agent.kernel.types.MessageRole.USER));
        store.appendMessage("e2e-live-1", msg("第一条助手回复",
                com.zifang.z.agent.kernel.types.MessageRole.ASSISTANT));
        store.appendMessage("e2e-live-2", msg("另一条会话的消息",
                com.zifang.z.agent.kernel.types.MessageRole.USER));
        store.appendMessage("e2e-arch-1", msg("归档里的消息",
                com.zifang.z.agent.kernel.types.MessageRole.USER));
        store.archiveSession("e2e-arch-1", true);
        fact("db", db.getAbsolutePath());
        fact("sessions", join(sortedNames(store)));
        fact("messages_e2e-live-1", Integer.valueOf(store.messageCount("e2e-live-1")));
        System.err.println("[driver] seed 完成 " + db);
    }

    private static List<String> sortedNames(StateStore store) {
        List<String> ids = new ArrayList<String>();
        for (StateStore.SessionRow r : store.listSessions(true)) {
            ids.add(r.id);
        }
        return sorted(ids);
    }

    private static com.zifang.z.agent.kernel.message.Msg msg(
            String text, com.zifang.z.agent.kernel.types.MessageRole role) {
        return new com.zifang.z.agent.kernel.message.Msg(
                role, null, text,
                com.zifang.z.agent.kernel.message.MessageType.TEXT, null,
                new ArrayList<com.zifang.z.agent.kernel.message.ToolCall>(),
                new java.util.HashMap<String, Object>());
    }

    // ------------------------------------------------------------------ 小工具

    @SuppressWarnings("unchecked")
    private static <T> T transportOf(McpClient client, Class<T> expected) {
        Object t = McpClientFactory.transportOf(client);
        if (!expected.isInstance(t)) {
            throw new IllegalStateException("transport 类型不对：期望 " + expected.getSimpleName()
                    + " 实得 " + (t == null ? "null" : t.getClass().getName()));
        }
        return (T) t;
    }

    private static String stringify(McpClient.McpResult r) {
        if (r == null) {
            return "null";
        }
        return "isError=" + r.isError + " content=" + String.valueOf(r.content)
                + (r.errorMessage == null ? "" : " error=" + r.errorMessage);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                continue;
            }
            m.put(args[i].substring(2), args[++i]);
        }
        return m;
    }

    private static long longOpt(Map<String, String> opt, String k, long def) {
        String v = opt.get(k);
        try {
            return v == null ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> sorted(Iterable<String> in) {
        List<String> l = new ArrayList<String>();
        for (String s : in) {
            l.add(s);
        }
        Collections.sort(l);
        return l;
    }

    private static String join(Iterable<String> in) {
        StringBuilder sb = new StringBuilder();
        for (String s : in) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String joinQuoted(List<String> in) {
        StringBuilder sb = new StringBuilder();
        for (String s : in) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(McpWire.shellQuote(s));
        }
        return sb.toString();
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static void fact(String k, Object v) {
        System.out.println("FACT\t" + k + "\t" + String.valueOf(v).replace('\n', ' '));
        System.out.flush();
    }
}
