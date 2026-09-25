package com.zifang.z.bot.mcp;

import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端聚合 — 按 {@link BotConfig#getMcpServers()} 给每个 server 起一个 bridge，
 * 全部注册进 toolkit。
 *
 * <p>失败隔离：单个 server 连不上不阻塞其他；summary 报告每个 server 的 ok/error/工具数。</p>
 *
 * <p>{@link #reload()} 重启所有 bridge（连接 / 列工具 / 注册）— 对应 {@code /mcp reload} slash 命令。</p>
 */
public final class McpManager {

    private static final Logger LOG = LoggerFactory.getLogger(McpManager.class);

    private final Toolkit toolkit;
    private final List<BotConfig.McpServerEntry> config;
    private final List<McpBridge> bridges = new ArrayList<McpBridge>();
    private final List<BridgeStatus> status = new ArrayList<BridgeStatus>();
    /** 测试用：用一组预构建的 McpClient 跳过 stdio 拉起。 */
    private List<McpClient> prebuilt;

    public McpManager(Toolkit toolkit, List<BotConfig.McpServerEntry> config) {
        this.toolkit = toolkit;
        this.config = config == null ? new ArrayList<BotConfig.McpServerEntry>() : config;
    }

    /** 测试用：用一组预构建的 McpClient 跳过 stdio 拉起。 */
    public static McpManager fromClients(Toolkit toolkit, List<McpClient> clients) {
        McpManager m = new McpManager(toolkit, new ArrayList<BotConfig.McpServerEntry>());
        m.prebuilt = clients == null ? new ArrayList<McpClient>() : clients;
        return m;
    }

    /** 启动所有 bridge，失败也保留状态报告（不让一个坏 server 拖死整个 agent）。 */
    public synchronized void startAll() {
        if (prebuilt != null) {
            for (McpClient client : prebuilt) {
                startOne(new McpBridge(client, toolkit), client.name(), true);
            }
            return;
        }
        for (BotConfig.McpServerEntry entry : config) {
            McpBridge bridge = new McpBridge(McpClientFactory.createStdio(entry), toolkit);
            startOne(bridge, entry.getName(), true);
        }
    }

    private void startOne(McpBridge bridge, String name, boolean stdio) {
        try {
            int count = bridge.registerAll();
            bridges.add(bridge);
            status.add(BridgeStatus.ok(name, count));
            LOG.info("[mcp] server {} 启动, 注册 {} 个工具", name, count);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status.add(BridgeStatus.error(name, msg));
            LOG.warn("[mcp] server {} 启动失败: {}", name, msg);
        }
    }

    /** 关闭 + 重启所有 bridge。 */
    public synchronized int reload() {
        stopAll();
        int before = status.size();
        status.clear();
        startAll();
        int total = 0;
        for (BridgeStatus s : status) {
            if (s.ok) {
                total += s.toolCount;
            }
        }
        LOG.info("[mcp] reload 完成: {} 个 server, {} 个工具", status.size(), total);
        return total;
    }

    /** 关闭所有 bridge + 反注册工具。 */
    public synchronized void stopAll() {
        for (McpBridge b : bridges) {
            try {
                b.unregisterAll();
            } catch (Exception e) {
                LOG.warn("[mcp] 关闭 {} 失败: {}", b.serverName(), e.getMessage());
            }
        }
        bridges.clear();
    }

    public synchronized List<BridgeStatus> snapshot() {
        return new ArrayList<BridgeStatus>(status);
    }

    /** 给前端 / HTTP / slash 命令看的 status map。 */
    public synchronized List<Map<String, Object>> toMapList() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (BridgeStatus s : status) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", s.name);
            m.put("ok", s.ok);
            m.put("toolCount", s.toolCount);
            m.put("tools", s.registeredNames);
            if (!s.ok) {
                m.put("error", s.error);
            }
            out.add(m);
        }
        return out;
    }

    public static final class BridgeStatus {
        public final String name;
        public final boolean ok;
        public final int toolCount;
        public final List<String> registeredNames;
        public final String error;

        private BridgeStatus(String name, boolean ok, int toolCount,
                             List<String> registeredNames, String error) {
            this.name = name;
            this.ok = ok;
            this.toolCount = toolCount;
            this.registeredNames = registeredNames;
            this.error = error;
        }

        static BridgeStatus ok(String name, int count) {
            return new BridgeStatus(name, true, count, new ArrayList<String>(), null);
        }

        static BridgeStatus error(String name, String error) {
            return new BridgeStatus(name, false, 0, new ArrayList<String>(), error);
        }
    }
}