package com.zifang.z.bot.cli;

import com.zifang.z.bot.store.StateStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code z-bot sessions …} — state.db 会话库管理：list / search / export / stats / prune。
 *
 * <p>默认操作 {@code ~/.zbot/state.db}，{@code --db} 可指向别的库文件（测试 / 多实例）。</p>
 */
@Command(name = "sessions", description = "state.db 会话库管理（list/search/export/stats/prune）",
        subcommands = {
                SessionsCommand.ListCmd.class,
                SessionsCommand.SearchCmd.class,
                SessionsCommand.ExportCmd.class,
                SessionsCommand.StatsCmd.class,
                SessionsCommand.PruneCmd.class})
public class SessionsCommand {

    @Option(names = {"--db"}, paramLabel = "FILE",
            description = "state.db 路径（默认 ~/.zbot/state.db，或 -Dzbot.state.db）")
    String db;

    static File dbFileOf(SessionsCommand parent) {
        String path = parent == null || parent.db == null || parent.db.trim().isEmpty()
                ? System.getProperty("zbot.state.db",
                System.getProperty("user.home") + "/.zbot/state.db")
                : parent.db.trim();
        return new File(path);
    }

    @Command(name = "list", description = "列出全部会话")
    public static class ListCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"-n", "--limit"}, defaultValue = "50", description = "最多显示条数")
        int limit;

        @Override
        public Integer call() {
            StateStore store = new StateStore(dbFileOf(parent));
            List<StateStore.SessionRow> rows = store.listSessions();
            java.io.PrintStream out = System.out;
            out.printf("%-28s  %-6s  %-32s  %s%n", "SESSION", "MSGS", "TITLE", "UPDATED");
            int n = 0;
            for (StateStore.SessionRow r : rows) {
                if (n++ >= limit) {
                    break;
                }
                out.printf("%-28s  %-6d  %-32s  %s%n", r.id, r.messageCount,
                        clip(r.title, 32), r.updatedAt);
            }
            out.println("共 " + rows.size() + " 个会话" + (store.isFtsEnabled() ? "" : "（FTS 不可用，检索为 LIKE 模式）"));
            return 0;
        }
    }

    @Command(name = "search", description = "全文检索消息内容（FTS5，降级 LIKE）")
    public static class SearchCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "最多返回条数")
        int limit;

        @Option(names = {"--session"}, paramLabel = "ID", description = "只搜指定会话")
        String session;

        @Parameters(paramLabel = "KEYWORD", description = "关键词")
        String keyword;

        @Override
        public Integer call() {
            StateStore store = new StateStore(dbFileOf(parent));
            List<StateStore.SearchHit> hits = store.search(keyword, limit);
            java.io.PrintStream out = System.out;
            for (StateStore.SearchHit h : hits) {
                if (session != null && !session.equals(h.sessionId)) {
                    continue;
                }
                out.println("[" + h.sessionId + "] …" + h.snippet.replace('\n', ' ') + "…");
            }
            out.println("命中 " + hits.size() + " 条");
            return 0;
        }
    }

    @Command(name = "export", description = "导出消息为 JSONL（每行一条消息）")
    public static class ExportCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"--session"}, paramLabel = "ID", description = "只导指定会话（缺省导全部）")
        String session;

        @Option(names = {"-o", "--out"}, paramLabel = "FILE", required = true, description = "输出文件")
        File out;

        @Override
        public Integer call() throws IOException {
            StateStore store = new StateStore(dbFileOf(parent));
            List<String> lines = store.exportJsonl(session);
            Files.write(out.toPath(), lines, StandardCharsets.UTF_8);
            System.out.println("已导出 " + lines.size() + " 条消息到 " + out.getAbsolutePath());
            return 0;
        }
    }

    @Command(name = "stats", description = "库级统计：会话数 / 消息数 / token / 按模型记账")
    public static class StatsCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Override
        public Integer call() {
            StateStore store = new StateStore(dbFileOf(parent));
            StateStore.Stats s = store.stats();
            System.out.println("sessions=" + s.sessions + " messages=" + s.messages
                    + " tokens=" + s.tokens + " api_calls=" + s.apiCalls);
            for (java.util.Map.Entry<String, Long> e : s.usageByModel.entrySet()) {
                System.out.println("  model=" + e.getKey() + " tasks=" + e.getValue());
            }
            System.out.println("db=" + store.getDbFile().getAbsolutePath()
                    + " fts=" + (store.isFtsEnabled() ? "on" : "off(LIKE)"));
            return 0;
        }
    }

    @Command(name = "prune", description = "清理空会话（updated_at 早于 N 天且 0 条消息）")
    public static class PruneCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"--days"}, defaultValue = "7", description = "N 天前的空会话才删")
        int days;

        @Override
        public Integer call() {
            StateStore store = new StateStore(dbFileOf(parent));
            int removed = store.prune(days);
            System.out.println("已清理 " + removed + " 个空会话（>" + days + " 天）");
            return 0;
        }
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
