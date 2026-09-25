package com.zifang.z.bot.channel;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Gateway} + {@link ChannelBus} 接线：注册两个 stub 通道，
 * 投递消息验证 bus → agent → channel.send() 的完整链路；
 * {@link PairingService} 注册到 gateway 后能 issue / consume。
 */
public class GatewayTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BotAgent prototype;
    private Gateway gw;
    private RecordingChannel chanA;
    private RecordingChannel chanB;

    @Before
    public void setUp() throws Exception {
        File sandboxDir = tmp.newFolder("sandbox");
        File sessionDir = tmp.newFolder("sessions");
        File configDir = tmp.newFolder("config");
        prototype = BotAgent.builder(BotConfig.load(configDir))
                .provider(new com.zifang.z.agent.kernel.llm.LlmProvider() {
                    @Override
                    public String name() { return "stub"; }
                    @Override public java.util.List<com.zifang.z.agent.kernel.llm.Model> listModels() { return java.util.Collections.emptyList(); }
                    @Override public boolean supportsModel(String m) { return true; }
                    @Override public com.zifang.z.agent.kernel.llm.ChatCompletionsResponse chat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r) {
                        return new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse("id", "stub",
                                java.util.Collections.singletonList(new com.zifang.z.agent.kernel.llm.ChatCompletionsResponse.Choice(0,
                                        "echo: " + r.getMessages().get(r.getMessages().size() - 1).getContent(),
                                        java.util.Collections.<com.zifang.z.agent.kernel.message.ToolCall>emptyList(), "stop")),
                                new com.zifang.z.agent.kernel.types.TokenUsage(5L, 3L, 8L), "stop", null);
                    }
                    @Override public void streamChat(com.zifang.z.agent.kernel.llm.ChatCompletionsRequest r,
                                                     java.util.function.Consumer<com.zifang.z.agent.kernel.llm.ChatCompletionsResponse> on,
                                                     java.util.function.Consumer<Throwable> err) {
                        on.accept(chat(r));
                    }
                })
                .sandbox(new Sandbox(sandboxDir.getAbsolutePath()))
                .sessionManager(new SessionManager(sessionDir))
                .model("stub")
                .withoutCenter()
                .build();

        chanA = new RecordingChannel("chanA");
        chanB = new RecordingChannel("chanB");
        gw = new Gateway(prototype);
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.stop();
        }
    }

    @Test
    public void registerAndStartAllChannels() throws Exception {
        gw.register(chanA);
        gw.register(chanB);
        gw.start();
        assertTrue(chanA.started);
        assertTrue(chanB.started);
        assertTrue(gw.isRunning());
        java.util.Map<String, Object> status = gw.status();
        assertEquals(2, ((java.util.List<?>) status.get("channels")).size());
    }

    @Test
    public void deliverRoutesReplyToSourceChannel() throws Exception {
        gw.register(chanA);
        gw.register(chanB);
        gw.start();

        gw.bus().deliver(new ChannelMessage("chanA", "c-1", "alice", "hello"));
        waitFor(() -> !chanA.sent.isEmpty(), 3000);
        assertEquals(1, chanA.sent.size());
        assertEquals("c-1", chanA.sent.get(0).replyTo);
        assertTrue(chanA.sent.get(0).text.contains("echo:"));
    }

    @Test
    public void historyRecordsEachDelivery() throws Exception {
        gw.register(chanA);
        gw.start();
        gw.bus().deliver(new ChannelMessage("chanA", "c1", "alice", "hi"));
        gw.bus().deliver(new ChannelMessage("chanA", "c2", "alice", "there"));
        waitFor(() -> gw.bus().history().size() >= 2, 3000);
        assertEquals(2, gw.bus().history().size());
    }

    @Test
    public void pairingServiceCanBeRegisteredAndUsed() throws Exception {
        File pairingFile = new File(tmp.getRoot(), "pairing.json");
        PairingService pairing = new PairingService(pairingFile);
        Gateway gw2 = new Gateway(prototype, pairing);
        gw2.register(chanA);
        try {
            gw2.start();
            PairingService.Pairing p = pairing.issue("chanA", "c1", "alice");
            assertNotNull(p.code);
            assertEquals(8, p.code.length());
            PairingService.Pairing consumed = pairing.consume(p.code);
            assertEquals("c1", consumed.conversationId);
        } finally {
            gw2.stop();
        }
    }

    @Test
    public void evictConversationDropsCachedAgent() throws Exception {
        gw.register(chanA);
        gw.start();
        gw.bus().deliver(new ChannelMessage("chanA", "c-x", "alice", "hi"));
        waitFor(() -> !chanA.sent.isEmpty(), 3000);
        gw.bus().evictConversation("c-x");
        // 没有对外可见副作用，但确保不抛
        gw.bus().deliver(new ChannelMessage("chanA", "c-x", "alice", "again"));
        waitFor(() -> chanA.sent.size() >= 2, 3000);
        assertTrue(chanA.sent.size() >= 2);
    }

    // ===== helpers =====

    private static void waitFor(java.util.function.BooleanSupplier cond, long maxMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static final class RecordingChannel implements Channel {
        final String name;
        boolean started;
        final List<OutboundMessage> sent = java.util.Collections.synchronizedList(new ArrayList<OutboundMessage>());

        RecordingChannel(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public void start() { started = true; }
        @Override public void stop() {}
        @Override public void awaitTermination() {}
        @Override public boolean isRunning() { return started; }
        @Override public void send(OutboundMessage message) { sent.add(message); }
    }
}