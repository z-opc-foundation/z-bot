import com.zifang.z.agent.kernel.mcp.McpClient;
import com.zifang.z.agent.kernel.tool.Tool;
import com.zifang.z.agent.kernel.tool.ToolDescriptor;
import com.zifang.z.agent.kernel.tool.ToolResult;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.mcp.McpBridge;
import com.zifang.z.bot.mcp.McpClientFactory;
import com.zifang.z.bot.mcp.McpManager;
import com.zifang.z.bot.tool.BuiltinTools;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.Toolkit;
import com.zifang.z.bot.tool.Toolsets;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * p20b 的 E2E 驱动：在<b>真 JVM</b>里跑真 MCP server 子进程 + 真时间探测窗口。
 *
 * <p>只打印 {@code KEY<TAB>VALUE} 读数，判定交给 p20b_e2e.py。</p>
 *
 * <p>用法：{@code P20bToolDriver <phase> <tmpHome> <assetsDir>}，
 * phase = mcp | cap | toolsets。</p>
 */
public final class P20bToolDriver {

    static final String STUB_KEY = "stub-key-not-real";

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("FATAL\t需要 phase/tmpHome/assetsDir");
            System.exit(2);
        }
        String phase = args[0];
        File home = new File(args[1]);
        File assets = new File(args[2]);
        t0 = System.currentTimeMillis();
        trace("phase=" + phase);
        configPin(home);
        if ("mcp".equals(phase)) {
            mcpPhase(home, assets);
        } else if ("cap".equals(phase)) {
            capPhase(home);
        } else if ("toolsets".equals(phase)) {
            toolsetsPhase(home);
        } else {
            System.out.println("FATAL\t未知 phase " + phase);
            System.exit(2);
        }
        trace("PHASE_RETURNED=" + phase);
        // 测量驱动跑完就退：不把线程收尾当被测行为（内核 StdioMcpTransport 的读线程
        // 实测是 daemon，见 EVIDENCE 里的 javap 读数），显式 exit 免得驱动自己拖时间。
        System.exit(0);
    }

    /** 红线 1 的反向钉：本次跑用的 key 必须就是那把 stub key（并且只从临时目录读）。 */
    static void configPin(File home) {
        BotConfig cfg = BotConfig.load(home);
        String key = cfg.activeProvider() == null ? null : cfg.activeProvider().getApiKey();
        p("CONFIG_DIR", String.valueOf(cfg.getConfigDir()));
        p("CONFIG_PROVIDER", String.valueOf(cfg.getActiveProviderCode()));
        p("CONFIG_KEY_VALUE", String.valueOf(key));
        p("CONFIG_KEY_IS_STUB", String.valueOf(STUB_KEY.equals(key)));
    }

    static void p(String k, Object v) {
        System.out.println(k + "\t" + v);
        System.out.flush();
    }

    static long t0;

    /** 卡在哪一步是可查的：每个可能阻塞的动作前打 TRACE（+相对 ms），事后靠日志定位，不靠猜。 */
    static void trace(String step) {
        p("TRACE", step + " +" + (System.currentTimeMillis() - t0) + "ms");
    }

    static String jvmPid() {
        String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = n.indexOf('@');
        return at > 0 ? n.substring(0, at) : n;
    }

    // ===================== MCP: 真子进程 + 真时间 + 同 JVM 注销 =====================

    static void mcpPhase(File home, File assets) throws Exception {
        p("JVM_PID", jvmPid());
        p("ZBOT_HOME_ENV", String.valueOf(System.getenv(Toolkit.ZBOT_HOME_ENV)));

        // ---------- 段 1：两个真 stdio server，掐死其中一个，量真时间探测窗口 ----------
        Toolkit tk1 = new Toolkit();
        File pidA = new File(home, "alpha.pid");
        File toolsA = new File(home, "alpha.tools");
        File pidB = new File(home, "bravo.pid");
        File toolsB = new File(home, "bravo.tools");
        write(toolsA, "t1:甲一\nt2:甲二\n");
        write(toolsB, "u1:乙一\n");
        McpBridge a = new McpBridge(stdioClient("alpha", pidA, toolsA, assets), tk1);
        McpBridge b = new McpBridge(stdioClient("bravo", pidB, toolsB, assets), tk1);
        trace("seg1.bridge.a.registerAll");
        p("SEG1_REGISTERED_A", a.registerAll());
        trace("seg1.bridge.b.registerAll");
        p("SEG1_REGISTERED_B", b.registerAll());
        p("SEG1_NAMES_AFTER_START", tk1.getToolNames());
        p("SEG1_TOOLSET_OF_T1", tk1.toolsetOf("mcp-alpha-t1"));
        p("SEG1_TOOLSETS_IN_USE", tk1.toolsetsInUse());
        p("SEG1_EXPOSED", tk1.getExposedToolNames());
        long anchor = System.currentTimeMillis();
        p("SEG1_PROBE_CALLS_AT_ANCHOR", a.probeInvocations());
        p("SEG1_TK_IDENTITY", System.identityHashCode(tk1));

        // 掐死 alpha 的 server 进程（真 kill + 等 kill 命令退出 + 等传输层真的看到进程没了）
        long pid = Long.parseLong(read(pidA).trim());
        trace("seg1.kill pid=" + pid);
        Process kill = new ProcessBuilder("kill", "-9", String.valueOf(pid)).start();
        p("SEG1_KILL_RC", kill.waitFor());
        trace("seg1.wait-transport-dead");
        long tKill = System.currentTimeMillis();
        long deadAt = -1;
        while (System.currentTimeMillis() - tKill < 10_000L) {
            if (!a.client().isConnected()) {
                deadAt = System.currentTimeMillis();
                break;
            }
            Thread.sleep(50);
        }
        p("SEG1_DEAD_CONFIRMED", deadAt > 0);
        p("SEG1_DEAD_WAIT_MS", deadAt > 0 ? deadAt - tKill : -1);

        // 真时间轮询：宽限窗内不许摘，过窗必须摘；同时看注册名清单不受影响
        trace("seg1.sample.loop.start");
        boolean hiddenSeen = false;
        long firstHiddenAt = -1;
        int samples = 0;
        long deadline = anchor + 80_000L;
        while (System.currentTimeMillis() < deadline) {
            long el = System.currentTimeMillis() - anchor;
            boolean exposed = tk1.getExposedToolNames().contains("mcp-alpha-t1");
            if (samples < 4 || !exposed) {
                p("SEG1_SAMPLE", el + "|" + exposed + "|probes=" + a.probeInvocations());
            }
            samples++;
            if (!exposed && !hiddenSeen) {
                hiddenSeen = true;
                firstHiddenAt = el;
                p("SEG1_FIRST_HIDDEN_MS", el);
                p("SEG1_HIDDEN_STILL_REGISTERED", tk1.getToolNames().contains("mcp-alpha-t1"));
                p("SEG1_HIDDEN_BUT_UNAVAILABLE_LISTED", tk1.unavailableToolNames());
                ToolResult r = tk1.execute("mcp-alpha-t1", args2("text", "hi"));
                p("SEG1_EXECUTE_WHILE_HIDDEN_ERR", r.isError());
                p("SEG1_EXECUTE_WHILE_HIDDEN_NOT_FOUND", r.getContent().contains("未找到工具"));
                break;
            }
            Thread.sleep(1_500);
        }
        p("SEG1_SAMPLES", samples);
        trace("seg1.sample.loop.end samples=" + samples);
        if (!hiddenSeen) {
            p("SEG1_FIRST_HIDDEN_MS", -1);
        }
        p("SEG1_ALPHA_PROBE_CALLS", a.probeInvocations());
        p("SEG1_BRAVO_STILL_EXPOSED", tk1.getExposedToolNames().contains("mcp-bravo-u1"));

        // 同一个 JVM、同一个 Toolkit 实例里注销
        trace("seg1.unregisterAllReturningNames");
        List<String> removed = a.unregisterAllReturningNames();
        p("SEG1_REMOVED", removed);
        p("SEG1_NAMES_AFTER_UNREGISTER", tk1.getToolNames());
        p("SEG1_ALPHA_GONE", !tk1.getToolNames().contains("mcp-alpha-t1")
                && !tk1.getToolNames().contains("mcp-alpha-t2"));
        p("SEG1_BRAVO_INTACT", tk1.getToolNames().contains("mcp-bravo-u1"));
        p("SEG1_SIZE", tk1.size());
        p("SEG1_JVM_PID_AT_END", jvmPid());
        p("SEG1_TK_IDENTITY_AT_END", System.identityHashCode(tk1));
        b.unregisterAll();

        // ---------- 段 2：走 McpManager.reload() 这条生产入口 ----------
        trace("seg1.bridge.b.unregisterAll.done");
        Toolkit tk2 = new Toolkit();
        write(toolsA, "t1:甲一\nt2:甲二\n");
        write(toolsB, "u1:乙一\n");
        McpClient a2 = stdioClient("alpha2", new File(home, "alpha2.pid"), toolsA, assets);
        McpClient b2 = stdioClient("bravo2", new File(home, "bravo2.pid"), toolsB, assets);
        McpManager mgr = McpManager.fromClients(tk2, Arrays.asList(a2, b2));
        trace("seg2.manager.startAll");
        mgr.startAll();
        p("SEG2_NAMES_BEFORE", tk2.getToolNames());
        long pid2 = Long.parseLong(read(new File(home, "alpha2.pid")).trim());
        Process k2 = new ProcessBuilder("kill", "-9", String.valueOf(pid2)).start();
        p("SEG2_KILL_RC", k2.waitFor());
        // server 侧换了工具名：老名字一个都不许留下
        write(toolsA, "renamed:改名后的工具\n");
        trace("seg2.manager.reload");
        int total = mgr.reload();
        p("SEG2_RELOAD_TOTAL", total);
        p("SEG2_NAMES_AFTER", tk2.getToolNames());
        p("SEG2_OLD_GONE", !tk2.getToolNames().contains("mcp-alpha2-t1")
                && !tk2.getToolNames().contains("mcp-alpha2-t2"));
        p("SEG2_NEW_PRESENT", tk2.getToolNames().contains("mcp-alpha2-renamed"));
        p("SEG2_BRAVO_INTACT", tk2.getToolNames().contains("mcp-bravo2-u1"));
        p("SEG2_JVM_PID", jvmPid());
        mgr.stopAll();
        p("SEG2_NAMES_AFTER_STOP", tk2.getToolNames());
        p("SEG2_EXPOSED_AFTER_STOP", tk2.getExposedToolNames());
        p("MCP_PHASE_DONE", "yes");
    }

    static McpClient stdioClient(String name, File pidFile, File toolsFile, File assets) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("python3", "python3"));
        cmd.add(new File(assets, "mcp_stub_server.py").getAbsolutePath());
        cmd.add(pidFile.getAbsolutePath());
        cmd.add(toolsFile.getAbsolutePath());
        return McpClientFactory.createStdio(new BotConfig.McpServerEntry(name, cmd));
    }

    // ===================== 结果上限 + 溢出落盘 =====================

    static void capPhase(File home) throws Exception {
        p("JVM_PID", jvmPid());
        p("OVERFLOW_DIR_PROPERTY", String.valueOf(System.getProperty(Toolkit.OVERFLOW_DIR_PROPERTY)));
        p("ZBOT_HOME_ENV", String.valueOf(System.getenv(Toolkit.ZBOT_HOME_ENV)));
        Toolkit tk = new Toolkit();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            body.append("0123456789abcdef");
        }
        final String big = body.toString(); // 4000 字符
        tk.setMaxResultChars(1_000L);
        tk.setPreviewChars(120L);
        tk.register(payloadTool("big_default", big), new ToolDescriptor(Toolsets.CORE, false, null,
                Toolkit.DEFAULT_OWNER, ToolDescriptor.PROBE_TTL_MS,
                ToolDescriptor.PROBE_FAILURE_GRACE_MS, ToolDescriptor.NO_MAX_RESULT_CHARS, null));
        ToolResult r = tk.execute("big_default", null);
        String note = r.getContent().substring(r.getContent().indexOf("\n\n["));
        p("CAP_BODY_LEN", big.length());
        p("CAP_GLOBAL", tk.getMaxResultChars());
        p("CAP_RESULT_LEN", r.getContent().length());
        p("CAP_PREVIEW_LEN", r.getContent().indexOf("\n\n["));
        p("CAP_NOTE", note.replace("\n", "\\n"));
        p("CAP_SAYS_CAP", note.contains("超单工具上限 1000"));

        File dir = new File(home, "tool-results");
        p("CAP_RESOLVED_OVERFLOW_DIR", String.valueOf(tk.getOverflowDir()));
        if (tk.getOverflowDir() != null) {
            File f = findOnlyFile(dir);
            p("CAP_FILE_NAME", f == null ? "NONE" : f.getName());
            p("CAP_FILE_LEN", f == null ? -1 : f.length());
            p("CAP_FILE_MD5", f == null ? "NONE" : md5(f));
            p("CAP_BODY_MD5", md5String(big));
            p("CAP_FILE_IS_FULL_BODY", f != null && md5(f).equals(md5String(big)));
            p("CAP_PATH_HINTED_IN_RESULT", f != null && r.getContent().contains(f.getAbsolutePath()));
        }

        // 工具自己声明更小的上限 ⇒ 走注册表口径，不吃全局值
        Toolkit tk2 = new Toolkit();
        tk2.setMaxResultChars(9_000L);
        tk2.register(payloadTool("small_declared", big), ToolDescriptor.withMaxResult(
                Toolsets.CORE, Toolkit.DEFAULT_OWNER, 300L));
        ToolResult r2 = tk2.execute("small_declared", null);
        p("CAP2_DECLARED", tk2.resultCapFor("small_declared"));
        p("CAP2_PREVIEW_LEN", r2.getContent().indexOf("\n\n["));

        // 声明不设限：一字不改
        Toolkit tk3 = new Toolkit();
        tk3.setMaxResultChars(10L);
        tk3.register(payloadTool("unbounded", big), ToolDescriptor.withMaxResult(
                Toolsets.FILE, Toolkit.DEFAULT_OWNER, ToolDescriptor.UNBOUNDED_RESULT_CHARS));
        ToolResult r3 = tk3.execute("unbounded", null);
        p("CAP3_UNBOUNDED_INTACT", r3.getContent().equals(big));
        p("CAP_PHASE_DONE", "yes");
    }

    static Tool payloadTool(final String name, final String body) {
        return Toolkit.of(name, "回吐定长文本", null, args -> ToolResult.text(body));
    }

    static File findOnlyFile(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) {
            return null;
        }
        Arrays.sort(fs);
        return fs[0];
    }

    // ===================== toolset 声明层 =====================

    static void toolsetsPhase(File home) throws Exception {
        Toolkit tk = new Toolkit();
        BuiltinTools.registerAll(tk, new Sandbox(new File(home, "workspace").getAbsolutePath()),
                "dangerous");
        p("DECLARED", Toolsets.declaredNames());
        p("CAPABILITY_NAMES", Toolsets.capabilityNames());
        p("TOOL_COUNT", tk.getToolNames().size());
        for (String ts : Toolsets.capabilityNames()) {
            p("TOOLSET_MEMBERS_" + ts, tk.namesOfToolset(ts));
        }
        p("EMPTY_CAPABILITY_AUDIT", Toolsets.emptyCapabilityToolsets(tk));
        p("UNDECLARED_TOOLSETS", Toolsets.undeclaredToolsets(tk));
        p("MANIFEST_NEVER_REGISTERED", Toolsets.manifestToolsNeverRegistered(tk));
        p("PARALLEL_SAFE", parallelSafe(tk));
        p("SCHEMA_FINGERPRINT", tk.schemaFingerprint());
        p("GENERATION", tk.generation());
        p("REBUILDS", tk.schemaSnapshotRebuilds());
        p("AUDIT_AFTER_DROP_EXEC", dropAndAudit(tk));
        p("TOOLSETS_PHASE_DONE", "yes");
    }

    static String parallelSafe(Toolkit tk) {
        StringBuilder sb = new StringBuilder();
        for (String n : tk.getToolNames()) {
            sb.append(n).append(tk.isParallelSafe(n) ? "=ro " : "=rw ");
        }
        return sb.toString().trim();
    }

    static String dropAndAudit(Toolkit tk) {
        for (String n : new ArrayList<String>(tk.namesOfToolset(Toolsets.EXEC))) {
            tk.deregister(n);
        }
        return Toolsets.emptyCapabilityToolsets(tk).toString();
    }

    // ===================== 小工具 =====================

    static Map<String, Object> args2(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    static void write(File f, String s) throws Exception {
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f), "UTF-8");
        w.write(s);
        w.close();
    }

    static String read(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(buf, "UTF-8");
    }

    static String md5(File f) throws Exception {
        return md5String(read(f));
    }

    static String md5String(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] raw = md.digest(s.getBytes("UTF-8"));
        StringBuilder out = new StringBuilder();
        for (byte bb : raw) {
            out.append(Character.forDigit((bb >> 4) & 0xF, 16)).append(Character.forDigit(bb & 0xF, 16));
        }
        return out.toString();
    }

}
