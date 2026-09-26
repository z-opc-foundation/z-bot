package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.mcp.McpTransport;
import com.zifang.z.agent.kernel.tool.BaseTool;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.Toolkit;
import com.zifang.z.bot.tool.Toolsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个 {@link McpClient} 上的所有工具桥接成 {@link Toolkit} 里的 kernel 工具，
 * 命名统一为 {@code mcp-<server>-<tool>}（中划线避免被工具调用解析歧义）。
 *
 * <p>每个 server 独占一个 toolset（{@code mcp-<server>}）与 owner（{@code mcp:<server>}），
 * 于是 {@link #unregisterAll()} 能按 toolset <b>真注销</b> —— 对齐 hermes
 * {@code registry.deregister}（registry.py:459，注释明写这是给 MCP dynamic discovery
 * 的 "nuke-and-repave" 用的，且 {@code mcp-*} 前缀豁免插件覆写闸门）。
 * 早前这里没有 deregister API，只能用同名 stub 覆盖收尾，reload 后旧工具名仍占着
 * schema（roadmap §1#8 的旧账），现在没有了。</p>
 */
public final class McpBridge {

    private static final Logger LOG = LoggerFactory.getLogger(McpBridge.class);

    private final McpClient client;
    private final Toolkit toolkit;
    private final List<String> registered = new ArrayList<String>();
    /** 只保护"注册表换血"这一段，网络 I/O 一律在锁外 —— 否则一次超时挂住整个 server 的刷新。 */
    private final Object swapLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean active;
    private volatile boolean refreshPending;
    private final java.util.concurrent.atomic.AtomicLong refreshCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong notificationCount =
            new java.util.concurrent.atomic.AtomicLong();

    public McpBridge(McpClient client, Toolkit toolkit) {
        this.client = client;
        this.toolkit = toolkit;
    }

    public String serverName() {
        return client.name();
    }

    /**
     * 这条通道能不能收到 server 主动推的 notification。
     *
     * <p>{@code listChanged} 会被写进 {@link McpManager#toMapList()} 对外广告，
     * 所以它必须等于"真能收到"，而不是"配置里写了"。内核 {@code StdioMcpTransport}
     * 不实现 {@link McpNotificationSource} ⇒ 这里回 false ⇒ 外面也不许广告。</p>
     */
    public boolean notificationCapable() {
        McpTransport t = McpClientFactory.transportOf(client);
        return t instanceof McpNotificationSource
                && ((McpNotificationSource) t).notificationCapable();
    }

    /** server 主动推来的 notification 条数（"通知通道真通了"的硬读数）。 */
    public long notificationsSeen() {
        return notificationCount.get();
    }

    /** 因 list_changed 而实际做完的"注销+重注册"轮数。 */
    public long refreshCount() {
        return refreshCount.get();
    }

    /** 该 server 在注册表里占的 toolset 名（拼法唯一真源：{@link Toolsets#mcpToolset}）。 */
    public String toolset() {
        return Toolsets.mcpToolset(client.name());
    }

    /** 该 server 的 owner 标识：只有本 bridge 能注销自己 server 的工具。 */
    public String owner() {
        return Toolsets.mcpOwner(client.name());
    }

    /**
     * 连接并把 tools 全部注册进 toolkit。失败抛 RuntimeException 不静默。
     *
     * <p>注册时带上<b>可用性探测</b>（check_fn）：连接断了就不再对外发这批 schema，
     * TTL 与瞬时失败宽限由内核 {@link ToolDescriptor} 承担；槽位保留，
     * {@code Toolkit#execute} 仍能调到（返回"调用失败"而不是"未找到工具"）——
     * 与她的 {@code get_definitions} 过滤、{@code dispatch} 不过滤同构。</p>
     */
    public int registerAll() {
        client.connect();
        active = true;
        hookNotifications();
        ConnectionProbe probe = new ConnectionProbe();
        this.availabilityProbe = probe;
        List<McpClient.McpTool> tools;
        try {
            tools = client.listTools();
        } catch (RuntimeException e) {
            client.disconnect();
            throw e;
        }
        return swapIn(tools, true);
    }

    /**
     * 只在通道支持时挂一次监听器：server 推 {@code notifications/tools/list_changed}
     * ⇒ 本 bridge 做<b>真</b>注销 + 重注册（{@link #refreshTools()}）。
     *
     * <p>重复 registerAll（reload 路径）不能重复挂，否则一次通知触发两轮刷新。</p>
     */
    private void hookNotifications() {
        McpTransport t = McpClientFactory.transportOf(client);
        if (!(t instanceof McpNotificationSource)) {
            return;
        }
        if (!((McpNotificationSource) t).notificationCapable()) {
            return;
        }
        ((McpNotificationSource) t).setNotificationListener(new McpNotificationListener() {
            @Override
            public void onNotification(String method, Map<String, Object> params) {
                notificationCount.incrementAndGet();
                if (!McpNotificationListener.TOOLS_LIST_CHANGED.equals(method)) {
                    return;
                }
                LOG.info("[mcp:{}] 收到 {}，重列工具表", client.name(), method);
                refreshTools();
            }
        });
    }

    /**
     * {@code notifications/tools/list_changed} 的兑现路径：<b>先</b>在网络外拿新表，
     * <b>再</b>在 {@link #swapLock} 内按 toolset 整体注销 + 逐条重注册。
     *
     * <p>顺序刻意如此：反过来（先摘再拉）会让中间那段窗口里模型看到"这个 server 一个工具都没有"。
     * 摘与装都在同一个锁段内完成，模型侧读注册表要么看到旧表要么看到新表。</p>
     *
     * <p>连着来的一串通知会合并成最多两轮（{@link #refreshPending}）：server 批量改工具
     * 常见的是连推好几条，逐条重列就是 N 次往返打一个网络工具。</p>
     *
     * @return 本轮注册上的工具数；连接已断或本 bridge 已注销时返回 -1
     */
    public int refreshTools() {
        if (!active) {
            return -1;
        }
        if (!refreshing.compareAndSet(false, true)) {
            refreshPending = true;
            return -1;
        }
        int last = -1;
        try {
            do {
                refreshPending = false;
                List<McpClient.McpTool> tools;
                try {
                    tools = client.listTools();
                } catch (RuntimeException e) {
                    LOG.warn("[mcp:{}] list_changed 后重列工具失败，保持旧表: {}",
                            client.name(), e.getMessage());
                    break;
                }
                last = swapIn(tools, false);
                refreshCount.incrementAndGet();
            } while (refreshPending && active);
        } finally {
            refreshing.set(false);
        }
        return last;
    }

    /** 注册表换血：{@code keepProbe=false} 时复用现有探测句柄，不另起一个（否则 TTL 记账断链）。 */
    private int swapIn(List<McpClient.McpTool> tools, boolean firstTime) {
        synchronized (swapLock) {
            if (!firstTime) {
                toolkit.deregisterToolset(toolset(), owner());
                registered.clear();
            }
            for (McpClient.McpTool t : tools) {
                String name = bridgeName(client.name(), t.name);
                if (toolkit.contains(name)) {
                    LOG.warn("[mcp:{}] 工具 {} 已存在，覆盖旧实现", client.name(), name);
                }
                // 网络工具的副作用不可知 → parallelSafe=false，交给派发侧串行
                toolkit.register(toKernelTool(name, t),
                        new ToolDescriptor(toolset(), false, availabilityProbe, owner()));
                registered.add(name);
                LOG.info("[mcp:{}] 注册工具 {} — {}", client.name(), name, abbreviate(t.description));
            }
            return registered.size();
        }
    }

    /** 本 server 的共享探测器：连接活着才把工具发给模型。探测次数留给验收读数用。 */
    private final class ConnectionProbe implements java.util.function.Supplier<Boolean> {
        private final java.util.concurrent.atomic.AtomicLong calls =
                new java.util.concurrent.atomic.AtomicLong();

        @Override
        public Boolean get() {
            calls.incrementAndGet();
            return Boolean.valueOf(client.isConnected());
        }
    }

    private volatile ConnectionProbe availabilityProbe;

    /** 该 server 的可用性探测被真正调用的次数（TTL 命中不算）。 */
    public long probeInvocations() {
        ConnectionProbe probe = availabilityProbe;
        return probe == null ? 0L : probe.calls.get();
    }

    /**
     * 关闭连接 + <b>按 toolset 整体注销</b>该 server 的所有工具（真删槽位，不是同名覆盖）。
     *
     * <p>整体注销比"逐个删记住的名字"硬：server 侧改了工具名时，老名字也一并清掉。
     */
    public void unregisterAll() {
        unregisterAllReturningNames();
    }

    /**
     * 同 {@link #unregisterAll()}，但把实际被摘掉的名字回出来。
     *
     * <p>故意不拿"我记住的那批名字"当结论：那正是旧实现（同名 stub 覆盖）的口径 ——
     * server 侧改过工具名时，老名字不在记忆里就漏掉，注册表里从此多出一个僵尸槽。</p>
     */
    public List<String> unregisterAllReturningNames() {
        active = false;
        McpTransport t = McpClientFactory.transportOf(client);
        if (t instanceof McpNotificationSource) {
            ((McpNotificationSource) t).setNotificationListener(null);
        }
        List<String> removed;
        synchronized (swapLock) {
            removed = toolkit.deregisterToolset(toolset(), owner());
            for (String name : registered) {
                if (!removed.contains(name)) {
                    LOG.warn("[mcp:{}] 注销后 {} 不在被删清单里 —— 注册表状态与 bridge 记账不一致",
                            client.name(), name);
                }
            }
            registered.clear();
            availabilityProbe = null;
        }
        try {
            client.disconnect();
        } catch (Exception ignored) {
        }
        return removed;
    }

    public List<String> registeredNames() {
        return new ArrayList<String>(registered);
    }

    public McpClient client() {
        return client;
    }

    public static String bridgeName(String server, String tool) {
        return "mcp-" + server + "-" + tool;
    }

    private Tool toKernelTool(String name, McpClient.McpTool t) {
        Map<String, Object> schema = t.inputSchema == null
                ? emptyObjectSchema()
                : t.inputSchema;
        return new BaseTool(name, t.description == null ? "" : t.description, schema) {
            @Override
            protected ToolResult doExecute(Map<String, Object> args) {
                McpClient.McpResult r;
                try {
                    r = client.call(t.name, args);
                } catch (Exception e) {
                    return ToolResult.error("MCP 调用失败: " + e.getMessage());
                }
                if (r == null) {
                    return ToolResult.error("MCP 返回 null");
                }
                if (r.isError) {
                    return ToolResult.error(r.errorMessage == null ? "未知错误" : r.errorMessage);
                }
                Object content = r.content;
                if (content == null) {
                    return ToolResult.text("");
                }
                return ToolResult.text(content.toString());
            }
        };
    }

    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}