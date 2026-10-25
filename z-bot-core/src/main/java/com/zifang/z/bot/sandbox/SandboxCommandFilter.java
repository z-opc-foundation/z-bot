package com.zifang.z.bot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 沙箱命令白名单校验：校验可执行文件 basename，并拦截 shell 操作符绕过。
 * 允许引号外的 {@code |}、{@code ||}、{@code &&}、{@code ;}，但每一段子命令都必须在白名单内。
 * 允许 {@code 2>/dev/null}、{@code 2>&1} 与一般 {@code >} / {@code >>} 写重定向
 * （写入越界由 OS 层沙箱 Seatbelt/bwrap 路径白名单强制拒绝）；拒绝命令替换与后台执行。
 * <p>
 * 复刻自 上游项目 的 {@code SandboxCommandFilter}，增加了对 shell 控制符的安全检查。
 */
public final class SandboxCommandFilter {

    private static final Logger log = LoggerFactory.getLogger(SandboxCommandFilter.class);

    private SandboxCommandFilter() {
    }

    /**
     * 校验 {@code sh -c} 命令串。
     *
     * @param whitelist 命令白名单
     * @param command   完整 shell 命令字符串
     * @param agentCode 调用方 agent code（用于报错）
     */
    public static void checkCommand(SandboxCommandWhitelist whitelist, String command, String agentCode) {
        if (whitelist == null || !whitelist.isEnabled() || command == null || command.trim().isEmpty()) {
            return;
        }
        Set<String> allowed = whitelist.allowedCommands();
        rejectUnquotedShellOperators(command);
        List<String> segments = splitByUnquotedConnectors(command);
        for (String segment : segments) {
            if (segment.trim().isEmpty()) {
                throw new SandboxCommandNotAllowedException("沙箱命令管道段为空", command, agentCode);
            }
            assertAllowed(firstToken(segment), allowed, command, agentCode);
        }
    }

    /**
     * 校验 argv 数组首个元素（可执行文件 basename）。
     *
     * @param whitelist 命令白名单
     * @param argv      命令参数数组
     * @param agentCode 调用方 agent code（用于报错）
     */
    public static void checkArgv(SandboxCommandWhitelist whitelist, String[] argv, String agentCode) {
        if (whitelist == null || !whitelist.isEnabled() || argv == null || argv.length == 0) {
            return;
        }
        Set<String> allowed = whitelist.allowedCommands();
        String first = argv[0];
        if (first == null || first.trim().isEmpty()) {
            throw new SandboxCommandNotAllowedException("argv is empty", String.join(" ", argv), agentCode);
        }
        assertAllowed(first, allowed, String.join(" ", argv), agentCode);
    }

    /**
     * 取可执行文件 basename 并小写：{@code /usr/bin/grep} → {@code grep}。
     */
    public static String normalizeExecutableName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return "";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void assertAllowed(String executable, Set<String> allowed, String fullCommand, String agentCode) {
        String name = normalizeExecutableName(executable);
        if (name.isEmpty() || !allowed.contains(name)) {
            log.warn("[Sandbox] command '{}' is not in whitelist for agent={}; allowed={}",
                    name.isEmpty() ? executable : name, agentCode, allowed);
            throw new SandboxCommandNotAllowedException(
                    "命令 [" + (name.isEmpty() ? executable : name) + "] 不在沙箱白名单中（agent=" + agentCode + "）",
                    fullCommand,
                    agentCode);
        }
    }

    static String firstToken(String command) {
        int i = 0;
        int len = command.length();
        while (i < len && Character.isWhitespace(command.charAt(i))) {
            i++;
        }
        if (i >= len) {
            return "";
        }
        char first = command.charAt(i);
        if (first == '\'' || first == '"') {
            i++;
            StringBuilder quoted = new StringBuilder();
            while (i < len && command.charAt(i) != first) {
                quoted.append(command.charAt(i));
                i++;
            }
            return quoted.toString();
        }
        StringBuilder token = new StringBuilder();
        while (i < len && !isTokenEnd(command.charAt(i))) {
            token.append(command.charAt(i));
            i++;
        }
        return token.toString();
    }

    private static boolean isTokenEnd(char c) {
        return Character.isWhitespace(c)
                || c == '|' || c == '&' || c == ';'
                || c == '>' || c == '<'
                || c == '(' || c == ')' || c == '`' || c == '$';
    }

    /**
     * 按引号外的 {@code |}、{@code ||}、{@code &&}、{@code ;} 拆段。引号内的 {@code |} 不拆（正则）。
     */
    static List<String> splitByUnquotedPipes(String command) {
        return splitByUnquotedConnectors(command);
    }

    static List<String> splitByUnquotedConnectors(String command) {
        List<String> segments = new ArrayList<String>();
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;
        int len = command.length();
        for (int i = 0; i < len; i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < len) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            int connectorLen = unquotedConnectorLength(command, i);
            if (connectorLen > 0) {
                segments.add(command.substring(start, i));
                start = i + connectorLen;
                i += connectorLen - 1;
            }
        }
        segments.add(command.substring(start));
        return segments;
    }

    /**
     * 引号外连接符长度：{@code |} / {@code ;} 为 1，{@code ||} / {@code &&} 为 2；否则 0。
     * {@code |&} 与后台 {@code &} 不作为连接符。
     */
    private static int unquotedConnectorLength(String command, int i) {
        char c = command.charAt(i);
        int len = command.length();
        if (c == ';') {
            return 1;
        }
        if (c == '&') {
            if (i + 1 < len && command.charAt(i + 1) == '&') {
                return 2;
            }
            return 0;
        }
        if (c == '|') {
            if (i + 1 < len && command.charAt(i + 1) == '|') {
                return 2;
            }
            if (i + 1 < len && command.charAt(i + 1) == '&') {
                return 0;
            }
            return 1;
        }
        return 0;
    }

    /**
     * 拒绝引号外的危险 shell 控制符。
     * 允许 {@code |}、{@code ||}、{@code &&}、{@code ;}，{@code N>/dev/null}、{@code N>&1}、{@code N>&2}，
     * 以及一般 {@code >} / {@code >>} 写重定向（目标写入越界由 OS 层沙箱强制）。
     * 仍拒绝后台 {@code &}、{@code |&}、命令替换、括号与换行。
     * 双引号内仍拒绝 {@code $} 与反引号。
     * 单引号 / 双引号内允许 {@code |}（如 {@code grep -E 'a|b'}）。
     */
    static void rejectUnquotedShellOperators(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        int len = command.length();
        for (int i = 0; i < len; i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < len) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDouble = false;
                    continue;
                }
                if (c == '$' || c == '`') {
                    throw new SecurityException("沙箱命令含非法字符: " + c);
                }
                continue;
            }
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == '>') {
                int end = endOfAllowedRedirect(command, i);
                if (end >= 0) {
                    i = end;
                    continue;
                }
                throw new SecurityException("沙箱命令含非法字符: >");
            }
            if (c == '|') {
                if (i + 1 < len && command.charAt(i + 1) == '&') {
                    throw new SecurityException("沙箱命令含非法字符: |&");
                }
                if (i + 1 < len && command.charAt(i + 1) == '|') {
                    i++;
                }
                continue;
            }
            if (c == '&') {
                if (i + 1 < len && command.charAt(i + 1) == '&') {
                    i++;
                    continue;
                }
                throw new SecurityException("沙箱命令含非法字符: &");
            }
            if (c == ';') {
                continue;
            }
            if (isUnquotedShellOperator(c)) {
                throw new SecurityException("沙箱命令含非法字符: " + visibleChar(c));
            }
        }
        if (inSingle || inDouble) {
            throw new SecurityException("沙箱命令引号未闭合");
        }
    }

    /**
     * 若 {@code command[gtIndex]} 是 {@code >} 重定向，返回最后一个已消费字符下标；否则 -1。
     * <p>支持 {@code >} / {@code >>} / {@code N>} / {@code N>>}（{@code N} 为 fd 数字，{@code >} 后可有空白）
     * 重定向到任意目标（文件写入越界由 OS 层沙箱 Seatbelt/bwrap 路径白名单强制拒绝，
     * 字符层不重复设防），以及 {@code \d*>&1} / {@code \d*>&2}；{@code >&} 后跟其他内容仍拒绝。
     */
    static int endOfAllowedRedirect(String command, int gtIndex) {
        if (gtIndex < 0 || gtIndex >= command.length() || command.charAt(gtIndex) != '>') {
            return -1;
        }
        int len = command.length();
        int j = gtIndex + 1;
        if (j < len && command.charAt(j) == '&') {
            if (j + 1 < len) {
                char fd = command.charAt(j + 1);
                if (fd == '1' || fd == '2') {
                    return j + 1;
                }
            }
            return -1;
        }
        // 追加标记（>>）
        if (j < len && command.charAt(j) == '>') {
            j++;
        }
        while (j < len && Character.isWhitespace(command.charAt(j))) {
            j++;
        }
        if (j >= len) {
            return -1;
        }
        char first = command.charAt(j);
        if (first == '\'' || first == '"') {
            j++;
            while (j < len && command.charAt(j) != first) {
                j++;
            }
            if (j >= len) {
                return -1;
            }
            return j;
        }
        while (j < len && !Character.isWhitespace(command.charAt(j))
                && command.charAt(j) != '>' && command.charAt(j) != '<'
                && unquotedConnectorLength(command, j) == 0) {
            j++;
        }
        return j - 1;
    }

    private static boolean isUnquotedShellOperator(char c) {
        return c == '`' || c == '$'
                || c == '(' || c == ')' || c == '<'
                || c == '\n' || c == '\r';
    }

    private static String visibleChar(char c) {
        if (c == '\n') {
            return "\\n";
        }
        if (c == '\r') {
            return "\\r";
        }
        return String.valueOf(c);
    }
}