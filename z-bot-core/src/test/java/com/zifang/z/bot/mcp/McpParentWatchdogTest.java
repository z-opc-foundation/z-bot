package com.zifang.z.bot.mcp;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * P21 §5：父死 watchdog。口径按工单钉死 —— {@code kill -9} 掉"z-bot"（这里是
 * {@link McpWatchdogProbe} 那个真 JVM）之后，MCP 子进程<b>不得成为孤儿</b>；
 * 前后 {@code pgrep -P} 计数各贴一次。
 *
 * <p>四支测试是一套<b>双向</b>取证，缺对照就证不了什么：</p>
 * <ul>
 *   <li>{@link #killedParentTakesTheChildWithIt}：生产路径（真握手拉官方 SDK server）
 *       + watchdog 开 ⇒ 子进程必须跟着没。</li>
 *   <li>{@link #watchdogTakesEvenAnEofInsensitiveChildWithIt}：同一份 {@code launchArgv()}
 *       产物，但孩子换成不理 stdin 的长睡进程 ⇒ 照样带走。</li>
 *   <li>{@link #withoutWatchdogAnEofInsensitiveChildReallyBecomesAnOrphan}：同一个睡进程、
 *       watchdog 关 ⇒ <b>确实</b>活成孤儿。这一条就是"猎物"：它钉住"孙子没了"是 watchdog 干的。</li>
 *   <li>{@link #realServerExitsOnStdinEofSoItCannotServeAsTheControl}：把"为什么上一条的对照
 *       不能用真 server"量成实据 —— 真 server 在父死时 stdin EOF 会自己退，"没了"分不出谁干的。</li>
 * </ul>
 *
 * <p>计数一律用 pid 反查（{@code ps -o ppid}），不用命令行 pattern ——
 * 同机还有 p12e/p18b 在跑 mvn，pattern 会串号。</p>
 */
public class McpParentWatchdogTest {

    /** 收尾等待的上限：绝不允许无界轮询（量具纪律）。 */
    private static final long READY_BUDGET_MILLIS = 40_000L;
    private static final long DEATH_BUDGET_MILLIS = 12_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<Long> mustKill = new ArrayList<Long>();
    private File readings;
    private Process probe;

    @Before
    public void requireHarness() throws IOException {
        RealMcpHarness.requireOfficialSdk();
        readings = readingsFile();
    }

    @After
    public void tearDown() {
        // 有上限的收尾：先杀探针，再兜底杀任何还在的登记 pid；每条各带超时
        RealMcpHarness.destroyQuietly(probe);
        probe = null;
        for (Long pid : mustKill) {
            try {
                if (McpWatchdogProbe.alive(pid.longValue())) {
                    kill(pid.longValue());
                    RealMcpHarness.sleep(300L);
                }
            } catch (IOException ignored) {
            }
        }
        mustKill.clear();
    }

    // ------------------------------------------------------------------ 用例

    /** 生产路径：{@link ZBotStdioMcpTransport} 真握手拉起官方 SDK server，父被 {@code kill -9}。 */
    @Test
    public void killedParentTakesTheChildWithIt() throws Exception {
        Outcome on = runProbe(true, "mcp");
        record("watchdog=on mode=mcp", on);
        assertEquals("拉起后探针只该有一个直接子进程（/bin/sh 监护脚本 exec 成真 server）",
                1, on.pgrepBefore);
        assertFalse("子进程还活着 ⇒ watchdog 没兑现，它就是孤儿: " + on.childPids
                + "（改嫁后的 ppid=" + on.childPpidAfterAdoption + "）", on.childStillAlive);
        assertEquals("父死之后 pgrep -P 计数", 0, on.childrenAfterKillCount);
        assertTrue("带走用了 " + on.millisToGone + "ms，应落在监护周期内",
                on.millisToGone >= 0L && on.millisToGone <= 5000L);
    }

    /**
     * 把混淆因子摘掉的那一条：child 换成<b>完全不理 stdin</b> 的长睡进程，
     * 但仍走 {@code launchArgv()} 产出的同一份 argv（含监护脚本）。
     * 这条绿了才说明"带走了"不依赖"孩子自己会退"。
     */
    @Test
    public void watchdogTakesEvenAnEofInsensitiveChildWithIt() throws Exception {
        Outcome on = runProbe(true, "sleeper");
        record("watchdog=on mode=sleeper", on);
        assertEquals(1, on.pgrepBefore);
        assertFalse("EOF 都不理的孩子都还活着 ⇒ 监护脚本根本没在杀父: " + on.childPids,
                on.childStillAlive);
        assertTrue("带走用了 " + on.millisToGone + "ms", on.millisToGone >= 0L && on.millisToGone <= 5000L);
    }

    /**
     * 阳性对照（猎物）：同一个不理 stdin 的孩子、同一份 argv 生成器，只是 watchdog 关 ⇒
     * 子进程<b>确实</b>活下来且 ppid 改嫁。上一条的"没了"在这条对照下才只可能是 watchdog 干的。
     */
    @Test
    public void withoutWatchdogAnEofInsensitiveChildReallyBecomesAnOrphan() throws Exception {
        Outcome off = runProbe(false, "sleeper");
        record("watchdog=off mode=sleeper", off);
        assertEquals(1, off.childPids.size());
        long child = off.childPids.get(0).longValue();
        assertTrue("对照组失效：没有 watchdog 时子进程应当还活着，实际已退出",
                off.childStillAlive);
        assertNotEquals("子进程 ppid 应当已从探针 JVM 改嫁成孤儿",
                String.valueOf(off.probePid), off.childPpidAfterAdoption.trim());
        assertFalse("对照组里子进程不该被带走（带走了就说明还有别的东西在收尾）",
                off.millisToGone >= 0L);
        // 收尾：这条留下的孤儿必须被清掉，且要确认清掉了（不留给下一支测试）
        kill(child);
        long deadline = System.currentTimeMillis() + DEATH_BUDGET_MILLIS;
        while (McpWatchdogProbe.alive(child) && System.currentTimeMillis() < deadline) {
            RealMcpHarness.sleep(100L);
        }
        assertFalse("清理失败，孤儿还在：" + child, McpWatchdogProbe.alive(child));
        mustKill.remove(Long.valueOf(child));
    }

    /**
     * 顺手把"为什么对照组不能用真 server"量成实据：真 ref server 在父死时 stdin 拿到 EOF，
     * 自己就退了 —— 所以 {@code watchdog=off} 的 mcp 那条<b>结构上分不出</b>是谁杀的，
     * 只能当观察、不能当对照（工单口径要求的 pid 前后计数仍然贴）。
     */
    /**
     * 顺手把"为什么对照组不能用真 server"量成实据：真 ref server 在父死时 stdin 拿到 EOF，
     * 自己就退了 —— 也就是 {@code watchdog=off} 时它照样"消失"。那条上"消失"分不出是谁干的，
     * 所以真正的对照必须换成不理 stdin 的孩子（上一条）。
     * 若哪天这条变红，说明 EOF 混淆因子不存在了，§5 的对照设计要重新评估。
     */
    @Test
    public void realServerExitsOnStdinEofSoItCannotServeAsTheControl() throws Exception {
        Outcome off = runProbe(false, "mcp");
        record("watchdog=off mode=mcp", off);
        assertEquals(1, off.childPids.size());
        assertFalse("预期：watchdog 关着真 server 也会因 stdin EOF 自己退出"
                + "（这正是它不能当对照的原因）。它还活着 ⇒ 混淆因子没了，对照设计要重估",
                off.childStillAlive);
    }

    // ------------------------------------------------------------------ 跑一次探针

    private static final class Outcome {
        long probePid;
        List<Long> childPids = new ArrayList<Long>();
        boolean childAlive;
        String childPpidAfterAdoption = "";
        int childrenAfterKillCount;
        boolean childStillAlive;
        int pgrepBefore;
        int pgrepAfterKillImmediately;
        long millisToGone = -1L;
        long orphanObservedMillis;
    }

    /**
     * 起探针 → 等 ready → 记录父死前状态 → {@code kill -9} 探针 → 轮询子进程状态。
     *
     * <p>断言"父死之后"一律等<b>因果那一边</b>（子进程真没了 / 真还活着），
     * 不等某一行日志 —— P17 量过的日志陈旧窗口在这里会直接把假阳性写成绿。</p>
     *
     * <p>第二道量具坑（实测踩过，故写死在这里）：{@code pgrep -P 探针} 在 {@code kill -9}
     * 之后<b>立刻</b>返回 0 —— 父一死孩子就被改嫁，那个计数先归零，跟孩子死没死无关。
     * 用它当扳机会把"没带走"读成"带走了"。所以下面的扳机挂在<b>子进程自身的死活与父号</b>上，
     * {@code pgrep -P} 只作为工单要求的"前后各贴一次"的读数记账。</p>
     */
    private Outcome runProbe(boolean watchdog, String mode) throws Exception {
        File ready = new File(tmp.getRoot(), "ready-" + mode + "-" + watchdog + ".txt");
        File log = new File(tmp.getRoot(), "probe-" + mode + "-" + watchdog + ".log");
        ProcessBuilder pb = new ProcessBuilder(java.util.Arrays.asList(
                java(System.getProperty("java.home")), "-cp",
                System.getProperty("java.class.path"),
                McpWatchdogProbe.class.getName(),
                ready.getAbsolutePath(), String.valueOf(watchdog), mode));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        probe = pb.start();

        String first = waitForReady(ready, probe);
        Outcome o = new Outcome();
        String[] parts = first.trim().split("\\s+");
        o.probePid = Long.parseLong(parts[1]);
        mustKill.add(Long.valueOf(o.probePid));
        for (int i = 2; i < parts.length; i++) {
            o.childPids.add(Long.valueOf(parts[i]));
        }
        o.pgrepBefore = o.childPids.size();
        o.childAlive = !o.childPids.isEmpty() && McpWatchdogProbe.alive(o.childPids.get(0));
        assertTrue("探针报 ready 但子进程不在: " + first, o.childAlive);

        // —— 父死：SIGKILL，shutdown hook 一律不跑（这正是这一节要的场景）
        kill(o.probePid);
        o.pgrepAfterKillImmediately = countChildren(o.probePid);
        long child = o.childPids.get(0).longValue();
        long t0 = System.currentTimeMillis();
        long deadline = t0 + DEATH_BUDGET_MILLIS;
        // 扳机挂在"子进程自身的死活/父号"上，不挂 pgrep -P 探针 —— 探针一死它的孩子
        // 立刻被改嫁，那个计数永远先归零，会把"没带走"读成"带走了"。
        while (System.currentTimeMillis() < deadline) {
            if (!McpWatchdogProbe.alive(child)) {
                o.millisToGone = System.currentTimeMillis() - t0;
                o.childStillAlive = false;
                break;
            }
            String pp = McpWatchdogProbe.ppidOf(child);
            if (!String.valueOf(o.probePid).equals(pp.trim())) {
                o.childPpidAfterAdoption = pp.trim();
                if (!watchdog) {
                    // 对照组：改嫁已成事实，再多看 1.5s（> 监护轮询 0.2s 的 7 个周期）
                    // 确认它不是"只是慢"，而是真的会永久留成孤儿
                    o.orphanObservedMillis = System.currentTimeMillis() - t0;
                    RealMcpHarness.sleep(1500L);
                    break;
                }
            }
            RealMcpHarness.sleep(100L);
        }
        o.childrenAfterKillCount = countChildren(o.probePid);
        o.childStillAlive = McpWatchdogProbe.alive(child);
        if (o.childStillAlive && o.childPpidAfterAdoption.isEmpty()) {
            o.childPpidAfterAdoption = McpWatchdogProbe.ppidOf(child);
        }
        if (o.millisToGone < 0L && !o.childStillAlive) {
            o.millisToGone = System.currentTimeMillis() - t0;
        }
        return o;
    }

    private static String waitForReady(File ready, Process p) throws IOException {
        long deadline = System.currentTimeMillis() + READY_BUDGET_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (ready.isFile() && ready.length() > 0) {
                String s = RealMcpHarness.tail(ready);
                int nl = s.indexOf('\n');
                String line = nl < 0 ? s : s.substring(0, nl);
                if (line.startsWith("READY ")) {
                    return line;
                }
            }
            if (!p.isAlive()) {
                throw new IOException("探针提前退出，exit=" + p.exitValue()
                        + "，现场见 " + ready.getParent());
            }
            RealMcpHarness.sleep(100L);
        }
        throw new IOException("等不到探针 ready-file: " + ready);
    }

    private static int countChildren(long parentPid) throws IOException {
        return McpWatchdogProbe.directChildrenOf(parentPid).size();
    }

    private static String java(String javaHome) {
        return javaHome + "/bin/java";
    }

    private void kill(long pid) throws IOException {
        Process p = new ProcessBuilder("/bin/kill", "-9", String.valueOf(pid))
                .redirectErrorStream(true).start();
        try {
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 读数落盘

    private static File readingsFile() throws IOException {
        String dir = System.getProperty("zbot.p21.readings.dir",
                System.getProperty("user.home") + "/.cache/zbot-p21");
        File d = new File(dir);
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            throw new IOException("建不出读数目录: " + d);
        }
        return new File(d, "p21_watchdog_readings.txt");
    }

    private void record(String tag, Outcome o) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(tag)
                .append(" probePid=").append(o.probePid)
                .append(" childPids=").append(o.childPids)
                .append(" pgrep-P-before=").append(o.pgrepBefore)
                .append(" pgrep-P-immediately-after-kill=").append(o.pgrepAfterKillImmediately)
                .append(" pgrep-P-after=").append(o.childrenAfterKillCount)
                .append(" childAliveAfterKill=").append(o.childStillAlive)
                .append(" childPpidAfterKill='").append(o.childPpidAfterAdoption).append('\'')
                .append(" msUntilChildGone=").append(o.millisToGone)
                .append(" orphanObservedAtMs=").append(o.orphanObservedMillis)
                .append('\n');
        Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(readings, true), StandardCharsets.UTF_8));
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
        System.out.println("[p21][§5] " + sb.toString().trim());
    }
}
