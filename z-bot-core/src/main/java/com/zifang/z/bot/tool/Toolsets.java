package com.zifang.z.bot.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * toolset 声明层 —— <b>建在 z-bot 侧，不进内核</b>。
 *
 * <p>对齐 hermes 的分层：内核 {@code ToolDescriptor} 只存"这个槽位属于哪个 toolset"这一
 * 字符串事实，"我们到底有哪些 toolset、每个装什么工具"是应用侧的能力清单（她的等价物是
 * {@code toolsets.py}（她的仓根，不是 {@code tools/} 下）+ {@code registry.register_toolset_alias}）。</p>
 *
 * <p>两条硬规矩（roadmap 红线 2）：
 * <ol>
 *   <li><b>只列有消费者的名字</b>：每个 {@link Kind#CAPABILITY 能力子集}都必须在本仓有真实注册
 *       调用点，且 {@link #emptyCapabilityToolsets(Toolkit)} 在装配好的 toolkit 上必须返回空。
 *       抄她的 33 个 toolset 名字而没有对应能力＝清单说谎，这里直接不让列。</li>
 *   <li><b>清单外的工具名不许进能力子集</b>：{@link #toolsetForTool(String)} 只认清单，
 *       没登记的工具落回 {@link Toolkit#DEFAULT_TOOLSET} 兜底槽，不会被算进任何能力面。</li>
 * </ol>
 *
 * <p>MCP 桥的 toolset 是 {@value #MCP_PREFIX}&lt;server&gt; 动态名（一个 server 一个），
 * 由 {@link #mcpToolset(String)} / {@link #mcpOwner(String)} 统一拼法 ——
 * 拼法只有一处，注销侧才可能拼得回来。</p>
 */
public final class Toolsets {

    /** 诊断/演示类只读工具。 */
    public static final String CORE = "core";
    /** 沙箱内文件读写与搜索。 */
    public static final String FILE = "file";
    /** 起子进程的命令执行。 */
    public static final String EXEC = "exec";
    /** 出网探测。 */
    public static final String NET = "net";

    /** MCP 桥 toolset 的固定前缀：{@code mcp-<server>}。 */
    public static final String MCP_PREFIX = "mcp-";
    /** MCP 桥 owner 的固定前缀：{@code mcp:<server>}。 */
    public static final String MCP_OWNER_PREFIX = "mcp:";

    /** 一个声明项的种类。 */
    public enum Kind {
        /** 能力子集：必须有消费者，装配后不得为空。 */
        CAPABILITY,
        /** 兜底槽：未登记工具的落点（{@code memory}/{@code delegate_task}/{@code cronjob} 等）。 */
        FALLBACK,
        /** 动态前缀族：MCP 每个 server 一个 toolset，装配期才知道名字。 */
        DYNAMIC_PREFIX
    }

    /** 一个 toolset 的声明。 */
    public static final class Declaration {
        private final String name;
        private final Kind kind;
        private final String consumer;
        private final List<String> tools;

        Declaration(String name, Kind kind, String consumer, List<String> tools) {
            this.name = name;
            this.kind = kind;
            this.consumer = consumer;
            this.tools = Collections.unmodifiableList(new ArrayList<String>(tools));
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        /** 消费者：本仓里真正往这个 toolset 注册工具的调用点（类#方法）。 */
        public String consumer() {
            return consumer;
        }

        /** 清单内的工具名（{@link Kind#CAPABILITY} 才非空；动态族没有固定成员）。 */
        public List<String> tools() {
            return tools;
        }

        @Override
        public String toString() {
            return name + "(" + kind + ", consumer=" + consumer + ", tools=" + tools + ")";
        }
    }

    private static final Map<String, Declaration> MANIFEST =
            new LinkedHashMap<String, Declaration>();

    private static void declare(String name, Kind kind, String consumer, String... tools) {
        List<String> list = new ArrayList<String>();
        for (String t : tools) {
            list.add(t);
        }
        MANIFEST.put(name, new Declaration(name, kind, consumer, list));
    }

    static {
        declare(CORE, Kind.CAPABILITY, "tool/BuiltinTools#registerAll",
                "echo", "time", "counter", "health", "sysinfo");
        declare(FILE, Kind.CAPABILITY, "tool/BuiltinTools#registerAll",
                "read_file", "write_file", "search");
        declare(EXEC, Kind.CAPABILITY, "tool/BuiltinTools#registerAll",
                "exec", "mvn_build");
        declare(NET, Kind.CAPABILITY, "tool/BuiltinTools#registerAll",
                "curl_test");
        declare(Toolkit.DEFAULT_TOOLSET, Kind.FALLBACK,
                "agent/BotAgent（memory/delegate_task/cronjob 走 register(tool) 兜底槽）");
        declare(MCP_PREFIX, Kind.DYNAMIC_PREFIX, "mcp/McpBridge#registerAll");
    }

    private Toolsets() {
    }

    /** @return 清单里全部 toolset 名（声明顺序） */
    public static List<String> declaredNames() {
        return Collections.unmodifiableList(new ArrayList<String>(MANIFEST.keySet()));
    }

    /** @return 全部能力子集名（红线 2 的审计对象） */
    public static List<String> capabilityNames() {
        List<String> out = new ArrayList<String>();
        for (Declaration d : MANIFEST.values()) {
            if (d.kind() == Kind.CAPABILITY) {
                out.add(d.name());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static Declaration declaration(String toolset) {
        return MANIFEST.get(toolset);
    }

    /** 该 toolset 是否在清单里（含兜底槽与 MCP 前缀族）。 */
    public static boolean isDeclared(String toolset) {
        return toolset != null
                && (MANIFEST.containsKey(toolset) || isMcpToolset(toolset));
    }

    public static boolean isMcpToolset(String toolset) {
        return toolset != null && toolset.startsWith(MCP_PREFIX) && toolset.length() > MCP_PREFIX.length();
    }

    /**
     * 工具名 → 该落哪个 toolset。清单外的工具退回兜底槽，绝不塞进别人的能力面。
     * 清单里查不到却又是内建工具，说明声明层漏登记 —— 由 {@code ToolsetsManifestTest} 判红。
     */
    public static String toolsetForTool(String toolName) {
        String ts = lookup(toolName);
        return ts == null ? Toolkit.DEFAULT_TOOLSET : ts;
    }

    /** @return 该工具在清单里的能力子集名；不在清单里返回 null */
    public static String capabilityOfTool(String toolName) {
        return toolName == null ? null : lookup(toolName);
    }

    private static String lookup(String toolName) {
        for (Declaration d : MANIFEST.values()) {
            if (d.kind() == Kind.CAPABILITY && d.tools().contains(toolName)) {
                return d.name();
            }
        }
        return null;
    }

    /** 清单里出现过的全部工具名（能力子集口径）。 */
    public static Set<String> declaredToolNames() {
        Set<String> out = new LinkedHashSet<String>();
        for (Declaration d : MANIFEST.values()) {
            out.addAll(d.tools());
        }
        return Collections.unmodifiableSet(out);
    }

    /** MCP 桥的 toolset 名拼法（唯一真源，注销侧共用）。 */
    public static String mcpToolset(String serverName) {
        return MCP_PREFIX + mcpToken(serverName);
    }

    public static String mcpOwner(String serverName) {
        return MCP_OWNER_PREFIX + (serverName == null ? "unnamed" : serverName);
    }

    /** server 名里的中划线换成下划线：{@code mcp-a-b} 分不清 server 是 {@code a} 还是 {@code a-b}。 */
    private static String mcpToken(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return "unnamed";
        }
        StringBuilder sb = new StringBuilder(serverName.length());
        for (int i = 0; i < serverName.length(); i++) {
            char c = serverName.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /**
     * 红线 2 的运行时审计：返回<b>装配完之后仍然一个工具都没有</b>的能力子集。
     *
     * <p>非空即说明清单里列了我们根本没有的能力面（或者注册调用点被摘掉了），
     * 由 {@code ToolsetsManifestTest} 判红；也供 {@code /status} 一类的自省面直接打印。</p>
     */
    public static List<String> emptyCapabilityToolsets(Toolkit toolkit) {
        List<String> empty = new ArrayList<String>();
        if (toolkit == null) {
            return empty;
        }
        for (String name : capabilityNames()) {
            if (toolkit.namesOfToolset(name).isEmpty()) {
                empty.add(name);
            }
        }
        Collections.sort(empty);
        return empty;
    }

    /**
     * 反向审计：注册表里出现了清单外的 toolset 名（拼法跑偏 / 声明层没跟上）。
     * MCP 前缀族算合法动态名。
     */
    public static List<String> undeclaredToolsets(Toolkit toolkit) {
        List<String> out = new ArrayList<String>();
        if (toolkit == null) {
            return out;
        }
        for (String ts : toolkit.toolsetsInUse()) {
            if (!isDeclared(ts)) {
                out.add(ts);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * 清单自洽审计：能力子集里列了"本仓根本不注册的工具名"（拼错/改名没跟上）时返回出来。
     *
     * @param registeredToolNames 装配完之后注册表里的全部工具名
     */
    public static List<String> manifestToolsNeverRegistered(Toolkit toolkit) {
        List<String> out = new ArrayList<String>();
        if (toolkit == null) {
            return out;
        }
        Set<String> present = new LinkedHashSet<String>(toolkit.getToolNames());
        for (String tool : declaredToolNames()) {
            if (!present.contains(tool)) {
                out.add(tool);
            }
        }
        Collections.sort(out);
        return out;
    }
}
