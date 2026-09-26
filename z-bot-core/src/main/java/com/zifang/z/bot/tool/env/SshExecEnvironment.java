package com.zifang.z.bot.tool.env;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * ssh 后端（P22）。
 *
 * <p>三条硬规矩，每条都配了一支点名变异体（{@code p22_mutation.py}）：</p>
 * <ol>
 *   <li><b>目标主机只来自配置</b>（{@code exec.env.ssh.host/port/user/identity.file}），
 *       且必须过 {@link #HOST_PATTERN}；不合法 ⇒ {@link ExecEnvException.Code#SSH_TARGET_NOT_CONFIGURED}。
 *       工具的 {@code command} 参数永远没机会变成主机名或 ssh 选项。</li>
 *   <li><b>不许把命令拼成字符串</b>：ssh 的 argv 一段一段给；远端要再过一次 shell，
 *       所以每个 token 单独走 {@link ExecRequest#posixQuote}（见 {@link #remoteInvocation}）。
 *       谁把它改成 {@code "ssh " + host + " " + cmd} 就是开了注入面。</li>
 *   <li><b>失败就报错，不许静默退 local</b>：没配主机、没凭据、连不上 ⇒ 显式异常码。</li>
 * </ol>
 */
public final class SshExecEnvironment implements ExecEnvironment {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 主机名只允许这些字符，而且不许以 {@code -} 开头（挡掉 {@code -oProxyCommand=…} 那类选项注入）。 */
    static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9._][A-Za-z0-9._-]{0,252}$");
    static final Pattern USER_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final ExecEnvConfig config;
    private final File sandboxRoot;

    public SshExecEnvironment(ExecEnvConfig config, File sandboxRoot) throws IOException {
        this.config = config == null ? new ExecEnvConfig() : config;
        this.sandboxRoot = sandboxRoot;
        validateTarget(this.config.sshHost(), this.config.sshUser(), this.config.sshPort());
        requireCredentials();
    }

    /** 目标校验独立成静态方法：变异体把它摘掉时，单测必须立刻变红。 */
    static void validateTarget(String host, String user, int port) throws ExecEnvException {
        if (host == null || host.trim().isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    "backend=ssh 但 exec.env.ssh.host 没配 —— 目标主机只能来自配置，"
                            + "也不许悄悄换成 local 跑");
        }
        String h = host.trim();
        if (!HOST_PATTERN.matcher(h).matches()) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    "exec.env.ssh.host 形态不合法（长度/字符/以 - 开头都不行）：" + shape(h));
        }
        if (user != null && !user.trim().isEmpty() && !USER_PATTERN.matcher(user.trim()).matches()) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    "exec.env.ssh.user 形态不合法：" + shape(user));
        }
        if (port < 1 || port > 65535) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    "exec.env.ssh.port 不在 1..65535：" + port);
        }
    }

    /** 只出长度和首字符，绝不把可疑值整段回显到日志里。 */
    private static String shape(String v) {
        String t = v.trim();
        return "len=" + t.length() + ", head=" + (t.isEmpty() ? "" : Character.toString(t.charAt(0)));
    }

    private void requireCredentials() throws ExecEnvException {
        String identity = config.sshIdentityFile();
        if (identity.isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_NO_CREDENTIALS,
                    "backend=ssh 需要 exec.env.ssh.identity.file（BatchMode 下没有交互口令这回事）"
                            + " —— 显式失败，不换后端");
        }
        File f = new File(identity);
        if (!f.isFile()) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_NO_CREDENTIALS,
                    "identity 文件不存在：" + f.getPath());
        }
    }

    @Override
    public String name() {
        return SSH;
    }

    @Override
    public boolean supportsStdin() {
        return true;
    }

    @Override
    public String sandboxRoot() {
        return sandboxRoot == null ? config.sshWorkdir() : sandboxRoot.getPath();
    }

    @Override
    public String describe() {
        return "ssh{target=" + config.sshUser() + "@" + config.sshHost() + ":" + config.sshPort()
                + ", bin=" + config.sshBin() + ", connectTimeoutMs=" + config.sshConnectTimeoutMs()
                + ", identity=(set)}";
    }

    /**
     * 组出 ssh 的 argv —— <b>这个方法里没有任何 {@code +} 拼命令</b>。
     *
     * @param request 命令（argv 形态）
     */
    List<String> buildArgv(ExecRequest request) throws IOException {
        List<String> argv = new ArrayList<String>();
        argv.add(config.sshBin());
        argv.add("-o");
        argv.add("BatchMode=yes");
        argv.add("-o");
        argv.add("ConnectTimeout=" + Math.max(1, config.sshConnectTimeoutMs() / 1000));
        argv.add("-o");
        argv.add("StrictHostKeyChecking=accept-new");
        argv.add("-p");
        argv.add(String.valueOf(config.sshPort()));
        String identity = config.sshIdentityFile();
        if (!identity.isEmpty()) {
            argv.add("-i");
            argv.add(identity);
        }
        String user = config.sshUser().trim();
        String destination = user.isEmpty() ? config.sshHost().trim()
                : user + "@" + config.sshHost().trim();
        argv.add(destination);
        argv.add(remoteInvocation(request));
        return argv;
    }

    /**
     * 远端那一坨：{@code cd <workdir> && <argv…>}，每个 token 单独单引号转义后再 join。
     *
     * <p>join 的是<b>已转义的</b> token，不是原始输入 —— 这是 ssh 协议本身的要求
     * （远端 shell 还要再解析一次），也是"拼接"与"逐 token 转义"的分界线：
     * 变异体把 {@link ExecRequest#posixQuote} 去掉，{@code p22_mutation.py} 里
     * {@code SSH_COMMAND_CONCAT} 那一族就会红。</p>
     */
    String remoteInvocation(ExecRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        String workdir = config.sshWorkdir();
        if (workdir != null && !workdir.trim().isEmpty()) {
            sb.append("cd ").append(ExecRequest.posixQuote(workdir.trim())).append(" && ");
        }
        for (int i = 0; i < request.argv().size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(ExecRequest.posixQuote(request.argv().get(i)));
        }
        if (request.env() != null) {
            // 环境变量同样是逐 token 转义，不拼原始值
            StringBuilder prefix = new StringBuilder();
            for (Map.Entry<String, String> e : request.env().entrySet()) {
                prefix.append("export ").append(e.getKey()).append('=')
                        .append(ExecRequest.posixQuote(e.getValue())).append("; ");
            }
            sb.insert(0, prefix.toString());
        }
        return sb.toString();
    }

    @Override
    public ExecResult exec(ExecRequest request) throws IOException {
        validateTarget(config.sshHost(), config.sshUser(), config.sshPort());
        requireCredentials();
        List<String> argv = buildArgv(request);
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (request.cwd() != null) {
            pb.directory(request.cwd());
        }
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_TARGET_NOT_CONFIGURED,
                    "ssh 可执行文件起不来（" + config.sshBin() + "）：" + e.getMessage()
                            + " —— 显式失败，不换 local", e);
        }
        final ExecRequest.ProcessObserver observer = request.observer();
        observer.started(p);
        long timeout = request.timeoutMillis() > 0 ? request.timeoutMillis() : config.defaultTimeoutMs();
        long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0L;
        BoundedCollector out = new BoundedCollector(request.maxOutputBytes(), request.policy());
        BoundedCollector err = new BoundedCollector(request.maxOutputBytes(), request.policy());
        Thread to = drain(p.getInputStream(), out);
        Thread te = drain(p.getErrorStream(), err);
        Thread stdinPiper = null;
        byte[] stdin = request.stdin();
        if (stdin != null && stdin.length > 0) {
            final OutputStream os = p.getOutputStream();
            final byte[] payload = stdin;
            stdinPiper = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        os.write(payload);
                        os.flush();
                    } catch (IOException ignored) {
                        // 对端关了就算了
                    } finally {
                        try {
                            os.close();
                        } catch (IOException ignored) {
                            // 同上
                        }
                    }
                }
            }, "zbot-ssh-stdin");
            stdinPiper.setDaemon(true);
            stdinPiper.start();
        } else {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // 同上
            }
        }
        boolean timedOut = false;
        int killed = 0;
        try {
            while (true) {
                boolean alive;
                if (deadline > 0) {
                    long left = deadline - System.currentTimeMillis();
                    alive = left <= 0 ? p.isAlive() : !p.waitFor(Math.min(left, 50L), TimeUnit.MILLISECONDS);
                } else {
                    alive = !p.waitFor(50L, TimeUnit.MILLISECONDS);
                }
                if (!alive) {
                    break;
                }
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    timedOut = true;
                    killed = ProcessTree.killTree(p);
                    break;
                }
                if (observer.interrupted()) {
                    killed = ProcessTree.killTree(p);
                    break;
                }
            }
        } catch (InterruptedException e) {
            ProcessTree.killTree(p);
            Thread.currentThread().interrupt();
            throw new IOException("ssh 后端等待远端时被中断", e);
        } finally {
            if (stdinPiper != null) {
                try {
                    stdinPiper.join(2000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            joinQuietly(to);
            joinQuietly(te);
            observer.finished(p);
        }
        int exit;
        try {
            exit = p.isAlive() ? -1 : p.exitValue();
        } catch (IllegalThreadStateException e) {
            exit = -1;
        }
        if (exit == 255 && !timedOut) {
            // ssh 自己 255 = 连不上/认证失败，不是远端命令的退出码 ⇒ 必须说出来，不许当成功
            String detail = err.render("");
            throw new ExecEnvException(ExecEnvException.Code.SSH_NO_CREDENTIALS,
                    "ssh 传输层失败（255）：" + (detail.isEmpty() ? "无 stderr" : head(detail, 200))
                            + " —— 显式失败，不换 local");
        }
        return ExecResult.builder(SSH)
                .exitCode(exit)
                .stdout(out.render(""))
                .stderr(err.render(""))
                .stdoutTruncated(out.truncated())
                .stderrTruncated(err.truncated())
                .stdoutTotalBytes(out.totalBytes())
                .stderrTotalBytes(err.totalBytes())
                .timedOut(timedOut)
                .killedDescendants(killed)
                .build();
    }

    @Override
    public void writeFile(String path, byte[] content) throws IOException {
        // 走 scp 的 argv 形态（同样不拼字符串）：远端路径由 checkedRemote 兜住
        String remote = checkedRemote(path);
        List<String> argv = new ArrayList<String>();
        argv.add(config.sshBin().endsWith("ssh") ? config.sshBin().replace("ssh", "scp") : config.sshBin());
        argv.add("-P");
        argv.add(String.valueOf(config.sshPort()));
        argv.add("-o");
        argv.add("BatchMode=yes");
        String identity = config.sshIdentityFile();
        if (!identity.isEmpty()) {
            argv.add("-i");
            argv.add(identity);
        }
        argv.add("-");
        String user = config.sshUser().trim();
        argv.add((user.isEmpty() ? "" : user + "@") + config.sshHost().trim() + ":" + remote);
        ProcessBuilder pb = new ProcessBuilder(argv);
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            if (content != null) {
                os.write(content);
            }
        }
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread t = pump(p.getErrorStream(), errBuf);
        boolean done;
        try {
            done = p.waitFor(30L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            ProcessTree.killTree(p);
            Thread.currentThread().interrupt();
            throw new IOException("scp 被中断，已端掉进程树");
        }
        if (!done) {
            ProcessTree.killTree(p);
            throw new IOException("scp 超时（>30s），已端掉进程树");
        }
        joinQuietly(t);
        if (p.exitValue() != 0) {
            throw new ExecEnvException(ExecEnvException.Code.SSH_NO_CREDENTIALS,
                    "scp 写文件失败 rc=" + p.exitValue() + " " + head(new String(errBuf.toByteArray(), UTF_8), 200));
        }
    }

    @Override
    public byte[] readFile(String path) throws IOException {
        String remote = checkedRemote(path);
        ExecRequest req = ExecRequest.builder("cat", "--", remote)
                .maxOutputBytes(config.outputMaxBytes())
                .timeoutMillis(config.defaultTimeoutMs())
                .build();
        ExecResult r = exec(req);
        if (r.exitCode() != 0) {
            throw new IOException("远端 cat 失败 rc=" + r.exitCode() + " " + head(r.stderr(), 200));
        }
        return r.stdout().getBytes(UTF_8);
    }

    /** 远端路径检查：与 local 后端同一套"不许越出沙箱根"的口径。 */
    String checkedRemote(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("path 不能为空");
        }
        String root = config.sshWorkdir();
        if (root == null || root.trim().isEmpty()) {
            root = sandboxRoot == null ? null : sandboxRoot.getPath();
        }
        if (root == null || root.trim().isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE,
                    "ssh 后端没有沙箱根（exec.env.ssh.workdir 没配），不敢拼路径");
        }
        String p = path.trim();
        if (p.contains("..")) {
            throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE,
                    "路径里不许有 ..（远端沙箱根 " + root + "）：「" + p + "」");
        }
        if (p.startsWith("/") && !p.startsWith(root)) {
            throw new ExecEnvException(ExecEnvException.Code.SANDBOX_ESCAPE,
                    "绝对路径不在远端沙箱根下：" + p);
        }
        String base = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return p.startsWith("/") ? p : base + "/" + p;
    }

    @Override
    public void close() throws IOException {
        // 没有常驻资源：每次 exec 一条 ssh 连接，收尾在 exec 的 finally 里
    }

    private static Thread drain(final InputStream in, final BoundedCollector sink) {
        Thread t = new Thread(new Runnable() {
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
                } catch (IOException ignored) {
                    // 流被强杀时读失败是预期内的，退出码/超时位会说明发生了什么
                }
            }
        }, "zbot-ssh-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Thread pump(final InputStream in, final ByteArrayOutputStream sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (sink.size() + n > 262144) {
                            return;
                        }
                        sink.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }, "zbot-ssh-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= n ? flat : flat.substring(0, n) + "…";
    }
}
