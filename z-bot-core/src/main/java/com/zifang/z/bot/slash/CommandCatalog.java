package com.zifang.z.bot.slash;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.center.BotCenterClient;
import com.zifang.z.bot.session.SessionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P19 · 命令表<b>单一真源</b> —— 名字、参数提示、说明、各端可见性、执行入口，全在这里一处定义。
 *
 * <h2>为什么要有这个类（p28 的实测账）</h2>
 * 在 P19 之前，同一张命令面散在四处各自手抄：{@code SlashRegistry} 的 20 条内置命令、
 * {@code RawTerminalReader.LOCAL_COMMANDS} 的 9 条终端私有名、{@code TerminalChannel.LOCAL_DOC}
 * 的 6 条终端说明、以及 {@code web/index.html} 里手写的 6 行 {@code /help} 文本。
 * 抄了四份就必然漂四份 —— p28 的 {@code WIRING.md} §3.4/§3.5 量出 web 侧广告了根本没有分支接住的
 * {@code /exit}、却没有真接住的 {@code /help}，§3.6 量出 {@code /help} 两端各写一份、内容必然不同。
 *
 * <h2>本表与四端的消费关系（谁读哪一列）</h2>
 * <ul>
 *   <li><b>服务端注册表</b>：{@link #serverDefs()} → {@code SlashRegistry.withBuiltinCommands()}
 *       照本表逐条注册；注册表只剩"装配 + 技能那一段"，内置命令的名字/说明/入口不再在它里面。</li>
 *   <li><b>TUI</b>：{@code TerminalChannel} 的补全池 = {@link Endpoint#TUI}，帮助表 = 同一段里
 *       非别名的那些条（别名折进主条目，与收口前的 {@code LOCAL_DOC} 逐行同序同文）。</li>
 *   <li><b>HTTP</b>：{@code GET /api/commands} 把整张表序列化出去（含每行的 {@code endpoints}），
 *       控制台据此渲染 —— 见 {@link #toRow(Def)}。</li>
 *   <b>WEB</b>：{@code web/index.html} 不再自己写清单，改读 {@link Endpoint#WEB} 那一段。
 *   <li><b>ACP</b>：{@code session/update} 的 {@code available_commands_update} 帧
 *       发 {@link Endpoint#ACP} 那一段。帧形状取自权威 schema（{@code acp/schema.py:2138}
 *       {@code AvailableCommand{name,description,input}} 与 {@code :2443} 的
 *       {@code sessionUpdate} 判别字），不是凭记忆编的。</li>
 * </ul>
 *
 * <h2>可见性口径：广告即兑现</h2>
 * {@code endpoints} 记的是"这个端点<b>真接得住</b>这条命令"，不是"想不想显示它"。
 * 于是 {@code /exit} 不在 {@link Endpoint#WEB} 里（web 没有退出的概念），
 * {@code /theme} 也不在（主题只有终端有）—— 每条都要在对应端点里有实打实的执行入口才进那一段。
 * 这条口径由 {@code CommandSurfaceConsistencyTest} 三面钉住，并用注入探针证明它抓得到漂移。
 *
 * <p>技能派生的命令不在本表里（它们是运行时扫出来的，名字不是字面量），
 * 由 {@code SlashRegistry.registerSkillCommands} 叠到服务端那一段尾巴上，仍不产生第二份清单。</p>
 */
public final class CommandCatalog {

    /** 消费这张表的端点。 */
    public enum Endpoint {
        /** 终端 REPL（JLine 与 raw 两条输入实现共用同一张表）。 */
        TUI("tui"),
        /** HTTP 控制面：{@code GET /api/commands}。 */
        HTTP("http"),
        /** 内置 Web 控制台（{@code web/index.html}）。 */
        WEB("web"),
        /** ACP（Agent Client Protocol）IDE 面。 */
        ACP("acp");

        private final String jsonKey;

        Endpoint(String jsonKey) {
            this.jsonKey = jsonKey;
        }

        /** 序列化给 {@code /api/commands} 的键名（控制台按它过滤）。 */
        public String jsonKey() {
            return jsonKey;
        }
    }

    /** 服务端命令的执行入口；终端私有命令没有（{@code null}），由各自通道的分支执行。 */
    public interface Executor {
        String run(BotAgent agent, String args);
    }

    /** 表里的一行。字段全 final —— 这张表在类初始化时就定死，运行时不改写。 */
    public static final class Def {
        private final String name;
        private final String description;
        private final String argsHint;
        private final Set<Endpoint> endpoints;
        private final Executor executor;
        private final String aliasOf;

        private Def(String name, String description, String argsHint,
                    Set<Endpoint> endpoints, Executor executor, String aliasOf) {
            this.name = name;
            this.description = description;
            this.argsHint = argsHint;
            this.endpoints = endpoints;
            this.executor = executor;
            this.aliasOf = aliasOf;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        /** 参数提示；空串 = 无参数。ACP 的 {@code input.hint} 与 HTTP 的 {@code args} 都读它。 */
        public String argsHint() {
            return argsHint;
        }

        public Set<Endpoint> endpoints() {
            return endpoints;
        }

        public boolean visibleAt(Endpoint e) {
            return endpoints.contains(e);
        }

        /** 执行入口；{@code null} = 通道私有命令（本表只登记它存在，执行在通道里）。 */
        public Executor executor() {
            return executor;
        }

        public boolean isServerCommand() {
            return executor != null;
        }

        /** 非 null = 这条是别名，不进帮助表（只在补全池里）；值是它归属的主条目。 */
        public String aliasOf() {
            return aliasOf;
        }

        public boolean isAlias() {
            return aliasOf != null;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Def> DEFS = new ArrayList<Def>();

    /** 技能派生命令的可见性段（政策只写一处，见 {@link #skillEndpoints()}）。 */
    private static final EnumSet<Endpoint> SKILL_ENDPOINTS =
            EnumSet.of(Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);

    private static Def def(String name, String description, String argsHint,
                           EnumSet<Endpoint> endpoints, Executor executor, String aliasOf) {
        Def d = new Def(name, description, argsHint,
                Collections.unmodifiableSet(new LinkedHashSet<Endpoint>(endpoints)), executor, aliasOf);
        DEFS.add(d);
        return d;
    }

    /**
     * 登记一条服务端命令：名字必须在 {@link #EXECUTORS} 里有执行入口，否则类初始化就炸。
     *
     * <p>"名字 + 入口"分开写是故意的 —— 入口体是 lambda，混进表里会让这张表读不出"有哪些命令"；
     * 但分开写就有各说两话的风险，所以这里双向钉：表里有名字没入口 ⇒ 抛，入口里有名字没表 ⇒
     * 由 {@link #selfCheck()} 与 {@code CommandSurfaceConsistencyTest} 抓。</p>
     */
    private static void server(String name, String description, String argsHint, Endpoint... visible) {
        Executor ex = EXECUTORS.get(name);
        if (ex == null) {
            throw new IllegalStateException("命令表登记了 " + name + " 却没有执行入口");
        }
        def(name, description, argsHint, EnumSet.copyOf(Arrays.asList(visible)), ex, null);
    }

    static {
        // ---- 终端私有命令：需要通道自己的本地 UI 状态，故意不进服务端注册表 ----
        // 名字顺序 = 收口前 RawTerminalReader.LOCAL_COMMANDS 的顺序；
        // 非别名行的顺序 = 收口前 TerminalChannel.LOCAL_DOC 的顺序 —— 两条都是观测面，不许漂。
        def("/status", "显示当前状态（模型 / 工具 / 技能）", "",
                EnumSet.of(Endpoint.TUI, Endpoint.WEB), null, null);
        def("/theme", "切换主题（cyan / green / amber）", "cyan|green|amber",
                EnumSet.of(Endpoint.TUI), null, null);
        def("/feedback", "对上一次回复评分（up / down / <int>）", "up|down|<int>",
                EnumSet.of(Endpoint.TUI), null, null);
        def("/confirm", "确认上次等待中的危险命令", "",
                EnumSet.of(Endpoint.TUI, Endpoint.WEB), null, null);
        def("/help", "显示此帮助", "",
                EnumSet.of(Endpoint.TUI, Endpoint.WEB), null, null);
        def("/?", "显示此帮助", "", EnumSet.of(Endpoint.TUI), null, "/help");
        def("/exit", "退出（别名 /quit, /q, /?）", "", EnumSet.of(Endpoint.TUI), null, null);
        def("/quit", "退出（别名 /quit, /q, /?）", "", EnumSet.of(Endpoint.TUI), null, "/exit");
        def("/q", "退出（别名 /quit, /q, /?）", "", EnumSet.of(Endpoint.TUI), null, "/exit");
    }

    /**
     * 内置服务端命令的执行入口。放在静态表之后初始化，是因为 {@link #server} 要按名字回查它 ——
     * 名字是这张表的键：写了两遍名字（一处定义、一处入口）会在这里被自己抓出来。
     */
    private static final Map<String, Executor> EXECUTORS = new LinkedHashMap<String, Executor>();

    private static void executor(String name, Executor ex) {
        if (EXECUTORS.put(name, ex) != null) {
            throw new IllegalStateException("执行入口重复登记: " + name);
        }
    }

    static {
        // 以下 20 条的 description 与执行体逐字来自收口前的 SlashRegistry.withBuiltinCommands()。
        // 只搬位置、不改语义：本轮不新增命令、不改任何一条的行为。
        executor("/new", (agent, args) -> "新会话已创建: " + agent.newSession());
        executor("/clear", (agent, args) -> {
            agent.clearMemory();
            return "记忆已清空";
        });
        executor("/sessions", (agent, args) -> {
            List<SessionManager.SessionSummary> sessions = agent.listSessions();
            if (sessions.isEmpty()) {
                return "本地暂无会话";
            }
            String current = agent.currentSessionId();
            StringBuilder sb = new StringBuilder("本地会话 (" + sessions.size() + ")\n");
            for (SessionManager.SessionSummary s : sessions) {
                sb.append(s.id.equals(current) ? "* " : "  ")
                        .append(s.id).append("  ").append(s.messageCount).append(" msgs  ")
                        .append(s.title).append('\n');
            }
            return sb.toString().trim();
        });
        executor("/switch", (agent, args) -> {
            if (args.isEmpty()) {
                return "格式: /switch <sessionId>（先 /sessions 查看）";
            }
            agent.switchSession(args);
            return "已切换到会话 " + args + "（" + agent.getMemory().size() + " 条消息）";
        });
        executor("/tools", (agent, args) -> {
            StringBuilder sb = new StringBuilder("已注册工具 (" + agent.getToolkit().size() + ")\n");
            for (String name : agent.getToolkit().getToolNames()) {
                sb.append("- ").append(name).append('\n');
            }
            return sb.toString().trim();
        });
        executor("/skills", (agent, args) -> agent.skillsManage(args));
        executor("/sync", (agent, args) -> {
            BotCenterClient c = agent.getCenterClient();
            if (c == null || !c.isEnabled()) {
                return "Bot 未接入 z-agent-center，无法 sync";
            }
            int wrote = agent.syncSkillsFromCenter();
            return "同步完成：写入了 " + wrote + " 个 SKILL.md 到本地";
        });
        executor("/model", (agent, args) -> "model: " + agent.getProviderCode() + " / " + agent.getModel());
        executor("/usage", (agent, args) -> "apiCalls=" + agent.context().budget().apiCalls()
                + "  tokens=" + agent.budgetLedger().effectiveTokensUsed()
                + "  messages=" + agent.getMemory().size()
                + "  running=" + agent.isRunning());
        executor("/stop", (agent, args) -> {
            if (!agent.isRunning()) {
                return "当前没有正在运行的任务";
            }
            agent.stop();
            return "已请求停止，将在最近的迭代边界生效";
        });
        executor("/steer", (agent, args) -> {
            if (args.isEmpty()) {
                return "格式: /steer <text>";
            }
            agent.steer(args);
            return agent.isRunning()
                    ? "已入队 steer，将在工具间隙注入"
                    : "已入队，将在下次对话开头并入";
        });
        executor("/queue", (agent, args) -> {
            if (args.isEmpty()) {
                return "格式: /queue <text>";
            }
            // /queue 是多条按序保留（一次排好几件事），/steer 是单槽后到盖先到 ——
            // 两者语义不同，不能都走 steer()，否则前一条排队消息会被后一条悄悄吃掉。
            agent.enqueue(args);
            return "已排队，将在下次对话开头并入";
        });
        executor("/compress", (agent, args) -> agent.compressNow(args));
        executor("/memory", (agent, args) -> {
            String local = agent.memoryManage(args);
            if (!args.isEmpty()) {
                return local;
            }
            // 无参数时顺带召回 center 侧长期记忆（历史上它是另一条 /memory，被重复注册静默吞掉）
            BotCenterClient c = agent.getCenterClient();
            if (c == null || !c.isEnabled()) {
                return local;
            }
            String recalled = c.recallMemory();
            return recalled == null || recalled.isEmpty()
                    ? local + "\n\ncenter 暂无长期记忆（首轮 chat 后会自动积累）"
                    : local + "\n\n[center 长期记忆]\n" + recalled;
        });
        executor("/cron", (agent, args) -> agent.cronManage(args));
        executor("/checkpoints", (agent, args) -> agent.checkpointManage(args));
        executor("/rollback", (agent, args) -> agent.rollbackCheckpoint(args));
        executor("/background", (agent, args) -> {
            if (args != null && args.toLowerCase().startsWith("result")) {
                return agent.backgroundResult(args.substring(6).trim());
            }
            if (args == null || args.trim().isEmpty()) {
                return "格式: /background <task> 或 /background result <id>";
            }
            return agent.submitBackground(args);
        });
        executor("/agents", (agent, args) -> agent.describeAgents());
        executor("/skill", (agent, args) -> agent.invokeSkillByName(args));

        // ---- 20 条内置服务端命令：名字 / 说明 / 参数提示 / 各端可见性 ----
        // WEB 段只登记 index.html 真有水流的三条（/new /clear /tools）+ 它的本地 /status /confirm /help；
        // 其余 17 条在 web 里没有转发分支，所以不进 WEB 段（p28 §3.1 量的就是这 17 条缺转发）。
        server("/new", "新建会话", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.WEB, Endpoint.ACP);
        server("/clear", "清空当前会话记忆", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.WEB, Endpoint.ACP);
        server("/sessions", "列出本地会话", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/switch", "切换会话: /switch <sessionId>", "<sessionId>",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/tools", "列出已注册工具", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.WEB, Endpoint.ACP);
        server("/skills", "已安装 Skill: /skills 列表, /skills view <name> 查看内容",
                "[view <name>]", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/sync", "从 z-agent-center 同步 Skill", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/model", "显示当前模型", "", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/usage", "本轮运行用量（api 调用 / tokens / 消息数）", "",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/stop", "请求协作式中断，在最近的迭代/工具边界生效", "",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/steer", "运行中插话: /steer <text>，工具间隙注入 [User steer]", "<text>",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/queue", "排队消息: /queue <text>，下次对话开头并入 [User queued]", "<text>",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/compress", "上下文压缩: /compress 查看状态并执行, /compress preview 只看状态",
                "[preview]", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/memory", "本地记忆: /memory 查看, /memory user, /memory pending, /memory forget [user]",
                "[user|pending|forget [user]]", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/cron", "定时任务: /cron 列表, /cron add <schedule> | <name> | <prompt>, remove/pause/resume <id>",
                "[add <schedule> | <name> | <prompt>]", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/checkpoints", "沙箱快照列表: /checkpoints, /checkpoints prune [n] 修剪", "[prune [n]]",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/rollback", "回滚沙箱到快照: /rollback [id]（缺省最近一次）", "[id]",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/background", "异步委托子代理: /background <task>，/background result <id> 取回结果",
                "<task>", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/agents", "查看异步委托台账（在跑/已完成/失败的子代理）", "",
                Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
        server("/skill", "显式加载技能: /skill <name> [指令]（绕过相关性门，并说明为什么它没进命令表）",
                "<name> [指令]", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);
    }

    private static final List<Def> IMMUTABLE =
            Collections.unmodifiableList(new ArrayList<Def>(DEFS));

    private CommandCatalog() {
    }

    /** 整张表（声明序：先终端私有 9 条，再服务端 20 条；各端点读的是自己的那一段）。 */
    public static List<Def> defs() {
        return IMMUTABLE;
    }

    /** 服务端那一段：执行入口就在本表里，{@code SlashRegistry} 照它注册。 */
    public static List<Def> serverDefs() {
        List<Def> out = new ArrayList<Def>();
        for (Def d : IMMUTABLE) {
            if (d.isServerCommand()) {
                out.add(d);
            }
        }
        return out;
    }

    /** 通道私有一段（{@code /status} {@code /theme} …）：注册表里没有它们，也不该有。 */
    public static List<Def> localDefs() {
        List<Def> out = new ArrayList<Def>();
        for (Def d : IMMUTABLE) {
            if (!d.isServerCommand()) {
                out.add(d);
            }
        }
        return out;
    }

    /** 某个端点该广告的那一段（含该端点的别名）。 */
    public static List<Def> defsFor(Endpoint e) {
        List<Def> out = new ArrayList<Def>();
        for (Def d : IMMUTABLE) {
            if (d.visibleAt(e)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 某个端点该广告的名字集合。 */
    public static Set<String> namesFor(Endpoint e) {
        Set<String> out = new LinkedHashSet<String>();
        for (Def d : defsFor(e)) {
            out.add(d.name());
        }
        return out;
    }

    /** 帮助表行（该端点可见、且不是别名）：{@code name — description}。 */
    public static List<String[]> rowsFor(Endpoint e) {
        List<String[]> out = new ArrayList<String[]>();
        for (Def d : defsFor(e)) {
            if (!d.isAlias()) {
                out.add(new String[]{d.name(), d.description()});
            }
        }
        return out;
    }

    public static Def find(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase();
        for (Def d : IMMUTABLE) {
            if (d.name.equalsIgnoreCase(key)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 一行命令 → {@code GET /api/commands} 的 JSON 对象。
     *
     * <p>HTTP 面吐的是<b>整张表</b>（每行自带 {@code endpoints} 可见性），这样控制台与任何客户端
     * 都只有一处清单可读；{@code scope} 是 {@code server}（有执行入口）、{@code channel}
     * （通道私有，执行在各自通道里）或 {@code skill}（运行时从技能扫出来的）。</p>
     */
    public static Map<String, Object> toRow(Def d) {
        return row(d.name, d.description, d.argsHint,
                d.isServerCommand() ? "server" : "channel", d.endpoints, d.aliasOf);
    }

    /**
     * 技能派生命令的可见性：它们由服务端执行（{@code SlashRegistry.registerSkillCommands} 叠进
     * 同一张注册表），所以 TUI / HTTP / ACP 三端都有；web 控制台没有转发分支
     * （p28 {@code WIRING.md} §3.1 量的就是"缺转发"），所以不进 {@link Endpoint#WEB} 段。
     *
     * <p>这条政策只写在这一个地方 —— {@code /api/commands} 与 ACP 帧都读它，不许各自再判一次。</p>
     */
    public static Set<Endpoint> skillEndpoints() {
        return Collections.unmodifiableSet(SKILL_ENDPOINTS);
    }

    /** 技能派生命令的那一行（{@code scope=skill}）；名字不在静态表里，所以由注册表现给。 */
    public static Map<String, Object> skillRow(String name, String description) {
        return row(name, description == null ? "" : description, "", "skill", SKILL_ENDPOINTS, null);
    }

    private static Map<String, Object> row(String name, String description, String argsHint,
                                           String scope, Set<Endpoint> endpoints, String aliasOf) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("description", description);
        m.put("args", argsHint);
        m.put("scope", scope);
        List<String> eps = new ArrayList<String>();
        for (Endpoint e : Endpoint.values()) {
            if (endpoints.contains(e)) {
                eps.add(e.jsonKey());
            }
        }
        m.put("endpoints", eps);
        if (aliasOf != null) {
            m.put("aliasOf", aliasOf);
        }
        return m;
    }

    /** 整张表的序列化形态（{@code /api/commands} 与 ACP 帧共用的分母）。 */
    public static List<Map<String, Object>> asRows() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Def d : IMMUTABLE) {
            out.add(toRow(d));
        }
        return out;
    }

    /** 名字 → 该端点是否可见（ACP 帧与 HTTP 行都用它做分母）。 */
    public static Set<String> serverNames() {
        Set<String> out = new LinkedHashSet<String>();
        for (Def d : serverDefs()) {
            out.add(d.name());
        }
        return out;
    }

    /** 补全池（某端点可见的全部名字，含别名）—— TUI 的两条输入实现共用它。 */
    public static List<String> completionPoolFor(Endpoint e) {
        List<String> out = new ArrayList<String>();
        for (Def d : defsFor(e)) {
            out.add(d.name());
        }
        return out;
    }

    /** 供 {@link Endpoint#TUI} 段做断言对照的通道私有名（顺序即声明顺序）。 */
    public static List<String> localNames() {
        List<String> out = new ArrayList<String>();
        for (Def d : localDefs()) {
            out.add(d.name());
        }
        return out;
    }

    /** 一张表内部不许出现第二个同名条目；{@code aliasOf} 必须指向表里真实存在的主条目。 */
    public static List<String> selfCheck() {
        List<String> problems = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Def d : IMMUTABLE) {
            if (!seen.add(d.name.toLowerCase())) {
                problems.add("重复的命令名: " + d.name);
            }
            if (d.description == null || d.description.trim().isEmpty()) {
                problems.add(d.name + " 没有说明");
            }
        }
        for (Def d : IMMUTABLE) {
            if (d.aliasOf != null && find(d.aliasOf) == null) {
                problems.add(d.name + " 的别名主条目不在表里: " + d.aliasOf);
            }
        }
        return problems;
    }
}
