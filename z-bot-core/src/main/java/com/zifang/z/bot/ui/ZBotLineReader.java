package com.zifang.z.bot.ui;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.Map;

/**
 * JLine 装配的多行/折叠契约：修饰键 + Enter 插换行、行尾反斜杠续行、
 * 括号粘贴过阈值折成标记、提交那一刻把标记换回原文。
 */
public final class ZBotLineReader extends LineReaderImpl {

    private static final char ESC = 27;

    /** 换行键位：CSI-u 的 Shift/Ctrl/Alt+Enter，加传统 Meta+CR、Meta+LF。 */
    static final String[] NEWLINE_KEYS = {
            ESC + "[13;2u", ESC + "[13;5u", ESC + "[13;8u", ESC + "\r", ESC + "\n"
    };

    private final PasteFolder.Snips snips = new PasteFolder.Snips();

    public ZBotLineReader(Terminal terminal, String appName, Map<String, Object> variables)
            throws IOException {
        super(terminal, appName, variables);
        installBindings();
    }

    public PasteFolder.Snips snips() {
        return snips;
    }

    private void installBindings() {
        final Widget newline = new NewlineWidget();
        final Widget enter = new EnterWidget();
        for (String name : new String[] {"emacs", "viins"}) {
            KeyMap<Binding> map = getKeyMaps().get(name);
            if (map == null) {
                continue;
            }
            for (String key : NEWLINE_KEYS) {
                map.bind(newline, key);
            }
            map.bind(enter, "\r");
        }
    }

    boolean insertNewline() {
        getBuffer().write("\n");
        return true;
    }

    /** 行尾孤立一个反斜杠 = 续行：吃掉它、换行、继续读；否则交给 accept-line。 */
    boolean enterOrContinue() {
        Buffer buffer = getBuffer();
        String content = buffer.toString();
        if (buffer.cursor() == content.length() && Continuation.endsWithContinuation(content)) {
            buffer.backspace();
            buffer.write("\n");
            return true;
        }
        callWidget("accept-line");
        return true;
    }

    @Override
    public boolean beginPaste() {
        boolean result = super.beginPaste();
        foldLastPaste();
        return result;
    }

    /** super 刚写进 buffer 的那一段就是粘贴原文：区间 [regionMark, cursor)。 */
    void foldLastPaste() {
        Buffer buffer = getBuffer();
        int from = getRegionMark();
        int to = buffer.cursor();
        if (from < 0 || from > to) {
            return;
        }
        String pasted = buffer.substring(from, to);
        if (!PasteFolder.shouldFold(pasted)) {
            return;
        }
        String label = PasteFolder.token(pasted);
        snips.record(label, pasted);
        buffer.cursor(from);
        buffer.delete(to - from);
        buffer.write(label);
    }

    @Override
    protected boolean acceptLine() {
        Buffer buffer = getBuffer();
        String raw = buffer.toString();
        String expanded = snips.expand(raw);
        if (!expanded.equals(raw)) {
            buffer.clear();
            buffer.write(expanded);
        }
        snips.clear();
        return super.acceptLine();
    }

    private final class NewlineWidget implements Widget {
        @Override
        public boolean apply() {
            return insertNewline();
        }
    }

    private final class EnterWidget implements Widget {
        @Override
        public boolean apply() {
            return enterOrContinue();
        }
    }
}
