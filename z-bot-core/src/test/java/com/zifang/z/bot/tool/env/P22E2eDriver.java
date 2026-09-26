package com.zifang.z.bot.tool.env;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

/**
 * P22 杠③ 的现场探针（跑在<b>真 jar</b> 上，由 {@code p22_e2e.py} 拉起）。
 *
 * <p>只打 {@code KEY=VALUE} 到 stdout，判词全部留给 Python 那一层 ——
 * 免得"自己打印自己通过"。配置从 {@code <scratch>/config.properties} 读
 * （{@link ExecEnvConfig} 就是按 {@code <profile>/config.properties} 这个名字读的），
 * 临时根由 {@code -Dzbot.home=<scratch>} 决定，绝不碰真 {@code ~/.zbot}。</p>
 *
 * <p>退出码：0 = 探针自己跑完（不代表断言通过，断言在 Python 层）；
 * 2 = 探针里出现了它该报的显式错误；3 = 意外异常。</p>
 */
public final class P22E2eDriver {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private P22E2eDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：P22E2eDriver <mode> <scratchDir>");
            System.exit(64);
        }
        String mode = args[0];
        File scratch = new File(args[1]);
        if (!scratch.isDirectory() && !scratch.mkdirs()) {
            System.err.println("建不了现场目录：" + scratch);
            System.exit(65);
        }
        System.out.println("HEAD-CONFIG-DIR=" + System.getProperty("zbot.home", "(unset)"));
        if ("local".equals(mode)) {
            local(scratch);
        } else if ("docker".equals(mode)) {
            docker(scratch);
        } else if ("ssh".equals(mode)) {
            ssh(scratch);
        } else if ("wiring".equals(mode)) {
            wiring(scratch);
        } else if ("legacy-hang-model".equals(mode)) {
            legacyHangModel(scratch);
        } else {
            System.err.println("未知 mode：" + mode);
            System.exit(66);
        }
    }

    // ===== local：真子进程、超时端整棵树、有界输出 =====

    private static void local(File scratch) throws Exception {
        File sandboxDir = new File(scratch, "sandbox");
        LocalExecEnvironment env = new LocalExecEnvironment(new ExecEnvConfig(scratch),
                new Sandbox(sandboxDir.getPath()));
        System.out.println("BACKEND=" + env.name());

        // ① 树杀取证：起一个有后代的命令，超时之后按 ps 取证
        String marker = "p22e2e" + System.nanoTime();
        long t0 = System.currentTimeMillis();
        ExecResult r = env.exec(ExecRequest.builder("/bin/bash", "-c",
                "echo " + marker + " >/dev/null; sleep 25 & sleep 25 & wait")
                .timeoutMillis(800L)
                .maxOutputBytes(4096)
                .build());
        System.out.println("TREE_TIMEOUT=" + r.timedOut());
        System.out.println("TREE_KILLED_DESCENDANTS=" + r.killedDescendants());
        System.out.println("TREE_EXIT=" + r.exitCode());
        System.out.println("TREE_COST_MS=" + (System.currentTimeMillis() - t0));
        ExecResult probe = env.exec(ExecRequest.builder("/bin/bash", "-c",
                "ps -axo pid,ppid,command | grep -c '[s]leep 25' || true").maxOutputBytes(4096).build());
        System.out.println("TREE_SURVIVORS_AFTER=" + probe.stdout().trim().replace("\n", ""));
        // 另一面取证：pgrep -P（父进程死了，孩子会被 launchd 认养，这里看的是还没死透的）
        ExecResult pg = env.exec(ExecRequest.builder("/bin/bash", "-c",
                "pgrep -P 1 x 2>/dev/null | wc -l; pgrep 'sleep 25' | wc -l").maxOutputBytes(4096).build());
        System.out.println("TREE_PGREP=" + pg.stdout().trim().replace("\n", "/"));

        // ② 有界输出：5MB 的 stdout 不许进内存
        byte[] big = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'z');
        long t1 = System.currentTimeMillis();
        ExecResult bounded = env.exec(ExecRequest.builder("/bin/cat")
                .stdin(big).maxOutputBytes(4096).build());
        System.out.println("BOUND_EXIT=" + bounded.exitCode());
        System.out.println("BOUND_TOTAL=" + bounded.stdoutTotalBytes());
        System.out.println("BOUND_TRUNCATED=" + bounded.stdoutTruncated());
        System.out.println("BOUND_RENDERED=" + bounded.stdout().length());
        System.out.println("BOUND_COST_MS=" + (System.currentTimeMillis() - t1));

        // ③ 到上限之后继续 drain（旧实现在这里会把 waitFor() 永久堵死）
        long t2 = System.currentTimeMillis();
        ExecResult many = env.exec(ExecRequest.builder("/bin/bash", "-c",
                "i=0; while [ $i -lt 60000 ]; do echo line-$i; i=$((i+1)); done").build());
        System.out.println("DRAIN_EXIT=" + many.exitCode());
        System.out.println("DRAIN_TOTAL=" + many.stdoutTotalBytes());
        System.out.println("DRAIN_COST_MS=" + (System.currentTimeMillis() - t2));

        // ④ stdin 是单独线程灌的（hermes base.py:202/:219）
        ExecResult wc = env.exec(ExecRequest.builder("/bin/sh", "-c", "wc -c")
                .stdin(big).maxOutputBytes(64).build());
        System.out.println("STDIN_COUNT=" + wc.stdout().trim());

        // ⑤ 越界必须拒
        try {
            env.writeFile("../escaped.txt", "no".getBytes(UTF_8));
            System.out.println("ESCAPE=ALLOWED");
        } catch (IOException e) {
            System.out.println("ESCAPE=REFUSED:" + e.getMessage());
        }
        env.close();
        System.out.println("LOCAL_RC=0");
    }

    // ===== docker：真发 HTTP 到假 dockerd，逐字段由 Python 侧断言 =====

    private static void docker(File scratch) throws Exception {
        DockerExecEnvironment env = new DockerExecEnvironment(new ExecEnvConfig(scratch));
        System.out.println("BACKEND=" + env.name());
        System.out.println("DESCRIBE=" + env.describe());
        ExecResult r = env.exec(ExecRequest.builder("/bin/echo", "hello-from-p22-e2e")
                .timeoutMillis(5000L).build());
        System.out.println("DOCKER_EXIT=" + r.exitCode());
        System.out.println("DOCKER_STDOUT=" + r.stdout().trim());
        System.out.println("DOCKER_STDERR=" + r.stderr().trim());
        System.out.println("DOCKER_TRUNCATED=" + r.stdoutTruncated());
        env.writeFile("out/payload.txt", "e2e 写进容器的内容\n".getBytes(UTF_8));
        System.out.println("DOCKER_WRITE_OK=1");
        byte[] back = env.readFile("out/payload.txt");
        System.out.println("DOCKER_READ_BACK=" + new String(back, UTF_8).trim());
        // 超时那一路：容器不许留下 running
        ExecResult slow = env.exec(ExecRequest.builder("sleep", "999").timeoutMillis(600L).build());
        System.out.println("DOCKER_TIMEOUT=" + slow.timedOut());
        // 显式不支持 stdin（不许偷偷换后端）
        try {
            env.exec(ExecRequest.builder("cat").stdin("x".getBytes(UTF_8)).build());
            System.out.println("DOCKER_STDIN=ALLOWED");
        } catch (ExecEnvException e) {
            System.out.println("DOCKER_STDIN=" + e.codeName());
        }
        // 台账里塞一条"上个进程死了留下的"，然后回收
        int reaped = env.reapOrphans();
        System.out.println("DOCKER_REAPED=" + reaped);
        env.close();
        System.out.println("DOCKER_RC=0");
    }

    // ===== ssh：假 ssh 可执行文件（只在本机），看 argv 形态与失败码 =====

    private static void ssh(File scratch) throws Exception {
        ExecEnvConfig cfg = new ExecEnvConfig(scratch);
        System.out.println("SSH_BACKEND_FROM_CONFIG=" + cfg.backend());
        SshExecEnvironment env = new SshExecEnvironment(cfg, new File(scratch, "sandbox"));
        System.out.println("BACKEND=" + env.name());
        List<String> argv = env.buildArgv(ExecRequest.builder("/bin/echo", "hi; rm -rf /tmp/nope").build());
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            sb.append('[').append(a).append(']');
        }
        System.out.println("SSH_ARGV=" + sb);
        System.out.println("SSH_REMOTE=" + env.remoteInvocation(
                ExecRequest.builder("/bin/echo", "hi; rm -rf /tmp/nope").build()));
        // 真跑一次（跑的是 Python 准备的假 ssh：它把 argv 落盘再按脚本退码）
        ExecResult r = env.exec(ExecRequest.builder("/bin/echo", "hi; rm -rf /tmp/nope")
                .maxOutputBytes(4096).timeoutMillis(8000L).build());
        System.out.println("SSH_EXIT=" + r.exitCode());
        System.out.println("SSH_STDOUT=" + r.stdout().trim().replace("\n", "\\n"));
        env.close();

        // 无凭据：必须显式报，不许静默退 local
        ExecEnvConfig broken = new ExecEnvConfig(new File(scratch, "no-cred"));
        try {
            new SshExecEnvironment(broken, new File(scratch, "sandbox"));
            System.out.println("SSH_NO_CRED=ALLOWED");
        } catch (ExecEnvException e) {
            System.out.println("SSH_NO_CRED=" + e.codeName());
        }
        System.out.println("SSH_RC=0");
    }

    // ===== 接线层：BuiltinTools 的 exec 到底走没走 SPI =====

    private static void wiring(File scratch) throws Exception {
        final int[] calls = {0};
        ExecEnvironment spy = new ExecEnvironment() {
            @Override
            public String name() {
                return "spy";
            }

            @Override
            public ExecResult exec(ExecRequest request) throws IOException {
                calls[0]++;
                return ExecResult.builder("spy").exitCode(0).stdout("从 SPI 回来的\n")
                        .stdoutTotalBytes(12).build();
            }

            @Override
            public void writeFile(String path, byte[] content) throws IOException {
            }

            @Override
            public byte[] readFile(String path) throws IOException {
                return new byte[0];
            }

            @Override
            public String sandboxRoot() {
                return new File(scratch, "sandbox").getPath();
            }

            @Override
            public boolean supportsStdin() {
                return false;
            }

            @Override
            public String describe() {
                return "spy{}";
            }

            @Override
            public void close() throws IOException {
            }
        };
        ExecEnvironments.install(spy);
        Tool exec = BuiltinTools.exec(new Sandbox(sandboxDir(scratch)), "off");
        ToolResult r = exec.execute(Collections.<String, Object>singletonMap("command", "echo wired"));
        System.out.println("WIRING_SPY_CALLS=" + calls[0]);
        System.out.println("WIRING_TOOL_OUTPUT=" + r.getContent().replace("\n", "\\n"));
        System.out.println("WIRING_IS_ERROR=" + r.isError());
        ExecEnvironments.install(null);
        // 摘掉接线的话 WIRING_SPY_CALLS=0 —— 这一支在 p22_mutation.py 里叫 WIRING_DETACHED
        System.out.println("WIRING_RC=0");
    }

    private static String sandboxDir(File scratch) {
        File d = new File(scratch, "sandbox");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d.getPath();
    }

    /**
     * 旧实现的死锁模型（原样复现 {@code BuiltinTools.java:471-478} 的读法）：
     * 读满 100 行就 break、不再读管道，然后 {@code waitFor()}。
     * 子进程继续往 64KB 的管道里写 ⇒ 两边互相等死。
     *
     * <p>这支探针带硬上限（{@code POSE_WAIT_MS}），到点自己认输并打印 HANG=1，
     * 绝不把 E2E 拖成永久挂死。</p>
     */
    private static void legacyHangModel(File scratch) throws Exception {
        LocalExecEnvironment env = new LocalExecEnvironment(new ExecEnvConfig(scratch),
                new Sandbox(new File(scratch, "sandbox").getPath()));
        // 新实现：同样的命令（60000 行 >> 64KB 管道），必须回来
        long t0 = System.currentTimeMillis();
        ExecResult now = env.exec(ExecRequest.builder("/bin/bash", "-c",
                "i=0; while [ $i -lt 60000 ]; do echo line-$i; i=$((i+1)); done").build());
        System.out.println("NEW_TOTAL_LINES=" + countLines(now.stdout()));
        System.out.println("NEW_TOTAL_BYTES=" + now.stdoutTotalBytes());
        System.out.println("NEW_COST_MS=" + (System.currentTimeMillis() - t0));
        env.close();

        // 旧读法：读 100 行就撒手，再 waitFor()，外面只给 6s
        Process p = new ProcessBuilder("/bin/bash", "-c",
                "i=0; while [ $i -lt 60000 ]; do echo line-$i; i=$((i+1)); done").start();
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), UTF_8));
        int lines = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (lines >= 100) {
                break;
            }
            lines++;
        }
        boolean finished = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("LEGACY_READ_LINES=" + lines);
        System.out.println("LEGACY_HANG=" + (finished ? "0" : "1"));
        if (!finished) {
            ProcessTree.killTree(p);
        }
        System.out.println("LEGACY_RC_MODEL_DONE=1");
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
