package com.zifang.z.bot.tool.env;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 进程树收尾（P22）。
 *
 * <p>{@code Process#destroyForcibly()} 只杀直接子进程；{@code bash -c "sleep 300 &"} 那种
 * 挂在 bash 名下的孙进程会变成孤儿继续跑。hermes 的 {@code base.py:289 ProcessHandle}
 * 把 {@code kill} 定成"这一坨都没了"，仓里 P12 也已经认了这个口径
 * （{@code agent/InterruptScope#killTree} 先取后代再杀自己）。</p>
 *
 * <p>注意仓里现存的三套 exec 都没做这件事：{@code CheckpointManager#exec} 超时只
 * {@code destroyForcibly()} 自己那一层，{@code BuiltinTools#bash} 压根没有时间上限。</p>
 *
 * <h2>Java 8 下怎么拿到"整棵树"（本类的移植口径）</h2>
 *
 * <p>{@code Process#descendants()} 与 {@code ProcessHandle} 是 Java 9 才有的 API，JDK 8
 * 里<b>没有任何等价物</b>（既没有 {@code Process#pid()}，也没有进程遍历）。这里改成
 * 走本仓已有的既有 idiom（{@code DeliveryLedger.OsProcessLiveness} 用 {@code ps -p}、
 * 测试侧 {@code RealMcpHarness.pgrepCount} 用 {@code pgrep}）：一次
 * {@code ps -eo pid=,ppid=} 拍一张父子关系快照（macOS 与 Linux 这一路输出格式一致、
 * 纯数字两列，不受本地化影响），在内存里从目标 pid 往下 BFS 得到整棵子树。</p>
 *
 * <p>命令是<b>常量</b> argv，pid 只作为解析结果参与内存计算、不回灌进命令行；发信号用
 * {@code /bin/kill -9 <pid>}（同样逐 pid 传 argv，不拼 shell），所以这不构成注入面。</p>
 *
 * <h3>与 Java 9 {@code descendants()} 的语义差异（有意为之）</h3>
 * <ul>
 *   <li>快照是一次性的：拍完之后新 fork 的后代不在这一批里（9 的懒流同样有这一类竞态）。
 *       杀的顺序是<b>深→浅</b>（BFS 逆序），所以已入树的后代不会因为父先死而被改嫁掉 ——
 *       这一点比原实现更稳（原来是浅→深，孙代有漏杀窗口）。</li>
 *   <li>根已经退出时返回空集：显式用 {@code isAlive()} 兜住，既是 9 的文档语义，
 *       也顺手排掉了"pid 被回收给别的进程"这种误伤。</li>
 * </ul>
 *
 * <h3>降级（读不到进程表时）</h3>
 *
 * <p>{@code ps} 不存在/超时/输出解析不出，或 {@code Process} 的真实 pid 拿不到（非
 * {@code UNIXProcess} 系、且无 9+ 的 {@code pid()}）时，本类一律<b>不抛</b>、返回空集，
 * {@code killTree} 退化成"只端根"并把根照常 {@code destroyForcibly()} 掉。此时语义弱在：
 * 后代可能被漏成孤儿继续跑，返回值（被杀后代数）也就近 0 —— 调用方与测试据此能观察到
 * 这一退化，而不是被静悄悄当成"断干净了"。</p>
 */
public final class ProcessTree {

    /** 一次快照最多认多少个节点：防御 pid 数据异常/自环，绝不因为表脏了而无限循环。 */
    static final int MAX_NODES = 2048;
    /** 单次 {@code ps} 的读取上限（几十万个进程也够用；越界即视为读不出来）。 */
    static final int PS_MAX_BYTES = 8 * 1024 * 1024;
    /** {@code ps} 的等待上限：拿不到就降级，绝不把中断路径挂在外部命令上。 */
    static final long PS_TIMEOUT_MILLIS = 3_000L;
    /** 发信号的等待上限。 */
    static final long KILL_TIMEOUT_MILLIS = 2_000L;
    /** 一次 {@code kill} 调用最多带多少个 pid（argv 长度上限，超了就分批）。 */
    static final int KILL_BATCH = 128;

    private static volatile long ownPid = -1L;

    private ProcessTree() {
    }

    // ===== 树：枚举 =====

    /** 这棵树的现役后代（不含根）。 */
    public static Set<Long> descendants(Process p) {
        Set<Long> out = new TreeSet<Long>();
        if (p == null) {
            return out;
        }
        try {
            if (!p.isAlive()) {
                return out; // 根都没了：与 Java 9 descendants() 同语义，空集就是正确答案
            }
            long root = pidOf(p);
            if (root <= 0L) {
                return out; // 降级：认不出根就不猜（见类注释）
            }
            out.addAll(descendantsOf(root, snapshot()));
        } catch (Exception ignored) {
            // 进程可能已经退了；空集就是正确答案
        }
        return out;
    }

    /** 后代 pid 列表（取证用）。 */
    public static List<Long> descendantPids(Process p) {
        return new ArrayList<Long>(descendants(p));
    }

    /**
     * 已知 pid 的后代（取证/自证用；语义与 {@link #descendants(Process)} 一致，只是根是裸 pid）。
     *
     * @return 浅→深的后代 pid；根不存在或读不到进程表时为空
     */
    public static List<Long> descendantPidsOf(long rootPid) {
        if (rootPid <= 0L) {
            return new ArrayList<Long>();
        }
        try {
            return descendantsOf(rootPid, snapshot());
        } catch (Exception ignored) {
            return new ArrayList<Long>();
        }
    }

    // ===== 树：收尾 =====

    /**
     * 端掉整棵树：先后代（<b>深的先走</b>，避免父先死导致后代被 reparent 之后再也枚举不到），
     * 再杀根。
     *
     * @return 实际被强杀的后代个数（不含根）；用来证伪「只杀了父」。降级到"只端根"时这个数是 0
     */
    public static int killTree(Process p) {
        if (p == null) {
            return 0;
        }
        int killed = 0;
        try {
            if (p.isAlive()) {
                long root = pidOf(p);
                if (root > 0L) {
                    killed = killDescendants(descendantsOf(root, snapshot()));
                }
            }
        } catch (Exception ignored) {
            // 落到下面的根进程收尾 —— 幂等：根一定被 destroy 一次
        }
        p.destroyForcibly();
        return killed;
    }

    /** 只杀根进程本身 —— <b>故意留在 API 上</b>：变异体把 killTree 换成它，测试必须变红。 */
    public static int killParentOnly(Process p) {
        if (p == null) {
            return 0;
        }
        p.destroyForcibly();
        return 0;
    }

    /** 等一棵树彻底没了，带硬上限（不许无限挂）。 */
    public static boolean awaitGone(Process p, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        try {
            while (p.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !p.isAlive();
    }

    // ===== pid：Java 8 拿 Process 的 OS pid =====

    /**
     * {@code Process#pid()} 是 Java 9 才有的方法，JDK 8 上只能从实现类的私有字段里读
     * （{@code java.lang.UNIXProcess#pid}，8 是 {@code int}、9 起是 {@code long}，两种都认）。
     *
     * @return 真实 OS pid；认不出来返回 {@code 0L}（调用方据此降级，绝不猜一个）
     */
    public static long pidOf(Process p) {
        if (p == null) {
            return 0L;
        }
        // 9+ 的 JVM：走公开的 Process#pid()
        try {
            Long viaMethod = (Long) p.getClass().getMethod("pid").invoke(p);
            if (viaMethod != null) {
                return viaMethod.longValue();
            }
        } catch (Exception ignored) {
            // JDK 8 上没有这个方法（NoSuchMethodException），落到字段路径
        }
        for (Class<?> c = p.getClass(); c != null && Process.class.isAssignableFrom(c); c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("pid");
                f.setAccessible(true);
                Object v = f.get(p);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Exception ignored) {
                // 字段名/可访问性随 JDK 发行版会变：一个类一条路走不通就往上找，最后降级
            }
        }
        return 0L;
    }

    /** 本进程 pid（{@code RuntimeMXBean} 名字形如 {@code <pid>@<host>}，取前缀；本仓通用写法）。 */
    static long ownPid() {
        long cached = ownPid;
        if (cached >= 0L) {
            return cached;
        }
        long pid = 0L;
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            pid = Long.parseLong(at > 0 ? name.substring(0, at) : name.trim());
        } catch (RuntimeException e) {
            pid = 0L;
        }
        ownPid = pid;
        return pid;
    }

    // ===== 快照：一次 ps 拍父子关系 =====

    /**
     * 读一次 {@code ps -eo pid=,ppid=}，建成 {@code ppid → 孩子列表}。
     *
     * <p>解析只认纯数字的两列，其它行（表头残留、空行、ps 的提示）一律跳过；不依赖任何
     * 本地化文案。外部命令不存在/超时/读不出时返回<b>空表</b>，等价于"树里没有后代"，
     * 于是上层自然降级成"只端根"。</p>
     */
    private static Map<Long, List<Long>> snapshot() {
        Map<Long, List<Long>> children = new HashMap<Long, List<Long>>();
        String out = runPs();
        if (out == null) {
            return children;
        }
        int nodes = 0;
        int lineStart = 0;
        int len = out.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || out.charAt(i) == '\n') {
                if (i > lineStart) {
                    Long pid = null;
                    Long ppid = null;
                    String[] cols = out.substring(lineStart, i).trim().split(" +");
                    if (cols.length >= 2 && isDigits(cols[0]) && isDigits(cols[1])) {
                        pid = Long.valueOf(parsePid(cols[0]));
                        ppid = Long.valueOf(parsePid(cols[1]));
                    }
                    if (pid != null && ppid != null && pid.longValue() > 0L && nodes < MAX_NODES) {
                        List<Long> kids = children.get(ppid);
                        if (kids == null) {
                            kids = new ArrayList<Long>();
                            children.put(ppid, kids);
                        }
                        kids.add(pid);
                        nodes++;
                    }
                }
                lineStart = i + 1;
            }
        }
        return children;
    }

    /** 从 {@code rootPid} 往下 BFS，返回浅→深的后代（不含根）；根不在快照里就是空列表。 */
    private static List<Long> descendantsOf(long rootPid, Map<Long, List<Long>> children) {
        List<Long> out = new ArrayList<Long>();
        if (rootPid <= 0L || children.isEmpty()) {
            return out;
        }
        Set<Long> seen = new HashSet<Long>();
        seen.add(Long.valueOf(rootPid));
        ArrayDeque<Long> queue = new ArrayDeque<Long>();
        queue.add(Long.valueOf(rootPid));
        while (!queue.isEmpty() && out.size() < MAX_NODES) {
            Long cur = queue.poll();
            List<Long> kids = children.get(cur);
            if (kids == null) {
                continue;
            }
            for (Long kid : kids) {
                long pid = kid.longValue();
                // 自己不进结果；本 JVM 也不进（pid 被回收时的最后一道误伤保险）
                if (seen.add(kid) && pid != ownPid()) {
                    out.add(kid);
                    queue.add(kid);
                }
            }
        }
        return out;
    }

    /**
     * 把一批后代端掉，返回"确认发出了 SIGKILL"的个数。
     *
     * <p>常见路径是<b>一次</b> {@code /bin/kill -9 p1 p2 …}：全批 rc=0 就说明每个都发到了。
     * rc≠0（其中有进程已经自己退了 / 没权限）时再逐个补发，好把计数做准 ——
     * 测试拿 {@code killedDescendants} 判"只杀了父"，计数糊里不得。</p>
     */
    private static int killDescendants(List<Long> shallowToDeep) {
        int killed = 0;
        // 深→浅：逆着 BFS 走，任何一个节点被杀时它的后代已经没了，不会被改嫁漏掉
        List<Long> reversed = new ArrayList<Long>(shallowToDeep);
        for (int i = reversed.size() - 1; i >= 0; i--) {
            if (reversed.get(i).longValue() <= 1L) {
                reversed.remove(i); // 绝不碰 init
            }
        }
        if (reversed.isEmpty()) {
            return 0;
        }
        for (int from = 0; from < reversed.size(); from += KILL_BATCH) {
            List<Long> batch = reversed.subList(from, Math.min(from + KILL_BATCH, reversed.size()));
            if (killBatch(batch)) {
                killed += batch.size();
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                if (killOne(batch.get(i).longValue())) {
                    killed++;
                }
            }
        }
        return killed;
    }

    private static boolean killBatch(List<Long> pids) {
        List<String> argv = new ArrayList<String>(pids.size() + 2);
        argv.add("/bin/kill");
        argv.add("-9");
        for (Long pid : pids) {
            argv.add(Long.toString(pid.longValue()));
        }
        return runKill(argv) == 0;
    }

    private static boolean killOne(long pid) {
        List<String> argv = new ArrayList<String>(3);
        argv.add("/bin/kill");
        argv.add("-9");
        argv.add(Long.toString(pid));
        return runKill(argv) == 0;
    }

    /** {@code /bin/kill} 不存在（精简镜像）时退回 PATH 上的 kill；两条都不通就算没杀成。 */
    private static int runKill(List<String> argv) {
        int rc = spawn(argv);
        if (rc >= 0) {
            return rc;
        }
        List<String> viaPath = new ArrayList<String>(argv.size());
        viaPath.add("kill");
        for (int i = 1; i < argv.size(); i++) {
            viaPath.add(argv.get(i));
        }
        return spawn(viaPath);
    }

    // ===== 外部命令：一律常量 argv，pid 只当参数传、不拼字符串 =====

    /** 跑 {@code ps -eo pid=,ppid=}；读不出（命令缺失/超时/异常）返回 {@code null}。 */
    private static String runPs() {
        Process p = null;
        try {
            p = new ProcessBuilder("ps", "-eo", "pid=,ppid=").start();
            return readCapped(p.getInputStream(), PS_MAX_BYTES);
        } catch (Exception e) {
            return null;
        } finally {
            destroyQuietly(p, PS_TIMEOUT_MILLIS);
        }
    }

    /** 跑一条 kill 命令，返回退出码；起不来/超时返回 {@code -1}（当作"没杀成"）。 */
    private static int spawn(List<String> argv) {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[512];
            while (is.read(buf) != -1) {
                // 输出只要"发没发到"，不要内容：ESRCH/EPERM 的文案是本地化的
            }
            if (p.waitFor(KILL_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            destroyQuietly(p, 0L);
        }
    }

    private static void destroyQuietly(Process p, long graceMillis) {
        if (p == null) {
            return;
        }
        try {
            if (graceMillis > 0L && !p.waitFor(graceMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return;
            }
            if (!p.isAlive()) {
                return;
            }
            p.destroyForcibly();
        } catch (Exception ignored) {
            // 外部命令死活都不许把中断路径带崩
        }
    }

    private static String readCapped(InputStream is, int cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int total = 0;
        int len;
        while ((len = is.read(buf)) != -1) {
            total += len;
            if (total > cap) {
                return null; // 大得离谱 = 读不出，降级而不是把内存吃掉
            }
            bos.write(buf, 0, len);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 纯数字串转 long（位数离谱就当读不出）。 */
    private static long parsePid(String digits) {
        if (digits.length() > 10) {
            return -1L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
