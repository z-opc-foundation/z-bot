package com.zifang.z.bot.ui;

/**
 * z-bot 终端 UI 工具集 — 对标 OpenCode / Hermes / Claude Code TUI。
 *
 * <p>提供：</p>
 * <ul>
 *   <li>256 色主题（PRIMARY 青 / ACCENT 粉 / SUCCESS 绿 / WARN 黄 / ERROR 红 / DIM 灰）</li>
 *   <li>渐变 ASCII logo（青色 → 品红）</li>
 *   <li>启动欢迎面板（模型 / 工具数 / skills 数 / instance / center 状态）</li>
 *   <li>用户输入 / AI 输出 / 工具调用的彩色 prompt</li>
 *   <li>StatusBar 助手（打印单行 / 多行状态条）</li>
 *   <li>Thinking Spinner（无 LLM 流时的 fallback 动画）</li>
 *   <li>Token / step 计数器显示</li>
 * </ul>
 *
 * <p>所有样式都是裸 ANSI escape sequence，Windows / Linux / macOS 终端一致。</p>
 */
public final class TerminalUI {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";
    public static final String STRIKE = "\u001B[9m";

    // 256-色主题
    public static final String PRIMARY = "\u001B[38;5;51m";      // 亮青
    public static final String ACCENT = "\u001B[38;5;213m";       // 品红
    public static final String SUCCESS = "\u001B[38;5;114m";      // 薄荷绿
    public static final String WARN = "\u001B[38;5;221m";         // 金黄
    public static final String ERROR = "\u001B[38;5;203m";        // 红
    public static final String DIM_GRAY = "\u001B[38;5;244m";     // 灰
    public static final String MID_GRAY = "\u001B[38;5;245m";
    public static final String BG_DARK = "\u001B[48;5;236m";       // 暗背景
    public static final String BG_ACCENT = "\u001B[48;5;237m";

    /**
     * OpenCode 风格 logo（每行 2 个字符宽度不同，做"渐变"靠换行着色）
     */
    public static final String[][] LOGO_LINES = {
            {"  ______      ___           _       _    ", "       ", "         ", "    ", "             "},
            {" |__  (_)___ | __| _ _  __ | |_  __| |_  ", "       ", "         ", "    ", "             "},
            {"   / /| / _ \\| _|| '_|/ _|| | || _||  _| ", "       ", "         ", "    ", "             "},
            {"  /_/ | \\___/|_| |_|  \\__||_||_|\\__|\\__| ", "       ", "         ", "    ", "             "},
    };

    /**
     * Hermes 风格 ASCII（备选 logo）
     */
    public static final String[] HERMES_LINES = {
            " ╭───────────────────────────────────╮ ",
            " │  ⚡ z-bot — local ReAct agent    │ ",
            " ╰───────────────────────────────────╯ ",
    };

    private TerminalUI() {
    }

    /**
     * 重复字符 c n 次。
     * <p>本地实现替代已不再可直接访问的 {@code org.apache.poi.util.StringUtil#repeat(char, int)}。</p>
     */
    private static String repeat(char c, int n) {
        if (n <= 0) {
            return "";
        }

        char[] buf = new char[n];
        for (int i = 0; i < n; i++) buf[i] = c;
        return new String(buf);
    }

    /**
     * 在 startup 打印渐变 logo + 欢迎横幅。
     */
    public static void printWelcome() {
        // 渐变 cyan → magenta
        String[] gradient = {
                "\u001B[38;5;81m",   // cyan
                "\u001B[38;5;111m",
                "\u001B[38;5;141m",
                "\u001B[38;5;171m",
                "\u001B[38;5;201m",  // magenta
        };
        System.out.println();
        for (int i = 0; i < LOGO_LINES.length; i++) {
            String line = LOGO_LINES[i][0];
            String color = gradient[Math.min(i, gradient.length - 1)];
            System.out.println(BOLD + color + line + RESET);
        }
        System.out.println();
        System.out.println(DIM + "  ⚡ local ReAct agent  •  z-agent-kernel providers  •  Java 8" + RESET);
        System.out.println();
    }

    /**
     * 打印启动后的状态面板（模型 / 工具数 / skill 数 / instance / center）。
     */
    public static void printStatusPanel(String model, int toolCount, int skillCount,
                                        String instanceCode, String centerStatus) {
        int width = 60;
        String border = DIM_GRAY + repeat('─', width) + RESET;
        System.out.println(border);
        printKv("🤖  Model", model, width);
        printKv("🔧  Tools", String.valueOf(toolCount), width);
        printKv("📦  Skills", String.valueOf(skillCount), width);
        if (instanceCode != null) {
            printKv("🆔  Instance", instanceCode, width);
        } else {
            printKv("🆔  Instance", DIM + "(local-only, no center)" + RESET, width);
        }
        if (centerStatus != null) {
            printKv("🌐  Center", centerStatus, width);
        }
        System.out.println(border);
        System.out.println();
    }

    private static void printKv(String key, String value, int width) {
        int pad = Math.max(1, width - 16 - stripAnsi(value).length());
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(DIM).append(key).append(RESET);
        sb.append("  ");
        sb.append(value);
        if (pad > 0) {
            sb.append(repeat(' ', pad));
        }

        System.out.println(sb);
    }

    /**
     * 打印命令列表（用于 /help 输出）。
     */
    public static void printCommandTable() {
        System.out.println(BOLD + PRIMARY + "  ⚡ Slash Commands" + RESET);
        System.out.println();
        String[][] rows = {
                {"/new", "🆕  开启新会话（切换会话 id，清空记忆）"},
                {"/clear", "🧹  仅清空当前会话记忆"},
                {"/sessions", "🗂  列出本地会话（~/.zbot/sessions）"},
                {"/switch", "🔀  切换会话：/switch <sessionId>"},
                {"/status", "📊  显示当前状态（模型 / 工具 / 技能）"},
                {"/tools", "🔧  列出已注册的工具"},
                {"/skills", "📦  列出已同步的 Skill（来自 z-agent-center）"},
                {"/sync", "🔄  手动触发 Skill 同步（拉取 center 的 pending skills）"},
                {"/memory", "🧠  显示长期记忆摘要（来自 center）"},
                {"/feedback", "👍👎  对上一次回复评分（1=赞 / -1=踩 / 0=中性）"},
                {"/usage", "📈  当前会话统计（消息数 / token）"},
                {"/model", "🤖  显示当前模型"},
                {"/theme", "🎨  切换主题（cyan / green / amber）"},
                {"/confirm", "⚠️  确认上次等待中的危险命令"},
                {"/help", "❓  显示此帮助"},
                {"/exit", "👋  退出（别名 /quit, /q）"},
        };
        for (String[] r : rows) {
            System.out.println("    " + PRIMARY + padRight(r[0], 12) + RESET + "  " + DIM + r[1] + RESET);
        }
        System.out.println();
        System.out.println(DIM + "  Tip: 输入 / 后按 Tab 补全命令" + RESET);
        System.out.println();
    }

    /**
     * 打印状态条（用于 chat 进行中时底部显示）。
     */
    public static void printStatusBar(String model, String status, int step, int tokens) {
        StringBuilder sb = new StringBuilder();
        sb.append(DIM_GRAY).append("  ─── ");
        sb.append(PRIMARY).append(model);
        sb.append(DIM_GRAY).append(" • ");
        sb.append(WARN).append(status);
        sb.append(DIM_GRAY).append(" • step=").append(PRIMARY).append(step);
        sb.append(DIM_GRAY).append(" • tokens=").append(PRIMARY).append(tokens);
        sb.append(DIM_GRAY).append(" ───").append(RESET);
        System.out.println(sb);
    }

    /**
     * 用户输入提示符。
     */
    public static void prompt() {
        System.out.print(PRIMARY + "❯ " + RESET);
    }

    /**
     * 续行（用户输入多行内容时的辅助提示符）。
     */
    public static void promptContinuation() {
        System.out.print(DIM_GRAY + ". " + RESET);
    }

    /**
     * AI 思考中（流式输出中）。
     */
    public static void thinkingHeader() {
        System.out.println();
        System.out.println(PRIMARY + BOLD + "● " + RESET + " " + BOLD + "z-bot is thinking..." + RESET);
        System.out.println(DIM_GRAY + "  ───────────────────────────────────────────────" + RESET);
    }

    /**
     * 工具调用横幅。
     */
    public static void toolCall(String toolName, String argsJson) {
        String argPreview = argsJson;
        if (argPreview != null && argPreview.length() > 100) {
            argPreview = argPreview.substring(0, 100) + "...";
        }
        System.out.println();
        System.out.println("  " + WARN + "▸ " + BOLD + "tool/" + toolName + RESET
                + DIM_GRAY + "  " + argPreview + RESET);
    }

    /**
     * 工具结果。
     */
    public static void toolResult(String result, boolean success) {
        String color = success ? SUCCESS : ERROR;
        String sym = success ? "✓" : "✗";
        String r = result == null ? "" : result;
        if (r.length() > 400) {
            r = r.substring(0, 400) + "...";
        }

        System.out.println("  " + color + sym + " " + RESET + DIM + r.replace("\n", "\n    ") + RESET);
    }

    /**
     * 完成分隔。
     */
    public static void done(int steps, long tokens, long elapsedMs) {
        System.out.println();
        System.out.println(DIM_GRAY + "  ───────────────────────────────────────────────" + RESET);
        System.out.println(DIM + "  ✓ done"
                + DIM_GRAY + " • "
                + PRIMARY + steps + DIM + " step"
                + DIM_GRAY + " • "
                + PRIMARY + tokens + DIM + " tok"
                + DIM_GRAY + " • "
                + PRIMARY + elapsedMs + DIM + " ms" + RESET);
        System.out.println();
    }

    /**
     * 错误信息。
     */
    public static void error(String message) {
        System.out.println(ERROR + "  ✗ " + message + RESET);
    }

    /**
     * 警告。
     */
    public static void warn(String message) {
        System.out.println(WARN + "  ⚠ " + message + RESET);
    }

    /**
     * 成功。
     */
    public static void success(String message) {
        System.out.println(SUCCESS + "  ✓ " + message + RESET);
    }

    /**
     * 信息。
     */
    public static void info(String message) {
        System.out.println(PRIMARY + "  ℹ " + message + RESET);
    }

    /**
     * 安静打印（纯内容，不带 emoji / 颜色）。
     */
    public static void plain(String line) {
        System.out.println(line);
    }

    /**
     * Thinking spinner — 单字符 spin，用 Thread + 100ms tick 循环打印。
     */
    public static Thread startSpinner(String label, java.io.PrintStream out) {
        Thread t = new Thread(() -> {
            char[] frames = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};
            int i = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    out.print("\r  " + PRIMARY + frames[i++ % frames.length] + RESET
                            + " " + DIM + label + RESET);
                    out.flush();
                    Thread.sleep(80);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                out.print("\r" + repeat(' ', Math.max(80, label.length() + 4)) + "\r");
                out.flush();
            }
        }, "spinner");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Tab 补全：给定已输入 buffer 和候选列表，返回最长公共前缀（用于自动补全命令）。
     */
    public static String commonPrefix(java.util.List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String first = candidates.get(0);
        int prefixLen = first.length();
        for (int i = 1; i < candidates.size(); i++) {
            String cur = candidates.get(i);
            int max = Math.min(prefixLen, cur.length());
            int j = 0;
            while (j < max && first.charAt(j) == cur.charAt(j)) j++;
            prefixLen = j;
            if (prefixLen == 0) {
                break;
            }
        }
        return first.substring(0, prefixLen);
    }

    /**
     * 右补空格到指定宽度（中文/宽字符按 1 计算）。
     */
    public static String padRight(String s, int width) {
        if (s == null) {
            s = "";
        }

        if (s.length() >= width) {
            return s;
        }

        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /**
     * 去除 ANSI 转义码（用于计算实际显示宽度）。
     */
    public static String stripAnsi(String s) {
        if (s == null) {
            return "";
        }

        return s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    /**
     * 检查 stdout 是否连接到 TTY（用于决定要不要发 ANSI）。
     */
    public static boolean isAnsiSupported() {
        // AnsiConsole 自动探测；简单场景下认为是 true
        return true;
    }
}
