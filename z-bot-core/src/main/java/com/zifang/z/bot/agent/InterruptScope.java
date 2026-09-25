package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.InterruptFlag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程作用域的中断旗子（P12）：把「哪条执行线程正属于哪次会话」这层映射补出来。
 *
 * <p>kernel 的 {@link InterruptFlag} 是「一个 agent 一面旗子」，但工具实现
 * （{@code exec} 读输出循环 / 文件读写 / {@code mvn_build} / delegate 子循环）是静态代码，
 * 手里没有 agent，也就拿不到那面旗子 —— 结果就是「只在循环边界能断，工具正飞着时断不掉」。
 * 这里让 {@link BotAgent#chat} 在入口把旗子绑到执行线程上、退出时还原，工具侧只要调
 * {@link #checkpoint()} 就能拿到<b>本次这条执行线程所属会话</b>的中断状态。</p>
 *
 * <p>为什么按线程定向而不是全局一个布尔：gateway 是多会话并发的（每会话一个 agent、
 * 各自跑在通道线程上），全局布尔会让「A 会话按了 stop」把 B 会话正在跑的工具一起打断 —— 串台。
 * 并行批次的工具跑在池线程上，所以 {@link BotAgent} 提交任务时要把旗子显式带过去。</p>
 *
 * <p>没有绑定（cron 线程池、测试里直接调工具、{@code /confirm} 事后放行）时
 * {@link #checkpoint()} 是空操作：工具照跑，绝不因为「没人登记过」就抛异常。</p>
 */
public final class InterruptScope {

    /** 看门狗轮询间隔：决定「按了停止」到「子进程被端掉」之间的最坏延迟。 */
    static final long WATCHDOG_POLL_MILLIS = 50L;

    private static final ThreadLocal<InterruptFlag> CURRENT = new ThreadLocal<InterruptFlag>();
    /** 在飞的工具子进程 → 它所属会话的旗子。 */
    private static final Map<Process, InterruptFlag> LIVE = new ConcurrentHashMap<Process, InterruptFlag>();
    private static volatile Thread watchdog;

    private InterruptScope() {
    }

    /** 绑上这面旗子，返回之前那面（可能为 null）；配对调用 {@link #restore}。 */
    public static InterruptFlag bind(InterruptFlag flag) {
        InterruptFlag previous = CURRENT.get();
        if (flag == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(flag);
        }
        return previous;
    }

    /** 还原绑定前的旗子；{@code null} 表示解绑（池线程会复用，留残渣等于给下一个会话串旗子）。 */
    public static void restore(InterruptFlag previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** 当前线程这次执行所属会话的旗子（未绑定为 null）。 */
    public static InterruptFlag current() {
        return CURRENT.get();
    }

    /** 当前线程是否已被请求中断。 */
    public static boolean isInterrupted() {
        InterruptFlag f = CURRENT.get();
        return f != null && f.isInterrupted();
    }

    /** 中断检查点：置位即抛 kernel 的 {@link InterruptFlag.AgentInterruptedException}。 */
    public static void checkpoint() {
        InterruptFlag f = CURRENT.get();
        if (f != null) {
            f.checkpoint();
        }
    }

    /**
     * 登记一个在飞的工具子进程，交给看门狗看着：旗子一置位就把整个进程树端掉。
     *
     * <p>为什么必须有这条看门狗：{@code BufferedReader.readLine()} 会一直阻塞到子进程出字或退出，
     * {@code sleep 45}/{@code mvn} 这类命令可以几十秒不吐一行 —— 把检查点写在读输出循环里
     * 对它根本不会执行。真断了没有？判据在这儿，不在日志里。</p>
     */
    public static void watch(Process p) {
        InterruptFlag f = CURRENT.get();
        if (f == null || p == null) {
            return;
        }
        LIVE.put(p, f);
        ensureWatchdog();
    }

    /** 子进程收工，取消登记。 */
    public static void unwatch(Process p) {
        if (p != null) {
            LIVE.remove(p);
        }
    }

    /** 当前在飞的工具子进程数（测试与 {@code /status} 对账用）。 */
    public static int liveProcessCount() {
        return LIVE.size();
    }

    private static void ensureWatchdog() {
        if (watchdog != null) {
            return;
        }
        synchronized (LIVE) {
            if (watchdog != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (sweepOnce()) {
                        try {
                            Thread.sleep(WATCHDOG_POLL_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    // 空了就自己退，下次 watch 再拉起来：不留一条永久空转的线程
                    watchdog = null;
                }
            }, "zbot-interrupt-watchdog");
            t.setDaemon(true);
            watchdog = t;
            t.start();
        }
    }

    /**
     * 扫一轮：杀掉已置位旗子名下还活着的进程树。
     *
     * @return 还有没有在飞的进程（false 时看门狗可以收工）
     */
    static boolean sweepOnce() {
        boolean anyLive = false;
        for (Map.Entry<Process, InterruptFlag> e : LIVE.entrySet()) {
            Process p = e.getKey();
            if (!p.isAlive()) {
                LIVE.remove(p);
                continue;
            }
            anyLive = true;
            if (e.getValue().isInterrupted()) {
                killTree(p);
            }
        }
        return anyLive || !LIVE.isEmpty();
    }

    /**
     * 连根拔：{@code destroyForcibly()} 只杀 {@code bash} 本身，它名下的 {@code sleep}/{@code mvn}
     * 会孤儿化继续跑 —— 那样「断了」只是假的。先杀后代，再杀自己。
     */
    static void killTree(Process p) {
        try {
            p.descendants().forEach(ph -> ph.destroyForcibly());
        } catch (Exception ignored) {
            // 进程可能已经退了；下面这句是幂等的
        }
        p.destroyForcibly();
    }
}
