package com.zifang.z.bot.tool.env;

import com.zifang.z.bot.tool.Sandbox;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P22 SPI 算法层：有界输出 / local 后端 / 后端选择 / ssh 组命令。
 *
 * <p>刻意把"能不能挑着跑"写清楚：每个方法名都对得上 {@code p22_mutation.py} 的一族，
 * 变异注入后必须有一支变红（杠② 的五档台账）。</p>
 */
public class ExecEnvironmentSpiTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static ExecEnvConfig cfg(Properties p) {
        return ExecEnvConfig.of(p);
    }

    private static Properties empty() {
        Properties p = new Properties();
        return p;
    }

    // ===== 有界输出 =====

    @Test
    public void boundedCollector_neverKeepsMoreThanTheCap() {
        BoundedCollector c = new BoundedCollector(1000, ExecRequest.OutputPolicy.WINDOW);
        byte[] chunk = new byte[4096];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 50; i++) {
            c.append(chunk, 0, chunk.length);
        }
        assertEquals("全量字节数必须如实记下来", 50L * 4096L, c.totalBytes());
        assertTrue("超限必须标 truncated", c.truncated());
        String rendered = c.render("");
        assertTrue("渲染结果不许超过上界：实测 " + rendered.length(), rendered.length() <= 1000);
        assertTrue("省略标记里要能看见总共多少字节",
                rendered.contains("OUTPUT TRUNCATED") && rendered.contains("204800"));
    }

    @Test
    public void boundedCollector_headPolicyKeepsThePrefix() {
        BoundedCollector c = new BoundedCollector(8, ExecRequest.OutputPolicy.HEAD);
        c.append("0123456789abcdef".getBytes(UTF_8), 0, 16);
        assertEquals("01234567", c.render(""));
        assertEquals(16L, c.totalBytes());
        assertTrue(c.truncated());
    }

    @Test
    public void boundedCollector_underCapKeepsEverything() {
        BoundedCollector c = new BoundedCollector(4096, ExecRequest.OutputPolicy.WINDOW);
        c.append("hello".getBytes(UTF_8), 0, 5);
        assertEquals("hello", c.render(""));
        assertFalse(c.truncated());
        assertEquals(0L, c.droppedBytes());
    }

    // ===== local 后端 =====

    @Test
    public void localExec_runsArgvAndReportsExitCode() throws Exception {
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()),
                new Sandbox(tempDir("p22-local-basic")));
        try {
            ExecResult r = env.exec(ExecRequest.builder("/bin/echo", "hi-from-p22").build());
            assertEquals(0, r.exitCode());
            assertTrue(r.stdout().contains("hi-from-p22"));
            assertEquals("local", r.backend());
        } finally {
            env.close();
        }
    }

    @Test
    public void localExec_boundsOutputAndSaysSo() throws Exception {
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()),
                new Sandbox(tempDir("p22-local-bound")));
        try {
            // 一次产出 200KB，上界 4KB：必须被截断，但 total 要如实
            byte[] payload = new byte[200000];
            Arrays.fill(payload, (byte) 'x');
            ExecResult r = env.exec(ExecRequest.builder("/bin/cat").maxOutputBytes(4096)
                    .stdin(payload).build());
            assertEquals(0, r.exitCode());
            assertTrue("stdout 必须被压在上界内：实测 " + r.stdout().length(),
                    r.stdout().length() <= 4096);
            assertEquals("/bin/cat 把 stdin 全数吐回来，总量应是 200000", 200000L, r.stdoutTotalBytes());
            assertTrue("超上界必须显式标 truncated", r.stdoutTruncated());
        } finally {
            env.close();
        }
    }

    @Test
    public void localExec_keepsDrainingAfterTheCapSoWaitCannotHang() throws Exception {
        // 旧实现（BuiltinTools.java:471-478）到 maxLines 就 break 不再读，
        // 子进程继续写会把 waitFor() 永久堵死；新后端必须把管道抽干到 EOF。
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()),
                new Sandbox(tempDir("p22-local-drain")));
        long t0 = System.currentTimeMillis();
        try {
            ExecResult r = env.exec(ExecRequest.builder("/bin/sh", "-c",
                    "i=0; while [ $i -lt 40000 ]; do echo line-$i; i=$((i+1)); done").build());
            long cost = System.currentTimeMillis() - t0;
            assertEquals(0, r.exitCode());
            assertTrue("40000 行必须在 20s 内收完（超上限就证明又堵死了），实测 " + cost + "ms", cost < 20000L);
            assertTrue(r.stdoutTotalBytes() > 400000L);
        } finally {
            env.close();
        }
    }

    @Test
    public void localExec_timeoutTakesTheWholeProcessTreeDown() throws Exception {
        String token = "p22tree" + System.nanoTime();
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()),
                new Sandbox(tempDir("p22-local-tree")));
        try {
            ExecRequest req = ExecRequest.builder("/bin/sh", "-c",
                    "sleep 30 & sleep 30 & wait")
                    .env(withToken(token))
                    .timeoutMillis(600L)
                    .build();
            long t0 = System.currentTimeMillis();
            ExecResult r = env.exec(req);
            long cost = System.currentTimeMillis() - t0;
            assertTrue("必须标超时", r.timedOut());
            assertTrue("超时收尾必须真的端掉后代，实测 killedDescendants=" + r.killedDescendants(),
                    r.killedDescendants() >= 1);
            assertTrue("收尾要有上限（不许无限等），实测 " + cost + "ms", cost < 8000L);
            // 再取一次证据：进程表里不该有这条命令留下的活口
            ExecResult probe = env.exec(ExecRequest.builder("/bin/sh", "-c",
                    "pgrep -f 'sleep 30' | wc -l").build());
            assertNotNull(probe);
        } finally {
            env.close();
        }
    }

    @Test
    public void localExec_rejectsSandboxEscape() throws Exception {
        // p22b：沙箱根原来叫 tempDir("p22-local-sandbox")，它的**父目录是所有用例共用的 scratch 根** ——
        // SANDBOX_CHECK 变异体留下的 escaped.txt 落在那儿就一直红（杠② 第一跑里 OUTPUT_CAP / SSH /
        // BACKEND_FALLBACK 三支都被它连坐成"额外红"）。改成每次调用一个独立父目录，串味才没了。
        String dir = tempDir("p22-local-sandbox-" + System.nanoTime() + "/ws");
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()), new Sandbox(dir));
        try {
            env.writeFile("ok.txt", "fine".getBytes(UTF_8));
            assertTrue(new File(dir, "ok.txt").isFile());
            try {
                env.writeFile("../escaped.txt", "no".getBytes(UTF_8));
                fail("越界路径必须被拒（这条红了就是 Sandbox 检查被摘掉）");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("沙箱"));
            }
            assertFalse(new File(dir).getParentFile().exists()
                    && new File(dir).getParentFile().isDirectory()
                    && new File(new File(dir).getParentFile(), "escaped.txt").exists());
        } finally {
            env.close();
        }
    }

    @Test
    public void localExec_stdinIsPipedOnItsOwnThread() throws Exception {
        LocalExecEnvironment env = new LocalExecEnvironment(cfg(empty()),
                new Sandbox(tempDir("p22-local-stdin")));
        try {
            byte[] payload = new byte[200000];
            Arrays.fill(payload, (byte) 'a');
            ExecResult r = env.exec(ExecRequest.builder("/bin/sh", "-c", "wc -c")
                    .stdin(payload).maxOutputBytes(64).build());
            assertEquals(0, r.exitCode());
            assertEquals("200000", r.stdout().trim());
        } finally {
            env.close();
        }
    }

    // ===== 后端选择 =====

    @Test
    public void factory_unknownBackendFailsLoudlyNeverFallsBackToLocal() {
        Properties p = empty();
        p.setProperty(ExecEnvConfig.KEY_BACKEND, "modal");
        try {
            ExecEnvironments.create(cfg(p), new Sandbox(tempDir("p22-factory-unknown")));
            fail("后端名不认识必须抛 BACKEND_SELECTION_FAILED（静默退 local 是本战役明令禁的）");
        } catch (IOException e) {
            assertTrue(e instanceof ExecEnvException);
            assertEquals(ExecEnvException.Code.BACKEND_SELECTION_FAILED,
                    ((ExecEnvException) e).code());
            assertTrue(e.getMessage(), e.getMessage().contains("不静默")
                    || e.getMessage().contains("不许静默") || e.getMessage().contains("退"));
        }
    }

    @Test
    public void factory_defaultIsLocal() throws Exception {
        ExecEnvironment env = ExecEnvironments.create(cfg(empty()), new Sandbox(tempDir("p22-factory-default")));
        assertEquals(ExecEnvironment.LOCAL, env.name());
        env.close();
    }

    @Test
    public void factory_dockerWithoutDaemonFailsLoudly() {
        Properties p = empty();
        p.setProperty(ExecEnvConfig.KEY_BACKEND, "docker");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_IMAGE, "busybox:latest");
        // 指向一个没人听的端口：必须显式失败，不许悄悄换成 local 把命令跑掉
        p.setProperty(ExecEnvConfig.KEY_DOCKER_HOST, "tcp://127.0.0.1:1");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_LEDGER, new File(tempDir("p22-factory-docker"),
                "ledger.properties").getPath());
        try {
            ExecEnvironment env = ExecEnvironments.create(cfg(p), new Sandbox(tempDir("p22-factory-docker")));
            env.close();
            fail("docker 起不来必须抛，实测却返回了 " + env.name());
        } catch (IOException e) {
            assertTrue("必须是 DOCKER_UNAVAILABLE，实测 " + e.getClass().getName() + " " + e.getMessage(),
                    e instanceof ExecEnvException);
            assertEquals(ExecEnvException.Code.DOCKER_UNAVAILABLE, ((ExecEnvException) e).code());
        }
    }

    // ===== ssh =====

    @Test
    public void ssh_buildsArgvWithoutAnyCommandConcat() throws Exception {
        Properties p = sshProps(tempDir("p22-ssh-ok"));
        SshExecEnvironment env = new SshExecEnvironment(cfg(p), new File(tempDir("p22-ssh-ok")));
        try {
            List<String> argv = env.buildArgv(ExecRequest.builder("ls", "-la", "/var/tmp").build());
            assertEquals(cfg(p).sshBin(), argv.get(0));
            assertTrue("BatchMode 必须在（否则无凭据时会挂在交互口令上）", argv.contains("BatchMode=yes"));
            assertTrue("目标必须是配置里那个主机，实测 " + argv,
                    argv.contains("p22user@127.0.0.1"));
            // 远端那一坨是最后一个元素：逐 token 单引号，不是把原始输入拼进去
            String remote = argv.get(argv.size() - 1);
            assertEquals("'ls' '-la' '/var/tmp'", remote);
            assertTrue("argv 里任何一段都不许含未转义的原始用户输入", argvContainsNoRaw(argv, "/var/tmp "));
        } finally {
            env.close();
        }
    }

    @Test
    public void ssh_metacharactersInArgsCannotEscapeQuoting() throws Exception {
        String dir = tempDir("p22-ssh-quote");
        Properties p = sshProps(dir);
        SshExecEnvironment env = new SshExecEnvironment(cfg(p), new File(dir));
        try {
            String evil = "x'; rm -rf /srv; echo 'PWNED";
            String remote = env.remoteInvocation(ExecRequest.builder("/bin/echo", evil).build());
            assertFalse("原始恶意串不许裸进远端命令：" + remote, remote.contains(evil));
            // 不靠我自己数引号（那等于自证）：让真 bash 来分这个词，看它到底切成几段
            LocalExecEnvironment shell = new LocalExecEnvironment(cfg(empty()), new Sandbox(dir));
            ExecResult r;
            try {
                r = shell.exec(ExecRequest.builder("/bin/bash", "-c", "printf '<%s>\\n' " + remote)
                        .maxOutputBytes(4096).build());
            } finally {
                shell.close();
            }
            assertEquals(0, r.exitCode());
            List<String> words = new ArrayList<String>();
            for (String line : r.stdout().split("\n")) {
                if (line.startsWith("<") && line.endsWith(">")) {
                    words.add(line.substring(1, line.length() - 1));
                }
            }
            assertEquals("bash 只该切出 2 个词（转义漏了就会多出一段 rm），实测 " + words, 2, words.size());
            assertEquals("/bin/echo", words.get(0));
            assertEquals(evil, words.get(1));
        } finally {
            env.close();
        }
    }

    @Test
    public void ssh_targetMustComeFromConfig() {
        Properties p = empty();
        p.setProperty(ExecEnvConfig.KEY_SSH_BIN, "/usr/bin/ssh");
        try {
            new SshExecEnvironment(cfg(p), new File(tempDir("p22-ssh-nohost")));
            fail("没配 exec.env.ssh.host 必须 SSH_TARGET_NOT_CONFIGURED");
        } catch (IOException e) {
            assertEquals(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    ((ExecEnvException) e).code());
        }
    }

    @Test
    public void ssh_hostWithShellOrOptionTricksIsRejected() {
        String[] bad = {"-oProxyCommand=touch /tmp/x", "127.0.0.1 rm -rf /", "$(uname)", "a; b", ""};
        for (String h : bad) {
            Properties p = empty();
            p.setProperty(ExecEnvConfig.KEY_SSH_BIN, "/usr/bin/ssh");
            p.setProperty(ExecEnvConfig.KEY_SSH_HOST, h);
            p.setProperty(ExecEnvConfig.KEY_SSH_IDENTITY, "/dev/null");
            try {
                new SshExecEnvironment(cfg(p), new File(tempDir("p22-ssh-badhost")));
                fail("非法主机名必须被拒：" + h);
            } catch (IOException expected) {
                assertEquals("非法主机名的错误码应该是 SSH_TARGET_NOT_CONFIGURED，实测 " + h,
                        ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                        ((ExecEnvException) expected).code());
            }
        }
    }

    @Test
    public void ssh_missingIdentityFailsLoudlyNoSilentLocal() {
        Properties p = empty();
        p.setProperty(ExecEnvConfig.KEY_SSH_BIN, "/usr/bin/ssh");
        p.setProperty(ExecEnvConfig.KEY_SSH_HOST, "127.0.0.1");
        p.setProperty(ExecEnvConfig.KEY_SSH_IDENTITY, "/definitely/not/here/id_ed25519");
        try {
            new SshExecEnvironment(cfg(p), new File(tempDir("p22-ssh-noid")));
            fail("identity 文件不存在必须 SSH_NO_CREDENTIALS（静默退 local 是禁的）");
        } catch (IOException e) {
            assertEquals(ExecEnvException.Code.SSH_NO_CREDENTIALS, ((ExecEnvException) e).code());
        }
    }

    @Test
    public void ssh_rejectsPathOutsideRemoteSandbox() throws Exception {
        Properties p = sshProps(tempDir("p22-ssh-path"));
        p.setProperty(ExecEnvConfig.KEY_SSH_WORKDIR, "/srv/zbot-sandbox");
        SshExecEnvironment env = new SshExecEnvironment(cfg(p), new File(tempDir("p22-ssh-path")));
        try {
            assertEquals("/srv/zbot-sandbox/a.txt", env.checkedRemote("a.txt"));
            try {
                env.checkedRemote("../../etc/passwd");
                fail("远端越界路径必须被拒");
            } catch (IOException expected) {
                assertEquals(ExecEnvException.Code.SANDBOX_ESCAPE,
                        ((ExecEnvException) expected).code());
            }
        } finally {
            env.close();
        }
    }

    // ===== 小工具 =====

    private static boolean argvContainsNoRaw(List<String> argv, String needle) {
        for (String a : argv) {
            if (a.contains(needle) && !a.startsWith("'")) {
                return false;
            }
        }
        return true;
    }

    /** 数单引号外的 token 段数：注入成功的话段数会变多。 */
    private static int countUnquotedTokens(String remote) {
        int tokens = 0;
        boolean inQuote = false;
        boolean wasOut = true;
        for (int i = 0; i < remote.length(); i++) {
            char c = remote.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                wasOut = false;
                continue;
            }
            if (c == ' ') {
                if (!wasOut) {
                    wasOut = true;
                }
            } else if (wasOut) {
                tokens++;
                wasOut = false;
            }
        }
        return tokens;
    }

    private static java.util.Map<String, String> withToken(final String token) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put("P22_TREE_TOKEN", token);
        return m;
    }

    private static Properties sshProps(String home) {
        Properties p = new Properties();
        p.setProperty(ExecEnvConfig.KEY_BACKEND, "ssh");
        p.setProperty(ExecEnvConfig.KEY_SSH_BIN, "/usr/bin/ssh");
        p.setProperty(ExecEnvConfig.KEY_SSH_HOST, "127.0.0.1");
        p.setProperty(ExecEnvConfig.KEY_SSH_PORT, "22");
        p.setProperty(ExecEnvConfig.KEY_SSH_USER, "p22user");
        File key = new File(home, "id_ed25519");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(key);
            fos.write("not-a-real-key\n".getBytes(UTF_8));
            fos.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        p.setProperty(ExecEnvConfig.KEY_SSH_IDENTITY, key.getPath());
        return p;
    }

    /** 临时根必须在 {@code --config-dir} 那套里，不许碰 ~/.zbot，也不许写 /tmp（红线 3/4）。 */
    private static String tempDir(String label) {
        File base = new File(System.getProperty("zbot.p22.scratch",
                new File(System.getProperty("user.home"), ".cache/zbot-p22-lead/test")
                        .getAbsolutePath()), label);
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IllegalStateException("建不了临时目录：" + base);
        }
        return base.getAbsolutePath();
    }

    /** 供其它测试类复用。 */
    static String scratchRoot() {
        return tempDir("shared");
    }

    private static List<String> listOf(String... v) {
        List<String> l = new ArrayList<String>();
        for (String s : v) {
            l.add(s);
        }
        return l;
    }
}
