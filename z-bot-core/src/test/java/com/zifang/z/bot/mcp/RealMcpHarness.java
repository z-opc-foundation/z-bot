package com.zifang.z.bot.mcp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * P21 真进程验收的公共夹具：定位 {@code _doc/acceptance/p21} 下的 python 参照 server、
 * 起进程、等就绪、数进程。
 *
 * <p>这里的"就绪"一律用<b>文件副作用回读</b>而不是"日志里出现了某行"当扳机 ——
 * P17 实测过 日志→落盘 有 0.1–8.8ms 窗口且 10 次里 2 次读到陈旧。</p>
 */
final class RealMcpHarness {

    static final String REF_SERVER = "p21_ref_mcp_server.py";
    static final String RELAY = "p21_pipe_relay.py";

    private RealMcpHarness() {
    }

    /** surefire 的 cwd 是模块目录，仓库根的 acceptance 目录要在上一级找。 */
    static File acceptanceDir() {
        String override = System.getProperty("zbot.acceptance.dir");
        if (override != null && !override.isEmpty()) {
            File f = new File(override);
            if (f.isDirectory()) {
                return f;
            }
        }
        File cwd = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (File dir = cwd; dir != null; dir = dir.getParentFile()) {
            File direct = new File(dir, "_doc/acceptance/p21");
            if (new File(direct, REF_SERVER).isFile()) {
                return direct;
            }
            File under = new File(dir, "z-bot-core/_doc/acceptance/p21");
            if (new File(under, REF_SERVER).isFile()) {
                return under;
            }
        }
        File guess = new File(cwd, "../_doc/acceptance/p21").getAbsoluteFile();
        if (!new File(guess, REF_SERVER).isFile()) {
            throw new IllegalStateException("找不到 _doc/acceptance/p21（从 " + cwd + " 往上找了一遍）"
                    + "；可用 -Dzbot.acceptance.dir= 指定");
        }
        return guess;
    }

    static File file(String name) {
        File f = new File(acceptanceDir(), name);
        if (!f.isFile()) {
            throw new IllegalStateException("缺少验收脚本 " + f);
        }
        return f;
    }

    static String python() {
        String v = System.getenv("P21_PYTHON");
        if (v != null && !v.isEmpty() && new File(v).canExecute()) {
            return v;
        }
        for (String cand : new String[] {"python3", "/usr/bin/python3", "/opt/homebrew/bin/python3"}) {
            if (which(cand)) {
                return cand;
            }
        }
        throw new IllegalStateException("机器上没有 python3 —— 参照 server 跑不起来");
    }

    private static boolean which(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-c", "print(1)")
                    .redirectErrorStream(true).start();
            p.getInputStream().read();
            return p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 官方 SDK 必须在<b>本机已装</b>；不在就直接失败，不许 skip（skip 会把这一杠糊成满分）。 */
    static void requireOfficialSdk() {
        String v = mcpSdkVersion();
        if (!v.matches("\\d+\\.\\d+.*")) {
            throw new AssertionError("官方 MCP Python SDK 不可用（server 端 import 也过不去）: " + v);
        }
    }

    /** 与参照 server 里 {@code pkg_version("mcp")} 同源：官方 server 自报的 version 就是这个值。 */
    static String mcpSdkVersion() {
        try {
            Process p = new ProcessBuilder(python(), "-c",
                    "import sys\n"
                  + "from importlib.metadata import version\n"
                  + "import mcp.server.fastmcp\n"
                  + "print(version('mcp'))")
                    .redirectErrorStream(true).start();
            String out = readAll(p);
            int nl = out.indexOf('\n');
            String first = (nl < 0 ? out : out.substring(0, nl)).trim();
            return first.isEmpty() ? "unavailable:" + out.trim() : first;
        } catch (Exception e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    static String readAll(Process p) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = p.getInputStream().read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 起进程

    /** 真 stdio server（不带中继）。 */
    static Process startRefStdioServer(File logFile, List<String> extraEnv) throws IOException {
        List<String> argv = new ArrayList<String>(Arrays.asList(
                python(), "-u", file(REF_SERVER).getAbsolutePath(), "--transport", "stdio"));
        return launch(argv, logFile, extraEnv);
    }

    /** 真 stdio server + 中继：中继把两个方向的原始字节逐行落到 relayLog。 */
    static Process startRelayedStdioServer(File relayLog, File serverLog,
                                           boolean unusedKeepRaw, List<String> extraEnv) throws IOException {
        List<String> argv = new ArrayList<String>(Arrays.asList(
                python(), "-u", file(RELAY).getAbsolutePath(), "--",
                python(), "-u", file(REF_SERVER).getAbsolutePath(), "--transport", "stdio"));
        List<String> env = new ArrayList<String>(extraEnv == null
                ? new ArrayList<String>() : extraEnv);
        env.add("P21_RELAY_LOG=" + relayLog.getAbsolutePath());
        env.add("P21_SERVER_LOG=" + serverLog.getAbsolutePath());
        return launch(argv, serverLog, env);
    }

    /** 真 StreamableHTTP server：{@code --port 0} 由 uvicorn 自己 bind(0)，端口从 port-file 回读。 */
    static HttpServer startRefHttpServer(File serverLog, File portFile, List<String> extraEnv)
            throws IOException {
        if (portFile.exists() && !portFile.delete()) {
            throw new IOException("删不掉旧 port-file: " + portFile);
        }
        List<String> argv = new ArrayList<String>(Arrays.asList(
                python(), "-u", file(REF_SERVER).getAbsolutePath(),
                "--transport", "http", "--port", "0",
                "--port-file", portFile.getAbsolutePath()));
        Process p = launch(argv, serverLog, extraEnv);
        String url = waitForUrl(portFile, 25_000L, p);
        return new HttpServer(p, url);
    }

    static String waitForUrl(File portFile, long timeoutMillis, Process owner) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (portFile.isFile() && portFile.length() > 0) {
                String s = tail(portFile).trim();
                if (s.startsWith("http://127.0.0.1:")) {
                    return s;
                }
            }
            if (!owner.isAlive()) {
                throw new IOException("参照 http server 提前退出，exit=" + owner.exitValue());
            }
            sleep(50L);
        }
        throw new IOException("等不到 port-file 里的 url: " + portFile);
    }

    private static Process launch(List<String> argv, File logFile, List<String> extraEnv)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        if (logFile != null) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        }
        if (extraEnv != null) {
            for (String pair : extraEnv) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    pb.environment().put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return pb.start();
    }

    // ------------------------------------------------------------------ 数进程 / 读文件

    /** {@code pgrep -f <pattern>} 的条数；0 表示"没有了"。pgrep 无匹配时 rc=1，不当错误。 */
    static int pgrepCount(String pattern) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c",
                    "pgrep -f " + McpWire.shellQuote(pattern) + " 2>/dev/null | wc -l")
                    .redirectErrorStream(true).start();
            String out = readAll(p).trim();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return out.isEmpty() ? 0 : Integer.parseInt(out.replaceAll("\\D+", ""));
        } catch (Exception e) {
            throw new RuntimeException("pgrep 数进程失败: " + e.getMessage(), e);
        }
    }

    /** 中继日志里是否出现过含这些片段的行（片段之间是"与"）。 */
    static boolean relayLogContains(File relayLog, String... fragments) throws IOException {
        if (!relayLog.isFile()) {
            return false;
        }
        String all = slurp(relayLog);
        for (String f : fragments) {
            if (!all.contains(f)) {
                return false;
            }
        }
        return true;
    }

    /** 中继日志里以该前缀开头的行数（"这一向到底有没有字节"的量化读数）。 */
    static int countLines(File relayLog, String prefix) throws IOException {
        if (!relayLog.isFile()) {
            return 0;
        }
        int n = 0;
        for (String line : slurp(relayLog).split("\n")) {
            if (line.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    static String slurp(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String tail(File f) throws IOException {
        if (!f.isFile()) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0L, len - 8192L);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void destroyQuietly(Process p) {
        if (p == null) {
            return;
        }
        try {
            p.destroy();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
    }

    /** 起好的 http server 句柄。 */
    static final class HttpServer {
        final Process process;
        final String url;

        HttpServer(Process process, String url) {
            this.process = process;
            this.url = url;
        }
    }
}
