package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.BaseTool;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolRegistry;
import com.zifang.z.agent.kernel.tool.ToolResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 工具注册表 — <b>真注册表</b>：槽位/owner/代际/探测/结果上限全部由 kernel
 * {@link ToolRegistry} 承载，本类只做 z-bot 侧的门面与 schema 缓存。
 *
 * <p>对齐 hermes {@code tools/registry.py}（810 行）的能力面：
 * <ul>
 *   <li>{@link #deregister(String, String)} / {@link #deregisterToolset(String)} —
 *       真删槽位（registry.py:459）。此前 MCP 桥只能用同名 stub 覆盖收尾（§1#8 的旧账），
 *       注销后工具名仍占着 schema。</li>
 *   <li>{@link #generation()} — 代际计数（registry.py:239）。任何注册/注销都会 +1，
 *       {@link #getToolsDescription()} / {@link #getAllTools()} / {@link #schemaFingerprint()}
 *       这几个 schema 快照据此整体作废重建，见 {@link #snapshotFor(long)}。</li>
 *   <li>可用性探测（check_fn）在 kernel {@link ToolDescriptor}：TTL 30s + 距上次成功 60s 的
 *       瞬时失败宽限。不可用的工具只从 schema 里消失，槽位与派发仍在
 *       （她的分层：{@code get_definitions} 过滤，{@code dispatch} 不过滤）。</li>
 *   <li>单工具结果上限 + 溢出落文件（registry.py:650 get_max_result_size +
 *       tools/tool_result_storage.py）：超过上限的结果全文写进
 *       {@linkplain #resolveOverflowDir() 溢出目录}，回给模型的只有预览 + 文件路径。</li>
 * </ul>
 *
 * <p>同名注册会覆盖（与 z-opc 老 bot 行为一致：ZAgent 先注册裸 read_file/exec，
 * TerminalBot 再用带沙箱的版本盖掉）；跨 owner 覆盖被 kernel 注册表拒绝
 * —— 插件/外部桥不能悄悄顶掉内建工具。</p>
 *
 * <p>并行安全：本类只持有<b>声明</b>（{@code parallelSafe} 落在 kernel ToolDescriptor 上，
 * 是全仓唯一真源）；<b>分批判定在派发侧</b>（对齐她的
 * {@code agent/tool_dispatch_helpers.py}，我们的等价物是 {@code agent/BotAgent#executeBatch}）。
 * 注册表不参与调度。</p>
 */
public final class Toolkit {

    /** 内建注册的默认 owner（同名覆盖只在这个 owner 内允许）。 */
    public static final String DEFAULT_OWNER = "z-bot";
    /** 内建注册的默认 toolset 分组。 */
    public static final String DEFAULT_TOOLSET = "builtin";
    /** 溢出后留在上下文里的预览字符数（hermes budget_config.DEFAULT_PREVIEW_SIZE_CHARS=1_500）。 */
    public static final long DEFAULT_PREVIEW_CHARS = 1_500L;
    /** 溢出目录的显式覆盖入口：{@code -Dzbot.tool.result.dir=/some/data/dir}。 */
    public static final String OVERFLOW_DIR_PROPERTY = "zbot.tool.result.dir";
    /** 溢出目录的 profile 覆盖入口：{@code $ZBOT_HOME/tool-results}（红线 1：跟 profile 走，不写死 ~/.zbot）。 */
    public static final String ZBOT_HOME_ENV = "ZBOT_HOME";

    private final ToolRegistry registry = new ToolRegistry();

    /** 全局单工具结果上限（工具未自己声明时的兜底，hermes DEFAULT_RESULT_SIZE_CHARS）。 */
    private volatile long maxResultChars = ToolRegistry.DEFAULT_RESULT_SIZE_CHARS;
    private volatile long previewChars = DEFAULT_PREVIEW_CHARS;
    /** 溢出落盘目录；显式注入优先，否则按 {@link #resolveOverflowDir()} 解析。 */
    private volatile File overflowDir;

    /** 按 generation 键控的 schema 快照 — 代际一变即整体作废（红线 6：schema 参与 prompt）。 */
    private volatile SchemaSnapshot snapshot;
    private final AtomicLong snapshotRebuilds = new AtomicLong();

    public Toolkit register(Tool tool) {
        return register(tool, false);
    }

    public Toolkit register(Tool tool, boolean parallelSafe) {
        return register(tool, DEFAULT_TOOLSET, DEFAULT_OWNER, parallelSafe);
    }

    /** 带 toolset/owner 的注册（MCP 桥按 {@code mcp-<server>} 分组 + {@code mcp:<server>} owner）。 */
    public Toolkit register(Tool tool, String toolset, String owner, boolean parallelSafe) {
        return register(tool, new ToolDescriptor(toolset, parallelSafe, null, owner));
    }

    /**
     * 用 kernel 的完整描述注册 — 需要可用性探测 (check_fn) 或自定义结果上限时走这里。
     * 同名工具: 未注册过 → 注册; 同 owner → 覆写; 异 owner → 抛 {@link IllegalStateException}。
     */
    public Toolkit register(Tool tool, ToolDescriptor descriptor) {
        if (tool == null || tool.getName() == null || tool.getName().isEmpty()) {
            throw new IllegalArgumentException("tool 及 tool.name 不能为空");
        }
        registry.register(tool, descriptor);
        return this;
    }

    /**
     * 用 lambda 快速注册一个工具（内置工具和测试桩都走这里）。
     */
    public Toolkit register(String name, String description, Map<String, Object> schema,
                            Function<Map<String, Object>, ToolResult> handler) {
        return register(name, description, schema, handler, false);
    }

    public Toolkit register(String name, String description, Map<String, Object> schema,
                            Function<Map<String, Object>, ToolResult> handler, boolean parallelSafe) {
        return register(of(name, description, schema, handler), parallelSafe);
    }

    public static Tool of(String name, String description, Map<String, Object> schema,
                          final Function<Map<String, Object>, ToolResult> handler) {
        return new BaseTool(name, description, schema == null ? emptyObjectSchema() : schema) {
            @Override
            protected ToolResult doExecute(Map<String, Object> args) {
                return handler.apply(args);
            }
        };
    }

    /**
     * 无参工具也要给出合法 JSON Schema：kernel 的 BaseTool 缺省给空 Map，
     * 而 OpenAI 的 {@code tools[].function.parameters} 需要 type/properties 才不被拒。
     */
    private static Map<String, Object> emptyObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    // ===== 注销（真删槽位）=====

    /**
     * 注销一个工具 —— 默认 owner。注销不存在的名字是 no-op（返回 false），不抛。
     *
     * @return 是否真的删掉了
     */
    public boolean deregister(String name) {
        return deregister(name, DEFAULT_OWNER);
    }

    /** 注销指定 owner 的工具；跨 owner 抛 {@link IllegalStateException}（防插件偷偷摘工具）。 */
    public boolean deregister(String name, String owner) {
        return name != null && registry.deregister(name, owner);
    }

    /**
     * 按 toolset 整体注销 —— MCP {@code notifications/tools/list_changed} 的
     * nuk-and-repave：服务端改了工具名时，老名字也一起清掉，不会留在表里占 schema 名额。
     *
     * @return 实际被注销的工具名（注册顺序）
     */
    public List<String> deregisterToolset(String toolset) {
        return registry.deregisterByToolset(toolset, null);
    }

    public List<String> deregisterToolset(String toolset, String owner) {
        return registry.deregisterByToolset(toolset, owner);
    }

    // ===== 查询 =====

    public Tool get(String name) {
        return name == null ? null : registry.get(name);
    }

    public boolean contains(String name) {
        return name != null && registry.has(name);
    }

    /** 该工具是否允许与同批次其他工具并发执行（声明层；分批判定在派发侧）。 */
    public boolean isParallelSafe(String name) {
        ToolDescriptor d = name == null ? null : registry.descriptor(name);
        return d != null && d.isParallelSafe();
    }

    /** 工具的 toolset 分组；未注册返回 null。 */
    public String toolsetOf(String name) {
        ToolDescriptor d = name == null ? null : registry.descriptor(name);
        return d == null ? null : d.getToolset();
    }

    /**
     * 注册表代际 — 每次注册/注销都 +1。
     * 上层任何"缓存了工具 schema / 工具清单文本"的地方都必须以它为键（红线 6）。
     */
    public long generation() {
        return registry.generation();
    }

    /**
     * 执行工具。工具不存在 / 抛异常都转成 error {@link ToolResult}，不向外抛。
     * 结果超过该工具的上限时走溢出落盘（{@link #capAndPersist}），回给模型的只有预览 + 路径。
     */
    public ToolResult execute(String name, Map<String, Object> args) {
        Tool tool = get(name);
        if (tool == null) {
            return ToolResult.failure(null, name, "未找到工具: " + name);
        }
        try {
            ToolResult raw = tool.execute(args == null ? new LinkedHashMap<String, Object>() : args);
            if (raw == null) {
                return ToolResult.failure(null, name, "工具 " + name + " 返回 null");
            }
            return capAndPersist(withName(raw, name));
        } catch (Exception e) {
            return ToolResult.failure(null, name, "工具 " + name + " 执行失败: " + e.getMessage());
        }
    }

    public int size() {
        return registry.size();
    }

    /** 全部已注册工具名（注册顺序，含当前不可用的）—— hermes get_all_tool_names 口径。 */
    public List<String> getToolNames() {
        return Collections.unmodifiableList(registry.names());
    }

    /** 当前对外可见的工具名（注册顺序，已过可用性探测）。 */
    public List<String> getExposedToolNames() {
        return snapshot().names;
    }

    /**
     * 当前对外可见的工具（注册顺序）—— 送给 provider 的 schema 原料。
     * 不可用的工具（探测失败且已过宽限窗）不在其中，但槽位仍在、{@link #execute} 仍可调用。
     */
    public List<Tool> getAllTools() {
        return snapshot().tools;
    }

    /** 全部已注册工具（不过滤可用性， introspection 用）。 */
    public List<Tool> getRegisteredTools() {
        List<Tool> out = new ArrayList<Tool>();
        for (String name : registry.names()) {
            Tool t = registry.get(name);
            if (t != null) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 供 system prompt 使用的工具清单文本（快照：代际不变 → 逐字节重放）。
     */
    public String getToolsDescription() {
        return snapshot().description;
    }

    /**
     * schema 指纹：对 {工具名, 描述, 规范化后的 schema} 取 sha256。
     * 代际没变 → 指纹必须一模一样；代际变了 → 指纹必然换。
     * 这是"代际计数使 schema 缓存失效"的可直接断言证据，E2E 也打印它。
     */
    public String schemaFingerprint() {
        return snapshot().fingerprint;
    }

    /** 快照重建次数 —— 证明缓存真的在服务工具集变化时被作废过。 */
    public long schemaSnapshotRebuilds() {
        return snapshotRebuilds.get();
    }

    /** 按当前注册表状态取快照；代际不一致就整体重建（并原子替换）。 */
    private SchemaSnapshot snapshot() {
        return snapshotFor(registry.generation());
    }

    private SchemaSnapshot snapshotFor(long generation) {
        SchemaSnapshot current = snapshot;
        if (current != null && current.generation == generation) {
            return current;
        }
        snapshotRebuilds.incrementAndGet();
        SchemaSnapshot rebuilt = new SchemaSnapshot(generation, registry);
        snapshot = rebuilt;
        return rebuilt;
    }

    /** schema 快照：工具列表 + 清单文本 + 指纹，三者对同一个代际成立。 */
    private static final class SchemaSnapshot {
        private final long generation;
        private final List<Tool> tools;
        private final List<String> names;
        private final String description;
        private final String fingerprint;

        SchemaSnapshot(long generation, ToolRegistry registry) {
            this.generation = generation;
            List<Tool> exposed = registry.availableTools();
            this.tools = Collections.unmodifiableList(new ArrayList<Tool>(exposed));
            List<String> nameList = new ArrayList<String>();
            StringBuilder desc = new StringBuilder();
            StringBuilder canon = new StringBuilder();
            for (Tool t : exposed) {
                nameList.add(t.getName());
                desc.append("- ").append(t.getName()).append(": ")
                        .append(t.getDescription() == null ? "(no description)" : t.getDescription())
                        .append('\n');
                canon.append(t.getName()).append('\u0000')
                        .append(t.getDescription() == null ? "" : t.getDescription()).append('\u0000')
                        .append(canonicalize(t.getSchema())).append('\u0001');
            }
            this.names = Collections.unmodifiableList(nameList);
            this.description = desc.toString();
            this.fingerprint = sha256(canon.toString());
        }
    }

    /**
     * Map/List 递归按 key 排序的稳定序列化 —— prompt cache 要求 schema 前缀逐字节可重放，
     * 而 schema 是从 JSON 解析来的、key 顺序不保证（hermes 侧同样对工具 schema 做 sorted()）。
     */
    static String canonicalize(Object node) {
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, node);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCanonical(StringBuilder sb, Object node) {
        if (node == null) {
            sb.append("~");
            return;
        }
        if (node instanceof Map) {
            Map<Object, Object> map = new TreeMap<Object, Object>(new Comparator<Object>() {
                @Override
                public int compare(Object a, Object b) {
                    return String.valueOf(a).compareTo(String.valueOf(b));
                }
            });
            map.putAll((Map<Object, Object>) node);
            sb.append('{');
            for (Map.Entry<Object, Object> e : map.entrySet()) {
                sb.append(e.getKey()).append(':');
                appendCanonical(sb, e.getValue());
                sb.append(',');
            }
            sb.append('}');
            return;
        }
        if (node instanceof Iterable) {
            sb.append('[');
            for (Object item : (Iterable<Object>) node) {
                appendCanonical(sb, item);
                sb.append(',');
            }
            sb.append(']');
            return;
        }
        sb.append(node);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            // JDK 必带 SHA-256；真出问题退回长度指纹，也仍然能区分"变/没变"
            return "len-" + Integer.toHexString(input.hashCode()) + "-" + input.length();
        }
    }

    // ===== 单工具结果上限 + 溢出落盘 =====

    /** 全局结果上限（工具未自述上限时的兜底）。 */
    public Toolkit setMaxResultChars(long chars) {
        this.maxResultChars = chars;
        return this;
    }

    public long getMaxResultChars() {
        return maxResultChars;
    }

    /** 溢出后留在上下文里的预览长度。 */
    public Toolkit setPreviewChars(long chars) {
        this.previewChars = Math.max(0L, chars);
        return this;
    }

    /** 显式指定溢出目录（必须是数据目录，随 --config-dir / ZBOT_HOME 走；红线 1）。 */
    public Toolkit setOverflowDir(File dir) {
        this.overflowDir = dir;
        return this;
    }

    /** @return 当前生效的溢出目录；null = 不落盘，只就地截断。 */
    public File getOverflowDir() {
        return resolveOverflowDir();
    }

    /**
     * 解析溢出目录：显式注入 &gt; {@code -Dzbot.tool.result.dir} &gt; {@code $ZBOT_HOME/tool-results}。
     * 三者都没有 → null（宁可截断，也不凭空写死一个 ~/.zbot —— 红线 1）。
     */
    private File resolveOverflowDir() {
        File injected = overflowDir;
        if (injected != null) {
            return injected;
        }
        String prop = System.getProperty(OVERFLOW_DIR_PROPERTY);
        if (prop != null && !prop.trim().isEmpty()) {
            return new File(prop.trim());
        }
        String home = System.getenv(ZBOT_HOME_ENV);
        if (home != null && !home.trim().isEmpty()) {
            return new File(new File(home.trim()), "tool-results");
        }
        return null;
    }

    /** 该工具生效的结果上限（kernel 注册表口径：声明值优先，否则全局缺省）。 */
    public long resultCapFor(String name) {
        return registry.maxResultChars(name, maxResultChars);
    }

    /**
     * 结果超上限 → 全文落盘 + 回预览；落盘目录没配置 / 写失败 → 就地截断并明说。
     * 对齐 hermes tools/tool_result_storage.py 的 per-result 层。
     */
    private ToolResult capAndPersist(ToolResult result) {
        String content = result.getContent();
        if (content == null) {
            return result;
        }
        long cap = resultCapFor(result.getName());
        if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() <= cap) {
            return result;
        }
        int keep = (int) Math.min(previewChars, cap);
        String preview = content.substring(0, keep);
        String path = spillToFile(result.getName(), result.getCallId(), content);
        String note = path == null
                ? String.format("\n\n[结果 %d 字符，超单工具上限 %d，已截断至 %d；"
                        + "未配置溢出目录（setOverflowDir / -D%s / $ZBOT_HOME），全文未落盘]",
                        content.length(), cap, keep, OVERFLOW_DIR_PROPERTY)
                : String.format("\n\n[结果 %d 字符，超单工具上限 %d，上下文只保留 %d 字符预览；"
                        + "全文已存 %s，可用 read_file 取用]",
                        content.length(), cap, keep, path);
        return new ToolResult(result.getCallId(), result.getName(), preview + note,
                result.isError(), result.getMetadata());
    }

    /** @return 落盘后的绝对路径；目录不可用或写失败返回 null（调用方降级为截断）。 */
    private String spillToFile(String toolName, String callId, String content) {
        File dir = resolveOverflowDir();
        if (dir == null) {
            return null;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            File target = new File(dir, safeResultFileName(toolName, callId));
            Writer writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
            try {
                writer.write(content);
            } finally {
                writer.close();
            }
            return target.getAbsolutePath();
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 文件名只留 {@code [A-Za-z0-9_.-]}，其余换下划线并补 sha256 前 12 位（对齐她的 _safe_result_filename）。 */
    static String safeResultFileName(String toolName, String callId) {
        String raw = (toolName == null ? "tool" : toolName) + "-" + (callId == null ? "anon" : callId);
        StringBuilder stem = new StringBuilder();
        boolean mutated = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            if (ok) {
                stem.append(c);
            } else {
                stem.append('_');
                mutated = true;
            }
        }
        while (stem.length() > 0 && (stem.charAt(0) == '.' || stem.charAt(0) == '-' || stem.charAt(0) == '_')) {
            stem.deleteCharAt(0);
            mutated = true;
        }
        String name = stem.toString();
        if (name.isEmpty()) {
            name = "tool_result";
            mutated = true;
        }
        if (name.length() > 120) {
            name = name.substring(0, 120);
            mutated = true;
        }
        if (mutated) {
            name = name + "-" + sha256(raw).substring(0, 12);
        }
        return name + ".txt";
    }

    /** kernel 的 {@code ToolResult.text()/error()} 不带 name，回灌消息前补齐工具名。 */
    private static ToolResult withName(ToolResult result, String name) {
        if (name.equals(result.getName())) {
            return result;
        }
        return new ToolResult(result.getCallId(), name, result.getContent(),
                result.isError(), result.getMetadata());
    }
}
