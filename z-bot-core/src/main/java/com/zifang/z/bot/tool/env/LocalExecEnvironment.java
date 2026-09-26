package com.zifang.z.bot.tool.env;

import com.zifang.z.bot.tool.Sandbox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * local 后端（P22 缺省）：真起子进程，argv 形态，<b>有界</b>输出，超时端掉整棵进程树。
 *
 * <p>与既有 {@code BuiltinTools#bash}（{@code BuiltinTools.java:455-490}）的差别只有三处，
 * 而且每处都是"把已经认下来的坑补上"：</p>
 * <ol>
 *   <li>收输出走 {@link BoundedCollector}，不再整段进 {@code StringBuilder}；</li>
 *   <li>行数/字节数到顶之后<b>继续 drain 到 EOF</b>：旧实现在 {@code maxLines} 处
 *       {@code break} 就不读了（{@code :471-473}），子进程往填不满的管道里继续写会阻塞，
 *       于是 {@code p.waitFor()}（{@code :478}）永远等不到 —— 见 EVIDENCE §2 的复现；</li>
 *   <li>有 {@code timeoutMillis} 这道时间上限，旧实现一个都没有。</li>
 * </ol>
 *
 * <p>并发是有常数额度的（{@code exec.env.max.concurrent}，缺省 4），不随核数漂：
 * 每次 exec 最多 3 条内部线程（stdout/stderr 读 + stdin 灌），乘额度就是线程上界。</p>
 */
public final class LocalExecEnvironment implements ExecEnvironment {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int SWEEP_MILLIS = 25;
    /** 收尾取证用的硬上限：进程被强杀后最多再等这么久，绝不无限挂。 */
    private static final long REAP_GRACE_MILLIS = 2000L;

    private final ExecEnvConfig config;
    private final Sandbox sandbox;
    private final Semaphore permits;

    public LocalExecEnvironment(ExecEnvConfig config, Sandbox sandbox) {
        this.config = config == null ? new ExecEnvConfig() : config;
        this.sandbox = sandbox;
        int n = this.config.maxConcurrent();
        this.permits = new Semaphore(n <= 0 ? 1 : n, true);
    }

    @Override
    public String name() {
        return LOCAL;
    }

    @Override
    public boolean supportsStdin() {
        return true;
    }

    @Override
    public String sandboxRoot() {
        return sandbox == null ? "(无沙箱)" : sandbox.root().getPath();
    }

    @Override
    public String describe() {
        return "local{root=" + sandboxRoot() + ", maxConcurrent=" + config.maxConcurrent()
                + ", maxOut=" + config.outputMaxBytes() + "B}";
    }

    @Override
    public int maxOutputBytesHint() {
        return config.outputMaxBytes();
    }

    @Override
    public long defaultTimeoutMillisHint() {
        return config.defaultTimeoutMs();
    }

    @Override
    public ExecResult exec(ExecRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        final ExecRequest req = applyDefaults(request);
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(Math.max(SWEEP_MILLIS, req.timeoutMillis() > 0
                    ? req.timeoutMillis() + REAP_GRACE_MILLIS : 60000L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("local 后端等待额度时被中断", e);
        }
        if (!acquired) {
            throw new IOException("local 后端并发额度（exec.env.max.concurrent=" + config.maxConcurrent()
                    + "）等待超时，命令未执行");
        }
        try {
            return doExec(req);
        } finally {
            permits.release();
        }
    }

    /** 请求没给上界/超时时才套配置值；给过的一个都不改（回归要用调用方自己的口径）。 */
    private ExecRequest applyDefaults(ExecRequest req) {
        if (req.timeoutMillis() > 0 && req.maxOutputBytes() > 0) {
            return req;
        }
        ExecRequest.Builder b = ExecRequest.builder(req.argv())
                .cwd(req.cwd())
                .stdin(req.stdin())
                .policy(req.policy())
                .mergeStreams(req.mergeStreams())
                .observer(req.observer());
        Map<String, String> env = req.env();
        if (env != null) {
            b.env(env);
        }
        b.timeoutMillis(req.timeoutMillis() > 0 ? req.timeoutMillis() : config.defaultTimeoutMs());
        b.maxOutputBytes(req.maxOutputBytes() > 0 ? req.maxOutputBytes() : config.outputMaxBytes());
        return b.build();
    }

    private ExecResult doExec(final ExecRequest req) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(req.argv());
        if (req.cwd() != null) {
            pb.directory(req.cwd());
        }
        if (req.env() != null) {
            pb.environment().putAll(req.env());
        }
        if (req.mergeStreams()) {
            pb.redirectErrorStream(true);
        }
        final Process p = pb.start();
        final ExecRequest.ProcessObserver observer = req.observer();
        observer.started(p);
        final boolean[] interruptedFlag = {false};
        long timeout = req.timeoutMillis();
        long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0L;
        BoundedCollector out = new BoundedCollector(req.maxOutputBytes(), req.policy());
        BoundedCollector err = new BoundedCollector(req.maxOutputBytes(), req.policy());
        InputStream outStream = p.getInputStream();
        InputStream errStream = req.mergeStreams() ? null : p.getErrorStream();
        StreamDrainer outDrainer = new StreamDrainer(outStream, out);
        StreamDrainer errDrainer = errStream == null ? null : new StreamDrainer(errStream, err);
        outDrainer.start();
        if (errDrainer != null) {
            errDrainer.start();
        }
        Thread stdinPiper = null;
        byte[] stdin = req.stdin();
        if (stdin != null && stdin.length > 0) {
            // 她 base.py:202 _pipe_stdin + :219 单独线程写：主线程在读输出，
            // 这里同步写会把两边一起堵死（子进程输出满 ⇔ 它读不到 stdin）
            final OutputStream os = p.getOutputStream();
            final byte[] payload = stdin;
            stdinPiper = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        os.write(payload);
                        os.flush();
                    } catch (IOException ignored) {
                        // 子进程自己关了 stdin 就随它去，退出码/输出会说明发生了什么
                    } finally {
                        try {
                            os.close();
                        } catch (IOException ignored) {
                            // 同上
                        }
                    }
                }
            }, "zbot-env-stdin");
            stdinPiper.setDaemon(true);
            stdinPiper.start();
        } else {
            closeQuietly(p.getOutputStream());
        }

        boolean timedOut = false;
        int killedDescendants = 0;
        try {
            while (true) {
                // 每轮先问一次旗子再起下一步：中止的语义与既有看门狗一致，
                // 而且"从没问过旗子"这个漏子本身就是变异体要打的（见 BuiltinToolsExecWiringTest）
                if (observer.interrupted()) {
                    // 中止和超时同样要端干净：只杀 bash 会把 sleep/mvn 留成孤儿
                    killedDescendants = ProcessTree.killTree(p);
                    break;
                }
                long slice = deadline > 0 ? Math.min(REAP_GRACE_MILLIS, SWEEP_MILLIS) : SWEEP_MILLIS;
                boolean alive;
                if (deadline > 0) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        alive = p.isAlive();
                    } else {
                        alive = !p.waitFor(Math.min(slice, left), TimeUnit.MILLISECONDS);
                    }
                } else {
                    alive = !p.waitFor(slice, TimeUnit.MILLISECONDS);
                }
                if (!alive) {
                    break;
                }
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                    killedDescendants = ProcessTree.killTree(p);
                    break;
                }
            }
            if (!ProcessTree.awaitGone(p, REAP_GRACE_MILLIS)) {
                killedDescendants = Math.max(killedDescendants, ProcessTree.descendantPids(p).size());
            }
        } catch (InterruptedException e) {
            ProcessTree.killTree(p);
            Thread.currentThread().interrupt();
            throw new IOException("local 后端等待子进程时被中断", e);
        } finally {
            if (stdinPiper != null) {
                joinQuietly(stdinPiper, REAP_GRACE_MILLIS);
            }
            // 收尾有上限：读线程最多多等 REAP_GRACE_MILLIS，绝不无限挂（本战役在 JDK httpserver
            // stop() 永久挂死上吃过亏，这里一律带 deadline）
            outDrainer.finish(REAP_GRACE_MILLIS);
            if (errDrainer != null) {
                errDrainer.finish(REAP_GRACE_MILLIS);
            }
            closeQuietly(p.getInputStream());
            if (errStream != null) {
                closeQuietly(errStream);
            }
            observer.finished(p);
        }
        int exit;
        if (timedOut || !p.isAlive()) {
            try {
                exit = p.exitValue();
            } catch (IllegalThreadStateException e) {
                exit = -1;
            }
        } else {
            p.destroyForcibly();
            ProcessTree.awaitGone(p, REAP_GRACE_MILLIS);
            try {
                exit = p.exitValue();
            } catch (IllegalThreadStateException e) {
                exit = -1;
            }
        }
        if (interruptedFlag[0]) {
            timedOut = false;
        }
        return ExecResult.builder(LOCAL)
                .exitCode(exit)
                .stdout(out.render(""))
                .stderr(err.render(""))
                .stdoutTruncated(out.truncated())
                .stderrTruncated(err.truncated())
                .stdoutTotalBytes(out.totalBytes())
                .stderrTotalBytes(err.totalBytes())
                .timedOut(timedOut)
                .killedDescendants(killedDescendants)
                .build();
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        File target = checked(path);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建目录失败：" + parent.getPath());
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
            if (content != null) {
                fos.write(content);
            }
        }
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        File target = checked(path);
        if (!target.isFile()) {
            throw new IOException("文件不存在：「" + path + "」");
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(
                Math.max(16L, target.length()), (long) config.outputMaxBytes()));
        try (InputStream in = new java.io.FileInputStream(target)) {
            byte[] buf = new byte[8192];
            long cap = config.outputMaxBytes();
            long read = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (read + n > cap) {
                    bos.write(buf, 0, (int) Math.max(0, cap - read));
                    throw new ExecEnvException(ExecEnvException.Code.CAPABILITY_UNSUPPORTED,
                            "readFile 超过上界 " + cap + "B：「" + path + "」（要用大文件请走上界可配的调用方）");
                }
                bos.write(buf, 0, n);
                read += n;
            }
        }
        return bos.toByteArray();
    }

    /** 沙箱越界检查：local 后端把路径交给 {@code Sandbox#resolve}，它不在就直接拒。 */
    private File checked(String path) throws IOException {
        if (sandbox == null) {
            throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE, "local 后端没有沙箱根");
        }
        return sandbox.resolve(path);
    }

    @Override
    public void close() throws IOException {
        // 没有常驻资源要放：额度是内存信号量，子进程在 exec 返回前一定被收干净
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 收尾关不掉不是错误
            }
        }
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 读一条流到 {@link BoundedCollector}，带硬上限的收尾。 */
    private static final class StreamDrainer {
        private final InputStream in;
        private final BoundedCollector sink;
        private Thread thread;
        private volatile IOException failure;

        StreamDrainer(InputStream in, BoundedCollector sink) {
            this.in = in;
            this.sink = sink;
        }

        void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[8192];
                    try {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (n > 0) {
                                sink.append(buf, 0, n);
                            }
                        }
                    } catch (IOException e) {
                        failure = e;
                    }
                }
            }, "zbot-env-drain");
            thread.setDaemon(true);
            thread.start();
        }

        void finish(long millis) {
            if (thread != null) {
                joinQuietly(thread, millis);
            }
        }

        IOException failure() {
            return failure;
        }
    }
}
