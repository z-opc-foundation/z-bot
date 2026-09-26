package com.zifang.z.bot.cli;

import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.mcp.ZBotMcpServe;
import com.zifang.z.bot.store.StateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code z-bot mcp …} — 反向 MCP：把 z-bot 当 <b>MCP server</b> 暴露出去（P21 §6）。
 *
 * <p>注册形状照 {@link SessionsCommand}：顶层子命令 {@code mcp} + 嵌套 {@code serve}，
 * 配置目录/库文件的解析口径也走同一套（{@code --db > agent.state.db > -Dzbot.state.db >
 * <configDir>/state.db}），免得出现"两条命令看的是两个库"这种第二事实来源。</p>
 *
 * <p><b>只读</b>：本命令只挂 {@link ZBotMcpServe} 那两条读端工具（会话列表 / 消息读取），
 * 不接 {@code BotAgent}、不建会话、不写消息。stdout 全权交给 MCP 线，
 * 任何提示信息一律进 stderr。</p>
 */
@Command(name = "mcp", description = "把 z-bot 作为 MCP server 暴露（只读：会话与消息）",
        subcommands = {McpCommand.ServeCmd.class})
public class McpCommand {

    @Option(names = {"--db"}, paramLabel = "FILE",
            description = "state.db 路径（覆盖 --config-dir 推导）")
    String db;

    @Option(names = {"--config-dir"}, paramLabel = "DIR",
            description = "配置目录 = profile（默认 $ZBOT_HOME，再退 ~/.zbot）；state.db 缺省落在这里")
    File configDir;

    static StateStore openStore(McpCommand parent) {
        File dir = parent == null || parent.configDir == null
                ? BotConfig.defaultConfigDir() : parent.configDir;
        BotConfig cfg = BotConfig.load(dir);
        String explicit = parent == null ? null : parent.db;
        File dbFile = explicit == null || explicit.trim().isEmpty()
                ? new File(cfg.getStateDbPath()) : new File(explicit.trim());
        return new StateStore(dbFile, cfg.stateStoreOptions());
    }

    /**
     * {@code z-bot mcp serve} — stdio 上跑反向 MCP server。
     *
     * <p>{@code --read-only} 不是一个开关而是<b>实现约束</b>：没有写侧代码可关。
     * 这里留着的是"要不要把软归档的会话也列出去"。</p>
     */
    @Command(name = "serve", description = "在 stdout 上跑 MCP server（newline-delimited JSON-RPC）")
    public static class ServeCmd implements Callable<Integer> {

        @ParentCommand
        McpCommand parent;

        @Option(names = {"--include-archived"},
                description = "会话列表默认包含软归档项（对端也可以按请求覆盖）")
        boolean includeArchived;

        @Option(names = {"--limit"}, paramLabel = "N", defaultValue = "50",
                description = "单次读的最大条数上限（对端 limit 再大也被夹到这里）")
        int limit;

        @Option(names = {"--max-requests"}, paramLabel = "N", defaultValue = "0",
                description = "处理多少条请求后主动收尾（0 = 用默认上限；测试里给小值验证收尾有界）")
        long maxRequests;

        @Override
        public Integer call() throws Exception {
            StateStore store = openStore(parent);
            ZBotMcpServe.Options o = new ZBotMcpServe.Options()
                    .serverVersion(implementedVersion())
                    .defaultLimit(limit <= 0 ? 50 : limit);
            if (maxRequests > 0) {
                o.maxRequests(maxRequests);
            }
            ZBotMcpServe server = new ZBotMcpServe(
                    new StoreSource(store, includeArchived, limit <= 0 ? 50 : limit), o);
            System.err.println("[z-bot mcp serve] 只读 MCP server 已就绪: tools="
                    + ZBotMcpServe.toolDefinitions().size() + ", db=" + store.getDbFile()
                    + "（stdout 只走 MCP 线，这行进 stderr）");
            int handled = server.serve(System.in, System.out);
            System.err.println("[z-bot mcp serve] 收尾：处理 " + handled + " 行，拒绝 "
                    + server.rejectedRequests() + " 行");
            return 0;
        }

        /** 版本号只有一个来源：jar manifest；跑在 target/classes 下时才是 dev 串。 */
        private static String implementedVersion() {
            String v = McpCommand.class.getPackage().getImplementationVersion();
            return v == null || v.isEmpty() ? "0.2.0-dev" : v;
        }
    }

    /**
     * {@link ZBotMcpServe.ConversationSource} 的 state.db 实现。
     *
     * <p>刻意<b>不</b>实现任何写方法：这个类只有 {@code conversations(...)} 与
     * {@code messages(...)} 两个方法可调用，接口上没有写的口子（编译器就是这道围栏）。</p>
     */
    static final class StoreSource implements ZBotMcpServe.ConversationSource {

        private final StateStore store;
        private final boolean defaultIncludeArchived;
        private final int hardLimit;

        StoreSource(StateStore store, boolean defaultIncludeArchived, int hardLimit) {
            this.store = store;
            this.defaultIncludeArchived = defaultIncludeArchived;
            this.hardLimit = hardLimit;
        }

        @Override
        public List<Map<String, Object>> conversations(boolean includeArchived, int limit) {
            boolean withArchived = includeArchived || defaultIncludeArchived;
            List<StateStore.SessionRow> rows = store.listSessions(Boolean.valueOf(withArchived));
            int cap = Math.min(limit <= 0 ? hardLimit : limit, hardLimit);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            for (StateStore.SessionRow r : rows) {
                if (out.size() >= cap) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("conversation_id", r.id);
                m.put("title", r.title);
                m.put("model", r.model);
                m.put("provider", r.provider);
                m.put("message_count", Integer.valueOf(r.messageCount));
                m.put("tokens", Long.valueOf(r.tokens));
                m.put("created_at", r.createdAt);
                m.put("updated_at", r.updatedAt);
                m.put("source", r.source);
                m.put("parent_conversation_id", r.parentSessionId);
                m.put("ended_at", r.endedAt);
                m.put("archived", Boolean.valueOf(r.archived));
                out.add(m);
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> messages(String conversationId, int limit) {
            if (!store.sessionExists(conversationId)) {
                throw new IllegalArgumentException("未知会话 id: " + conversationId
                        + "（先用 " + ZBotMcpServe.TOOL_CONVERSATIONS + " 取列表）");
            }
            int cap = limit <= 0 ? hardLimit : limit;
            List<Msg> msgs = store.loadMessages(conversationId);
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            int idx = 0;
            for (Msg m : msgs) {
                if (out.size() >= cap) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("idx", Integer.valueOf(idx++));
                row.put("role", m.getRole() == null ? null : m.getRole().name());
                row.put("content", m.getContent());
                row.put("content_type", m.getType() == null ? null : m.getType().name());
                row.put("tool_call_id", m.getToolCallId());
                out.add(row);
            }
            return out;
        }
    }
}
