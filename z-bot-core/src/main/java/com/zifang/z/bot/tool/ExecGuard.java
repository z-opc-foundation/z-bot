package com.zifang.z.bot.tool;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * exec 工具的安全闸门：绝对禁止的命令直接拒绝，高危命令按模式决定是否走人工确认。
 *
 * <p>模式（配置项 {@code agent.exec.confirm}）：</p>
 * <ul>
 *   <li>{@code off} — 全部自动放行（z-opc 老 bot 的"开发模式"行为）</li>
 *   <li>{@code dangerous} — 命中 {@link #isDangerous(String)} 的命令需要 /confirm（默认）</li>
 *   <li>{@code all} — 每条 exec 都需要确认</li>
 * </ul>
 */
public final class ExecGuard {

    public static final String MODE_OFF = "off";
    public static final String MODE_DANGEROUS = "dangerous";
    public static final String MODE_ALL = "all";

    /** 无论什么模式都直接拒绝的自毁式命令。 */
    private static final String[] FORBIDDEN = {
            "rm -rf /",
            "rm -rf /*",
            "> /dev/sda",
            "mkfs",
            ":(){:|:&}:",
            "dd if=",
    };

    private static final Pattern[] DANGEROUS = {
            Pattern.compile("\\brm\\s+-[a-z]*r[a-z]*f\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+-[a-z]*f[a-z]*r\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsudo\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchmod\\s+-R\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchown\\s+-R\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(git)\\s+push\\s+.*(--force|-f)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmv\\s+.*\\s/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bkill(all)?\\s+(-9\\s+)?-?1\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(curl|wget)\\b[^|;\\n]*\\|\\s*(ba)?sh\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile(">\\s*/etc/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcrontab\\b", Pattern.CASE_INSENSITIVE),
    };

    private ExecGuard() {
    }

    public static boolean isForbidden(String command) {
        if (command == null) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String f : FORBIDDEN) {
            if (lower.contains(f.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDangerous(String command) {
        if (command == null) {
            return false;
        }
        for (Pattern p : DANGEROUS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该命令此刻是否需要人工确认；返回 null 表示直接放行。
     * 已确认过的（args 带 {@link Confirmations#CONFIRMED_ARG}）不再拦。
     */
    public static String confirmationReason(String mode, String command, boolean alreadyConfirmed) {
        if (alreadyConfirmed || mode == null || MODE_OFF.equalsIgnoreCase(mode)) {
            return null;
        }
        boolean needs = MODE_ALL.equalsIgnoreCase(mode) || isDangerous(command);
        return needs ? "高危命令需要确认: " + command : null;
    }
}
