package com.zifang.z.bot.cli;

import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.store.SchemaMigrations;
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
 * {@code z-bot sessions …} — state.db 会话库管理：list / search / export / stats / prune
 * + P15 的 version / lineage / end / resume / archive / checkpoint。
 *
 * <p>缺省操作 {@code <configDir>/state.db}（{@code --config-dir} 决定 profile），
 * {@code --db} 可直接指向别的库文件（测试 / 多实例）。</p>
 */
@Command(name = "sessions",
        description = "state.db 会话库管理（list/search/export/stats/prune/version/lineage/end/archive）",
        subcommands = {
                SessionsCommand.ListCmd.class,
                SessionsCommand.SearchCmd.class,
                SessionsCommand.ExportCmd.class,
                SessionsCommand.StatsCmd.class,
                SessionsCommand.PruneCmd.class,
                SessionsCommand.VersionCmd.class,
                SessionsCommand.LineageCmd.class,
                SessionsCommand.EndCmd.class,
                SessionsCommand.ResumeCmd.class,
                SessionsCommand.ArchiveCmd.class,
                SessionsCommand.CheckpointCmd.class})
public class SessionsCommand {

    @Option(names = {"--db"}, paramLabel = "FILE",
            description = "state.db 路径（覆盖 --config-dir 推导）")
    String db;

    @Option(names = {"--config-dir"}, paramLabel = "DIR",
            description = "配置目录 = profile（默认 $ZBOT_HOME，再退 ~/.zbot）；state.db 缺省落在这个目录下")
    File configDir;

    /** 解析优先级：--db &gt; 配置里的 agent.state.db &gt; -Dzbot.state.db &gt; &lt;configDir&gt;/state.db。 */
    private static File dbFileOf(SessionsCommand parent, BotConfig cfg) {
        if (parent != null && parent.db != null && !parent.db.trim().isEmpty()) {
            return new File(parent.db.trim());
        }
        return new File(cfg.getStateDbPath());
    }

    private static File configDirOf(SessionsCommand parent) {
        return parent == null || parent.configDir == null
                ? com.zifang.z.bot.config.BotConfig.defaultConfigDir() : parent.configDir;
    }

    /**
     * 开库统一入口：路径见 {@link #dbFileOf}，PRAGMA/重试/校验/自动清理那 10 个参数
     * 走 profile 的 {@code config.properties}（P15 接线）。
     *
     * <p>CLI 与 {@code BotAgent} 必须读同一份参数 —— 否则 {@code z-bot sessions prune} 和
     * 会话运行期看到的是两套数据库行为，配置就有了两个事实来源。</p>
     */
    static StateStore openStore(SessionsCommand parent) {
        BotConfig cfg = BotConfig.load(configDirOf(parent));
        return new StateStore(dbFileOf(parent, cfg), cfg.stateStoreOptions());
    }

    @Command(name = "list", description = "列出会话（默认隐藏软归档的）")
    public static class ListCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"-n", "--limit"}, defaultValue = "50", description = "最多显示条数")
        int limit;

        @Option(names = {"--all"}, description = "把软归档（archived=1）的会话也列出来")
        boolean all;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            List<StateStore.SessionRow> rows = store.listSessions(all);
            java.io.PrintStream out = System.out;
            out.printf("%-28s  %-4s  %-6s  %-30s  %-19s  %s%n",
                    "SESSION", "END", "MSGS", "TITLE", "UPDATED", "PARENT");
            int n = 0;
            for (StateStore.SessionRow r : rows) {
                if (n++ >= limit) {
                    break;
                }
                out.printf("%-28s  %-4s  %-6d  %-30s  %-19s  %s%n", r.id,
                        r.archived ? "arch" : (r.isEnded() ? "end" : "-"),
                        r.messageCount, clip(r.title, 30), r.updatedAt,
                        r.parentSessionId == null ? "" : r.parentSessionId);
            }
            out.println("共 " + rows.size() + " 个会话" + (all ? "（含归档）" : "（--all 连归档一起看）")
                    + (store.isFtsEnabled() ? "" : "（FTS 不可用，检索为 LIKE 模式）"));
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
            StateStore store = openStore(parent);
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
            StateStore store = openStore(parent);
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
            StateStore store = openStore(parent);
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

    @Command(name = "prune", description = "按保留策略清理（默认只删『已结束且 0 消息』；在飞/有消息都要显式开关）")
    public static class PruneCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"--days"}, defaultValue = "7", description = "N 天前的才进窗口")
        int days;

        @Option(names = {"--include-in-flight"},
                description = "连未结束（在飞）的会话一起删；只在明确知道没人在用时用")
        boolean includeInFlight;

        @Option(names = {"--cascade"},
                description = "父命中时沿 parent_session_id 连带删掉整条血统（默认是置空子会话的父指针）")
        boolean cascade;

        @Option(names = {"--dry-run"}, description = "只点名不动手")
        boolean dryRun;

        @Option(names = {"--archive"}, description = "软归档（打 archived 标记、不删数据）而不是硬删")
        boolean archive;

        @Option(names = {"--purge-archived"}, description = "硬删已归档且超过 --days 的会话")
        boolean purgeArchived;

        @Option(names = {"--source"}, paramLabel = "SRC", description = "只处理该来源的会话")
        String source;

        @Option(names = {"--max-messages"}, paramLabel = "N",
                description = "只处理消息数 <= N 的会话（默认 0：只清空的）")
        Integer maxMessages;

        @Option(names = {"--include-non-empty"},
                description = "连有消息的会话一起硬删（不加这个开关时 prune 只清 0 消息会话，"
                        + "与 P2 的行为一致；结束状态不是删除授权）")
        boolean includeNonEmpty;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            StateStore.PruneCriteria c = StateStore.PruneCriteria.olderThanDays(days)
                    .requireEnded(!includeInFlight)
                    // store 层的兼容档把"0 消息的在飞会话"也算作可删（P2 语义，auto_prune 走那条），
                    // 但这条命令的 --include-in-flight 开关不能是空话：在飞就是不能没经授权就被抹掉。
                    // E2E 实测抓出来的：默认档下 0 消息的在飞会话会被连带删掉（单测那条造的是有消息的在飞，看不见这个形状）。
                    .treatUnusedEmptyAsEnded(false)
                    .cascadeChildren(cascade)
                    .detachOrphans(!cascade)
                    .dryRun(dryRun);
            if (source != null && !source.trim().isEmpty()) {
                c.source(source.trim());
            }
            if (maxMessages != null) {
                c.maxMessages(maxMessages);
            } else if (!includeNonEmpty && !archive) {
                // 默认钉死在 P2 的语义上：这个命令曾经只能删空会话。"已结束"只是生命周期状态，
                // 不等于人可以把它连消息一起抹掉 —— 扩面必须显式给开关。
                // 只钉硬删这一档：--archive 是软归档（数据原样留着、可 --unarchive 撤销），
                // 给它加同样的封顶就等于逼用户为了归档再打一次删除开关。
                c.maxMessages(Integer.valueOf(0));
                System.out.println("按 0 消息范围清理（要连有消息的已结束会话一起删: --include-non-empty，"
                        + "或改用 --archive 软归档）");
            }
            StateStore.PruneReport r = archive ? store.archiveMatching(c) : store.pruneDetailed(c);
            if (purgeArchived) {
                int purged = store.deleteArchived(days);
                System.out.println("已硬删归档会话 " + purged + " 个（>" + days + " 天）");
            }
            System.out.println((archive ? "软归档 " : "清理 ") + r
                    + (dryRun ? " ⇒ dry-run，未改动任何数据" : ""));
            for (String id : r.ids) {
                System.out.println("  " + id);
            }
            return 0;
        }
    }

    @Command(name = "version", description = "schema 版本与迁移台账；--check 顺带校验 head")
    public static class VersionCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Option(names = {"--check"}, description = "跑一次 head 对齐校验，缺项则退出码 1")
        boolean check;

        @Option(names = {"--steps"}, description = "列出迁移阶梯")
        boolean steps;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            System.out.println("schema_version=" + store.schemaVersion()
                    + " head=" + SchemaMigrations.HEAD_VERSION
                    + " db=" + store.getDbFile().getAbsolutePath());
            SchemaMigrations.Report last = store.lastMigration();
            if (last != null) {
                System.out.println("本次开库迁移: " + last.summary());
            }
            String ledger = store.getMeta(StateStore.META_LAST_MIGRATION);
            System.out.println("迁移台账(state_meta): " + (ledger == null ? "(空)" : ledger));
            if (store.recoveredFromBackup() != null) {
                System.out.println("注意：原库判为不可用，已备份到 "
                        + store.recoveredFromBackup().getAbsolutePath() + "，当前是重建的空库");
            }
            if (steps) {
                for (SchemaMigrations.Step s : SchemaMigrations.steps()) {
                    System.out.println("  v" + s.version + " " + s.name + " — " + s.what);
                }
            }
            if (!check) {
                return 0;
            }
            List<String> problems = store.verifySchema();
            for (String p : problems) {
                System.out.println("  缺项: " + p);
            }
            System.out.println(problems.isEmpty() ? "schema 校验通过" : "schema 校验不通过");
            return problems.isEmpty() ? 0 : 1;
        }
    }

    @Command(name = "lineage", description = "看某个会话的血统链（往上到根 + 往下列子会话）")
    public static class LineageCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Parameters(paramLabel = "SESSION", description = "会话 id")
        String session;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            if (!store.sessionExists(session)) {
                System.out.println("会话不存在: " + session);
                return 1;
            }
            java.io.PrintStream out = System.out;
            List<StateStore.LineageStep> chain = store.lineageOf(session);
            for (int i = 0; i < chain.size(); i++) {
                StateStore.LineageStep s = chain.get(i);
                StringBuilder indent = new StringBuilder();
                for (int j = 0; j < i; j++) {
                    indent.append("  ");
                }
                out.printf("%s%s  msgs=%d  %s%s%n", indent, s.sessionId,
                        store.messageCount(s.sessionId),
                        s.ended ? "ended(" + s.endReason + ")" : "in-flight",
                        i == chain.size() - 1 ? "   ← 当前" : "");
            }
            List<StateStore.SessionRow> children = store.listChildSessions(session);
            for (StateStore.SessionRow child : children) {
                if (chain.size() > 1 && child.id.equals(chain.get(chain.size() - 2).sessionId)) {
                    continue; // 链上已经打过父节点
                }
                out.println("  ↳ " + child.id + "  " + clip(child.title, 30));
            }
            if (children.isEmpty()) {
                out.println("（无子会话）");
            }
            return 0;
        }
    }

    @Command(name = "end", description = "标成已结束（ended_at/end_reason），prune 才会碰它")
    public static class EndCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Parameters(paramLabel = "SESSION", description = "会话 id")
        String session;

        @Option(names = {"--reason"}, paramLabel = "WHY", defaultValue = "manual",
                description = "结束原因（第一个写进去的原因赢）")
        String reason;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            boolean ok = store.endSession(session, reason);
            System.out.println(ok ? "已标记结束: " + session + " (" + reason + ")"
                    : "没改动（会话不存在或早已结束）: " + session);
            return ok ? 0 : 1;
        }
    }

    @Command(name = "resume", description = "撤销 end 标记，让会话回到在飞状态")
    public static class ResumeCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Parameters(paramLabel = "SESSION", description = "会话 id")
        String session;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            boolean ok = store.reopenSession(session);
            System.out.println(ok ? "已回到在飞: " + session : "没改动（会话不存在或本就未结束）: " + session);
            return ok ? 0 : 1;
        }
    }

    @Command(name = "archive", description = "软归档 / 取消归档（不删数据）")
    public static class ArchiveCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Parameters(paramLabel = "SESSION", description = "会话 id；配 --old N 时留空")
        String session;

        @Option(names = {"--unarchive"}, description = "改成未归档")
        boolean unarchive;

        @Option(names = {"--old"}, paramLabel = "N", description = "批量：把 N 天前且已结束的会话整组归档")
        Integer oldDays;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            if (oldDays != null) {
                StateStore.PruneReport r = store.archiveMatching(
                        StateStore.PruneCriteria.olderThanDays(oldDays.intValue()));
                System.out.println("批量归档 " + r);
                return 0;
            }
            if (session == null || session.trim().isEmpty()) {
                System.out.println("要么给 SESSION，要么给 --old N");
                return 1;
            }
            boolean ok = store.archiveSession(session.trim(), !unarchive);
            System.out.println(ok ? (unarchive ? "已取消归档: " + session : "已软归档: " + session)
                    : "会话不存在: " + session);
            return ok ? 0 : 1;
        }
    }

    @Command(name = "checkpoint", description = "立刻做一次 wal_checkpoint(TRUNCATE)，收缩 -wal")
    public static class CheckpointCmd implements Callable<Integer> {
        @ParentCommand
        SessionsCommand parent;

        @Override
        public Integer call() {
            StateStore store = openStore(parent);
            boolean ok = store.checkpointNow();
            System.out.println(ok ? "wal_checkpoint 完成（写事务计数 " + store.writeCounters() + "）"
                    : "wal_checkpoint 失败: " + store.lastError());
            return ok ? 0 : 1;
        }
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
