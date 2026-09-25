package com.zifang.z.bot.ui;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * 行编辑器 — JLine 3 优先（历史 / Tab 补全 / 高亮 / 右分栏状态），
 * 非 TTY 或 JLine 初始化失败时降级到 {@link RawTerminalReader}（行模式）。
 *
 * <p>与 {@link RawTerminalReader} 保持同一输入契约：</p>
 * <ul>
 *   <li>Ctrl-C — 打印 {@code ^C} 后抛 {@code IOException("interrupted")}</li>
 *   <li>Ctrl-D（空行）— 返回 {@code null}（EOF）</li>
 *   <li>返回的行不含末尾换行（JLine 行为）；降级模式与 RawTerminalReader 一致含 {@code \n}，调用方一律 trim</li>
 * </ul>
 */
public final class LineEditor implements AutoCloseable {

    /** 编辑模式：JLine 全功能 / 降级行模式。 */
    public enum Mode { JLINE, FALLBACK }

    private final Mode mode;
    private final LineReader reader;
    /** 斜杠命令全集（SlashRegistry ∪ 终端私有命令），补全单一来源。 */
    private final Supplier<List<String>> commandNames;
    /** 降级读取器懒加载：RawTerminalReader 构造会跑 stty（备份落在本进程的临时文件），延迟到首次读。 */
    private RawTerminalReader fallback;

    private LineEditor(Mode mode, LineReader reader, Supplier<List<String>> commandNames) {
        this.mode = mode;
        this.reader = reader;
        this.commandNames = commandNames;
    }

    /**
     * 创建 JLine 编辑器；任何异常或 dumb 终端都降级到行模式（永不抛）。
     *
     * @param historyFile  历史文件路径（可为 null 表示不持久化）
     * @param commandNames 斜杠命令全集（注册表派生），可为 null（用 RawTerminalReader 内置清单）
     * @param sessionIds   {@code /switch} 第二词的补全源，可为 null
     */
    public static LineEditor create(File historyFile,
                                    Supplier<List<String>> commandNames,
                                    Supplier<List<String>> sessionIds) {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            if (terminal.getType() != null && terminal.getType().toLowerCase().contains("dumb")) {
                terminal.close();
                return fallback(commandNames);
            }
            LineReaderBuilder b = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("z-bot")
                    .completer(new SlashCompleter(commandNames, sessionIds))
                    .highlighter(new InputHighlighter());
            if (historyFile != null) {
                b.variable(LineReader.HISTORY_FILE, historyFile);
            }
            return new LineEditor(Mode.JLINE, b.build(), commandNames);
        } catch (Throwable t) {
            closeQuietly(terminal);
            return fallback(commandNames);
        }
    }

    /** 纯降级模式（非 TTY / 测试直接用行模式）。 */
    public static LineEditor fallback() {
        return fallback(null);
    }

    private static LineEditor fallback(Supplier<List<String>> commandNames) {
        return new LineEditor(Mode.FALLBACK, null, commandNames);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 读取一行。
     *
     * @param prompt      左提示符（可含 ANSI 颜色）
     * @param rightPrompt 右分栏状态（仅 JLINE 模式生效，可为 null）
     * @return 输入行（trim 前原样）；EOF 返回 null
     * @throws IOException Ctrl-C 中断
     */
    public String readLine(String prompt, String rightPrompt) throws IOException {
        if (mode == Mode.JLINE) {
            try {
                // JLine 签名是 (prompt, rightPrompt, mask, buffer)；null mask 需显式转型消歧
                return reader.readLine(prompt, rightPrompt, (Character) null, null);
            } catch (UserInterruptException e) {
                System.out.print("^C\n");
                throw new IOException("interrupted");
            } catch (EndOfFileException e) {
                return null;
            }
        }
        System.out.print(prompt);
        if (rightPrompt != null && !rightPrompt.isEmpty()) {
            // 行模式没有分栏，右侧状态直接跟在提示符后
            System.out.print(rightPrompt);
        }
        if (fallback == null) {
            fallback = new RawTerminalReader();
            if (commandNames != null) {
                List<String> names = commandNames.get();
                if (names != null && !names.isEmpty()) {
                    fallback.setSlashCommands(names);
                }
            }
        }
        String line = fallback.readLine();
        return line == null ? null : line;
    }

    @Override
    public void close() {
        if (mode == Mode.JLINE) {
            try {
                reader.getTerminal().close();
            } catch (Exception ignored) {
            }
        } else if (fallback != null) {
            fallback.close();
        }
    }

    private static void closeQuietly(Terminal t) {
        if (t == null) {
            return;
        }
        try {
            t.close();
        } catch (Exception ignored) {
        }
    }

    // ===== 补全 =====

    /**
     * 斜杠命令补全：首词 {@code /xx} 按前缀匹配命令池（由 SlashRegistry 派生）；
     * {@code /switch ...} 第二词起补会话 id（由 sessionIds 供给）。
     */
    static final class SlashCompleter implements Completer {
        private final Supplier<List<String>> pool;
        private final Supplier<List<String>> sessionIds;

        SlashCompleter(Supplier<List<String>> pool, Supplier<List<String>> sessionIds) {
            this.pool = pool;
            this.sessionIds = sessionIds;
        }

        /** 补全命令池 — 注册表派生；未注入时退回终端私有命令清单。 */
        List<String> commandPool() {
            if (pool == null) {
                return RawTerminalReader.LOCAL_COMMANDS;
            }
            List<String> names = pool.get();
            return names == null || names.isEmpty() ? RawTerminalReader.LOCAL_COMMANDS : names;
        }

        @Override
        public void complete(LineReader lineReader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word() == null ? "" : line.word();
            List<String> words = line.words();
            if (line.wordIndex() == 0) {
                if (word.startsWith("/")) {
                    addMatches(candidates, commandPool(), word);
                }
                return;
            }
            String first = words.isEmpty() ? "" : words.get(0);
            if ("/switch".equals(first) && sessionIds != null) {
                List<String> ids = sessionIds.get();
                if (ids != null) {
                    addMatches(candidates, ids, word);
                }
            }
        }

        static void addMatches(List<Candidate> out, List<String> pool, String prefix) {
            for (String w : pool) {
                if (w != null && w.startsWith(prefix)) {
                    out.add(new Candidate(w, w, null, null, null, null, true));
                }
            }
        }
    }

    // ===== 高亮 =====

    /**
     * 输入高亮：首词斜杠命令绿粗体，反引号内代码段青色，其余原样。
     */
    static final class InputHighlighter implements Highlighter {

        private static final AttributedStyle SLASH =
                AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
        private static final AttributedStyle CODE =
                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);

        @Override
        public AttributedString highlight(LineReader lineReader, String buffer) {
            if (buffer == null || buffer.isEmpty()) {
                return AttributedString.EMPTY;
            }
            AttributedStringBuilder sb = new AttributedStringBuilder();
            int i = 0;
            if (buffer.startsWith("/")) {
                int sp = buffer.indexOf(' ');
                int end = sp < 0 ? buffer.length() : sp;
                sb.append(buffer.substring(0, end), SLASH);
                i = end;
            }
            appendWithCode(sb, buffer.substring(i));
            return sb.toAttributedString();
        }

        /** JLine 的补全/解析错误标记接口 — 本高亮器不做错误标注。 */
        @Override
        public void setErrorPattern(java.util.regex.Pattern errorPattern) {
        }

        @Override
        public void setErrorIndex(int errorIndex) {
        }

        /** 非贪婪扫反引号成对区间，段内青色。 */
        private static void appendWithCode(AttributedStringBuilder sb, String s) {
            int from = 0;
            while (from < s.length()) {
                int open = s.indexOf('`', from);
                if (open < 0) {
                    sb.append(s.substring(from));
                    return;
                }
                int close = s.indexOf('`', open + 1);
                if (close < 0) {
                    sb.append(s.substring(from));
                    return;
                }
                sb.append(s.substring(from, open));
                sb.append(s.substring(open, close + 1), CODE);
                from = close + 1;
            }
        }
    }
}
