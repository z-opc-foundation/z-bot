package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * check_fn（可用性探测）在 {@link Toolkit} 这一层的语义（P20，对齐 hermes
 * {@code tools/registry.py:143/147} 的 TTL 30s + 瞬时失败宽限 60s）。
 *
 * <p>时间边界用<b>注入假钟</b>量（确定性、跑得快）；{@link #realClockProbeCachesItsVerdictForThirtySeconds()}
 * 再用<b>真时间</b>复算一次"TTL 窗内不重复探"这条最容易被改坏的性质，避免假钟自证。</p>
 */
public class ToolkitProbeTest {

    /** 可推进的假钟（毫秒）。 */
    private static final class FakeClock implements LongSupplier {
        private long now;

        FakeClock(long start) {
            this.now = start;
        }

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** 返回值由测试摆布的探测器，顺便数被真正调用了多少次。 */
    private static final class Flag implements java.util.function.Supplier<Boolean> {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean ok = true;

        @Override
        public Boolean get() {
            calls.incrementAndGet();
            return Boolean.valueOf(ok);
        }
    }

    private static Tool tool(String name) {
        return Toolkit.of(name, "工具 " + name, null, args -> ToolResult.text(name + ":ok"));
    }

    private static List<String> exposed(Toolkit tk) {
        List<String> out = new ArrayList<String>();
        for (Tool t : tk.getAllTools()) {
            out.add(t.getName());
        }
        return out;
    }

    // ===== TTL 缓存 =====

    @Test
    public void probeResultIsCachedForTheWholeTtlWindow() {
        FakeClock clock = new FakeClock(0L);
        Flag flag = new Flag();
        Toolkit tk = new Toolkit();
        tk.register(tool("probe_me"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER, ToolDescriptor.PROBE_TTL_MS,
                ToolDescriptor.PROBE_FAILURE_GRACE_MS, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));

        assertEquals(Collections.singletonList("probe_me"), exposed(tk));
        assertEquals(1, flag.calls.get());
        flag.ok = false;
        for (int i = 0; i < 4; i++) {
            assertEquals("TTL 窗内一律吃缓存", Collections.singletonList("probe_me"), exposed(tk));
        }
        assertEquals("TTL 窗内一次都不许真去探", 1, flag.calls.get());

        clock.advance(ToolDescriptor.PROBE_TTL_MS); // 30s → 缓存到期，必须重探
        assertEquals("但这次失败距上次成功只有 30s，还在 60s 宽限窗内 ⇒ 算抖动，不摘工具",
                Collections.singletonList("probe_me"), exposed(tk));
        assertEquals(2, flag.calls.get());
        assertEquals("抖动不写缓存 ⇒ 下一次取 schema 还要再探",
                Collections.singletonList("probe_me"), exposed(tk));
        assertEquals(3, flag.calls.get());
        assertEquals(Collections.singletonList("probe_me"), tk.getToolNames());

        clock.advance(ToolDescriptor.PROBE_TTL_MS); // 现在距 last-good 已 60s+ ⇒ 认账
        assertEquals("持续失败过了宽限窗必须把工具从外发清单里摘掉",
                Collections.emptyList(), exposed(tk));
        assertEquals(Collections.singletonList("probe_me"), tk.getToolNames());
        assertEquals(Collections.singletonList("probe_me"), tk.unavailableToolNames());
    }

    @Test
    public void realClockProbeCachesItsVerdictForThirtySeconds() {
        // 真时间复算：30s TTL 内多次取 schema 只该探一次（假钟自证不可信，这段用 System clock）。
        Flag flag = new Flag();
        Toolkit tk = new Toolkit();
        tk.register(tool("real_clock"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER)); // 不注入钟 ⇒ 内核缺省的 System.nanoTime 单调钟
        long t0 = System.currentTimeMillis();
        assertTrue(exposed(tk).contains("real_clock"));
        int afterFirst = flag.calls.get();
        long t1 = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            exposed(tk);
            tk.getToolsDescription();
            tk.schemaFingerprint();
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals("真钟下 30s TTL 窗内不许重复探", afterFirst, flag.calls.get());
        assertEquals("首探就该发生一次真探测", 1, afterFirst);
        assertTrue("这段确实跑在 TTL 窗内：elapsed=" + elapsed + "ms 必须 < "
                + ToolDescriptor.PROBE_TTL_MS + "ms", elapsed < ToolDescriptor.PROBE_TTL_MS);
        assertTrue("取 schema 用了真时间：t1-t0=" + (t1 - t0) + "ms", t1 - t0 >= 0);
    }

    // ===== 瞬时失败宽限 =====

    @Test
    public void transientFailureInsideGraceWindowKeepsTheToolAndIsNotCached() {
        FakeClock clock = new FakeClock(10_000L);
        Flag flag = new Flag();
        Toolkit tk = new Toolkit();
        tk.register(tool("flaky"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER, 1_000L, ToolDescriptor.PROBE_FAILURE_GRACE_MS,
                ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        assertEquals(Collections.singletonList("flaky"), exposed(tk)); // 成功，锚定 last-good
        assertEquals(1, flag.calls.get());

        flag.ok = false;
        clock.advance(1_000L); // 越过 1s TTL ⇒ 必须重探
        assertEquals("宽限窗内的抖动不许把工具摘掉", Collections.singletonList("flaky"), exposed(tk));
        assertEquals(2, flag.calls.get());
        clock.advance(1_000L);
        assertEquals("宽限窗内的抖动不写缓存", Collections.singletonList("flaky"), exposed(tk));
        assertEquals("抖动不写缓存 ⇒ 下一次还要再探", 3, flag.calls.get());

        // 持续失败越过 60s 宽限窗 → 这次认账，并缓存 false
        clock.advance(60_000L);
        assertEquals("宽限窗外的持续失败必须认账", Collections.emptyList(), exposed(tk));
        int callsAfterHonored = flag.calls.get();
        clock.advance(1_000L);
        assertEquals("认账的 false 要进缓存（对齐她窗内不缓存/窗外缓存）",
                callsAfterHonored, flag.calls.get());
    }

    @Test
    public void sustainedOutageHidesSchemaButDispatchStillReachesTheSlot() {
        LongSupplier clock = new Clock2();
        Flag flag = new Flag();
        flag.ok = false;
        Toolkit tk = new Toolkit();
        tk.register(tool("dark"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER, 1_000L, 0L, // 宽限 0 ⇒ 第一次失败就认账
                ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        assertEquals(Collections.emptyList(), exposed(tk));
        assertEquals("槽位仍在，注册名清单要看得见", Collections.singletonList("dark"), tk.getToolNames());
        assertTrue("get() 仍能拿到工具", tk.get("dark") != null);
        ToolResult r = tk.execute("dark", Collections.<String, Object>emptyMap());
        assertFalse("她的分层：dispatch 不按可用性过滤，所以还能真执行", r.isError());
        assertEquals("dark:ok", r.getContent());
        assertEquals(Collections.singletonList("dark"), tk.unavailableToolNames());
    }

    /** 一个恒定前进 1ms 的假钟（只用于 sustainedOutage 那条，时间边界由 TTL=1s 控制）。 */
    private static final class Clock2 implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return ++now;
        }
    }

    // ===== 探测翻转必须打掉 schema 缓存（代际没动也要）=====

    @Test
    public void availabilityFlipInvalidatesTheSchemaSnapshotEvenWithoutGenerationChange() {
        FakeClock clock = new FakeClock(0L);
        Flag flag = new Flag();
        Toolkit tk = new Toolkit();
        tk.register(tool("probe_me"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER, 1_000L, 0L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        assertEquals(Collections.singletonList("probe_me"), exposed(tk));
        String fpOn = tk.schemaFingerprint();
        long gen = tk.generation();

        flag.ok = false;
        clock.advance(1_000L); // 越 TTL，重探 ⇒ false
        assertEquals("探测翻转（没有任何注册/注销）也要让外发清单立刻收手",
                Collections.emptyList(), exposed(tk));
        assertEquals("代际确实一动没动", gen, tk.generation());
        assertFalse("指纹必须跟着换", fpOn.equals(tk.schemaFingerprint()));

        flag.ok = true;
        clock.advance(1_000L);
        assertEquals("后端回来就该重新外发", Collections.singletonList("probe_me"), exposed(tk));
        assertEquals("恢复后的指纹要与当初逐字节一致", fpOn, tk.schemaFingerprint());
    }

    @Test
    public void systemPromptToolListFollowsTheAvailabilityFlip() {
        FakeClock clock = new FakeClock(0L);
        Flag flag = new Flag();
        Toolkit tk = new Toolkit();
        tk.register(tool("always"), Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        tk.register(tool("gated"), new ToolDescriptor(Toolsets.CORE, false, flag,
                Toolkit.DEFAULT_OWNER, 1_000L, 0L, ToolDescriptor.NO_MAX_RESULT_CHARS, clock));
        assertTrue(tk.getToolsDescription().contains("- gated:"));
        flag.ok = false;
        clock.advance(1_000L);
        String desc = tk.getToolsDescription();
        assertFalse("不可用的工具不许出现在 system prompt 的工具清单里", desc.contains("- gated:"));
        assertTrue("可用的必须留着", desc.contains("- always:"));
    }

    @Test
    public void toolsWithoutProbeAreAlwaysExposed() {
        Toolkit tk = new Toolkit();
        tk.register(tool("plain"), Toolsets.CORE, Toolkit.DEFAULT_OWNER, false);
        assertEquals(Collections.singletonList("plain"), exposed(tk));
        assertEquals(Collections.emptyList(), tk.unavailableToolNames());
    }

    @Test
    public void kernelProbeConstantsMatchTheHermesNumbersThisTicketCopies() {
        // 她：_CHECK_FN_TTL_SECONDS=30.0 / _CHECK_FN_FAILURE_GRACE_SECONDS=60.0
        // 内核 0.2.1 不许动，这里把它当外部事实钉住：漂了就要重新对账，而不是偷偷改测试。
        assertEquals(30_000L, ToolDescriptor.PROBE_TTL_MS);
        assertEquals(60_000L, ToolDescriptor.PROBE_FAILURE_GRACE_MS);
        assertEquals(100_000L, com.zifang.z.agent.kernel.tool.ToolRegistry.DEFAULT_RESULT_SIZE_CHARS);
    }
}
