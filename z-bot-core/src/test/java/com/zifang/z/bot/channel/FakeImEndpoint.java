package com.zifang.z.bot.channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的假飞书/钉钉端点：只绑 {@code 127.0.0.1} + {@code bind(0)} 空闲端口。
 *
 * <p>本期没有真凭据（EVIDENCE §0-8），所以"真发验收"不存在；能验的是<b>发出去的字节</b>：
 * 每一次请求的 method / path / query / header / body 都原样记下来，测试对着这些断言协议形状，
 * 而不是对着日志。响应用 {@link #queueResponse} 脚本化，用来演"token 判废""平台报 chat not found"
 * 这些只能靠对端才能演出来的分支。</p>
 */
final class FakeImEndpoint implements AutoCloseable {

    /** 一次被收到的请求（header 名统一小写，query 已拆开）。 */
    static final class Recorded {
        final String method;
        final String path;
        final String rawQuery;
        final Map<String, String> query;
        final Map<String, String> headers;
        final String body;

        Recorded(String method, String path, String rawQuery, Map<String, String> query,
                 Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        /**
         * 服务端视角还原出来的请求原文（请求行 + 全部请求头 + body）。
         * query 用的是 {@code getRawQuery()} 未解码原文 —— 断言"签名这类必须精确上线的字段
         * 到底以什么字节过了线"，靠这个而不是靠 Java 对象里的值。
         */
        String rawDump() {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path);
            if (rawQuery != null && !rawQuery.isEmpty()) {
                sb.append('?').append(rawQuery);
            }
            sb.append(" HTTP/1.1\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            sb.append("\n").append(body);
            return sb.toString();
        }

        String header(String lowerName) {
            return headers.get(lowerName);
        }

        @Override
        public String toString() {
            return method + " " + path + " " + query + " " + headers + " body=" + body;
        }
    }

    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final HttpServer server;
    private final List<Recorded> requests =
            Collections.synchronizedList(new ArrayList<Recorded>());
    private final List<Reply> scripted = Collections.synchronizedList(new ArrayList<Reply>());
    private final AtomicInteger tokenFetches = new AtomicInteger();
    private final AtomicInteger messagePosts = new AtomicInteger();

    /** 没脚本时的缺省应答。 */
    private volatile String tokenOkBody = "{\"code\":0,\"msg\":\"ok\","
            + "\"tenant_access_token\":\"t-fake-0001\",\"expire\":3600}";
    private volatile String messageOkBody = "{\"code\":0,\"msg\":\"success\","
            + "\"data\":{\"message_id\":\"om_fake_0001\"}}";
    private volatile String robotOkBody = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    FakeImEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String base() {
        return "http://127.0.0.1:" + port();
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<Recorded>(requests);
        }
    }

    List<Recorded> requestsTo(String pathPrefix) {
        List<Recorded> out = new ArrayList<Recorded>();
        for (Recorded r : requests()) {
            if (r.path.startsWith(pathPrefix)) {
                out.add(r);
            }
        }
        return out;
    }

    int tokenFetches() {
        return tokenFetches.get();
    }

    int messagePosts() {
        return messagePosts.get();
    }

    void setTokenResponse(String body) {
        this.tokenOkBody = body;
    }

    void setMessageResponse(String body) {
        this.messageOkBody = body;
    }

    void setRobotResponse(String body) {
        this.robotOkBody = body;
    }

    /** 下一次请求（任意路径）改成这个应答：用来演 401 / errcode!=0。 */
    void queueResponse(int status, String body) {
        scripted.add(new Reply(status, body));
    }

    void clear() {
        requests.clear();
        scripted.clear();
        tokenFetches.set(0);
        messagePosts.set(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
            headers.put(e.getKey().toLowerCase(Locale.ROOT),
                    e.getValue().isEmpty() ? "" : e.getValue().get(0));
        }
        String body = readAll(ex.getRequestBody());
        requests.add(new Recorded(ex.getRequestMethod(), path,
                ex.getRequestURI().getRawQuery(), query, headers, body));

        Reply reply = scripted.isEmpty() ? null : scripted.remove(0);
        int status = 200;
        String out;
        if (reply != null) {
            status = reply.status;
            out = reply.body;
        } else if (path.startsWith("/auth/")) {
            out = tokenOkBody;
        } else if (path.startsWith("/im/")) {
            out = messageOkBody;
        } else if (path.startsWith("/robot/")) {
            out = robotOkBody;
        } else {
            out = "{\"code\":0,\"msg\":\"ok\"}";
        }
        if (path.startsWith("/auth/")) {
            tokenFetches.incrementAndGet();
        } else if (path.startsWith("/im/") || path.startsWith("/robot/")) {
            messagePosts.incrementAndGet();
        }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else if (!pair.isEmpty()) {
                out.put(pair, "");
            }
        }
        return out;
    }
}
