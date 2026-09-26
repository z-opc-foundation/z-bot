package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.tool.ToolSchemaBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * z-bot 内置工具集（对齐 z-opc 老 z-agent-bot 的 TerminalBot + ZAgent 注册面）。
 *
 * <p>沙箱根目录 = {@code <profile>/workspace}（{@code --sandbox} 可覆盖），写/读/exec 都被约束在里面；
 * search 是只读遍历，允许指定任意目录（与老 bot 一致）。</p>
 */
public final class BuiltinTools {

    private static final int READ_MAX_CHARS = 2000;
    private static final int READ_MAX_LINES = 200;
    private static final int EXEC_MAX_LINES = 100;
    private static final int EXEC_MAX_CHARS = 3000;

    private BuiltinTools() {
    }

    /**
     * 注册全部内置工具。
     *
     * @param execConfirmMode {@link ExecGuard} 的 off / dangerous / all
     */
    public static Toolkit registerAll(Toolkit toolkit, Sandbox sandbox, String execConfirmMode) {
        return registerAll(toolkit, sandbox, execConfirmMode, Collections.<String>emptyList());
    }

    /**
     * 注册全部内置工具（带 exec 免确认白名单）。
     *
     * <p>并行安全标记：只读工具（echo/time/health/sysinfo/read_file/search）允许同批并发，
     * 写/exec/网络类一律串行。</p>
     */
    public static Toolkit registerAll(Toolkit toolkit, Sandbox sandbox, String execConfirmMode,
                                      List<String> execWhitelist) {
        return registerAll(toolkit, sandbox, execConfirmMode, execWhitelist, null);
    }

    /**
     * 同上，再接上审批服务：exec 的待批请求会进 {@link ApprovalService} 的每会话 FIFO，
     * session/always 档的精确免确认名单也由它提供。传 null 时退化为"只回抛一条、不入队"。
     *
     * <p>toolset 归属由 {@link Toolsets} 清单决定（z-bot 侧声明层，不进内核）：诊断类
     * {@value Toolsets#CORE} / 文件类 {@value Toolsets#FILE} / 执行类 {@value Toolsets#EXEC}
     * / 出网类 {@value Toolsets#NET}。并行安全的<b>声明</b>也在这里落进注册表，
     * 分批判定在派发侧（{@code agent/BotAgent#executeBatch}）。</p>
     */
    public static Toolkit registerAll(Toolkit toolkit, Sandbox sandbox, String execConfirmMode,
                                      List<String> execWhitelist, ApprovalService approvals) {
        final AtomicInteger counter = new AtomicInteger(0);

        declare(toolkit, echo(), true);
        declare(toolkit, time(), true);
        declare(toolkit, counter(counter), false);
        declare(toolkit, health(), true);
        declare(toolkit, readFile(sandbox), true);
        declare(toolkit, writeFile(sandbox), false);
        declare(toolkit, exec(sandbox, execConfirmMode, execWhitelist, approvals), false);
        declare(toolkit, search(), true);
        declare(toolkit, sysinfo(), true);
        declare(toolkit, mvnBuild(sandbox), false);
        declare(toolkit, curlTest(), false);
        return toolkit;
    }

    /** 按 {@link Toolsets} 清单落 toolset 后注册（未登记的工具退回兜底槽，不会塞进别人的能力面）。 */
    private static void declare(Toolkit toolkit, Tool tool, boolean parallelSafe) {
        toolkit.register(tool, Toolsets.toolsetForTool(tool.getName()), Toolkit.DEFAULT_OWNER, parallelSafe);
    }


    // ===== 基础 =====

    public static Tool echo() {
        return Toolkit.of("echo", "回显输入的文本，原样返回",
                new ToolSchemaBuilder().string("message", "要回显的文本", false).build(),
                args -> text(arg(args, "message", arg(args, "text", ""))));
    }

    public static Tool time() {
        return Toolkit.of("time", "获取当前 Unix 时间戳（毫秒）", null,
                args -> text(String.valueOf(System.currentTimeMillis())));
    }

    public static Tool counter(final AtomicInteger holder) {
        return Toolkit.of("counter", "计数器，支持 inc/get/reset",
                new ToolSchemaBuilder().string("action", "inc / get / reset", false).build(),
                args -> {
                    String action = arg(args, "action", "get");
                    switch (action) {
                        case "inc":
                        case "increment":
                            return text(String.valueOf(holder.incrementAndGet()));
                        case "reset":
                            holder.set(0);
                            return text("0");
                        default:
                            return text(String.valueOf(holder.get()));
                    }
                });
    }

    public static Tool health() {
        return Toolkit.of("health", "系统健康检查，返回内存/线程信息", null, args -> {
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            long free = rt.freeMemory();
            return text(String.format("status=UP memory={total=%d,used=%d,free=%d} threads=%d",
                    total, total - free, free, Thread.activeCount()));
        });
    }

    public static Tool sysinfo() {
        return Toolkit.of("sysinfo", "获取系统信息（OS/内存/CPU/hostname）", null, args -> {
            Runtime rt = Runtime.getRuntime();
            long total = rt.totalMemory();
            long free = rt.freeMemory();
            String hostname;
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                hostname = "unknown";
            }
            return text(String.format("hostname=%s os=%s %s java=%s processors=%d memory={total=%d,used=%d,free=%d}",
                    hostname, System.getProperty("os.name"), System.getProperty("os.version"),
                    System.getProperty("java.version"), rt.availableProcessors(),
                    total, total - free, free));
        });
    }

    // ===== 文件 =====

    public static Tool readFile(final Sandbox sandbox) {
        return Toolkit.of("read_file",
                "读取指定文件的内容（最多 200 行 / 2000 字符，仅限沙箱目录）",
                new ToolSchemaBuilder().string("path", "文件路径（相对路径，基于沙箱目录）").build(),
                args -> {
                    String path = arg(args, "path", "");
                    if (path.trim().isEmpty()) {
                        return ToolResult.error("参数错误：path 不能为空");
                    }
                    try {
                        File target = sandbox.resolve(path);
                        if (!target.exists()) {
                            return ToolResult.error("文件不存在：「" + path + "」");
                        }
                        if (!target.canRead()) {
                            return ToolResult.error("文件不可读：「" + path + "」");
                        }
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new FileReader(target))) {
                            String line;
                            int lines = 0;
                            while ((line = br.readLine()) != null && lines < READ_MAX_LINES) {
                                sb.append(line).append('\n');
                                lines++;
                            }
                        }
                        String content = sb.toString();
                        return text(content.length() > READ_MAX_CHARS
                                ? content.substring(0, READ_MAX_CHARS) + "\n...(已截断)" : content);
                    } catch (Exception e) {
                        return ToolResult.error("读取失败：" + e.getMessage());
                    }
                });
    }

    public static Tool writeFile(final Sandbox sandbox) {
        return Toolkit.of("write_file",
                "写入内容到文件（仅限沙箱目录 " + sandbox.root() + "）",
                new ToolSchemaBuilder()
                        .string("path", "文件路径（相对路径，基于沙箱目录）")
                        .string("content", "文件内容")
                        .build(),
                args -> {
                    String path = arg(args, "path", "");
                    String content = arg(args, "content", "");
                    if (path.trim().isEmpty()) {
                        return ToolResult.error("参数错误：path 不能为空");
                    }
                    try {
                        File target = sandbox.resolve(path);
                        target.getParentFile().mkdirs();
                        // 某些模型会把换行回传成字面量 \n
                        String actual = content.replace("\\n", "\n");
                        try (java.io.FileWriter fw = new java.io.FileWriter(target)) {
                            fw.write(actual);
                        }
                        return text("写入成功：「" + target.getCanonicalPath() + "」（" + actual.length() + " bytes）");
                    } catch (Exception e) {
                        return ToolResult.error("写入失败：" + e.getMessage());
                    }
                });
    }

    public static Tool search() {
        return Toolkit.of("search", "在指定目录下搜索文件（按名称，支持通配符 *）",
                new ToolSchemaBuilder()
                        .string("name", "要搜索的文件名（支持通配符 *）")
                        .string("dir", "搜索目录（默认当前目录）", false)
                        .build(),
                args -> {
                    String namePattern = arg(args, "name", "");
                    String dir = arg(args, "dir", ".");
                    if (namePattern.trim().isEmpty()) {
                        return ToolResult.error("参数错误：name 不能为空");
                    }
                    File root = new File(dir);
                    if (!root.exists() || !root.isDirectory()) {
                        return ToolResult.error("目录不存在：" + dir);
                    }
                    StringBuilder sb = new StringBuilder();
                    int[] count = {0};
                    searchFiles(root, namePattern.replace("*", ".*"), sb, count, 10);
                    return count[0] == 0 ? ToolResult.error("未找到匹配的文件：" + namePattern) : text(sb.toString());
                });
    }

    private static void searchFiles(File dir, String pattern, StringBuilder sb, int[] count, int limit) {
        if (count[0] >= limit) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (count[0] >= limit) {
                break;
            }
            if (f.getName().matches(pattern)) {
                sb.append(f.getAbsolutePath()).append('\n');
                count[0]++;
            }
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                searchFiles(f, pattern, sb, count, limit);
            }
        }
    }

    // ===== 进程 =====

    public static Tool exec(final Sandbox sandbox, final String confirmMode) {
        return exec(sandbox, confirmMode, Collections.<String>emptyList(), null);
    }

    public static Tool exec(final Sandbox sandbox, final String confirmMode, final List<String> whitelist) {
        return exec(sandbox, confirmMode, whitelist, null);
    }

    /**
     * exec 工具本体 —— 审批缝只有这一处判定（{@link ExecGuard#decide}）。
     *
     * <ul>
     *   <li>硬线命中：直接 error，任何模式（含 {@code off}/{@code --yolo}）、已盖章、白名单都拦不住；</li>
     *   <li>需要人审：请求进 {@link ApprovalService} 的每会话 FIFO（无 approval 服务时只回抛不入队）；</li>
     *   <li>人给的 session/always 精确名单由 {@link ApprovalService#isApproved} 提供，
     *       它在 decide 之后才起作用，所以盖不掉硬线。</li>
     * </ul>
     */
    public static Tool exec(final Sandbox sandbox, final String confirmMode, final List<String> whitelist,
                            final ApprovalService approvals) {
        return Toolkit.of("exec",
                "执行系统命令并返回输出（仅在沙箱目录 " + sandbox.root() + " 下执行，高危命令需审批）",
                new ToolSchemaBuilder().string("command", "要执行的系统命令").build(),
                args -> {
                    String command = arg(args, Confirmations.COMMAND_ARG, "");
                    if (command.trim().isEmpty()) {
                        return ToolResult.error("参数错误：command 不能为空");
                    }
                    String sessionKey = approvals == null ? null : approvals.currentSessionKey();
                    boolean exactApproval = approvals != null
                            && approvals.isApproved(sessionKey, ExecGuard.approvalKey(command));
                    ExecGuard.Decision decision = ExecGuard.decide(confirmMode, command,
                            Confirmations.alreadyConfirmed(args) || exactApproval,
                            approvals != null ? mergeWhitelist(approvals, whitelist) : whitelist);
                    if (decision.denied()) {
                        return ToolResult.error("安全错误：" + decision.reason());
                    }
                    if (decision.needsApproval()) {
                        String requestId = null;
                        if (approvals != null) {
                            requestId = approvals.submit(sessionKey, "exec",
                                    commandArgsJson(command), command, decision.normalized(),
                                    decision.reason(), decision.rule()).id();
                        }
                        return Confirmations.needsConfirmation(decision.reason(), requestId);
                    }
                    try {
                        ProcResult r = bash(sandbox, command, EXEC_MAX_LINES, EXEC_MAX_CHARS);
                        return text("exit=" + r.exitCode + "\n" + r.output);
                    } catch (Exception e) {
                        return ToolResult.error("执行失败：" + e.getMessage());
                    }
                });
    }

    /** 把命令包成 exec 的参数 JSON（FIFO 里存的就是可直接回放的 argsJson）。 */
    private static String commandArgsJson(String command) {
        StringBuilder sb = new StringBuilder("{\"").append(Confirmations.COMMAND_ARG).append("\":\"");
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"}").toString();
    }

    /** 配置里的前缀白名单 + 运行时新加的一起交给闸门（去重、保序）。 */
    private static List<String> mergeWhitelist(ApprovalService approvals, List<String> configured) {
        List<String> merged = new java.util.ArrayList<String>(approvals.prefixWhitelist());
        if (configured != null) {
            for (String entry : configured) {
                if (entry != null && !entry.trim().isEmpty() && !merged.contains(entry.trim())) {
                    merged.add(entry.trim());
                }
            }
        }
        return merged;
    }

    public static Tool mvnBuild(final Sandbox sandbox) {
        return Toolkit.of("mvn_build", "在项目目录执行 Maven 编译",
                new ToolSchemaBuilder().string("goal", "Maven 目标 (默认: clean install -DskipTests)", false).build(),
                args -> {
                    String goal = arg(args, "goal", "");
                    if (goal.trim().isEmpty()) {
                        goal = "clean install -DskipTests";
                    }
                    // P11 前这个拼接路径完全不过闸门：goal 里塞 "; rm -rf /" 就是直通车。
                    // 现在至少硬线表在任何模式下都拦（危险表仍只管 exec，避免改变 mvn 的免确认语义）。
                    if (ExecGuard.isHardline("mvn " + goal)) {
                        return ToolResult.error("安全错误：" + ExecGuard.hardlineMessage("mvn " + goal));
                    }
                    try {
                        ProcResult r = bash(sandbox, "mvn " + goal + " 2>&1 | tail -50", 0, 2000);
                        return text("exit=" + r.exitCode + "\n" + r.output);
                    } catch (Exception e) {
                        return ToolResult.error("编译失败：" + e.getMessage());
                    }
                });
    }

    public static Tool curlTest() {
        return Toolkit.of("curl_test", "测试 HTTP 接口，返回响应状态码和内容",
                new ToolSchemaBuilder()
                        .string("url", "请求 URL")
                        .string("method", "HTTP 方法 (默认: GET)", false)
                        .string("body", "请求体 (JSON)", false)
                        .build(),
                args -> {
                    String url = arg(args, "url", "");
                    String method = arg(args, "method", "GET");
                    String body = arg(args, "body", "");
                    if (url.trim().isEmpty()) {
                        return ToolResult.error("参数错误：url 不能为空");
                    }
                    String cmd = !body.trim().isEmpty()
                            ? "curl -s --max-time 10 -X " + method + " '" + url + "'"
                            + " -H 'Content-Type: application/json'"
                            + " -d '" + body.replace("'", "'\\''") + "' 2>&1 | head -c 2000"
                            : "curl -s --max-time 10 -X " + method + " '" + url + "' 2>&1 | head -c 2000";
                    // method/url/body 都是裸拼接 ⇒ 这里必须补硬线（同 mvn_build，P11 前是直通车）
                    if (ExecGuard.isHardline(cmd)) {
                        return ToolResult.error("安全错误：" + ExecGuard.hardlineMessage(cmd));
                    }
                    try {
                        return text(bash(null, cmd, 0, 2000).output);
                    } catch (Exception e) {
                        return ToolResult.error("请求失败：" + e.getMessage());
                    }
                });
    }

    // ===== helper =====

    static final class ProcResult {
        final int exitCode;
        final String output;

        ProcResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * 在沙箱里跑一条 shell 命令。
     *
     * @param maxLines 0 表示不限行数；超限部分直接丢弃
     * @param maxChars 0 表示不限长度
     */
    private static ProcResult bash(Sandbox cwd, String script, int maxLines, int maxChars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", script);
        if (cwd != null) {
            pb.directory(cwd.root());
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null) {
                if (maxLines > 0 && lines >= maxLines) {
                    break;
                }
                out.append(line).append('\n');
                lines++;
            }
        }
        int exit = p.waitFor();
        String result = out.toString();
        if (maxChars > 0 && result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n...(已截断)";
        }
        return new ProcResult(exit, result);
    }

    private static ToolResult text(String content) {
        return ToolResult.text(content == null ? "" : content);
    }

    private static String arg(Map<String, Object> args, String key, String fallback) {
        if (args == null) {
            return fallback;
        }
        Object v = args.get(key);
        return v == null ? fallback : v.toString();
    }
}
