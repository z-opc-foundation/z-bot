package com.zifang.z.bot.tool;

import com.zifang.z.agent.kernel.tool.ToolResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * toolset 声明层（{@link Toolsets}）的守卫 —— 清单是 z-bot 侧的东西，不进内核（P20）。
 *
 * <p>红线 2 的两条腿都在这里判：<b>不许列没有消费者的 toolset</b>
 * （{@link Toolsets#emptyCapabilityToolsets(Toolkit)} 装配后必须为空）与
 * <b>清单里的工具名必须真被注册出来</b>（{@link Toolsets#manifestToolsNeverRegistered(Toolkit)}）。</p>
 */
public class ToolsetsManifestTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Toolkit assembled() {
        Toolkit tk = new Toolkit();
        BuiltinTools.registerAll(tk, new Sandbox(tmp.getRoot().getAbsolutePath()), "dangerous");
        return tk;
    }

    // ===== 清单自身 =====

    @Test
    public void everyCapabilityToolsetDeclaresConsumerAndMembers() {
        List<String> caps = Toolsets.capabilityNames();
        assertFalse("清单里一个能力子集都没有", caps.isEmpty());
        for (String name : caps) {
            Toolsets.Declaration d = Toolsets.declaration(name);
            assertEquals(name, d.name());
            assertEquals(Toolsets.Kind.CAPABILITY, d.kind());
            assertFalse(name + " 没写消费者", d.consumer().trim().isEmpty());
            assertFalse(name + " 是空清单", d.tools().isEmpty());
            assertTrue(name + " 的消费者要指到本仓调用点: " + d.consumer(),
                    d.consumer().contains("#") || d.consumer().contains("("));
        }
    }

    @Test
    public void noToolNameIsClaimedByTwoToolsets() {
        Set<String> seen = new HashSet<String>();
        int total = 0;
        for (String name : Toolsets.capabilityNames()) {
            List<String> tools = Toolsets.declaration(name).tools();
            total += tools.size();
            for (String t : tools) {
                assertTrue("工具 " + t + " 同时被 " + name + " 和 " + seen + " 认领", seen.add(t));
            }
        }
        assertEquals("声明的工具名总数要与去重后一致", total, Toolsets.declaredToolNames().size());
    }

    @Test
    public void defaultToolsetIsDeclaredAsFallbackAndMcpAsDynamicPrefix() {
        Toolsets.Declaration fallback = Toolsets.declaration(Toolkit.DEFAULT_TOOLSET);
        assertEquals(Toolsets.Kind.FALLBACK, fallback.kind());
        assertEquals(Collections.emptyList(), fallback.tools());
        Toolsets.Declaration dynamic = Toolsets.declaration(Toolsets.MCP_PREFIX);
        assertEquals(Toolsets.Kind.DYNAMIC_PREFIX, dynamic.kind());
        assertTrue(Toolsets.isDeclared(Toolkit.DEFAULT_TOOLSET));
        assertTrue("mcp-<server> 是动态名，按前缀认", Toolsets.isDeclared("mcp-any-server"));
        assertTrue(Toolsets.isMcpToolset("mcp-x"));
        assertFalse("光有前缀没名字不算", Toolsets.isMcpToolset("mcp-"));
        assertFalse(Toolsets.isDeclared("browser-automation"));
        assertFalse(Toolsets.isDeclared(null));
    }

    // ===== 注册归属（真实注册调用点）=====

    @Test
    public void builtinAssemblyFillsEveryDeclaredCapability() {
        Toolkit tk = assembled();
        assertEquals("装配完之后不许有空的能力子集（红线 2）",
                Collections.emptyList(), Toolsets.emptyCapabilityToolsets(tk));
        assertEquals("清单里不许有本仓根本没注册的工具名",
                Collections.emptyList(), Toolsets.manifestToolsNeverRegistered(tk));
        assertEquals("注册表里不许冒出清单外的 toolset",
                Collections.emptyList(), Toolsets.undeclaredToolsets(tk));
        assertEquals(Arrays.asList(Toolsets.CORE, Toolsets.EXEC, Toolsets.FILE, Toolsets.NET),
                new ArrayList<String>(new java.util.TreeSet<String>(Toolsets.capabilityNames())));
    }

    @Test
    public void eachBuiltinToolLandsInItsDeclaredCapability() {
        Toolkit tk = assembled();
        assertEquals(Arrays.asList("counter", "echo", "health", "sysinfo", "time"),
                sorted(tk.namesOfToolset(Toolsets.CORE)));
        assertEquals(Arrays.asList("read_file", "search", "write_file"),
                sorted(tk.namesOfToolset(Toolsets.FILE)));
        assertEquals(Arrays.asList("exec", "mvn_build"),
                sorted(tk.namesOfToolset(Toolsets.EXEC)));
        assertEquals(Collections.singletonList("curl_test"),
                sorted(tk.namesOfToolset(Toolsets.NET)));
        assertEquals("内置工具一律不许落进兜底槽",
                Collections.emptyList(), tk.namesOfToolset(Toolkit.DEFAULT_TOOLSET));
        assertEquals(11, tk.getToolNames().size());
    }

    @Test
    public void readOnlyToolsDeclareParallelSafetyAndWriteToolsDoNot() {
        Toolkit tk = assembled();
        for (String ro : Arrays.asList("echo", "time", "health", "sysinfo", "read_file", "search")) {
            assertTrue(ro + " 是只读的，必须声明可并行", tk.isParallelSafe(ro));
        }
        for (String rw : Arrays.asList("counter", "write_file", "exec", "mvn_build", "curl_test")) {
            assertFalse(rw + " 有副作用，必须声明不可并行", tk.isParallelSafe(rw));
        }
        // 声明之外不许有第二真源：注册表里没有批次/线程 API（ToolkitRegistryTest 判），
        // 每个工具的回读值必须与清单声明一致
        for (String name : tk.getToolNames()) {
            assertEquals(name, Toolsets.toolsetForTool(name), tk.toolsetOf(name));
        }
    }

    @Test
    public void auditReportsACapabilityWhoseToolsWereAllDeregistered() {
        Toolkit tk = assembled();
        assertEquals(Collections.emptyList(), Toolsets.emptyCapabilityToolsets(tk));
        for (String execTool : tk.namesOfToolset(Toolsets.EXEC)) {
            assertTrue(execTool, tk.deregister(execTool));
        }
        assertEquals("exec 面被清空必须被审计点名",
                Collections.singletonList(Toolsets.EXEC), Toolsets.emptyCapabilityToolsets(tk));
        assertEquals("审计不许写死返回空表：空 toolkit 上四个能力子集都该被点名",
                new ArrayList<String>(new java.util.TreeSet<String>(Toolsets.capabilityNames())),
                new ArrayList<String>(new java.util.TreeSet<String>(
                        Toolsets.emptyCapabilityToolsets(new Toolkit()))));
    }

    @Test
    public void undeclaredToolsetNameIsReportedNotSilentlyAccepted() {
        Toolkit tk = new Toolkit();
        tk.register(Toolkit.of("strange", "s", null, args -> ToolResult.text("x")),
                "browser-automation", Toolkit.DEFAULT_OWNER, false);
        assertEquals(Collections.singletonList("browser-automation"),
                Toolsets.undeclaredToolsets(tk));
        assertEquals("没登记的工具退回兜底槽，不塞进别人的能力面",
                Toolkit.DEFAULT_TOOLSET, Toolsets.toolsetForTool("strange"));
        assertNull(Toolsets.capabilityOfTool("strange"));
        assertEquals("没装配的 toolkit 上，清单里 11 个工具都算\"没被注册\"",
                11, Toolsets.manifestToolsNeverRegistered(tk).size());
    }

    @Test
    public void emptyManifestAuditsAreNullSafe() {
        assertEquals(Collections.emptyList(), Toolsets.emptyCapabilityToolsets(null));
        assertEquals(Collections.emptyList(), Toolsets.undeclaredToolsets(null));
        assertEquals(Collections.emptyList(), Toolsets.manifestToolsNeverRegistered(null));
        assertEquals(Toolkit.DEFAULT_TOOLSET, Toolsets.toolsetForTool(null));
    }

    // ===== MCP 动态名的拼法唯一真源 =====

    @Test
    public void mcpToolsetAndOwnerSpellingAreCentralized() {
        assertEquals("mcp-fs", Toolsets.mcpToolset("fs"));
        assertEquals("mcp:fs", Toolsets.mcpOwner("fs"));
        assertEquals("server 名里的中划线换成下划线，别让 toolset 名歧义",
                "mcp-deep_kb", Toolsets.mcpToolset("deep-kb"));
        assertEquals("mcp-a_b_c", Toolsets.mcpToolset("a b/c"));
        assertEquals("mcp-unnamed", Toolsets.mcpToolset(null));
        assertEquals("mcp-unnamed", Toolsets.mcpToolset(""));
        assertTrue(Toolsets.declaredNames().toString(),
                Toolsets.declaredNames().contains(Toolsets.MCP_PREFIX));
    }

    @Test
    public void toolsetMembershipQueryIsRegistrationOrdered() {
        Toolkit tk = new Toolkit();
        tk.register(Toolkit.of("z", "z", null, args -> ToolResult.text("z")),
                Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        tk.register(Toolkit.of("a", "a", null, args -> ToolResult.text("a")),
                Toolsets.CORE, Toolkit.DEFAULT_OWNER, true);
        assertEquals("注册顺序，不排序（schema 前缀要可重放）",
                Arrays.asList("z", "a"), tk.namesOfToolset(Toolsets.CORE));
        assertEquals(Collections.emptyList(), tk.namesOfToolset(null));
    }

    @Test
    public void crossOwnerDeregisterOfWholeToolsetIsRefused() {
        Toolkit tk = assembled();
        try {
            tk.deregisterToolset(Toolsets.FILE, "someone-else");
            fail("按 toolset 整组注销也要过 owner 校验");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("不能注销"));
        }
        assertEquals(3, tk.namesOfToolset(Toolsets.FILE).size());
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<String>(in);
        Collections.sort(out);
        return out;
    }
}
