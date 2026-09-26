package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.agent.InterruptScope;
import com.zifang.z.bot.tool.env.ExecEnvException;
import com.zifang.z.bot.tool.env.ExecEnvironment;
import com.zifang.z.bot.tool.env.ExecEnvironments;
import com.zifang.z.bot.tool.env.ExecRequest;
import com.zifang.z.bot.tool.env.ExecResult;
import com.zifang.z.bot.tool.env.LocalExecEnvironment;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P22 接线层：{@code BuiltinTools} 的 exec 那一路到底走没走 SPI，以及走了之后输出变没变。
 *
 * <p>这个类存在的唯一理由是"只测算法层不算数"：本战役在 P18 的 {@code D10} 上就是因为
 * 接线层没人守，变异注入摘掉接线之后照样 GREEN-BUT-MUTATED。所以这里两支硬断言：</p>
 * <ol>
 *   <li>{@link #execToolActuallyRoutesThroughTheInstalledBackend()} —— 装一个假后端，
 *       工具必须把命令交给它（摘掉接线 ⇒ 这支立刻红）；</li>
 *   <li>{@link #execOutputIsByteIdenticalToTheLegacyImplementation()} —— 拿旧实现
 *       （{@code BuiltinTools.java:455-490} 原样抄在本类里）做参照跑差分，
 *       抽层不许把既有语义改一个字节。</li>
 * </ol>
 */
public class BuiltinToolsExecWiringTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @After
    public void uninstall() {
        ExecEnvironments.install(null);
    }

    // ===== ① 接线层 =====

    /** 记录型后端：被调到了才说话。 */
    private static final class SpyEnv implements ExecEnvironment {
        private int calls;
        private ExecRequest last;
        private final String cannedStdout;

        SpyEnv(String cannedStdout) {
            this.cannedStdout = cannedStdout;
        }

        @Override
        public String name() {
            return "spy";
        }

        @Override
        public ExecResult exec(ExecRequest request) throws IOException {
            calls++;
            last = request;
            return ExecResult.builder("spy")
                    .exitCode(0)
                    .stdout(cannedStdout)
                    .stdoutTotalBytes(cannedStdout.length())
                    .build();
        }

        @Override
        public void writeFile(String path, byte[] content) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readFile(String path) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sandboxRoot() {
            return "spy-root";
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
    }

    @Test
    public void execToolActuallyRoutesThroughTheInstalledBackend() throws Exception {
        SpyEnv spy = new SpyEnv("从假后端回来的输出");
        ExecEnvironments.install(spy);
        String sandboxDir = scratch("p22-wiring-spy");
        Tool exec = BuiltinTools.exec(new Sandbox(sandboxDir), "off");
        ToolResult r = exec.execute(Collections.<String, Object>singletonMap("command", "echo 真子进程不该跑"));
        assertEquals("exec 工具一次都没经过 SPI —— 接线断了", 1, spy.calls);
        assertEquals(Arrays.asList("bash", "-c", "echo 真子进程不该跑"), spy.last.argv());
        assertTrue("必须按合并流口径问后端（既有 exec 就是 redirectErrorStream(true)）",
                spy.last.mergeStreams());
        assertEquals("抽层不许把可见输出改样（旧实现按行读，末尾一定补一个换行）",
                "exit=0\n从假后端回来的输出\n", r.getContent());
        assertEquals("HEAD 策略：既有语义是按头部截断", ExecRequest.OutputPolicy.HEAD, spy.last.policy());
        assertTrue("必须有字节上界（0=不封顶就是漏了）", spy.last.maxOutputBytes() > 0);
    }

    @Test
    public void execToolStillCarriesTheInterruptBridge() throws Exception {
        final boolean[] sawStarted = {false};
        final boolean[] sawInterruptedPolled = {false};
        final boolean[] sawFinished = {false};
        // P22b 结构性补口：上面三支旗子记的是 spy **自己重新塞进去**的 observer，
        // 产品那份（BuiltinTools 里 `.observer(new InterruptBridgeObserver())`）被摘掉时它们照样绿 ——
        // 杠② 第一跑把 INTERRUPT_BRIDGE 判成 SURVIVED，根因就在这。这里把 request 带进来的
        // 那份 observer 抓下来，直接按桥的契约验它（读得到旗子 / 进了在飞表 / 退了清掉）。
        final ExecRequest.ProcessObserver[] productObserver = {null};
        final String dir = scratch("p22-wiring-bridge");
        // 包装而不是继承：LocalExecEnvironment 是 final
        final ExecEnvironment inner = new LocalExecEnvironment(
                new com.zifang.z.bot.tool.env.ExecEnvConfig(new File(dir)), new Sandbox(dir));
        ExecEnvironments.install(new ExecEnvironment() {
            @Override
            public String name() {
                return inner.name();
            }

            @Override
            public ExecResult exec(ExecRequest request) throws IOException {
                productObserver[0] = request.observer();
                ExecRequest probed = ExecRequest.builder(request.argv())
                        .cwd(request.cwd())
                        .maxOutputBytes(request.maxOutputBytes())
                        .policy(request.policy())
                        .mergeStreams(request.mergeStreams())
                        .timeoutMillis(request.timeoutMillis())
                        .observer(new ExecRequest.ProcessObserver() {
                            @Override
                            public void started(Process p) {
                                sawStarted[0] = true;
                            }

                            @Override
                            public void finished(Process p) {
                                sawFinished[0] = true;
                            }

                            @Override
                            public boolean interrupted() {
                                sawInterruptedPolled[0] = true;
                                return false;
                            }
                        })
                        .build();
                return inner.exec(probed);
            }

            @Override
            public void writeFile(String path, byte[] content) throws IOException {
                inner.writeFile(path, content);
            }

            @Override
            public byte[] readFile(String path) throws IOException {
                return inner.readFile(path);
            }

            @Override
            public String sandboxRoot() {
                return inner.sandboxRoot();
            }

            @Override
            public boolean supportsStdin() {
                return inner.supportsStdin();
            }

            @Override
            public int maxOutputBytesHint() {
                return inner.maxOutputBytesHint();
            }

            @Override
            public long defaultTimeoutMillisHint() {
                return inner.defaultTimeoutMillisHint();
            }

            @Override
            public String describe() {
                return inner.describe();
            }

            @Override
            public void close() throws IOException {
                inner.close();
            }
        });
        Tool exec = BuiltinTools.exec(new Sandbox(dir), "off");
        ToolResult r = exec.execute(Collections.<String, Object>singletonMap("command", "echo bridge"));
        assertTrue("后端没见过子进程 ⇒ watch/unwatch 这条线是断的", sawStarted[0]);
        assertTrue("后端没问过中断旗子 ⇒ 工具在飞时断不掉", sawInterruptedPolled[0]);
        assertTrue("子进程收工没回调 unwatch", sawFinished[0]);
        assertTrue(r.getContent().contains("bridge"));
        // ↓↓↓ 这一段才是真的守 INTERRUPT_BRIDGE：验的是**产品递给 SPI 的那份 observer**本身。
        com.zifang.z.agent.kernel.agent.InterruptFlag flag =
                new com.zifang.z.agent.kernel.agent.InterruptFlag();
        com.zifang.z.agent.kernel.agent.InterruptFlag previous = InterruptScope.bind(flag);
        Process watched = null;
        try {
            assertTrue("exec 那一路一个 observer 都没递给 SPI ⇒ 中断桥根本没接线",
                    productObserver[0] != null);
            flag.request("p22b 杠② INTERRUPT_BRIDGE 判旗子");
            assertTrue("递给 SPI 的 observer 读不到中断旗子 ⇒ 摘掉的就是 InterruptBridgeObserver",
                    productObserver[0].interrupted());
            int before = InterruptScope.liveProcessCount();
            watched = new ProcessBuilder("/bin/sleep", "20").start();
            productObserver[0].started(watched);
            assertEquals("started 没把子进程登记进看门狗 ⇒ 在飞的命令断不掉",
                    before + 1, InterruptScope.liveProcessCount());
            productObserver[0].finished(watched);
            assertEquals("finished 没取消登记 ⇒ 看门狗里留了僵尸",
                    before, InterruptScope.liveProcessCount());
            watched.destroyForcibly();
            watched = null;
        } finally {
            if (watched != null) {
                watched.destroyForcibly();
            }
            InterruptScope.restore(previous);
        }
    }

    @Test
    public void execToolFailsLoudlyWhenTheBackendIsDown() throws Exception {
        ExecEnvironments.install(new ExecEnvironment() {
            @Override
            public String name() {
                return "docker";
            }

            @Override
            public ExecResult exec(ExecRequest request) throws IOException {
                throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE, "daemon 没起");
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
                return "/workspace";
            }

            @Override
            public boolean supportsStdin() {
                return false;
            }

            @Override
            public String describe() {
                return "docker{broken}";
            }

            @Override
            public void close() throws IOException {
            }
        });
        Tool exec = BuiltinTools.exec(new Sandbox(scratch("p22-wiring-down")), "off");
        ToolResult r = exec.execute(Collections.<String, Object>singletonMap("command", "echo hi"));
        assertTrue("后端不可用必须回 error，不许当成跑成功了", r.isError());
        assertTrue("错误里要看得见后端名", r.getContent().contains("daemon 没起"));
    }

    // ===== ② 逐字节回归 =====

    @Test
    public void execOutputIsByteIdenticalToTheLegacyImplementation() throws Exception {
        String dir = scratch("p22-regress");
        Sandbox sandbox = new Sandbox(dir);
        String[] scripts = {
                "echo hello",
                "printf 'a\\nb\\nc\\n'",
                "seq 1 200",
                "printf 'x%.0s' $(seq 1 5000); echo",
                "echo to-stderr 1>&2; echo to-stdout",
                "exit 3",
                "echo 中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍-中文输出-测试-截断-边界-长度-需要-超过-三千-字符-才行-所以-这里-重复-很多遍",
                "true",
                "echo -n no-trailing-newline",
                "printf 'l1\\nl2\\nl3'; echo"
        };
        Tool exec = BuiltinTools.exec(sandbox, "off");
        Map<String, Object> one = new HashMap<String, Object>();
        for (String script : scripts) {
            String legacy = legacyBash(sandbox, script, 100, 3000);
            one.put("command", script);
            String now = exec.execute(one).getContent();
            assertEquals("抽层之后 exec 的输出必须与旧实现逐字节相同（脚本：" + abbrev(script) + "）",
                    legacy, now);
        }
        // mvn_build 的口径（maxLines=0 / maxChars=2000）也走同一条 bash()
        for (String script : new String[]{"seq 1 500", "printf 'y%.0s' $(seq 1 9000); echo"}) {
            String legacy = legacyBash(sandbox, script, 0, 2000);
            String now = legacyBashViaSpi(sandbox, script, 0, 2000);
            assertEquals("不限行数那一路也要逐字节相同（脚本：" + abbrev(script) + "）", legacy, now);
        }
    }

    /** 旧实现原样抄写（{@code BuiltinTools.java:455-490} @ d23cd0d），只当参照，不进生产路径。 */
    private static String legacyBash(Sandbox cwd, String script, int maxLines, int maxChars) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", script);
        if (cwd != null) {
            pb.directory(cwd.root());
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF_8))) {
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
        // P22b 永挂体检：原样是 `int exit = p.waitFor();`（无界）。这段参照实现会被杠② 的每一次变异注入
        // 重跑，而杠② 的锁是全编队共享的 —— 一旦某个变异体让这个 bash 堵在管道上，无界 waitFor 会把锁占死。
        // 改成有界等待 + 显式判词（超时即判红，绝不静等）；正常退出路径取到的 exit 与原来逐字节相同。
        boolean finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            com.zifang.z.bot.tool.env.ProcessTree.killTree(p);
            fail("参照实现 legacyBash 的 waitFor 超过 20s 未返回（脚本：" + abbrev(script)
                    + "）⇒ 判红，不再无界等待");
        }
        int exit = p.exitValue();
        String result = out.toString();
        if (maxChars > 0 && result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n...(已截断)";
        }
        return "exit=" + exit + "\n" + result;
    }

    /** 新实现走 SPI 的那一路（与 mvn_build/curl_test 同参数口径）。 */
    private static String legacyBashViaSpi(Sandbox sandbox, String script, int maxLines, int maxChars)
            throws Exception {
        // 显式建后端：绝不让 current() 去碰 ~/.zbot（红线 3/4）
        ExecEnvironment env = new LocalExecEnvironment(
                new com.zifang.z.bot.tool.env.ExecEnvConfig(sandbox.root()), sandbox);
        ExecRequest req = ExecRequest.builder(Arrays.asList("bash", "-c", script))
                .cwd(sandbox.root())
                .mergeStreams(true)
                .policy(ExecRequest.OutputPolicy.HEAD)
                .maxOutputBytes(maxChars > 0 ? maxChars * 4 : env.maxOutputBytesHint())
                .build();
        ExecResult r = env.exec(req);
        env.close();
        String result = r.stdout();
        if (maxLines > 0) {
            String[] parts = result.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(maxLines, parts.length); i++) {
                sb.append(parts[i]).append('\n');
            }
            result = sb.toString();
        }
        if (maxChars > 0 && result.length() > maxChars) {
            result = result.substring(0, maxChars) + "\n...(已截断)";
        }
        return "exit=" + r.exitCode() + "\n" + result;
    }

    // ===== ③ 本地后端自己也得守住 =====

    @Test
    public void localBackendIsTheDefaultAndReportsItsName() throws Exception {
        ExecEnvironment env = ExecEnvironments.create(
                new com.zifang.z.bot.tool.env.ExecEnvConfig(new File(scratch("p22-default"))),
                new Sandbox(scratch("p22-default")));
        assertEquals("缺省后端必须是 local", ExecEnvironment.LOCAL, env.name());
        env.close();
    }

    @Test
    public void sandboxStillRefusesEscapingPaths() throws Exception {
        String dir = scratch("p22-sandbox-escape");
        Sandbox sandbox = new Sandbox(dir);
        // 这一支守的是 Sandbox.resolve 的越界检查：摘掉它，".." 就能写到沙箱外面去
        try {
            sandbox.resolve("../outside.txt");
            fail("Sandbox 的越界检查被摘掉了：\"..\" 竟然解析成功");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("沙箱"));
        }
        try {
            sandbox.resolve("a/../../outside.txt");
            fail("绕一道的 \"..\" 也必须拦");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("沙箱"));
        }
        // 同前缀的兄弟目录不能被当成"在沙箱内"（root=/a/b, 目标 /a/bc 要拒）
        File sibling = new File(new File(dir).getParentFile(), new File(dir).getName() + "-sibling");
        try {
            String escape = "../" + sibling.getName() + "/x.txt";
            File resolved = sandbox.resolve(escape);
            fail("越界检查漏了兄弟目录：" + resolved);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("沙箱"));
        }
    }

    // ===== 小工具 =====

    private static String abbrev(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    static String scratch(String label) {
        File base = new File(System.getProperty("zbot.p22.scratch",
                new File(System.getProperty("user.home"), ".cache/zbot-p22-lead/test").getAbsolutePath()), label);
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IllegalStateException("建不了临时目录：" + base);
        }
        return base.getAbsolutePath();
    }

    /** 只在本类内部用，避免和 java.util.Arrays 的重名冲突。 */
    private static final class Arrays {
        static java.util.List<String> asList(String... v) {
            java.util.List<String> l = new java.util.ArrayList<String>();
            Collections.addAll(l, v);
            return l;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), UTF_8);
    }
}
