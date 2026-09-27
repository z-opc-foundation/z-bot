package com.zifang.z.bot.delegate;

import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.ExecGuard;
import com.zifang.z.bot.tool.Sandbox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * P27c 杠③ 真进程 E2E 驱动（**不是单测**：名字不以 {@code Test} 结尾 ⇒ surefire 不收它，
 * 由 {@code _doc/acceptance/p27c/p27c_e2e.py} 以 {@code java -cp <真 jar + target/classes>}
 * 起真子进程跑）。
 *
 * <p>与单测那一层的分工：{@code SubagentApprovalDenialTest} / {@code DelegateSummaryWiringTest}
 * 里塞的是脚本化 {@code LlmProvider} 替身，进程边界以下的一切（{@code LlmRouter} 造的**真 HTTP
 * 客户端**、config 装配、沙箱落盘）都没走。这里**故意不注入 provider** ⇒
 * {@code BotAgent.build()} 走 {@code LlmRouter.create(config.activeProvider())}，
 * 每一次模型请求都是量具那个 127.0.0.1 假端点收到的一真 POST。</p>
 *
 * <p>三种模式（每轮各起一次进程，互不共享状态）：
 * <ul>
 *   <li>{@code --mode deny}：父 → {@code delegate_task} → 子 → {@code exec rm -rf ./target-cache}
 *       ⇒ 申请必须当场 deny（沙箱里那个目录得活着），子代理的长回复进父上下文前要过摘要尺；</li>
 *   <li>{@code --mode async}：{@code submitBackground} 走线程池那条路，取回的回复过同一把尺；</li>
 *   <li>{@code --mode closed}：{@code agent.shutdown()} 之后再提交 ⇒ 当场拒收并在台账上判 FAILED
 *       （不留在 QUEUED 占宽度槽）。</li>
 * </ul>
 * 每步打 {@code E2E|key=value} 一行；**判词全在量具那一侧**（盘 + 假端点收到的字节），
 * 本驱动只负责把事实摆到盘上。</p>
 */
public final class P27cDelegateDriver {

    private P27cDelegateDriver() {
    }

    public static void main(String[] args) throws Exception {
        File configDir = argDir(args, "--config-dir");
        String mode = arg(args, "--mode", "deny");
        if (configDir == null || !configDir.isDirectory()) {
            System.out.println("E2E|fatal=config-dir 缺失或不存在 " + configDir);
            System.exit(2);
        }
        BotConfig cfg = BotConfig.load(configDir);
        File sandboxRoot = new File(configDir, "workspace");
        assertTrue(sandboxRoot.isDirectory() || sandboxRoot.mkdirs(), "workspace 建不出来");
        // 诱饵：子代理那声 `rm -rf ./target-cache` 真的执行过就没了。
        File cache = new File(sandboxRoot, "target-cache");
        File keep = new File(cache, "keep.txt");
        assertTrue(cache.isDirectory() || cache.mkdirs(), "target-cache 建不出来");
        write(keep, "p27c-sentinel-must-survive\n");

        BotAgent agent = BotAgent.builder(cfg)
                .sandbox(new Sandbox(sandboxRoot.getAbsolutePath()))
                .sessionManager(new SessionManager(new File(configDir, "sessions")))
                .delegateDepth(0)
                .withoutCenter()
                .model("e2e-stub-model")
                .build();
        if (agent.getDelegation() == null) {
            System.out.println("E2E|fatal=delegate 面没接上（查 agent.delegate.max.depth）");
            System.exit(3);
        }
        System.out.println("E2E|mode=" + mode);
        System.out.println("E2E|config_summary_cap=" + cfg.getDelegateMaxSummaryChars());
        System.out.println("E2E|summaries_dir=" + agent.getDelegation().summariesRoot());
        System.out.println("E2E|ledger_root=" + agent.getDelegation().liveLedger().root());
        System.out.println("E2E|sentinel=" + keep.getAbsolutePath()
                + " exists_before=" + keep.isFile());

        if ("deny".equals(mode)) {
            deny(agent, keep);
        } else if ("async".equals(mode)) {
            async(agent, keep);
        } else if ("closed".equals(mode)) {
            closed(agent);
        } else {
            System.out.println("E2E|fatal=未知 mode " + mode);
            System.exit(2);
        }
        agent.shutdown();
        System.out.flush();
    }

    // ================= deny + 摘要尺（同步那条路）=================

    private static void deny(BotAgent agent, File keep) throws Exception {
        String reply = agent.chat("p27c 真进程：派一个子代理去删目录", StreamListener.NOOP);
        BotAgent child = agent.getDelegation().lastChild;
        System.out.println("E2E|parent_reply=" + oneLine(reply));
        System.out.println("E2E|parent_reply_b64=" + b64(reply));
        System.out.println("E2E|child_present=" + (child != null));
        if (child != null) {
            System.out.println("E2E|child_non_interactive=" + child.isNonInteractive());
            System.out.println("E2E|child_auto_denied=" + child.subagentAutoDeniedApprovals());
            System.out.println("E2E|child_pending=" + child.pendingApprovals().size());
        }
        System.out.println("E2E|parent_pending=" + agent.pendingApprovals().size());
        System.out.println("E2E|sentinel_after=" + keep.isFile());
        System.out.println("E2E|approval_prefix=" + oneLine(ExecGuard.APPROVAL_PREFIX));
        printSpills(agent);
    }

    // ================= 摘要尺（异步那条出口）=================

    private static void async(BotAgent agent, File keep) throws Exception {
        String submitted = agent.submitBackground("p27c 真进程异步：写一段长回复");
        System.out.println("E2E|submit_line=" + oneLine(submitted));
        String id = submitted.replaceFirst(".*(bg[0-9]+-[0-9]+).*", "$1");
        String result = null;
        long deadline = System.currentTimeMillis() + 30_000L;   // 有界，绝不无界 await()
        while (System.currentTimeMillis() < deadline) {
            result = agent.backgroundResult(id);
            if (result != null && !result.contains("QUEUED") && !result.contains("RUNNING")) {
                break;
            }
            Thread.sleep(100L);
        }
        System.out.println("E2E|background_result_b64=" + b64(result));
        System.out.println("E2E|background_result_len=" + (result == null ? -1 : result.length()));
        // 再拉一次：读路径不该再写第二份溢出文件（她收集时裁一次；修之前是每次拉取都裁一遍，
        // 文件名带毫秒戳 ⇒ 同一个委托 id 拉几次就多几个全文文件）。
        String second = agent.backgroundResult(id);
        System.out.println("E2E|background_result2_b64=" + b64(second));
        System.out.println("E2E|async_sentinel_after=" + keep.isFile());
        BotAgent child = agent.getDelegation().lastChild;
        System.out.println("E2E|async_child_auto_denied="
                + (child == null ? -1 : child.subagentAutoDeniedApprovals()));
        System.out.println("E2E|async_child_shutdown="
                + (child == null ? "none" : String.valueOf(child.isShutDown())));
        printSpills(agent);
    }

    // ================= 池随 agent 收口 =================

    private static void closed(BotAgent agent) throws Exception {
        agent.shutdown();
        String refused = agent.submitBackground("池已收，这条不该被接下");
        System.out.println("E2E|refused_line=" + oneLine(refused));
        List<String> states = new ArrayList<String>();
        File root = agent.getDelegation().liveLedger().root();
        File[] scenes = root.isDirectory() ? root.listFiles() : new File[0];
        if (scenes != null) {
            for (File scene : scenes) {
                File state = new File(scene, "state.json");
                if (!state.isFile()) {
                    continue;
                }
                String body = new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8);
                int i = body.indexOf("\"state\"");
                states.add(scene.getName() + "=" + (i < 0 ? "?" : body.substring(i, Math.min(body.length(), i + 24))));
            }
        }
        System.out.println("E2E|scenes=" + states);
        System.out.println("E2E|shutDown_flag=" + agent.isShutDown());
    }

    // ===== 小工具 =====

    private static void printSpills(BotAgent agent) {
        File dir = agent.getDelegation().summariesRoot();
        System.out.println("E2E|spill_dir=" + dir);
        File[] files = dir != null && dir.isDirectory() ? dir.listFiles() : null;
        int n = files == null ? 0 : files.length;
        System.out.println("E2E|spill_files=" + n);
        for (int i = 0; i < n; i++) {
            System.out.println("E2E|spill_name=" + files[i].getName()
                    + " bytes=" + files[i].length());
        }
    }

    private static void write(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static String oneLine(String s) {
        return s == null ? "null" : s.replace("\r", " ").replace("\n", "⏎");
    }

    /**
     * 量具那边的解析是按"键=非空格串"取的 ⇒ 带空格的原文会被从第一个空格切掉
     * （run1 里那句 footer 就是这么"看不见"的）。要送整份原文只能转码，不能截。
     */
    private static String b64(String s) {
        return s == null ? "null"
                : java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertTrue(boolean ok, String what) {
        if (!ok) {
            System.out.println("E2E|fatal=" + what);
            System.exit(4);
        }
    }

    private static String arg(String[] args, String key, String fallback) {
        String v = arg(args, key);
        return v == null ? fallback : v;
    }

    private static String arg(String[] args, String key) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static File argDir(String[] args, String key) {
        String v = arg(args, key);
        return v == null ? null : new File(v);
    }
}
