package com.zifang.z.bot.mcp;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 握手预算与单请求预算必须是<b>两条</b>预算。
 *
 * <p>此前 {@code ZBotStdioMcpTransport} 拿同一个 {@code timeoutMillis} 同时管 initialize
 * 握手和之后的每一次调用。stdio server 的握手等的是冷启动（exec、解释器起来、import），
 * 于是"单次调用上限 900ms"这种配置会把一个正常要 1 秒才起来的 server 永久挡在门外 ——
 * 而且这条守卫在空机上永远绿，只在机器忙时红：{@code McpRealStdioServerTest} 里那支
 * 900ms 的 deadline 用例就是这么在 load average 11 的机器上连红两跑的。</p>
 *
 * <p>两头各钉一次，缺一头就是空跑：握手侧证"900ms 掐不到 1.5s 的冷启动"，
 * 调用侧证"900ms 仍然真的掐" —— 否则把两条预算一起抬到 30s 也能骗过第一头。</p>
 */
public class McpHandshakeTimeoutTest {

    private static final long REQUEST_TIMEOUT_MILLIS = 900L;
    private static final double HANDSHAKE_DELAY_SECONDS = 1.5D;
    private static final long HANDSHAKE_DELAY_MILLIS = 1_500L;

    private ZBotStdioMcpTransport transport;

    @After
    public void closeTransport() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    /** 起一支"睡够才回 initialize、之后什么都不回"的假 server，并握完手。 */
    private ZBotStdioMcpTransport connectSlowHandshakeServer(long requestTimeoutMillis)
            throws Exception {
        File server = RealMcpHarness.file("p21_slow_handshake_server.py");
        ZBotStdioMcpTransport.Options o = new ZBotStdioMcpTransport.Options()
                .serverName("slow-handshake")
                .command(new ArrayList<String>(Arrays.asList(
                        RealMcpHarness.python(), "-u", server.getAbsolutePath(),
                        String.valueOf(HANDSHAKE_DELAY_SECONDS))))
                .timeoutMillis(requestTimeoutMillis)
                .parentWatchdog(false);
        transport = new ZBotStdioMcpTransport(o);
        transport.open();
        return transport;
    }

    @Test
    public void handshakeOutlivesATightRequestTimeout() throws Exception {
        long t0 = System.currentTimeMillis();
        connectSlowHandshakeServer(REQUEST_TIMEOUT_MILLIS);
        long elapsed = System.currentTimeMillis() - t0;
        // 阳性对照：假 server 真睡过，否则这条断言什么都没说
        assertTrue("握手只用了 " + elapsed + "ms，比假 server 自带的 " + HANDSHAKE_DELAY_MILLIS
                + "ms 延迟还短 ⇒ 延迟没生效，这一例是空跑", elapsed >= HANDSHAKE_DELAY_MILLIS - 300L);
        assertTrue("握手实测 " + elapsed + "ms 却仍被 " + REQUEST_TIMEOUT_MILLIS
                + "ms 的单请求预算掐了 ⇒ initialize 还在吃 timeoutMillis", elapsed < 30_000L);
    }

    @Test
    public void requestStillDiesOnItsOwnTightTimeout() throws Exception {
        ZBotStdioMcpTransport t = connectSlowHandshakeServer(REQUEST_TIMEOUT_MILLIS);
        long t0 = System.currentTimeMillis();
        try {
            t.request(McpWire.request(7L, "slow_probe", Collections.<String, Object>emptyMap()));
            fail("server 永远不回话而 request 竟然返回了 ⇒ 单请求预算被抬到握手的 30s 去了");
        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue("超时读数 " + elapsed + "ms 不贴着 " + REQUEST_TIMEOUT_MILLIS
                    + "ms ⇒ 单请求 deadline 又变成摆设了",
                    elapsed >= REQUEST_TIMEOUT_MILLIS - 100L && elapsed < 3_000L);
            assertTrue("错误里该带上是哪个方法超时，实得: " + e.getMessage(),
                    e.getMessage().contains("slow_probe"));
        }
    }
}
