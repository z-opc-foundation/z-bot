package com.zifang.z.bot.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Raw-mode terminal 输入读取器 — 实现 Tab 补全、方向键、Backspace 等交互。
 *
 * <p>Unix 系统：用 {@code stty raw -echo} 把终端切到 raw 模式；
 * 读 {@code System.in} 的单个 byte，按键实时刷新屏幕。</p>
 *
 * <p>Windows / 非 TTY 环境：自动降级到 line-mode（按 Enter 才返回整行），
 * 此时 Tab 补全不可用。</p>
 *
 * <p>支持按键：</p>
 * <ul>
 *   <li>可打印字符 — 加入 buffer，回显</li>
 *   <li>Enter (10 / 13) — 提交</li>
 *   <li>Backspace (127) / Ctrl-H (8) — 删除一个字符</li>
 *   <li>Tab (9) — 命令补全（仅 raw mode 有效）</li>
 *   <li>Ctrl-C (3) — 中断</li>
 *   <li>Ctrl-D (4) — 提交（空行表示 EOF）</li>
 *   <li>ESC [ A/B/C/D — 上下左右（历史 / 微移）</li>
 * </ul>
 */
public final class RawTerminalReader implements AutoCloseable {

    /**
     * 终端通道私有命令 — 需要本地 UI 状态，不进 {@code SlashRegistry}。
     * 也是未注入注册表时的缺省 Tab 补全池。
     */
    public static final List<String> LOCAL_COMMANDS = Arrays.asList(
            "/status", "/theme", "/feedback", "/confirm", "/help", "/?", "/exit", "/quit", "/q"
    );

    private final InputStream in;
    private final boolean rawMode;
    private final Process sttyProcess;  // 用于 raw mode 转换（Unix）
    /** {@code stty -g} 的落点：每个 reader 一份临时文件（写 {@code ~/.zbot} 会让两个 profile 的 REPL 互相踩掉对方的终端恢复）。 */
    private String sttyBackup;
    /** Tab 补全池：注册表命令 ∪ {@link #LOCAL_COMMANDS}，由 {@code LineEditor} 注入。 */
    private List<String> slashCommands = LOCAL_COMMANDS;
    private boolean closed = false;

    public RawTerminalReader() {
        this.in = System.in;
        boolean canRaw = isUnix() && isSttyAvailable();
        this.rawMode = canRaw;
        this.sttyProcess = null;
        if (canRaw) {
            enableRawMode();
        }
    }

    public void setSlashCommands(List<String> commands) {
        if (commands != null && !commands.isEmpty()) {
            this.slashCommands = commands;
        }
    }

    private static boolean isUnix() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("mac") || os.contains("aix")
                || os.contains("nix") || os.contains("nux") || os.contains("sunos")
                || os.contains("freebsd") || os.contains("openbsd");
    }

    private static boolean isSttyAvailable() {
        try {
            Process p = new ProcessBuilder("which", "stty").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 默认 readLine（带资源清理）。
     */
    public static String read() throws IOException {
        try (RawTerminalReader r = new RawTerminalReader()) {
            return r.readLine();
        }
    }

    /**
     * 读取一行（raw mode：实时回显 + Tab 补全；line mode：等到 Enter）。
     *
     * @return 输入的字符串（含 \n 末尾）；EOF（Ctrl-D on empty）返回 null
     */
    public String readLine() throws IOException {
        if (!rawMode) {
            return lineModeRead();
        }
        return rawModeRead();
    }

    private String lineModeRead() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (c == '\n') {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String rawModeRead() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == 3) {
                // Ctrl-C — 中断（向 JVM 发 SIGINT）
                System.out.print("^C\n");
                throw new IOException("interrupted");
            }
            if (c == 4) {
                // Ctrl-D — 提交
                System.out.print("\n" + TerminalUI.RESET);
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == 10 || c == 13) {
                System.out.print("\r\n" + TerminalUI.RESET);
                sb.append('\n');
                return sb.toString();
            }
            if (c == 127 || c == 8) {
                // Backspace
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                continue;
            }
            if (c == 9) {
                // Tab — 命令补全
                handleTab(sb);
                continue;
            }
            if (c == 27) {
                // ESC sequence (arrow keys etc.) — 简单吞掉 2 个 follow-up bytes
                in.read();
                in.read();
                continue;
            }
            if (c >= 32 && c < 127) {
                sb.append((char) c);
                System.out.print((char) c);
                System.out.flush();
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Tab 补全：只在 buffer 是 / 开头且不含空格时尝试。
     */
    private void handleTab(StringBuilder sb) {
        String cur = sb.toString();
        if (!cur.startsWith("/")) {
            // 普通 Tab — 插入 4 空格
            sb.append("    ");
            System.out.print("    ");
            System.out.flush();
            return;
        }
        // 找所有 prefix-match
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String cmd : slashCommands) {
            if (cmd.startsWith(cur)) {
                matches.add(cmd);
            }

        }
        if (matches.size() == 0) {
            return;
        }

        if (matches.size() == 1) {
            String fill = matches.get(0).substring(cur.length());
            sb.append(fill);
            System.out.print(fill);
            System.out.flush();
            return;
        }
        // 多个匹配 — 取最长公共前缀
        String common = TerminalUI.commonPrefix(matches);
        if (common.length() > cur.length()) {
            String fill = common.substring(cur.length());
            sb.append(fill);
            System.out.print(fill);
        }
        // 折行打印候选
        System.out.println();
        StringBuilder line = new StringBuilder("    ");
        for (String m : matches) {
            if (line.length() + m.length() + 2 > 80) {
                System.out.println(TerminalUI.DIM_GRAY + line + TerminalUI.RESET);
                line = new StringBuilder("    ");
            }
            line.append(m).append("  ");
        }
        if (line.length() > 4) {
            System.out.println(TerminalUI.DIM_GRAY + line + TerminalUI.RESET);
        }

        // 重打 buffer + prompt
        TerminalUI.prompt();
        System.out.print(sb.toString());
        System.out.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (rawMode) {
            try {
                disableRawMode();
            } catch (Exception ignored) {
            }
        }
    }

    private void enableRawMode() {
        try {
            // 保存当前设置（"sane"），切换到 raw -echo
            File bak = File.createTempFile("zbot-stty-", ".bak");
            sttyBackup = bak.getAbsolutePath();
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "stty -g 2>/dev/null > \"$1\"; stty raw -echo 2>/dev/null", "zbot",
                    sttyBackup}).waitFor();
        } catch (Exception e) {
            // fallback: ignore — reader will still work in line mode if raw failed
        }
    }

    private void disableRawMode() {
        try {
            // 恢复保存的设置；备份只属于本进程，读完即删
            if (sttyBackup == null) {
                return;
            }
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "if [ -s \"$1\" ]; then stty \"$(cat \"$1\")\" 2>/dev/null; fi;"
                            + " rm -f \"$1\"", "zbot", sttyBackup}).waitFor();
        } catch (Exception e) {
            // ignore
        } finally {
            sttyBackup = null;
        }
    }
}
