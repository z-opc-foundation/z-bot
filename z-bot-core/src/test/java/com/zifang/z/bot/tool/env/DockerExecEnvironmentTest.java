package com.zifang.z.bot.tool.env;

import com.zifang.z.bot.tool.Sandbox;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P22 docker 后端：用<b>本地假 dockerd</b>（只听 127.0.0.1，口由 {@code bind(0)} 取空闲）
 * 把"我们到底发什么"逐字段钉死。
 *
 * <p>本机真 daemon 没起（原始报错见 {@code EVIDENCE.md} §0.5），所以这里钉的是<b>请求面</b>；
 * "真 daemon 全链路"在 EVIDENCE §3 明确记 <b>未验收</b>，不拿单测绿冒充真跑。</p>
 *
 * <p>刻意不用 JDK 的 {@code com.sun.net.httpserver}：它 {@code stop()} 能永久挂死在
 * {@code preClose0}（本战役已知坑，SIGKILL 都停在 {@code ?E}）。这里手搓 {@code ServerSocket}，
 * 收尾 = 关监听口 + 关在活连接 + 带 deadline 的 join。</p>
 */
public class DockerExecEnvironmentTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String CID = "fakecontainerid0001";

    // ===== 假 dockerd =====

    private static final class Recorded {
        final String method;
        final String path;
        final byte[] body;

        Recorded(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        String bodyText() {
            return new String(body, UTF_8);
        }
    }

    private static final class FakeDockerd {
        final List<Recorded> requests = new CopyOnWriteArrayList<Recorded>();
        private final List<Socket> live = new CopyOnWriteArrayList<Socket>();
        private final AtomicInteger inspects = new AtomicInteger(0);
        private final ServerSocket server;
        private final Thread acceptor;
        volatile int pingStatus = 200;
        volatile boolean neverExit = false;

        FakeDockerd() throws IOException {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!server.isClosed()) {
                        try {
                            final Socket s = server.accept();
                            live.add(s);
                            Thread t = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    serve(s);
                                }
                            }, "fake-dockerd-conn");
                            t.setDaemon(true);
                            t.start();
                        } catch (IOException e) {
                            return;
                        }
                    }
                }
            }, "fake-dockerd-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String dockerHost() {
            return "tcp://127.0.0.1:" + server.getLocalPort();
        }

        private void serve(Socket s) {
            try {
                InputStream in = s.getInputStream();
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.trim().isEmpty()) {
                    return;
                }
                String[] head = requestLine.split(" ");
                String method = head[0];
                String path = head.length > 1 ? head[1] : "/";
                int contentLength = 0;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int c = line.indexOf(':');
                    if (c > 0 && "Content-Length".equalsIgnoreCase(line.substring(0, c).trim())) {
                        contentLength = Integer.parseInt(line.substring(c + 1).trim());
                    }
                }
                byte[] body = new byte[contentLength];
                int got = 0;
                while (got < contentLength) {
                    int n = in.read(body, got, contentLength - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
                requests.add(new Recorded(method, path, body));
                respond(s, method, path);
            } catch (IOException ignored) {
                // 客户端断了就断了
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 收尾
                }
                live.remove(s);
            }
        }

        private void respond(Socket s, String method, String path) throws IOException {
            if (path.endsWith("/_ping")) {
                if (pingStatus == 200) {
                    write(s, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n", "OK".getBytes(UTF_8));
                } else {
                    write(s, "HTTP/1.1 " + pingStatus + " Nope\r\nContent-Type: text/html\r\n",
                            "<html>not a docker endpoint</html>".getBytes(UTF_8));
                }
                return;
            }
            if (path.contains("/containers/create")) {
                write(s, "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n",
                        ("{\"Id\":\"" + CID + "\",\"Warnings\":[]}").getBytes(UTF_8));
                return;
            }
            if (path.endsWith("/start")) {
                write(s, "HTTP/1.1 204 No Content\r\nContent-Type: application/json\r\n", new byte[0]);
                return;
            }
            if (path.endsWith("/kill")) {
                write(s, "HTTP/1.1 204 No Content\r\nContent-Type: application/json\r\n", new byte[0]);
                return;
            }
            if (path.endsWith("/json") && path.contains("/containers/")) {
                boolean running = neverExit || inspects.incrementAndGet() == 1;
                // ExitCode 只写在 State 里（真 daemon 就是这么回的），别让解析器撞上两处同名字段
                write(s, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n",
                        ("{\"Id\":\"" + CID + "\",\"State\":{\"Status\":\""
                                + (running ? "running" : "exited") + "\",\"Running\":" + running
                                + ",\"ExitCode\":" + (running ? 0 : 7) + "}}")
                                .getBytes(UTF_8));
                return;
            }
            if (path.contains("/logs")) {
                byte[] out = muxFrame(1, "p22-docker-stdout\n");
                byte[] err = muxFrame(2, "p22-docker-stderr\n");
                byte[] both = new byte[out.length + err.length];
                System.arraycopy(out, 0, both, 0, out.length);
                System.arraycopy(err, 0, both, out.length, err.length);
                write(s, "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.docker.raw-stream\r\n", both);
                return;
            }
            if (path.contains("/archive") && "PUT".equals(method)) {
                write(s, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n",
                        "{\"msg\":\"ok\"}".getBytes(UTF_8));
                return;
            }
            if (path.contains("/archive") && "GET".equals(method)) {
                write(s, "HTTP/1.1 200 OK\r\nContent-Type: application/x-tar\r\n",
                        tarWithFile("payload.txt", "从容器读回来的内容\n"));
                return;
            }
            if (path.contains("/containers/json")) {
                write(s, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n",
                        "[{\"Id\":\"orphanfromlabelscan\"}]".getBytes(UTF_8));
                return;
            }
            if ("DELETE".equals(method)) {
                write(s, "HTTP/1.1 204 No Content\r\nContent-Type: application/json\r\n", new byte[0]);
                return;
            }
            write(s, "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\n",
                    "<html>not a docker endpoint</html>".getBytes(UTF_8));
        }

        private void write(Socket s, String headers, byte[] payload) throws IOException {
            OutputStream os = s.getOutputStream();
            // 每条响应都关连接：HttpURLConnection 会复用 socket，假服务不关就会串到下一笔请求上
            os.write((headers + "Connection: close\r\n").getBytes(UTF_8));
            os.write(("Content-Length: " + payload.length + "\r\n\r\n").getBytes(UTF_8));
            os.write(payload);
            os.flush();
        }

        List<String> paths() {
            List<String> out = new ArrayList<String>();
            for (Recorded r : requests) {
                out.add(r.method + " " + r.path);
            }
            return out;
        }

        Recorded find(String method, String pathContains) {
            for (Recorded r : requests) {
                if (r.method.equals(method) && r.path.contains(pathContains)) {
                    return r;
                }
            }
            return null;
        }

        boolean saw(String method, String pathContains) {
            return find(method, pathContains) != null;
        }

        void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关不掉也已经是死口了
            }
            for (Socket s : live) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 同上
                }
            }
            try {
                acceptor.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ===== 帧/编码小工具（与被测类同款口径，但独立实现，免得自证） =====

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return new String(bos.toByteArray(), UTF_8);
    }

    private static byte[] muxFrame(int stream, String text) {
        byte[] t = text.getBytes(UTF_8);
        byte[] f = new byte[8 + t.length];
        f[0] = (byte) stream;
        f[4] = (byte) ((t.length >> 24) & 0xFF);
        f[5] = (byte) ((t.length >> 16) & 0xFF);
        f[6] = (byte) ((t.length >> 8) & 0xFF);
        f[7] = (byte) (t.length & 0xFF);
        System.arraycopy(t, 0, f, 8, t.length);
        return f;
    }

    private static byte[] tarWithFile(String name, String content) {
        byte[] body = content.getBytes(UTF_8);
        byte[] header = new byte[512];
        byte[] nb = name.getBytes(UTF_8);
        System.arraycopy(nb, 0, header, 0, Math.min(nb.length, 100));
        octal(header, 100, 8, 0644);
        octal(header, 124, 12, body.length);
        header[156] = '0';
        System.arraycopy("ustar".getBytes(UTF_8), 0, header, 257, 5);
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        octal(header, 148, 8, sum);
        int padded = ((body.length + 511) / 512) * 512;
        byte[] out = new byte[512 + padded + 1024];
        System.arraycopy(header, 0, out, 0, 512);
        System.arraycopy(body, 0, out, 512, body.length);
        return out;
    }

    private static void octal(byte[] target, int offset, int width, long value) {
        String s = Long.toOctalString(value);
        while (s.length() < width - 1) {
            s = "0" + s;
        }
        byte[] b = s.getBytes(UTF_8);
        System.arraycopy(b, 0, target, offset, Math.min(b.length, width - 1));
        target[offset + width - 1] = ' ';
    }

    private static Properties props(FakeDockerd d, String dir) {
        Properties p = new Properties();
        p.setProperty(ExecEnvConfig.KEY_BACKEND, "docker");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_HOST, d.dockerHost());
        p.setProperty(ExecEnvConfig.KEY_DOCKER_IMAGE, "busybox:1.36");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_LEDGER, new File(dir, "ledger.properties").getPath());
        p.setProperty(ExecEnvConfig.KEY_DOCKER_WORKDIR, "/workspace");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_MEMORY_MB, "256");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_CPUS, "0.5");
        p.setProperty(ExecEnvConfig.KEY_DOCKER_PIDS_LIMIT, "64");
        return p;
    }

    // ===== 用例 =====

    @Test
    public void dockerSendsArgvNotAStringAndCarriesTheResourceLimits() throws Exception {
        String dir = scratch("p22-docker-req");
        FakeDockerd d = new FakeDockerd();
        try {
            DockerExecEnvironment env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            try {
                ExecResult r = env.exec(ExecRequest.builder("/bin/echo", "hello docker").build());
                assertEquals("假 dockerd 第二拍报 ExitCode=7", 7, r.exitCode());
                assertTrue("输出要从复用帧里拆出来，实测 " + r.stdout(),
                        r.stdout().contains("p22-docker-stdout"));
                assertTrue("stderr 也要拆出来（没合并那一路）", r.stderr().contains("p22-docker-stderr"));
                Recorded create = d.find("POST", "/containers/create");
                assertNotNull("没发 containers/create", create);
                String body = create.bodyText();
                assertTrue("Cmd 必须是数组：拼接过的字符串就是注入面。实测 " + brief(body),
                        body.contains("\"Cmd\":[\"/bin/echo\",\"hello docker\"]"));
                assertFalse("出现 \"Cmd\":\"…\" 就说明拼成一条字符串了", body.contains("\"Cmd\":\""));
                assertTrue(body.contains("\"Image\":\"busybox:1.36\""));
                assertTrue("资源限额三项都得在：" + brief(body), body.contains("\"Memory\":268435456")
                        && body.contains("\"NanoCpus\":500000000") && body.contains("\"PidsLimit\":64"));
                assertTrue("缺省断网：" + brief(body), body.contains("\"NetworkMode\":\"none\""));
                assertTrue("安全基线（hermes docker.py 承诺的 cap-drop ALL / no-new-privileges）"
                        + brief(body),
                        body.contains("\"CapDrop\":[\"ALL\"]")
                                && body.contains("\"SecurityOpt\":[\"no-new-privileges:true\"]"));
                assertTrue("必须贴 label（孤儿回收只认这个）：" + brief(body),
                        body.contains(DockerExecEnvironment.LABEL_MANAGED)
                                && body.contains(DockerExecEnvironment.LABEL_OWNER_PID)
                                && body.contains(DockerExecEnvironment.LABEL_RUN));
                assertNotNull("没 start", d.find("POST", "/start"));
                assertNotNull("没查状态", d.find("GET", "/containers/" + CID + "/json"));
                assertNotNull("没取日志", d.find("GET", "/logs"));
                Recorded del = d.find("DELETE", "/containers/" + CID);
                assertNotNull("收尾没 DELETE ⇒ 就是这么留下孤儿容器的", del);
                assertTrue("DELETE 必须 force：" + del.path, del.path.contains("force=true"));
                List<String> paths = d.paths();
                assertTrue("第一件事得是 _ping（先确认是 docker 再说别的）：" + paths,
                        paths.get(0).endsWith("/_ping"));
                assertTrue("_ping 之后才是 create：" + paths,
                        paths.indexOf(paths.get(1)) == 1 && paths.get(1).startsWith("POST "));
            } finally {
                env.close();
            }
        } finally {
            d.close();
        }
    }

    @Test
    public void dockerTimeoutStillKillsAndReclaimsTheContainer() throws Exception {
        String dir = scratch("p22-docker-timeout");
        FakeDockerd d = new FakeDockerd();
        d.neverExit = true;
        try {
            DockerExecEnvironment env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            long t0 = System.currentTimeMillis();
            ExecResult r = env.exec(ExecRequest.builder("sleep", "999").timeoutMillis(400L).build());
            long cost = System.currentTimeMillis() - t0;
            assertTrue("必须标超时", r.timedOut());
            assertTrue("超时收尾要有上限（实测 " + cost + "ms）", cost < 15000L);
            assertTrue("超时必须先 kill：实测 " + d.paths(), d.saw("POST", "/kill"));
            assertTrue("超时之后也必须 DELETE，否则就是 running 孤儿容器：实测 " + d.paths(),
                    d.saw("DELETE", "/containers/" + CID));
            env.close();
        } finally {
            d.close();
        }
    }

    @Test
    public void dockerReapsLedgerOrphansLeftByADeadProcess() throws Exception {
        String dir = scratch("p22-docker-ledger");
        File ledger = new File(dir, "ledger.properties");
        Properties seed = new Properties();
        seed.setProperty(DockerExecEnvironment.LEDGER_PREFIX + "orphanfromdeadpid", "deadrun@999999999");
        try (OutputStream os = new java.io.FileOutputStream(ledger)) {
            seed.store(os, "test seed：上一个进程被 kill 之后留下的");
        }
        FakeDockerd d = new FakeDockerd();
        try {
            DockerExecEnvironment env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            int n = env.reapOrphans();
            assertTrue("台账那条孤儿必须被删（这次删了 " + n + "）", n >= 1);
            assertTrue("DELETE 必须带 force：实测 " + d.paths(), d.paths().toString().contains("force=true"));
            assertFalse("删干净之后台账里不该再有它", env.ledgerIds().contains("orphanfromdeadpid"));
            assertTrue("label 兜底扫描也要发（只认自己贴过的 label）：实测 " + d.paths(),
                    d.saw("GET", "/containers/json"));
            env.close();
        } finally {
            d.close();
        }
    }

    @Test
    public void dockerTreatsConnectedButNotDockerAsFailure() throws Exception {
        String dir = scratch("p22-docker-404");
        FakeDockerd d = new FakeDockerd();
        d.pingStatus = 404;
        try {
            try {
                new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
                fail("_ping 回 404 必须 DOCKER_BAD_RESPONSE：连上了不等于\"是我那个服务\"（curl 对 404 也返回 0）");
            } catch (ExecEnvException e) {
                assertEquals(ExecEnvException.Code.DOCKER_BAD_RESPONSE, e.code());
            }
        } finally {
            d.close();
        }
    }

    @Test
    public void dockerRefusesStdinInsteadOfQuietlySwitchingToAnotherBackend() throws Exception {
        String dir = scratch("p22-docker-stdin");
        FakeDockerd d = new FakeDockerd();
        DockerExecEnvironment env = null;
        try {
            env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            assertFalse(env.supportsStdin());
            try {
                env.exec(ExecRequest.builder("cat").stdin("x".getBytes(UTF_8)).build());
                fail("docker 后端收了 stdin 就说明它偷偷换实现（应该显式说不支持）");
            } catch (ExecEnvException e) {
                assertEquals(ExecEnvException.Code.CAPABILITY_UNSUPPORTED, e.code());
            }
        } finally {
            if (env != null) {
                env.close();
            }
            d.close();
        }
    }

    @Test
    public void dockerFileOpsGoThroughArchiveApi() throws Exception {
        String dir = scratch("p22-docker-files");
        FakeDockerd d = new FakeDockerd();
        DockerExecEnvironment env = null;
        try {
            env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            env.writeFile("out/payload.txt", "写进去的内容\n".getBytes(UTF_8));
            Recorded put = d.find("PUT", "/archive");
            assertNotNull("写文件必须走 archive", put);
            assertTrue("path 参数要指向沙箱根内：" + put.path,
                    put.path.contains("path=%2Fworkspace%2Fout"));
            String name = new String(put.body, 0, Math.min(put.body.length, 100), UTF_8);
            assertTrue("请求体是 tar（前 100 字节里应有文件名），实测 " + name,
                    name.contains("payload.txt"));
            byte[] read = env.readFile("out/payload.txt");
            assertEquals("从容器读回来的内容", new String(read, UTF_8).trim());
            assertNotNull("文件操作要先有一条常驻会话容器", d.find("POST", "-session"));
        } finally {
            if (env != null) {
                env.close();
            }
            d.close();
        }
    }

    @Test
    public void dockerRejectsPathOutsideItsSandboxRoot() throws Exception {
        String dir = scratch("p22-docker-escape");
        FakeDockerd d = new FakeDockerd();
        DockerExecEnvironment env = null;
        try {
            env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            try {
                env.checkedRemote("../../etc/passwd");
                fail("远端越界路径必须被拒（这条红了就是越界检查被摘掉）");
            } catch (ExecEnvException e) {
                assertEquals(ExecEnvException.Code.SANDBOX_ESCAPE, e.code());
            }
            try {
                env.checkedRemote("/etc/passwd");
                fail("沙箱根外的绝对路径也必须拒");
            } catch (ExecEnvException e) {
                assertEquals(ExecEnvException.Code.SANDBOX_ESCAPE, e.code());
            }
            assertEquals("/workspace/a.txt", env.checkedRemote("a.txt"));
        } finally {
            if (env != null) {
                env.close();
            }
            d.close();
        }
    }

    @Test
    public void dockerContainerIsLedgeredBeforeItStarts() throws Exception {
        String dir = scratch("p22-docker-ledgerlive");
        FakeDockerd d = new FakeDockerd();
        DockerExecEnvironment env = null;
        try {
            env = new DockerExecEnvironment(ExecEnvConfig.of(props(d, dir)));
            String id = env.createContainer(ExecRequest.builder("true").build());
            assertTrue("容器还没起就得进台账（此刻被 SIGKILL 才捞得回来）", env.ledgerIds().contains(id));
            assertTrue("台账必须真落盘", new File(dir, "ledger.properties").isFile());
            env.deleteContainer(id, true);
            assertTrue(env.ledgerIds().isEmpty());
        } finally {
            if (env != null) {
                env.close();
            }
            d.close();
        }
    }

    @Test
    public void dockerFrameParserOnlyAcceptsRealDockerFrames() {
        assertFalse(DockerExecEnvironment.looksMultiplexed("plain text output".getBytes(UTF_8)));
        assertTrue(DockerExecEnvironment.looksMultiplexed(muxFrame(1, "abc")));
        byte[] bogus = muxFrame(1, "abcdef");
        bogus[7] = 127;   // 长度字段谎报比响应体还长 ⇒ 不能当帧解
        assertFalse(DockerExecEnvironment.looksMultiplexed(bogus));
    }

    @Test
    public void dockerKeepsOutputBounded() throws Exception {
        String dir = scratch("p22-docker-bound");
        Properties p = props(new FakeDockerd(), dir);
        p.setProperty(ExecEnvConfig.KEY_OUTPUT_MAX_BYTES, "8");
        FakeDockerd d = new FakeDockerd();
        Properties tight = props(d, dir);
        tight.setProperty(ExecEnvConfig.KEY_OUTPUT_MAX_BYTES, "12");
        DockerExecEnvironment env = null;
        try {
            env = new DockerExecEnvironment(ExecEnvConfig.of(tight));
            ExecResult r = env.exec(ExecRequest.builder("true").build());
            assertTrue("上界必须生效：实测 stdout 长度 " + r.stdout().length(),
                    r.stdout().length() <= 12);
            assertTrue("截断要如实标出来", r.stdoutTotalBytes() > 0);
        } finally {
            if (env != null) {
                env.close();
            }
            d.close();
        }
    }

    @Test
    public void localSandboxHelperIsStillTheSameOne() throws Exception {
        String dir = scratch("p22-docker-sbx");
        Sandbox sb = new Sandbox(dir);
        assertEquals(new File(dir).getCanonicalPath(), sb.root().getCanonicalPath());
        try {
            sb.resolve("../x");
            fail("Sandbox 的越界检查被摘掉了");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("沙箱"));
        }
    }

    @Test
    public void exceptionCodeIsVisibleThroughIoExceptionContract() {
        ExecEnvException e = new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED, "x");
        assertTrue(e instanceof IOException);
        assertEquals("BACKEND_SELECTION_FAILED", e.codeName());
    }

    private static String scratch(String label) {
        File base = new File(System.getProperty("zbot.p22.scratch",
                new File(System.getProperty("user.home"), ".cache/zbot-p22-lead/test").getAbsolutePath()), label);
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IllegalStateException("建不了临时目录：" + base);
        }
        return base.getAbsolutePath();
    }

    private static String brief(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "…";
    }
}
