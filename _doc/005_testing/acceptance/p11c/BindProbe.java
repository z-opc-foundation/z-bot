import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 机制探针：本机已有"别人"显式监听 127.0.0.1:P 时，
 * 我们的 wildcard 绑定（HttpChannel/Webhook/Feishu 用的 new InetSocketAddress(port)）会不会
 * ① 绑定成功（即 OS 允许共存），② 客户端连 127.0.0.1:P 时被路由到"别人"那边。
 * 对照组：显式绑 127.0.0.1 的 ServerSocket 能不能抢同一个 P。
 */
public final class BindProbe {

    public static void main(String[] args) throws Exception {
        // 1) 让"别人"先占住 127.0.0.1:P（显式 specific 绑定）
        ServerSocket foreign = new ServerSocket();
        foreign.setReuseAddress(true);
        foreign.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 5);
        int p = foreign.getLocalPort();
        Thread echo = new Thread(() -> {
            try {
                while (true) {
                    Socket s = foreign.accept();
                    OutputStream os = s.getOutputStream();
                    String body = "FOREIGN";
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/x-foreign\r\nContent-Length: "
                            + body.length() + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    s.close();
                }
            } catch (Exception ignored) {
            }
        });
        echo.setDaemon(true);
        echo.start();
        System.out.println("foreign specific listener: 127.0.0.1:" + p);

        // 2) 我们的通道怎么绑？wildcard(=产品现状) vs loopback(=候选修法)
        reportWildcard(p);
        reportLoopback(p);
    }

    private static void reportWildcard(int p) {
        HttpServer s = null;
        try {
            s = HttpServer.create(new InetSocketAddress(p), 0);   // 产品现状：wildcard + SO_REUSEADDR
            s.createContext("/", ex -> {
                byte[] b = "OURS".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            System.out.println("[wildcard] bind 成功 -> 共存成立");
            System.out.println("[wildcard] 客户端连 127.0.0.1:" + p + " 拿到 -> " + whoAnswers(p));
        } catch (Exception e) {
            System.out.println("[wildcard] bind 失败: " + e);
            return;
        } finally {
            if (s != null) s.stop(0);
        }
    }

    private static String whoAnswers(int p) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + p + "/bot/tools").openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        int code = c.getResponseCode();
        java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] buf = new byte[64];
        int n = is == null ? -1 : is.read(buf);
        String body = n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        c.disconnect();
        return "code=" + code + " ct=" + c.getContentType() + " body=" + body;
    }

    private static void reportLoopback(int p) {
        HttpServer s = null;
        try {
            s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 0);
            s.start();
            System.out.println("[loopback] bind 成功 -> 修法不成立（也能共存）");
        } catch (Exception e) {
            System.out.println("[loopback] bind 失败(预期 EADDRINUSE): " + e);
        } finally {
            if (s != null) s.stop(0);
        }
    }
}
