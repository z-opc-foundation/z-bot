package com.zifang.z.bot.ui;

import java.io.File;
import java.util.Arrays;

/**
 * 不是测试类（名字不以 Test 结尾 ⇒ surefire 不跑它、不占 @Test 分母），
 * 而是给杠③ 真进程 PTY 驱动用的<b>探针进程主体</b>。
 *
 * <p>为什么要它：{@link RawTerminalReader} 只有 {@code RawTerminalReader()} 一个构造器，
 * 输入源写死 {@code System.in} ⇒ 在 surefire 里根本拿不到 tty，也就永远测不到
 * "真 PTY 上 stty raw 切得过去" 这一支。p28a 把有界等待与四值判词
 * （DONE / TIMED_OUT / NONZERO_EXIT / SPAWN_FAILED）加进了生产码，
 * 但全仓对 {@code ExternalVerdict} 的引用只有生产文件自己那一份（杠③ 复算过），
 * 也就是这套判词一条都没被外部观察过。本探针把判词打到 stdout，
 * 由 {@code ~/.cache/zbot-p28-lead/p28b_pty_e2e.py} 在真 PTY / 真管道 / 投毒 PATH
 * 三种父环境下各起一个 JVM 读。</p>
 *
 * <p>输出协议（每行一条，key=value）：
 * <pre>
 * PROBE_RAW_VERDICT=DONE|TIMED_OUT|NONZERO_EXIT|SPAWN_FAILED|null
 * PROBE_RESTORE_VERDICT=...
 * PROBE_RAW_MODE=true|false
 * PROBE_CONSTRUCT_MS=&lt;long&gt;
 * PROBE readline 往返：PROBE_LINE=&lt;读到的整行，\r 转 \\r&gt;
 * PROBE_READLINE_MS=&lt;long&gt;
 * PROBE_CLOSE_MS=&lt;long&gt;
 * PROBE_BACKUP_PATH=&lt;String 或 null&gt;
 * PROBE_BACKUP_EXISTS=true|false
 * PROBE_TAB_ECHO=&lt;Tab 补全往 stdout 写了什么&gt;
 * </pre>
 */
public final class RawTerminalVerdictProbe {

    private RawTerminalVerdictProbe() {
    }

    public static void main(String[] args) throws Exception {
        // args: 要喂给读循环的字节由父进程从 stdin 送进来，这里只负责读一行 + 补全演示
        long t0 = System.currentTimeMillis();
        RawTerminalReader r = new RawTerminalReader();
        long tCtor = System.currentTimeMillis() - t0;
        // 与 TerminalChannel#slashCommandPool 同形：注册表派生命中 + 终端私有命令
        r.setSlashCommands(Arrays.asList(
                "/status", "/sessions", "/switch", "/stop", "/steer", "/skills", "/sync", "/skill"));

        String line;
        long tRead0 = System.currentTimeMillis();
        try {
            line = r.readLine();
        } finally {
            tRead0 = System.currentTimeMillis() - tRead0;
        }
        long tClose0 = System.currentTimeMillis();
        String backup = r.sttyBackupPath();
        r.close();
        long tClose = System.currentTimeMillis() - tClose0;

        out("PROBE_RAW_MODE", String.valueOf(isRawMode(r)));
        out("PROBE_CONSTRUCT_MS", String.valueOf(tCtor));
        out("PROBE_RAW_VERDICT", name(r.rawSwitchVerdict()));
        out("PROBE_RESTORE_VERDICT", name(r.restoreVerdict()));
        out("PROBE_LINE", line == null ? "<EOF>" : line.replace("\r", "\\r").replace("\n", "\\n"));
        out("PROBE_READLINE_MS", String.valueOf(tRead0));
        out("PROBE_CLOSE_MS", String.valueOf(tClose));
        out("PROBE_BACKUP_PATH", backup == null ? "<null>" : backup);
        out("PROBE_BACKUP_EXISTS",
                String.valueOf(backup != null && new File(backup).exists()));
        System.out.flush();
    }

    private static boolean isRawMode(RawTerminalReader r) {
        try {
            java.lang.reflect.Field f =
                    RawTerminalReader.class.getDeclaredField("rawMode");
            f.setAccessible(true);
            return f.getBoolean(r);
        } catch (Exception e) {
            throw new IllegalStateException("rawMode 字段读不到，探针与生产码脱钩了: " + e, e);
        }
    }

    private static String name(Enum<?> v) {
        return v == null ? "<null>" : v.name();
    }

    private static void out(String k, String v) {
        System.out.println(k + "=" + v);
    }
}
