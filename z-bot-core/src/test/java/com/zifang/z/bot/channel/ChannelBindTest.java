package com.zifang.z.bot.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * P11c 监听地址守卫：缺省只绑回环，通配必须是显式 opt-in 才出现。
 *
 * <p>钉两层，缺一不可：① 每个通道**实际 socket** 绑在回环上（把任一处改回
 * {@code new InetSocketAddress(port)} 就要红）；② 显式写 {@code 0.0.0.0} 确实绑得到通配 ——
 * 少了这一层，① 会因为"代码里根本绑不出通配"而空转，永远测不出真东西。</p>
 *
 * <p>③ 是被占端口要**当场报错**而不是静默共存：这正是 roadmap §6 记的那次间歇 400 的成因
 * （通配监听能和邻居的 {@code 127.0.0.1:P} 共存，请求被邻居应答）。</p>
 */
public class ChannelBindTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<Runnable> cleanup = new ArrayList<Runnable>();
    private int folderSeq;

    @After
    public void tearDown() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (Exception ignored) {
                // 收口阶段的清理失败不该把被测结论盖掉
            }
        }
        cleanup.clear();
    }

    // ===== ① 缺省回环：四个通道各自钉一遍，红的时候能直接点名是哪个 =====

    @Test
    public void httpChannelDefaultsToLoopback() throws Exception {
        HttpChannel ch = http(0, null);
        assertLoopback("HttpChannel", ch.getBindAddress());
    }

    @Test
    public void webhookChannelDefaultsToLoopback() throws Exception {
        WebhookChannel ch = webhook(0, null);
        assertLoopback("WebhookChannel", ch.getBindAddress());
    }

    @Test
    public void feishuChannelDefaultsToLoopback() throws Exception {
        FeishuChannel ch = feishu(0, null);
        assertLoopback("FeishuChannel", ch.getBindAddress());
    }

    @Test
    public void dingTalkChannelDefaultsToLoopback() throws Exception {
        DingTalkChannel ch = dingTalk(0, null);
        assertLoopback("DingTalkChannel", ch.getBindAddress());
    }

    // ===== ② 显式 opt-in 真的能绑到通配（否则上面的守卫是空跑）=====

    @Test
    public void explicitAnyHostActuallyBindsWildcardForHttp() throws Exception {
        HttpChannel ch = http(0, ChannelBind.ANY);
        InetAddress addr = ch.getBindAddress();
        assertTrue("显式 " + ChannelBind.ANY + " 应绑到通配，实得: " + addr, addr.isAnyLocalAddress());
        assertFalse("通配监听不能还被判成回环: " + addr, addr.isLoopbackAddress());
    }

    @Test
    public void explicitAnyHostActuallyBindsWildcardForWebhook() throws Exception {
        WebhookChannel ch = webhook(0, ChannelBind.ANY);
        assertTrue("显式 " + ChannelBind.ANY + " 应绑到通配，实得: " + ch.getBindAddress(),
                ch.getBindAddress().isAnyLocalAddress());
    }

    /** 回环那侧的对照：同一次运行里两种绑法都出现，才说明守卫读的是真地址而不是常量。 */
    @Test
    public void loopbackDefaultAndWildcardOptInAreDistinguishable() throws Exception {
        HttpChannel def = http(0, null);
        HttpChannel any = http(0, ChannelBind.ANY);
        assertTrue(def.getBindAddress().isLoopbackAddress());
        assertTrue(any.getBindAddress().isAnyLocalAddress());
        assertFalse("同一套代码两种绑法必须给出不同地址",
                def.getBindAddress().equals(any.getBindAddress()));
    }

    @Test
    public void blankHostResolvesToLoopbackAndAnyResolvesToWildcard() throws Exception {
        assertTrue("null 应回环", ChannelBind.resolve(null).isLoopbackAddress());
        assertTrue("空串应回环", ChannelBind.resolve("").isLoopbackAddress());
        assertTrue("空白应回环", ChannelBind.resolve("   ").isLoopbackAddress());
        assertTrue("0.0.0.0 应通配", ChannelBind.resolve(ChannelBind.ANY).isAnyLocalAddress());
        assertEquals("127.0.0.1", ChannelBind.resolve("127.0.0.1").getHostAddress());
    }

    /** 横幅印的是实际监听地址：绑通配时还照旧写 127.0.0.1 就是把"LAN 可达"说成"只在本机"。 */
    @Test
    public void consoleUrlReportsTheActualBindNotAHardcodedLoopback() throws Exception {
        HttpChannel def = http(0, null);
        assertTrue("缺省横幅应是 127.0.0.1: " + def.consoleUrl(),
                def.consoleUrl().startsWith("http://127.0.0.1:"));
        HttpChannel any = http(0, ChannelBind.ANY);
        assertTrue("通配横幅应如实写出: " + any.consoleUrl(),
                any.consoleUrl().startsWith("http://" + ChannelBind.ANY + ":"));
    }

    // ===== ③ 端口被邻居占住要当场红，不能"启动成功但没人应答" =====

    @Test
    public void occupiedLoopbackPortFailsFastInsteadOfSilentlyCoexisting() throws Exception {
        ServerSocket neighbor = new ServerSocket();
        neighbor.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        cleanup.add(close(neighbor));
        int taken = neighbor.getLocalPort();
        assertTrue("量具自己没绑上回环，测不出结论", neighbor.isBound());

        HttpChannel ch = new HttpChannel(stubAgent(), taken, (String) null);
        try {
            ch.start();
            ch.stop();
            fail("127.0.0.1:" + taken + " 已被邻居占住，缺省绑回环应当场报错，而不是静默共存");
        } catch (BindException expected) {
            assertTrue("报错要说清是地址/端口被占: " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).toLowerCase().contains("address already in use"));
        }
    }

    @Test
    public void occupiedLoopbackPortAlsoFailsFastForWebhook() throws Exception {
        ServerSocket neighbor = new ServerSocket();
        neighbor.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        cleanup.add(close(neighbor));
        int taken = neighbor.getLocalPort();
        try {
            new WebhookChannel(null, taken, "webhook", null).start();
            fail("WebhookChannel 同样要报错: 127.0.0.1:" + taken);
        } catch (BindException expected) {
            assertTrue(String.valueOf(expected.getMessage()).toLowerCase().contains("address already in use"));
        }
    }

    // ===== helpers =====

    private void assertLoopback(String who, InetAddress addr) {
        assertTrue(who + " 缺省应绑回环，实得: " + addr, addr != null && addr.isLoopbackAddress());
        assertFalse(who + " 缺省不得是通配地址: " + addr, addr.isAnyLocalAddress());
    }

    private HttpChannel http(int port, String host) throws Exception {
        HttpChannel ch = new HttpChannel(stubAgent(), port, host);
        ch.start();
        cleanup.add(ch::stop);
        return ch;
    }

    private WebhookChannel webhook(int port, String host) throws Exception {
        WebhookChannel ch = new WebhookChannel(null, port, "webhook", host);
        ch.start();
        cleanup.add(ch::stop);
        return ch;
    }

    private FeishuChannel feishu(int port, String host) throws Exception {
        FeishuChannel ch = new FeishuChannel(null, port, "app", "sec", "v", null, null, host);
        ch.start();
        cleanup.add(ch::stop);
        return ch;
    }

    private DingTalkChannel dingTalk(int port, String host) throws Exception {
        DingTalkChannel ch = new DingTalkChannel(null, port, "https://example.invalid/x", "SEC", host);
        ch.start();
        cleanup.add(ch::stop);
        return ch;
    }

    private static Runnable close(final ServerSocket socket) {
        return new Runnable() {
            @Override public void run() {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    private BotAgent stubAgent() throws Exception {
        // 一个测试里会建多个 agent：TemporaryFolder 对同名目录会直接抛，所以逐个取唯一名
        int seq = ++folderSeq;
        return BotAgent.builder(null)
                .provider(new StubProvider())
                .providerCode("stub")
                .sandbox(new Sandbox(tmp.newFolder("sandbox-" + seq).getAbsolutePath()))
                .sessionManager(new SessionManager(tmp.newFolder("sessions-" + seq)))
                .model("test-model")
                .withoutBuiltinTools()
                .build();
    }

    /** 只做绑定的测试不会打到模型，但 BotAgent.builder 要求一个 provider。 */
    private static final class StubProvider implements LlmProvider {
        @Override public String name() {
            return "stub";
        }

        @Override public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override public boolean supportsModel(String model) {
            return true;
        }

        @Override public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            throw new UnsupportedOperationException("P11c 守卫不发模型请求");
        }

        @Override public void streamChat(ChatCompletionsRequest request,
                                         Consumer<ChatCompletionsResponse> onNext,
                                         Consumer<Throwable> onError) {
            throw new UnsupportedOperationException("P11c 守卫不发模型请求");
        }
    }
}
