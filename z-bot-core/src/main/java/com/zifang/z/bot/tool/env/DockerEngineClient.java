package com.zifang.z.bot.tool.env;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 给 {@link DockerExecEnvironment} 用的最小 Docker Engine HTTP 客户端（P22）。
 *
 * <p>为什么手写：工单红线 6 = {@code pom.xml} 禁区，不许为 docker-java 加依赖。
 * 于是只有两条路 —— 裸 HTTP 或 CLI。这里 TCP 走裸 HTTP（{@code DOCKER_HOST=tcp://…}，
 * 假 dockerd 就是这条），unix socket 走 {@code curl --unix-socket}（真 daemon 那条，
 * 本机 daemon 没起 ⇒ 本期未验收）。</p>
 *
 * <p>所有请求都是 argv/URL 形态，不接受"把字符串拼进命令行"。</p>
 */
final class DockerEngineClient {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    enum Transport {
        TCP, UNIX_SOCKET_VIA_CURL
    }

    private final Transport transport;
    private final String host;
    private final int port;
    private final String socketPath;
    private final String curlBin;
    private final int timeoutMillis;

    private DockerEngineClient(Transport transport, String host, int port, String socketPath,
                               String curlBin, int timeoutMillis) {
        this.transport = transport;
        this.host = host;
        this.port = port;
        this.socketPath = socketPath;
        this.curlBin = curlBin;
        this.timeoutMillis = timeoutMillis;
    }

    static DockerEngineClient forHost(String dockerHost, int timeoutMillis) throws ExecEnvException {
        String v = dockerHost == null ? "" : dockerHost.trim();
        if (v.isEmpty()) {
            throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                    "exec.env.docker.host 没配（DOCKER_HOST 也没有），不猜、不降级");
        }
        if (v.startsWith("tcp://")) {
            String rest = v.substring("tcp://".length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            int colon = rest.lastIndexOf(':');
            String host = colon < 0 ? rest : rest.substring(0, colon);
            int port;
            try {
                port = colon < 0 ? 2375 : Integer.parseInt(rest.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                        "DOCKER_HOST 端口不是整数：" + v);
            }
            if (host.isEmpty()) {
                throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                        "DOCKER_HOST 没有主机名：" + v);
            }
            return new DockerEngineClient(Transport.TCP, host, port, null, null, timeoutMillis);
        }
        if (v.startsWith("unix://")) {
            String path = v.substring("unix://".length());
            if (path.isEmpty() || !path.startsWith("/")) {
                throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                        "unix:// 路径不合法：" + v);
            }
            return new DockerEngineClient(Transport.UNIX_SOCKET_VIA_CURL, null, 0, path,
                    "/usr/bin/curl", timeoutMillis);
        }
        throw new ExecEnvException(ExecEnvException.Code.DOCKER_UNAVAILABLE,
                "只认 tcp:// 与 unix:// 两种 DOCKER_HOST，实测：" + v);
    }

    Transport transport() {
        return transport;
    }

    String socketPath() {
        return socketPath;
    }

    /**
     * 发一个请求。
     *
     * @param method GET/POST/DELETE…
     * @param path   以 / 开头的 API 路径（含 query）
     * @param body   JSON 请求体，可为 null
     * @return 状态码 + 响应体
     * @throws IOException 连不上 / 非我这个服务（404 也算连上了，所以状态码必须由调用方判）
     */
    Response request(String method, String path, String body) throws IOException {
        if (transport == Transport.TCP) {
            return tcp(method, path, body);
        }
        return viaCurl(method, path, body);
    }

    private Response tcp(String method, String path, String body) throws IOException {
        URL url = new URL("http", host, port, path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMillis);
        c.setReadTimeout(timeoutMillis);
        c.setRequestProperty("Host", host + ":" + port);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.getBytes(UTF_8);
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            drainBounded(in, bos, 4L * 1024 * 1024);
        }
        String text = new String(bos.toByteArray(), UTF_8);
        String contentType = c.getHeaderField("Content-Type");
        c.disconnect();
        return new Response(code, bos.toByteArray(), text, contentType);
    }

    private Response viaCurl(String method, String path, String body) throws IOException {
        // argv 形态：--unix-socket <path> 单独成段，URL 只给 host 占位，绝不拼字符串
        List<String> argv = new ArrayList<String>();
        argv.add(curlBin);
        argv.add("-s");
        argv.add("-S");
        argv.add("--max-time");
        argv.add(String.valueOf(Math.max(1, timeoutMillis / 1000)));
        argv.add("--unix-socket");
        argv.add(socketPath);
        argv.add("-X");
        argv.add(method);
        argv.add("-w");
        argv.add("\n%{http_code}");
        argv.add("http://localhost" + path);
        if (body != null) {
            argv.add("-H");
            argv.add("Content-Type: application/json");
            argv.add("--data-binary");
            argv.add("@-");
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        if (body != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(body.getBytes(UTF_8));
            }
        } else {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // 子进程自己会收
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drainBounded(p.getInputStream(), out, 4L * 1024 * 1024);
        String err = readAll(p.getErrorStream());
        boolean finished = true;
        try {
            finished = p.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!finished) {
            ProcessTree.killTree(p);
            throw new IOException("curl 走 unix socket 超时（>" + 30 + "s），已端掉进程树");
        }
        if (p.exitValue() != 0) {
            throw new IOException("curl 失败 rc=" + p.exitValue() + " " + err.trim());
        }
        String text = new String(out.toByteArray(), UTF_8);
        int nl = text.lastIndexOf('\n');
        String codePart = nl < 0 ? "" : text.substring(nl + 1).trim();
        byte[] bodyBytes = nl < 0 ? out.toByteArray()
                : java.util.Arrays.copyOf(out.toByteArray(), nl);
        int code;
        try {
            code = Integer.parseInt(codePart);
        } catch (NumberFormatException e) {
            throw new IOException("curl 没回状态码（尾部实测：" + codePart + "）");
        }
        return new Response(code, bodyBytes, new String(bodyBytes, UTF_8), null);
    }

    private static void drainBounded(InputStream in, ByteArrayOutputStream sink, long cap) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > cap) {
                throw new IOException("响应体超过 " + cap + "B 上界");
            }
            sink.write(buf, 0, n);
            total += n;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 二进制体请求（archive API 的 tar 进 / tar 出、logs 的多路复用帧）。 */
    Response requestBytes(String method, String path, byte[] payload) throws IOException {
        if (transport == Transport.TCP) {
            URL url = new URL("http", host, port, path);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMillis);
            c.setReadTimeout(timeoutMillis);
            c.setRequestProperty("Host", host + ":" + port);
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                drainBounded(in, bos, 32L * 1024 * 1024);
            }
            String contentType = c.getHeaderField("Content-Type");
            c.disconnect();
            byte[] data = bos.toByteArray();
            return new Response(code, data, new String(data, UTF_8), contentType);
        }
        // unix socket：curl 的 --data-binary @- 收 stdin，响应按原文取回
        List<String> argv = new ArrayList<String>();
        argv.add(curlBin);
        argv.add("-s");
        argv.add("-S");
        argv.add("--max-time");
        argv.add(String.valueOf(Math.max(1, timeoutMillis / 1000)));
        argv.add("--unix-socket");
        argv.add(socketPath);
        argv.add("-X");
        argv.add(method);
        argv.add("-w");
        argv.add("\n%{http_code}");
        argv.add("http://localhost" + path);
        if (payload != null) {
            argv.add("-H");
            argv.add("Content-Type: application/octet-stream");
            argv.add("--data-binary");
            argv.add("@-");
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        Process p = pb.start();
        if (payload != null) {
            try (OutputStream os = p.getOutputStream()) {
                os.write(payload);
            }
        } else {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // 子进程自己会收
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drainBounded(p.getInputStream(), out, 32L * 1024 * 1024);
        String err = readAll(p.getErrorStream());
        boolean finished;
        try {
            finished = p.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            ProcessTree.killTree(p);
            Thread.currentThread().interrupt();
            throw new IOException("curl 被中断，已端掉进程树");
        }
        if (!finished) {
            ProcessTree.killTree(p);
            throw new IOException("curl 走 unix socket 超时（>30s），已端掉进程树");
        }
        if (p.exitValue() != 0) {
            throw new IOException("curl 失败 rc=" + p.exitValue() + " " + err.trim());
        }
        byte[] all = out.toByteArray();
        int nl = lastIndexOfByte(all, (byte) '\n');
        byte[] data = nl < 0 ? all : java.util.Arrays.copyOf(all, nl);
        String codePart = nl < 0 ? "" : new String(all, nl + 1, all.length - nl - 1, UTF_8).trim();
        int code;
        try {
            code = Integer.parseInt(codePart);
        } catch (NumberFormatException e) {
            throw new IOException("curl 没回状态码（尾部实测：" + codePart + "）");
        }
        return new Response(code, data, new String(data, UTF_8), null);
    }

    private static int lastIndexOfByte(byte[] b, byte target) {
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] == target) {
                return i;
            }
        }
        return -1;
    }

    static final class Response {
        private final int status;
        private final byte[] bytes;
        private final String body;
        private final String contentType;

        Response(int status, byte[] bytes, String body, String contentType) {
            this.status = status;
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.body = body == null ? "" : body;
            this.contentType = contentType;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        byte[] bytes() {
            return bytes;
        }

        String contentType() {
            return contentType;
        }

        /**
         * 判"是不是我这个服务"：404 对 curl/裸连也是成功，所以状态码 + 响应形状都要看。
         *
         * <p>注意 docker 的 {@code /_ping} 本来就是 {@code 200 + text/plain + "OK"}，
         * 不能拿"是不是 JSON"当判据；但回 HTML（{@code <} 开头）就说明后面是个网页不是 daemon。</p>
         */
        boolean looksLikeDockerJson(int... okStatuses) {
            for (int s : okStatuses) {
                if (status == s) {
                    return !body.trim().startsWith("<");
                }
            }
            return false;
        }
    }

    /** 只用于测试里造请求体时的顺序稳定（不做生产用途）。 */
    static String jsonOf(Map<String, Object> fields) {
        return JsonLite.write(fields);
    }

    static String newRunToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 用来在 EVIDENCE 里贴"我们到底发了什么"的字节数。 */
    static int utf8Len(String s) {
        return s.getBytes(UTF_8).length;
    }

    static void writeBigEndian(DataOutputStream dos, int v) throws IOException {
        dos.writeInt(v);
    }
}
