package com.zifang.z.bot.cron;

import com.zifang.z.bot.channel.Channel;
import com.zifang.z.bot.channel.OutboundMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P17 的投递闭环：{@link CronDelivery} 的三个落地 —— {@code local}（打印）/ {@code origin} /
 * {@code <通道名>[:会话]} / {@code all}，以及"没有来源时降级 local 而不是报错"（hermes #43014 的结论）。
 */
public class CronDeliveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cronDir;

    @Before
    public void setUp() throws Exception {
        cronDir = tmp.newFolder("cron");
    }

    // ===== 量具 =====

    /** 记录每一次 send 的假通道；{@code failOn} 让它按目标名抛错，好演"通道活着但发不出去"。 */
    private static final class FakeChannel implements Channel {
        final String name;
        final List<OutboundMessage> sent = new ArrayList<OutboundMessage>();
        RuntimeException failOn;

        FakeChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void awaitTermination() {
        }

        @Override
        public void send(OutboundMessage message) {
            if (failOn != null) {
                throw failOn;
            }
            sent.add(message);
        }
    }

    private static final class Table implements ChannelCronDelivery.Channels {
        final List<Channel> channels = new ArrayList<Channel>();

        @Override
        public Channel find(String name) {
            for (Channel c : channels) {
                if (c.name().equals(name)) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public List<String> names() {
            List<String> out = new ArrayList<String>();
            for (Channel c : channels) {
                out.add(c.name());
            }
            return out;
        }
    }

    private static final class CapturingPrint implements CronDelivery {
        final List<String> lines = new ArrayList<String>();

        @Override
        public String deliver(CronJob job, String text) {
            lines.add(job.id + "|" + text);
            return null;
        }
    }

    private static CronJob job(String id, String deliver, String origin) {
        CronJob j = new CronJob(id, "起名", "p", "every 5s");
        j.deliver = deliver;
        j.origin = origin;
        return j;
    }

    // ===== local 档：结果必须真打印，不再静默蒸发 =====

    @Test
    public void localDeliveryPrintsTheResultToTheGivenStream() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream probe = new PrintStream(buf, true, "UTF-8");
        String err = new LocalCronDelivery(probe).deliver(job("c_1", "local", null), "任务跑完了");
        assertNull("local 档不该报失败", err);
        String printed = new String(buf.toByteArray(), "UTF-8");
        assertTrue("打印里要能认出是哪条任务: " + printed, printed.contains("c_1"));
        assertTrue("正文得真打出来: " + printed, printed.contains("任务跑完了"));
    }

    @Test
    public void localDeliveryDefaultsToStandardOut() {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true));
            assertNull(new LocalCronDelivery().deliver(job("c_def", "local", null), "hi"));
        } finally {
            System.setOut(original);
        }
        assertTrue(new String(buf.toByteArray()).contains("hi"));
    }

    // ===== 路由：origin 与降级 =====

    @Test
    public void originRouteDeliversToTheCapturedSourceChannel() {
        Table table = new Table();
        FakeChannel feishu = new FakeChannel("feishu");
        table.channels.add(feishu);
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), true);

        String err = d.deliver(job("c_o", "origin", "feishu:oc_9"), "正文");
        assertNull(err);
        assertEquals(1, feishu.sent.size());
        assertEquals("oc_9", feishu.sent.get(0).replyTo);
        assertTrue(feishu.sent.get(0).text.contains("正文"));
    }

    @Test
    public void originWithoutASourceDegradesToLocalInsteadOfErroring() {
        Table table = new Table();
        CapturingPrint local = new CapturingPrint();
        ChannelCronDelivery d = new ChannelCronDelivery(table, local, true);
        CronJob j = job("c_no_origin", "origin", null);
        String err = d.deliver(j, "正文");
        // 她 #43014 的结论：CLI/工具建的任务从来没有会话来源，这时降级 local，
        // 而不是每跑一次就冒一个 "no delivery target" —— 那不是错误，是常态。
        assertNull("没有 origin 不该算投递失败", err);
        assertEquals(Arrays.asList("c_no_origin|正文"), local.lines);
        assertTrue(table.channels.isEmpty());
    }

    @Test
    public void malformedOriginDegradesInsteadOfTargetingSomething() {
        Table table = new Table();
        FakeChannel feishu = new FakeChannel("feishu");
        table.channels.add(feishu);
        CapturingPrint local = new CapturingPrint();
        ChannelCronDelivery d = new ChannelCronDelivery(table, local, false);
        for (String bad : new String[]{"feishu:", ":oc_1", "oc_1"}) {
            String err = d.deliver(job("c_bad", "origin", bad), "正文");
            assertNull("残缺形 origin=" + bad + " 应降级而不是报错", err);
        }
        assertEquals("三种残缺形各降级一次", 3, local.lines.size());
        assertEquals("一条都不该真投到通道上", 0, feishu.sent.size());
    }

    // ===== 路由：指定通道 / all / 组合 =====

    @Test
    public void explicitChannelAndConversationIsHonoured() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), false);
        assertNull(d.deliver(job("c_w", "webhook:conv-7", null), "正文"));
        assertEquals(1, webhook.sent.size());
        assertEquals("conv-7", webhook.sent.get(0).replyTo);
        assertEquals("wrap=false 时正文原样出去", "正文", webhook.sent.get(0).text);
    }

    @Test
    public void channelNameWithoutConversationFallsBackToTheJobsOwnOrigin() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), false);
        assertNull(d.deliver(job("c_w2", "webhook", "webhook:conv-8"), "正文"));
        assertEquals("conv-8", webhook.sent.get(0).replyTo);
    }

    @Test
    public void channelWithNoAddressableConversationIsReportedNotGuessed() {
        Table table = new Table();
        table.channels.add(new FakeChannel("webhook"));
        CapturingPrint local = new CapturingPrint();
        String err = new ChannelCronDelivery(table, local, false)
                .deliver(job("c_w3", "webhook", null), "正文");
        assertNotNull("通道在、但没地方可投：要说清楚，别静默丢", err);
        assertTrue(err, err.contains("webhook"));
        assertTrue("缺会话时不许瞎猜一个 target", local.lines.isEmpty());
    }

    @Test
    public void unknownChannelIsAnErrorNotASilentSuccess() {
        Table table = new Table();
        table.channels.add(new FakeChannel("webhook"));
        String err = new ChannelCronDelivery(table, new CapturingPrint(), false)
                .deliver(job("c_u", "feishu:oc_1", null), "正文");
        assertNotNull(err);
        assertTrue("要报得清是哪个通道: " + err, err.contains("unknown channel 'feishu'"));
    }

    @Test
    public void allExpandsEveryLiveChannelExactlyOnce() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        FakeChannel ding = new FakeChannel("dingtalk");
        table.channels.addAll(Arrays.<Channel>asList(webhook, ding));
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), false);
        assertNull(d.deliver(job("c_all", "all", null), "正文"));
        assertEquals(1, webhook.sent.size());
        assertEquals(1, ding.sent.size());
        assertEquals("没有 origin 时 all 用 cron 这个占位会话", "cron", webhook.sent.get(0).replyTo);
    }

    @Test
    public void allWithNoChannelAtAllIsReported() {
        Table table = new Table();
        String err = new ChannelCronDelivery(table, new CapturingPrint(), false)
                .deliver(job("c_all0", "all", null), "正文");
        assertNotNull("一个通道都没接上时 all 没有目标，得说人话", err);
        assertTrue(err, err.contains("no delivery target"));
    }

    @Test
    public void commaCombinedRoutesGoToEveryPart() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        CapturingPrint local = new CapturingPrint();
        ChannelCronDelivery d = new ChannelCronDelivery(table, local, false);
        assertNull(d.deliver(job("c_mix", "local,webhook:conv-9", null), "正文"));
        assertEquals(Arrays.asList("c_mix|正文"), local.lines);
        assertEquals(1, webhook.sent.size());
        assertEquals("conv-9", webhook.sent.get(0).replyTo);
    }

    @Test
    public void duplicateTargetsFromACombinedRouteAreSentOnce() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), false);
        assertNull(d.deliver(job("c_dup", "all,webhook:conv-1", "webhook:conv-1"), "正文"));
        assertEquals("同一 (通道,会话) 只投一次", 1, webhook.sent.size());
    }

    @Test
    public void sendFailureIsCollectedButOtherTargetsStillGetIt() {
        Table table = new Table();
        FakeChannel bad = new FakeChannel("feishu");
        bad.failOn = new RuntimeException("401 unauthorized");
        FakeChannel good = new FakeChannel("webhook");
        table.channels.addAll(Arrays.<Channel>asList(bad, good));
        ChannelCronDelivery d = new ChannelCronDelivery(table, new CapturingPrint(), false);
        String err = d.deliver(job("c_part", "feishu:oc_1,webhook:conv-2", null), "正文");
        assertNotNull(err);
        assertTrue(err, err.contains("401 unauthorized"));
        assertEquals("一个目标失败不该拖累另一个", 1, good.sent.size());
    }

    @Test
    public void emptyRouteMeansLocalAndIsNotAnError() {
        Table table = new Table();
        CapturingPrint local = new CapturingPrint();
        assertNull(new ChannelCronDelivery(table, local, false)
                .deliver(job("c_blank", "   ", null), "正文"));
        assertEquals(Arrays.asList("c_blank|正文"), local.lines);
    }

    @Test
    public void wrapAddsARecognisableHeader() {
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        new ChannelCronDelivery(table, new CapturingPrint(), true)
                .deliver(job("c_wrap", "webhook:conv-3", null), "正文");
        String sent = webhook.sent.get(0).text;
        assertTrue(sent, sent.startsWith("[cron] "));
        assertTrue(sent, sent.contains("c_wrap"));
        assertTrue(sent, sent.endsWith("正文"));
    }

    // ===== 路由串的语法闸门 =====

    @Test
    public void routeSyntaxIsChecked() {
        assertNull(ChannelCronDelivery.validateRoute("local"));
        assertNull(ChannelCronDelivery.validateRoute("origin"));
        assertNull(ChannelCronDelivery.validateRoute("all"));
        assertNull(ChannelCronDelivery.validateRoute("webhook:conv-1"));
        assertNull(ChannelCronDelivery.validateRoute("local,feishu:oc_1"));
        assertNull("只给通道名、不带会话是合法的（到点用任务自己的 origin 兜）",
                ChannelCronDelivery.validateRoute("webhook"));
        assertNotNull(ChannelCronDelivery.validateRoute("feishu:"));
        assertNotNull(ChannelCronDelivery.validateRoute(":oc_1"));
        assertNotNull(ChannelCronDelivery.validateRoute("local,,"));
        assertNotNull("local/origin/all 不是通道，不接受 :xxx 写法",
                ChannelCronDelivery.validateRoute("origin:oc_1"));
    }

    @Test
    public void addRejectsABrokenRouteAtCreationTime() {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "ok");
        try {
            s.add("t", "p", "every 5s", "feishu:");
            assertFalse("写错的硬路由没理由等到凌晨三点才告诉你", true);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("feishu:"));
        }
        assertTrue("被拒的路由不该留下一条永远投不出去的任务", s.list().isEmpty());
    }

    @Test
    public void addDefaultsBlankRouteToLocal() {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "ok");
        assertEquals("local", s.add("t1", "p", "every 5s", "  ").deliver);
        assertEquals("local", s.add("t2", "p", "hourly", null).deliver);
        assertEquals("WEBHOOK:C-1", s.add("t3", "p", "hourly", " WEBHOOK:C-1 ").deliver);
    }

    // ===== 装配口：活通道表 =====

    @Test
    public void forChannelsLooksUpIgnoreCaseAgainstALiveList() {
        final List<Channel> live = new ArrayList<Channel>();
        FakeChannel webhook = new FakeChannel("WebHook");
        ChannelCronDelivery.Channels view = ChannelCronDelivery.forChannels(new java.util.function.Supplier<List<Channel>>() {
            @Override
            public List<Channel> get() {
                return live;
            }
        });
        assertNull("装配时还没 register 的通道，查不到才对", view.find("webhook"));
        live.add(webhook);
        assertSameChannel(webhook, view.find("webhook"));
        assertSameChannel(webhook, view.find("WEBHOOK"));
        assertEquals(Arrays.asList("WebHook"), view.names());
        assertNull(view.find("feishu"));

        ChannelCronDelivery d = new ChannelCronDelivery(view, new CapturingPrint(), false);
        assertNull(d.deliver(job("c_live", "webhook:conv-4", null), "正文"));
        assertEquals(1, webhook.sent.size());
    }

    @Test
    public void forChannelsToleratesANullOrDirtyList() {
        ChannelCronDelivery.Channels view = ChannelCronDelivery.forChannels(
                new java.util.function.Supplier<List<Channel>>() {
                    @Override
                    public List<Channel> get() {
                        return null;
                    }
                });
        assertTrue(view.names().isEmpty());
        assertNull(view.find("webhook"));
        try {
            ChannelCronDelivery.forChannels(null);
            assertFalse("空表要当场拒，不能留到投递时才 NPE", true);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void assertSameChannel(Channel expected, Channel actual) {
        assertTrue("查出来的必须是同一个实例", expected == actual);
    }

    // ===== 端到端（进程内）：任务 → 通道 =====

    @Test
    public void executedJobResultReachesTheRoutedChannel() throws UnsupportedEncodingException {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "定时结果");
        Table table = new Table();
        FakeChannel webhook = new FakeChannel("webhook");
        table.channels.add(webhook);
        CapturingPrint local = new CapturingPrint();
        s.setDelivery(new ChannelCronDelivery(table, local, false));

        CronJob j = s.add("t", "p", "every 5s", "webhook:conv-5");
        s.execute(j);

        assertEquals(1, webhook.sent.size());
        assertEquals("conv-5", webhook.sent.get(0).replyTo);
        assertEquals("定时结果", webhook.sent.get(0).text);
        assertTrue(local.lines.isEmpty());
        CronJob after = s.findJob(j.id);
        assertEquals("ok", after.lastDelivery);
        assertEquals("定时结果", after.lastResult);
    }

    @Test
    public void schedulerDefaultsToLocalPrintingWhenNothingIsWired() {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "ok");
        assertTrue("缺省投递口必须是能真出声的 local，不是 null",
                s.delivery() instanceof LocalCronDelivery);
        s.setDelivery(null);
        assertTrue(s.delivery() instanceof LocalCronDelivery);
    }

    @Test
    public void deliverViaChannelsSwapsTheWholeDeliverySurface() {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "ok");
        ChannelCronDelivery d = s.deliverViaChannels(new Table());
        assertSameReal(d, s.delivery());
        assertTrue(s.delivery() instanceof ChannelCronDelivery);
    }

    private static void assertSameReal(Object expected, Object actual) {
        assertTrue("装配后的投递口必须是同一个对象", expected == actual);
    }

    @Test
    public void cronToolCarriesTheDeliverArgumentIntoTheJob() {
        CronScheduler s = new CronScheduler(cronDir, 60, prompt -> "ok");
        com.zifang.z.bot.tool.Toolkit toolkit = new com.zifang.z.bot.tool.Toolkit()
                .register(CronTools.cronTool(s));

        java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        args.put("action", "add");
        args.put("name", "n");
        args.put("prompt", "p");
        args.put("schedule", "every 5s");
        args.put("deliver", "webhook:conv-6");
        com.zifang.z.agent.kernel.tool.ToolResult added = toolkit.execute("cronjob", args);
        assertFalse(added.getContent(), added.isError());
        assertTrue(added.getContent(), added.getContent().contains("投递=webhook:conv-6"));
        assertEquals("webhook:conv-6", s.list().get(0).deliver);

        args.put("deliver", "feishu:");
        assertTrue("坏路由要当场回错，不能悄悄建成 local",
                toolkit.execute("cronjob", args).isError());

        java.util.Map<String, Object> list = new java.util.LinkedHashMap<String, Object>();
        list.put("action", "list");
        String shown = toolkit.execute("cronjob", list).getContent();
        assertTrue("list 得把路由和投递结论露出来", shown.contains("投递=webhook:conv-6"));
        assertEquals("被拒的坏路由不该留下一条永远投不出去的任务", 1, s.list().size());
    }

}
