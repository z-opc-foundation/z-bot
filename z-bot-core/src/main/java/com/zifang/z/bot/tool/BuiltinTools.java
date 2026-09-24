package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.agent.kernel.tool.ToolSchemaBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * z-bot 内置工具集（对齐 z-opc 老 z-agent-bot 的 TerminalBot + ZAgent 注册面）。
 *
 * <p>沙箱根目录 = {@code ~/.zbot/workspace}，写/读/exec 都被约束在里面；
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
        final AtomicInteger counter = new AtomicInteger(0);

        toolkit.register(echo());
        toolkit.register(time());
        toolkit.register(counter(counter));
        toolkit.register(health());
        toolkit.register(readFile(sandbox));
        toolkit.register(writeFile(sandbox));
        toolkit.register(exec(sandbox, execConfirmMode));
        toolkit.register(search());
        toolkit.register(sysinfo());
        toolkit.register(mvnBuild(sandbox));
        toolkit.register(curlTest());
        return toolkit;
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
        return Toolkit.of("exec",
                "执行系统命令并返回输出（仅在沙箱目录 " + sandbox.root() + " 下执行，高危命令需审批）",
                new ToolSchemaBuilder().string("command", "要执行的系统命令").build(),
                args -> {
                    String command = arg(args, "command", "");
                    if (command.trim().isEmpty()) {
                        return ToolResult.error("参数错误：command 不能为空");
                    }
                    if (ExecGuard.isForbidden(command)) {
                        return ToolResult.error("安全错误：禁止的危险命令");
                    }
                    String reason = ExecGuard.confirmationReason(confirmMode, command,
                            Confirmations.alreadyConfirmed(args));
                    if (reason != null) {
                        return Confirmations.needsConfirmation(reason);
                    }
                    try {
                        ProcResult r = bash(sandbox, command, EXEC_MAX_LINES, EXEC_MAX_CHARS);
                        return text("exit=" + r.exitCode + "\n" + r.output);
                    } catch (Exception e) {
                        return ToolResult.error("执行失败：" + e.getMessage());
                    }
                });
    }

    public static Tool mvnBuild(final Sandbox sandbox) {
        return Toolkit.of("mvn_build", "在项目目录执行 Maven 编译",
                new ToolSchemaBuilder().string("goal", "Maven 目标 (默认: clean install -DskipTests)", false).build(),
                args -> {
                    String goal = arg(args, "goal", "");
                    if (goal.trim().isEmpty()) {
                        goal = "clean install -DskipTests";
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
