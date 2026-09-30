import com.zifang.z.bot.tool.env.ProcessTree;
import java.util.List;

/**
 * 只读取证：killTree 在这台机器上到底拿不拿得到后代。
 * 复刻 ExecEnvironmentSpiTest.localExec_timeoutTakesTheWholeProcessTreeDown 的进程形状，
 * 存活数不读 ProcessTree 的返回值，另起 pgrep -x sleep 独立口径 + 前后基线相减。
 */
public class TreeProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("PROBE|PATH=" + System.getenv("PATH"));
        int base = countSleep();
        Process p = new ProcessBuilder("/bin/sh", "-c", "sleep 30 & sleep 30 & wait").start();
        Thread.sleep(400L);
        long root = ProcessTree.pidOf(p);
        List<Long> kids = ProcessTree.descendantPids(p);
        int during = countSleep();
        System.out.println("PROBE|root=" + root + " descendants=" + kids
                + "|sleep_procs_base=" + base + "_during=" + during);
        int killed = ProcessTree.killTree(p);
        Thread.sleep(400L);
        int after = countSleep();
        System.out.println("PROBE|killed_returned=" + killed + "|sleep_procs_after=" + after
                + "|survivors=" + (after - base));
        p.destroyForcibly();
    }

    private static int countSleep() throws Exception {
        Process q = new ProcessBuilder("/usr/bin/pgrep", "-x", "sleep").start();
        byte[] buf = new byte[8192];
        int n = q.getInputStream().read(buf);
        q.waitFor();
        if (n <= 0) {
            return 0;
        }
        String s = new String(buf, 0, n).trim();
        if (s.isEmpty()) {
            return 0;
        }
        return s.split("\\s+").length;
    }
}
