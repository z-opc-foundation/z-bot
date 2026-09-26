package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link Toolkit} 作为<b>真注册表</b>的守卫（P20）：
 * 注销是真删槽位、owner 校验不让人偷偷摘工具、代际计数使 schema 缓存整体作废、
 * 并行安全只是"声明"（注册表不参与调度）。
 */
public class ToolkitRegistryTest {

    private static Tool tool(String name) {
        return Toolkit.of(name, "工具 " + name, null,
                args -> ToolResult.text(name + ":ok"));
    }

    private static Toolkit with(String... names) {
        Toolkit tk = new Toolkit();
        for (String n : names) {
            tk.register(tool(n));
        }
        return tk;
    }

    // ===== 注销 =真删槽位，不是"同名覆盖" =====

    @Test
    public void deregisterActuallyRemovesTheSlotFromEveryView() {
        Toolkit tk = with("a", "b");
        long genBefore = tk.generation();
        assertTrue("注销必须报告真删掉了", tk.deregister("a"));

        assertFalse("槽位必须真的没了：contains", tk.contains("a"));
        assertNull("槽位必须真的没了：get", tk.get("a"));
        assertFalse("注册名清单里不许留着", tk.getToolNames().contains("a"));
        assertFalse("外发 schema 清单里不许留着", namesOf(tk).contains("a"));
        assertFalse("清单文本里不许留着", tk.getToolsDescription().contains("- a:"));
        assertEquals(1, tk.size());
        assertTrue("代际必须前进", tk.generation() > genBefore);
    }

    @Test
    public void deregisterOfUnknownNameIsNoOpNotThrow() {
        Toolkit tk = with("a");
        long gen = tk.generation();
        assertFalse(tk.deregister("nope"));
        assertFalse(tk.deregister(null));
        assertEquals("不存在的注销不许推代际", gen, tk.generation());
    }

    @Test
    public void executingADeregisteredToolReportsNotFoundInsteadOfSilentlyWorking() {
        Toolkit tk = with("a");
        assertTrue(tk.deregister("a"));
        ToolResult r = tk.execute("a", Collections.<String, Object>emptyMap());
        assertTrue("注销后必须判错", r.isError());
        assertTrue(r.getContent(), r.getContent().contains("未找到工具"));
    }

    @Test
    public void crossOwnerDeregisterIsRefusedAndLeavesTheSlotIntact() {
        Toolkit tk = new Toolkit();
        tk.register(tool("built_in"));
        tk.register(tool("plugged"), "plugin-set", "plugin-x", false);
        try {
            tk.deregister("built_in", "plugin-x");
            fail("异 owner 注销必须被拒");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不能注销"));
        }
        assertTrue("被拒之后槽位还得在原处", tk.contains("built_in"));
        assertTrue("内建工具仍可执行",
                !tk.execute("built_in", null).getContent().contains("未找到工具"));
    }

    @Test
    public void crossOwnerRegisterCannotHijackAnExistingSlot() {
        Toolkit tk = new Toolkit();
        tk.register(tool("shared"));
        try {
            tk.register(tool("shared"), "plugin-set", "plugin-x", false);
            fail("异 owner 覆盖必须被拒 —— 否则插件能顶掉内建工具");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不能覆写"));
        }
        assertEquals(1, tk.size());
    }

    @Test
    public void sameOwnerOverwriteIsAllowedAndBumpsGeneration() {
        Toolkit tk = new Toolkit();
        tk.register(tool("dup"), "s1", "o1", false);
        long gen = tk.generation();
        tk.register(Toolkit.of("dup", "第二版", null, args -> ToolResult.text("v2")), "s1", "o1", true);
        assertTrue(tk.generation() > gen);
        assertEquals("第二版", tk.get("dup").getDescription());
        assertTrue("覆盖时 parallelSafe 声明按最新描述走", tk.isParallelSafe("dup"));
        assertEquals("工具名不许因覆盖而重复", 1, tk.getToolNames().size());
    }

    // ===== 按 toolset 整体注销（MCP nuke-and-repave 的地基）=====

    @Test
    public void deregisterToolsetRemovesEveryMemberAndOnlyThose() {
        Toolkit tk = new Toolkit();
        for (int i = 0; i < 3; i++) {
            tk.register(tool("mcp-srv-t" + i), "mcp-srv", "mcp:srv", false);
        }
        tk.register(tool("keep"), "core", Toolkit.DEFAULT_OWNER, true);

        List<String> removed = tk.deregisterToolset("mcp-srv", "mcp:srv");
        assertEquals(removed.toString(), 3, removed.size());
        assertEquals("按注册顺序回名单", "mcp-srv-t0", removed.get(0));
        assertEquals(0, tk.namesOfToolset("mcp-srv").size());
        assertTrue("别人家的 toolset 不许被顺手动掉", tk.contains("keep"));
        assertEquals(1, tk.size());
    }

    @Test
    public void deregisterToolsetCleansNamesTheBridgeNoLongerKnowsAbout() {
        Toolkit tk = new Toolkit();
        // server 上一轮发的是 old，这一轮改名成 new —— 老名字必须一起清掉
        tk.register(tool("mcp-srv-old"), "mcp-srv", "mcp:srv", false);
        tk.register(tool("mcp-srv-new"), "mcp-srv", "mcp:srv", false);
        List<String> removed = tk.deregisterToolset("mcp-srv", "mcp:srv");
        assertTrue(removed.toString(), removed.contains("mcp-srv-old"));
        assertFalse(tk.getToolNames().toString(), tk.getToolNames().contains("mcp-srv-old"));
    }

    @Test
    public void deregisterEmptyToolsetIsHarmless() {
        Toolkit tk = with("a");
        assertEquals(Collections.emptyList(), tk.deregisterToolset("no-such", "o"));
        assertEquals(1, tk.size());
    }

    // ===== 代际 ⇒ schema 缓存整体作废 =====

    @Test
    public void schemaSnapshotIsByteReplayableWithinOneGeneration() {
        Toolkit tk = with("a", "b");
        String fp1 = tk.schemaFingerprint();
        String desc1 = tk.getToolsDescription();
        long rebuilds = tk.schemaSnapshotRebuilds();
        for (int i = 0; i < 5; i++) {
            assertEquals(fp1, tk.schemaFingerprint());
            assertEquals(desc1, tk.getToolsDescription());
        }
        assertEquals("同代际不许反复重建快照", rebuilds, tk.schemaSnapshotRebuilds());
        assertEquals(2, namesOf(tk).size());
    }

    @Test
    public void registerAndDeregisterEachInvalidateTheSchemaSnapshot() {
        Toolkit tk = with("a");
        String fpA = tk.schemaFingerprint();
        long r0 = tk.schemaSnapshotRebuilds();

        tk.register(tool("b"));
        assertFalse("代际变了 ⇒ 指纹必须换", fpA.equals(tk.schemaFingerprint()));
        assertTrue("代际变了 ⇒ 快照必须重建", tk.schemaSnapshotRebuilds() > r0);
        assertEquals(namesOf(tk).toString(), 2, namesOf(tk).size());

        String fpAB = tk.schemaFingerprint();
        long r1 = tk.schemaSnapshotRebuilds();
        tk.deregister("b");
        assertFalse("注销也要让缓存作废", fpAB.equals(tk.schemaFingerprint()));
        assertTrue(tk.schemaSnapshotRebuilds() > r1);
        assertEquals("回到只剩 a 时，指纹必须和当初一模一样", fpA, tk.schemaFingerprint());
    }

    @Test
    public void exposedNamesTrackTheRegistryAfterNukeAndRepave() {
        Toolkit tk = new Toolkit();
        tk.register(tool("mcp-x-1"), "mcp-x", "mcp:x", false);
        tk.register(tool("mcp-x-2"), "mcp-x", "mcp:x", false);
        assertEquals(2, namesOf(tk).size());
        tk.deregisterToolset("mcp-x", "mcp:x");
        assertEquals("注销后外发清单必须空", Collections.emptyList(), namesOf(tk));
        assertFalse(tk.getToolNames().toString(), tk.getToolNames().contains("mcp-x-1"));
        assertTrue("注册名清单与外发清单都得跟着掉", tk.getToolNames().isEmpty());
    }

    // ===== toolset / owner 查询与声明层 =====

    @Test
    public void toolsetAndOwnerQueriesReflectTheDescriptor() {
        Toolkit tk = new Toolkit();
        tk.register(tool("a"), Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        tk.register(tool("b"), "mcp-srv", "mcp:srv", false);
        assertEquals(Toolsets.CORE, tk.toolsetOf("a"));
        assertEquals("mcp-srv", tk.toolsetOf("b"));
        assertNull("未注册的工具没有 toolset", tk.toolsetOf("ghost"));
        assertEquals(java.util.Arrays.asList("a"), tk.namesOfToolset(Toolsets.CORE));
        assertTrue(tk.toolsetsInUse().contains("mcp-srv"));
        assertFalse(tk.toolsetsInUse().toString(), tk.toolsetsInUse().contains("ghost"));
    }

    @Test
    public void parallelSafetyIsOnlyADeclarationTheRegistryNeverSchedules() {
        Toolkit tk = new Toolkit();
        tk.register(tool("ro"), Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        tk.register(tool("rw"), Toolsets.FILE, Toolkit.DEFAULT_OWNER, false);
        assertTrue(tk.isParallelSafe("ro"));
        assertFalse(tk.isParallelSafe("rw"));
        assertFalse("未注册的名字一律按不安全处理", tk.isParallelSafe("ghost"));
        assertTrue(tk.deregister("ro"));
        assertFalse("注销后声明也跟着消失", tk.isParallelSafe("ro"));
        // 注册表不参与调度：只读声明之外没有任何批次/线程 API
        for (java.lang.reflect.Method m : Toolkit.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse("注册表不许长出调度面: " + m,
                    n.contains("batch") || n.contains("submit") || n.contains("executor"));
        }
    }

    @Test
    public void executeWrapsHandlerThrowablesIntoErrorResults() {
        Toolkit tk = new Toolkit();
        tk.register(Toolkit.of("boom", "会炸", null, args -> {
            throw new RuntimeException("炸了");
        }));
        ToolResult r = tk.execute("boom", null);
        assertTrue(r.isError());
        assertTrue(r.getContent(), r.getContent().contains("炸了"));
        assertEquals("结果必须带上被调用的工具名", "boom", r.getName());
    }

    @Test
    public void registeredToolsIncludeUnavailableOnesWhileAllToolsDoesNot() {
        Toolkit tk = new Toolkit();
        long[] clock = {1_000L};
        tk.register(tool("on"), new ToolDescriptor(Toolsets.CORE, false,
                () -> Boolean.TRUE, Toolkit.DEFAULT_OWNER,
                30_000L, 60_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, () -> clock[0]));
        tk.register(tool("off"), new ToolDescriptor(Toolsets.CORE, false,
                () -> Boolean.FALSE, Toolkit.DEFAULT_OWNER,
                30_000L, 60_000L, ToolDescriptor.NO_MAX_RESULT_CHARS, () -> clock[0]));
        assertEquals(2, tk.getRegisteredTools().size());
        assertEquals(Collections.singletonList("on"), namesOf(tk));
        assertEquals(Collections.singletonList("off"), tk.unavailableToolNames());
    }

    private static List<String> namesOf(Toolkit tk) {
        return exposedNames(tk);
    }

    private static List<String> exposedNames(Toolkit tk) {
        List<String> out = new java.util.ArrayList<String>();
        for (Tool t : tk.getAllTools()) {
            out.add(t.getName());
        }
        return out;
    }
}
