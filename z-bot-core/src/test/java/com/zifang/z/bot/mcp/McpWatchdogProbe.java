package com.zifang.z.bot.mcp;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link McpParentWatchdogTest} 的"父进程"替身：一个独立的真 JVM，按<b>生产路径</b>拉起子进程，
 * 把事实写进 ready-file 交回给测试进程，然后自己睡在那里等 {@code kill -9}。
 *
 * <p>为什么必须另起一个 JVM：watchdog 判的是"<b>我自己的</b> ppid 变了没有"，
 * 而"父死"这件事在测试自己的 JVM 里没法发生 —— 测试进程一 {@code kill -9} 自己，
 * 这一支测试也就没有结论了。所以父死由测试来执行，死的是这个探针。</p>
 *
 * <p>两种拉法（对应两种取证，缺一不可）：</p>
 * <ul>
 *   <li>{@code mcp}：{@link ZBotStdioMcpTransport} 走完 initialize 握手拉<b>官方 SDK 真 server</b>
 *       —— 这就是生产路径本来的样子，"带走了"要在这条上量。</li>
 *   <li>{@code sleeper}：用 {@code launchArgv()} 产出的<b>同一份 argv</b>（反射拿，方法改名就会红）
 *       拉一个完全不理 stdin 的长睡进程 —— 把"子进程其实是自己退的"这个混淆因子摘掉。
 *       真 server 在父死时 stdin 会拿到 EOF 从而自己退出，那条上"没了"不等于"watchdog 带走的"。</li>
 * </ul>
 *
 * <p>ready-file 第一行格式：{@code READY <探针JVM pid> <直接子进程 pid...>}
 * （子进程 pid 用 {@code ps -o ppid} 反查，不用命令行 pattern —— 并发波次里
 * p12e/p18b 也在跑 mvn，pattern 会串号）。</p>
 */
public final class McpWatchdogProbe {

    private McpWatchdogProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: McpWatchdogProbe <ready-file> <watchdog:true|false> "
                    + "<mcp|sleeper> [server 附加参数...]");
            System.exit(2);
            return;
        }
        File ready = new File(args[0]);
        boolean watchdog = Boolean.parseBoolean(args[1]);
        String mode = args[2];
        long me = ZBotStdioMcpTransport.jvmPid();

        List<String> kids;
        if ("sleeper".equals(mode)) {
            // 与生产同一个 argv 生成器（含 /bin/sh 监护脚本 + exec），只是 child 换成不理 stdin 的睡进程
            List<String> cmd = new ArrayList<String>(Arrays.asList(
                    RealMcpHarness.python(), "-u", "-c",
                    "import time\n"
                            + "time.sleep(600)\n"));
            List<String> argv = launchArgvOf(cmd, watchdog);
            new ProcessBuilder(argv).redirectErrorStream(true)
                    .redirectOutput(new File(ready.getParentFile(),
                            "sleeper-out-" + watchdog + ".txt")).start();
            kids = waitForChildren(me);
            System.err.println("[probe] sleeper 已起 watchdog=" + watchdog + " argv[0..2]="
                    + argv.subList(0, Math.min(3, argv.size())) + " kids=" + kids);
        } else {
            List<String> cmd = new ArrayList<String>(Arrays.asList(
                    RealMcpHarness.python(), "-u",
                    RealMcpHarness.file(RealMcpHarness.REF_SERVER).getAbsolutePath(),
                    "--transport", "stdio"));
            for (int i = 3; i < args.length; i++) {
                cmd.add(args[i]);
            }
            ZBotStdioMcpTransport transport = new ZBotStdioMcpTransport(
                    new ZBotStdioMcpTransport.Options()
                            .serverName("watchdog-probe").command(cmd).parentWatchdog(watchdog));
            // 走生产装配口径（McpClientFactory 的 stdio 分支用的就是这个构造器 + 同一个 transport）
            com.zifang.z.agent.kernel.mcp.McpClient client =
                    McpClientFactory.wrap("watchdog-probe", transport);
            client.connect();
            kids = waitForChildren(me);
            System.err.println("[probe] mcp 已握手 pid=" + me + " kids=" + kids
                    + " watchdog=" + watchdog + " serverInfo=" + transport.serverInfoName());
        }
        writeReady(ready, me, kids);
        // 等死：谁都不许在这之后主动退出，否则测的就不是"父死"
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
        }
        System.err.println("[probe] 兜底超时自退（测试没杀成我）");
    }

    /**
     * 取生产代码算出来的那份 argv。反射而不是复制粘贴：<b>故意</b>依赖私有方法名 ——
     * 谁改了 {@code launchArgv()} 就得同时改这里，取证不会悄悄退化成"我自己手搓的一份脚本"。
     */
    private static List<String> launchArgvOf(List<String> command, boolean watchdog) throws Exception {
        ZBotStdioMcpTransport t = new ZBotStdioMcpTransport(
                new ZBotStdioMcpTransport.Options()
                        .serverName("probe-sleeper").command(command).parentWatchdog(watchdog));
        Method m = ZBotStdioMcpTransport.class.getDeclaredMethod("launchArgv");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> argv = (List<String>) m.invoke(t);
        return argv;
    }

    /**
     * 有上限地等直接子进程在 {@code ps} 里现身（{@code ProcessBuilder.start()} 返回后
     * fork/exec 不一定已经可见，直接读会拿到空表）。超过预算就非零退出 —— 空表绝不能
     * 被当成"没有子进程"写进 ready-file。
     */
    private static List<String> waitForChildren(long parentPid) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        List<String> kids = new ArrayList<String>();
        while (System.currentTimeMillis() < deadline) {
            kids = directChildrenOf(parentPid);
            if (!kids.isEmpty()) {
                return kids;
            }
            Thread.sleep(100L);
        }
        throw new IOException("等不到父进程 " + parentPid + " 的直接子进程，实表=" + kids);
    }

    private static void writeReady(File ready, long me, List<String> kids) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ready), StandardCharsets.UTF_8));
        try {
            w.write("READY " + me + " " + join(kids) + "\n");
        } finally {
            w.close();
        }
    }

    private static String join(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (String s : v) {
            sb.append(sb.length() == 0 ? "" : " ").append(s);
        }
        return sb.toString();
    }

    /**
     * 直接子进程 pid 列表。跑 ps/awk 的那个 sh 自己也是子进程，故用 {@code $1!=me}
     * 把它自己摘掉，不然计数永远多 1。
     */
    static List<String> directChildrenOf(long parentPid) throws IOException {
        String script = "ps -eo pid=,ppid= | awk -v me=$$ '$2==" + parentPid
                + " && $1!=me {print $1}'";
        String out = sh(script);
        List<String> pids = new ArrayList<String>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                pids.add(t);
            }
        }
        return pids;
    }

    static boolean alive(long pid) throws IOException {
        return !sh("ps -o pid= -p " + pid).trim().isEmpty();
    }

    static String ppidOf(long pid) throws IOException {
        return sh("ps -o ppid= -p " + pid).trim();
    }

    private static String sh(String script) throws IOException {
        Process p = new ProcessBuilder("/bin/sh", "-c", script)
                .redirectErrorStream(true).start();
        String out = readAll(p);
        try {
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String readAll(Process p) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = p.getInputStream().read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
