package com.zifang.z.bot.tool.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 进程树收尾（P22）。
 *
 * <p>{@code Process#destroyForcibly()} 只杀直接子进程；{@code bash -c "sleep 300 &"} 那种
 * 挂在 bash 名下的孙进程会变成孤儿继续跑。hermes 的 {@code base.py:289 ProcessHandle}
 * 把 {@code kill} 定成"这一坨都没了"，仓里 P12 也已经认了这个口径
 * （{@code agent/InterruptScope#killTree} 先 {@code descendants()} 再杀自己）。
 * SPI 这边把它做成后端必须走的一道收尾，而不是散在各处的巧合。</p>
 *
 * <p>注意仓里现存的三套 exec 都没做这件事：{@code CheckpointManager#exec} 超时只
 * {@code destroyForcibly()} 自己那一层，{@code BuiltinTools#bash} 压根没有时间上限。</p>
 */
public final class ProcessTree {

    private ProcessTree() {
    }

    /** 这棵树的现役后代（不含根）。 */
    public static Set<Long> descendants(Process p) {
        Set<Long> out = new TreeSet<Long>();
        if (p == null) {
            return out;
        }
        try {
            p.descendants().forEach(ph -> out.add(ph.pid()));
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
     * 端掉整棵树：先后代（最深的先走，避免父先死导致后代被 reparent 之后再也枚举不到），
     * 再杀根。
     *
     * @return 实际被强杀的后代个数（不含根）；用来证伪「只杀了父」
     */
    public static int killTree(Process p) {
        if (p == null) {
            return 0;
        }
        List<Long> killed = new ArrayList<Long>();
        try {
            p.descendants()
                    .filter(ph -> ph.isAlive())
                    .forEach(ph -> {
                        if (ph.destroyForcibly()) {
                            killed.add(ph.pid());
                        }
                    });
        } catch (Exception ignored) {
            // 落到下面的根进程收尾
        }
        p.destroyForcibly();
        return killed.size();
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
}
