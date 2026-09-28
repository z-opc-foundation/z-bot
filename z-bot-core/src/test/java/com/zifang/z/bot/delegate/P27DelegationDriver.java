package com.zifang.z.bot.delegate;

import com.zifang.z.agent.kernel.llm.ChatCompletionsRequest;
import com.zifang.z.agent.kernel.llm.ChatCompletionsResponse;
import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.agent.kernel.message.ToolCall;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.bot.agent.BotAgent;
import com.zifang.z.bot.agent.StreamListener;
import com.zifang.z.bot.config.BotConfig;
import com.zifang.z.bot.session.SessionManager;
import com.zifang.z.bot.tool.Sandbox;
import com.zifang.z.bot.tool.env.ProcessTree;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * P27 杠③ 真进程 E2E 驱动（**不是单测**，由 {@code _doc/acceptance/p27/p27_e2e.py} 以
 * {@code java -cp <真 jar + target/classes ...>} 起真子进程跑，本进程本身会被 {@code kill -9}）。
 *
 * <p>三种模式：
 * <ul>
 *   <li>{@code --mode dispatch}：起一条异步委托（子代理里再 fork 一个真 {@code /bin/sleep}
 *       当孙进程），把委托 id 打到 stdout，然后一直活着等量具 {@code kill -9}；</li>
 *   <li>{@code --mode inspect}：**另一个** JVM 进程，从盘上把上一条现场读回来判词，
 *       再跑孤儿认领 / 保留期回收（正反两问）/ 投递上限（8 次）三段取证；</li>
 * </ul>
 * 每步都打 {@code E2E|key=value} 一行，量具按 token 锚定判读，不拿"尾巴"当结论。</p>
 */
public final class P27DelegationDriver {

    private static final AtomicLong GRANDCHILD_PID = new AtomicLong(-1L);

    private P27DelegationDriver() {
    }

    public static void main(String[] args) throws Exception {
        File configDir = argDir(args, "--config-dir");
        String mode = arg(args, "--mode", "inspect");
        if (configDir == null) {
            System.out.println("E2E|fatal=缺 --config-dir");
            System.exit(2);
        }
        if (!configDir.isDirectory()) {
            System.out.println("E2E|fatal=config-dir 不存在 " + configDir);
            System.exit(2);
        }
        if ("dispatch".equals(mode)) {
            dispatch(configDir);
        } else if ("inspect".equals(mode)) {
            inspect(configDir);
        } else {
            System.out.println("E2E|fatal=未知 mode " + mode);
            System.exit(2);
        }
    }

    // ================= dispatch：把现场留在全盘上，然后被干掉 =================

    private static void dispatch(File configDir) throws Exception {
        BotConfig cfg = BotConfig.load(configDir);
        File sandboxRoot = new File(configDir, "workspace");
        assertTrue(sandboxRoot.isDirectory() || sandboxRoot.mkdirs(), "workspace 建不出来");
        BotAgent agent = BotAgent.builder(cfg)
                .provider(new SleeperProvider())
                .sandbox(new Sandbox(sandboxRoot.getAbsolutePath()))
                .sessionManager(new SessionManager(new File(configDir, "sessions")))
                .delegateDepth(0)
                .withoutCenter()
                .model("e2e-stub-model")
                .build();
        DelegateManager dm = agent.getDelegation();
        if (dm == null) {
            System.out.println("E2E|fatal=delegate 面没接上（检查 agent.delegate.max.depth）");
            System.exit(3);
        }
        System.out.println("E2E|ledger_root=" + dm.liveLedger().root());
        System.out.println("E2E|owner_pid=" + DelegationLedger.currentPid());
        String submitted = dm.submitAsync("p27 真进程委托：子代理里 fork 一个 sleep 然后卡住");
        String id = submitted.replaceFirst(".*(bg[0-9]+-[0-9]+).*", "$1");
        System.out.println("E2E|delegation_id=" + id);
        System.out.println("E2E|submit_line=" + oneLine(submitted));
        System.out.flush();
        // 一直活着：量具会在确认盘上现场之后把本进程 kill -9。这里只设一个天花板，绝不无界。
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
        }
        System.out.println("E2E|deadline_reached=true");
        System.exit(0);
    }

    // ================= inspect：另一个进程，只从盘上判词 =================

    private static void inspect(File configDir) throws Exception {
        int checks = 0;
        int failed = 0;
        File root = new File(configDir, "delegate/live");
        System.out.println("E2E|inspect_root=" + root);
        System.out.println("E2E|root_exists=" + root.isDirectory());
        DelegationLedger ledger = new DelegationLedger(root);
        List<DelegationLedger.Entry> all = ledger.list();
        System.out.println("E2E|scenes_on_disk=" + all.size());
        DelegationLedger.Entry killed = null;
        for (DelegationLedger.Entry e : all) {
            System.out.println("E2E|scene id=" + e.id + " state=" + e.state + " delivery=" + e.delivery
                    + " attempts=" + e.deliveryAttempts + " owner_pid=" + e.ownerPid
                    + " dispatched_at=" + e.dispatchedAt + " updated_at=" + e.updatedAt
                    + " child_session=" + oneLine(e.childSession));
            if (e.id.startsWith("bg")) {
                killed = e;
            }
        }
        if (killed == null) {
            System.out.println("E2E|FATAL=no_killed_scene");
            System.exit(4);
        }
        // ① 被干掉的委托方留下的现场：非终态 + 投递一格没动
        checks++;
        if (!(!killed.state.terminal() && killed.delivery == DeliveryState.PENDING
                && killed.deliveryAttempts == 0)) {
            failed++;
        }
        System.out.println("E2E|after_kill state=" + killed.state + " delivery=" + killed.delivery
                + " attempts=" + killed.deliveryAttempts + " non_terminal=" + !killed.state.terminal());
        // ② 未过期不许删（正向证据）
        checks++;
        int prunedTooEarly = ledger.pruneStale(DelegationLedger.LIVE_RETENTION_MILLIS, System.currentTimeMillis());
        if (prunedTooEarly != 0) {
            failed++;
        }
        System.out.println("E2E|prune_before_adoption=" + prunedTooEarly);
        // ③ 孤儿认领：死掉的委托方必须被判成 UNKNOWN
        checks++;
        int adopted = ledger.adoptOrphans(0L, System.currentTimeMillis());
        DelegationLedger.Entry afterAdopt = ledger.load(killed.id);
        if (adopted != 1 || afterAdopt == null || afterAdopt.state != DelegateState.UNKNOWN) {
            failed++;
        }
        System.out.println("E2E|adopted=" + adopted + " state_after_adopt="
                + (afterAdopt == null ? "MISSING" : afterAdopt.state)
                + " events=" + ledger.tail(killed.id, 20).size());
        // ④ 到点就得删（反向证据）：同一条现场，窗口取 0 ⇒ 必须被回收
        checks++;
        int pruned = ledger.pruneStale(0L, System.currentTimeMillis());
        System.out.println("E2E|prune_with_zero_window=" + pruned + " still_readable=" + (ledger.load(killed.id) != null));
        if (pruned != 1 || ledger.load(killed.id) != null) {
            failed++;
        }

        // ⑤ 投递上限从盘上取证：一条 DONE 没人接的现场，烧 9 次
        DelegationLedger.Entry done = ledger.create("e2e-cap", "结果在盘上、对端是拉模式控制台", 0, "cap", null);
        ledger.advance(done, DelegateEvent.TASK_SPAWNED, "起跑");
        done.reply = "e2e-cap-reply";
        ledger.advance(done, DelegateEvent.TASK_COMPLETED, "收工");
        DelegationDelivery dd = new DelegationDelivery(ledger);
        checks++;
        if (dd.stateOf("e2e-cap") != DeliveryState.PENDING) {
            failed++;
        }
        System.out.println("E2E|cap_before_pull=" + dd.describe("e2e-cap"));
        String lastToken = null;
        for (int i = 1; i <= 9; i++) {
            String token = dd.claim("e2e-cap", "pull-console");
            if (token == null) {
                System.out.println("E2E|cap_attempt_" + i + "=refused " + dd.describe("e2e-cap"));
                checks++;
                if (i != DelegationDelivery.MAX_DELIVERY_ATTEMPTS + 1) {
                    failed++;
                }
                break;
            }
            lastToken = token;
            dd.release("e2e-cap", token);
            System.out.println("E2E|cap_attempt_" + i + "=" + dd.describe("e2e-cap"));
        }
        checks++;
        if (dd.stateOf("e2e-cap") != DeliveryState.DROPPED
                || dd.attempts("e2e-cap") != DelegationDelivery.MAX_DELIVERY_ATTEMPTS) {
            failed++;
        }
        System.out.println("E2E|cap_final=" + dd.describe("e2e-cap") + " token_used_last=" + lastToken
                + " restore_offered=" + dd.undeliveredTerminalResults().size());
        checks++;
        if (!dd.undeliveredTerminalResults().isEmpty()) {
            failed++; // dropped 不许被重放
        }
        // ⑥ 上限的可取证性：连 attempts 都是盘上读回来的（再换一个实例）
        checks++;
        DelegationLedger reopened = new DelegationLedger(root);
        DelegationLedger.Entry capRow = reopened.load("e2e-cap");
        if (capRow == null || capRow.deliveryAttempts != 8 || capRow.delivery != DeliveryState.DROPPED) {
            failed++;
        }
        System.out.println("E2E|cap_from_fresh_instance=" + (capRow == null ? "MISSING"
                : capRow.delivery + "(" + capRow.deliveryAttempts + ")"));
        // ⑦ 原子写不许留 tmp
        checks++;
        boolean tmpLeft = new File(root, "e2e-cap/state.json.tmp").exists();
        System.out.println("E2E|tmp_residue=" + tmpLeft);
        if (tmpLeft) {
            failed++;
        }
        // ⑧ 无凭证不许 ack（大声失败，投给拉模式控制台也不记 ok）
        checks++;
        DelegationLedger.Entry fresh = ledger.create("e2e-noclaim", "没 claim 就想签收", 0, "", null);
        String loudFail = "none";
        try {
            new DelegationDelivery(ledger).complete("e2e-noclaim", "凭空的凭证");
        } catch (IllegalStateException expected) {
            loudFail = "IllegalStateException";
        }
        System.out.println("E2E|ack_without_claim=" + loudFail + " delivery="
                + new DelegationDelivery(ledger).stateOf("e2e-noclaim"));
        if (!"IllegalStateException".equals(loudFail)) {
            failed++;
        }
        System.out.println("E2E|CHECKS=" + checks + " FAILED=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ================= helpers =================

    /** 子代理侧的 LLM 替身：真 fork 一个 {@code /bin/sleep}，然后把本进程卡住等死。 */
    private static final class SleeperProvider implements LlmProvider {
        @Override
        public String name() {
            return "e2e-sleeper";
        }

        @Override
        public List<Model> listModels() {
            return Collections.emptyList();
        }

        @Override
        public boolean supportsModel(String modelId) {
            return true;
        }

        @Override
        public ChatCompletionsResponse chat(ChatCompletionsRequest request) {
            try {
                Process p = new ProcessBuilder("/bin/sleep", "40").start();
                // {@code Process#pid()} 是 Java 9+：JDK 8 上按本仓口径走 {@link ProcessTree#pidOf}
                // （反射读 UNIXProcess 的私有 pid 字段），取不到就是 0，不猜。
                long grandchildPid = ProcessTree.pidOf(p);
                GRANDCHILD_PID.set(grandchildPid);
                System.out.println("E2E|grandchild_pid=" + grandchildPid + "（子代理真 fork 的孙进程，"
                        + "委托方被 kill -9 之后它还在不在由量具判）");
                System.out.flush();
                p.waitFor(30, TimeUnit.SECONDS);   // 有界，绝不裸 waitFor()
            } catch (Exception e) {
                System.out.println("E2E|grandchild_error=" + e);
            }
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new ChatCompletionsResponse("e2e", "e2e-stub-model",
                    Collections.singletonList(new ChatCompletionsResponse.Choice(0,
                            "e2e-child-reply", Collections.<ToolCall>emptyList(), "stop")),
                    new TokenUsage(1L, 1L, 2L), "stop", null);
        }

        @Override
        public void streamChat(ChatCompletionsRequest request, Consumer<ChatCompletionsResponse> onChunk,
                               Consumer<Throwable> onError) {
            onChunk.accept(chat(request));
        }
    }

    private static File argDir(String[] args, String key) {
        String v = arg(args, key, null);
        return v == null ? null : new File(v);
    }

    private static String arg(String[] args, String key, String dflt) {
        List<String> a = new ArrayList<String>();
        Collections.addAll(a, args);
        int i = a.indexOf(key);
        return i >= 0 && i + 1 < a.size() ? a.get(i + 1) : dflt;
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            System.out.println("E2E|fatal=" + msg);
            System.exit(5);
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
