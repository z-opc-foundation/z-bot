package com.zifang.z.bot.ui;

import com.zifang.z.bot.slash.SlashCommand;
import com.zifang.z.bot.slash.SlashRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link LineEditor} 单测 — 覆盖降级契约、注册表派生的补全池、输入高亮。
 *
 * <p>不起真终端：JLine 装配路径用 {@code jline.terminal=dumb} 逼出降级分支，
 * 补全/高亮两个内部类按纯函数直接验。</p>
 */
public class LineEditorTest {

    private final String originalTerminalProperty = System.getProperty("jline.terminal");

    @After
    public void restoreTerminalProperty() {
        if (originalTerminalProperty == null) {
            System.clearProperty("jline.terminal");
        } else {
            System.setProperty("jline.terminal", originalTerminalProperty);
        }
    }

    /** dumb 终端必须静默降级成行模式，且 create/close 全程不抛。 */
    @Test
    public void dumbTerminalFallsBackWithoutThrowing() {
        System.setProperty("jline.terminal", "dumb");
        LineEditor editor = LineEditor.create(null, null, null);
        try {
            assertEquals(LineEditor.Mode.FALLBACK, editor.mode());
        } finally {
            editor.close();
        }
    }

    /** 任何入参组合都不许抛 — TUI 起不来会整条 REPL 通道不可用。 */
    @Test
    public void createNeverThrows() {
        LineEditor editor = LineEditor.create(null, new java.util.function.Supplier<List<String>>() {
            @Override
            public List<String> get() {
                throw new IllegalStateException("pool unavailable");
            }
        }, null);
        assertNotNull(editor);
        editor.close();
    }

    /**
     * 补全池必须来自注册表：/checkpoints /rollback /background /agents /compress
     * 这 5 条曾在手写清单里漏掉，Tab 补不出来。
     */
    @Test
    public void completionPoolComesFromRegistry() {
        LineEditor.SlashCompleter completer =
                new LineEditor.SlashCompleter(registryPool(), null);
        List<String> pool = completer.commandPool();
        for (String cmd : Arrays.asList("/checkpoints", "/rollback", "/background", "/agents",
                "/compress", "/cron", "/queue", "/stop", "/steer")) {
            assertTrue("注册表命令未进补全池: " + cmd, pool.contains(cmd));
        }
        assertTrue("终端私有命令未进补全池: /status", pool.contains("/status"));

        List<Candidate> out = candidatesFor(completer, "/check", 0);
        assertEquals(1, out.size());
        assertEquals("/checkpoints", out.get(0).displ());
    }

    /** 首词非 / 开头不给候选（普通对话输入不该被命令污染）。 */
    @Test
    public void plainTextGetsNoCandidates() {
        LineEditor.SlashCompleter completer =
                new LineEditor.SlashCompleter(registryPool(), null);
        assertTrue(candidatesFor(completer, "hello", 0).isEmpty());
    }

    /** {@code /switch <id>} 第二词补会话 id。 */
    @Test
    public void switchSecondWordCompletesSessionIds() {
        final List<String> ids = Arrays.asList("session_a1", "session_b2", "other_c3");
        LineEditor.SlashCompleter completer = new LineEditor.SlashCompleter(
                registryPool(), new java.util.function.Supplier<List<String>>() {
                    @Override
                    public List<String> get() {
                        return ids;
                    }
                });
        List<Candidate> out = candidatesFor(completer, Arrays.asList("/switch", "session_"), 1);
        assertEquals(2, out.size());
        assertEquals("session_a1", out.get(0).displ());
        assertEquals("session_b2", out.get(1).displ());
    }

    /** 会话 id 供给方返回 null 不能炸。 */
    @Test
    public void nullSessionIdsAreIgnored() {
        LineEditor.SlashCompleter completer = new LineEditor.SlashCompleter(
                registryPool(), new java.util.function.Supplier<List<String>>() {
                    @Override
                    public List<String> get() {
                        return null;
                    }
                });
        assertTrue(candidatesFor(completer, Arrays.asList("/switch", "x"), 1).isEmpty());
    }

    /** 斜杠命令首词绿粗体，参数部分保持默认样式。 */
    @Test
    public void slashCommandIsHighlightedGreenBold() {
        AttributedString s = new LineEditor.InputHighlighter().highlight(null, "/exit now");
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold(), s.styleAt(0));
        assertEquals(AttributedStyle.DEFAULT, s.styleAt(6));
        assertEquals("/exit now", s.toString());
    }

    /** 反引号成对区间青色，区间外默认。 */
    @Test
    public void backtickRangeIsHighlightedCyan() {
        AttributedString s = new LineEditor.InputHighlighter().highlight(null, "run `ls -l` now");
        AttributedStyle cyan = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        assertEquals(cyan, s.styleAt(4));
        assertEquals(cyan, s.styleAt(10));
        assertEquals(AttributedStyle.DEFAULT, s.styleAt(3));
        assertEquals(AttributedStyle.DEFAULT, s.styleAt(12));
        // 未闭合的反引号不高亮
        assertEquals(AttributedStyle.DEFAULT,
                new LineEditor.InputHighlighter().highlight(null, "run `ls").styleAt(4));
    }

    /** 空 buffer 返回 EMPTY 而不是 null。 */
    @Test
    public void emptyBufferHighlightsToEmpty() {
        assertEquals(AttributedString.EMPTY,
                new LineEditor.InputHighlighter().highlight(null, ""));
    }

    // ===== helpers =====

    private static java.util.function.Supplier<List<String>> registryPool() {
        final SlashRegistry registry = SlashRegistry.withBuiltinCommands();
        return new java.util.function.Supplier<List<String>>() {
            @Override
            public List<String> get() {
                List<String> names = new ArrayList<>();
                for (SlashCommand c : registry.all()) {
                    names.add(c.name());
                }
                names.addAll(RawTerminalReader.LOCAL_COMMANDS);
                return names;
            }
        };
    }

    private List<Candidate> candidatesFor(LineEditor.SlashCompleter completer, String word, int wordIndex) {
        return candidatesFor(completer, Collections.singletonList(word), wordIndex);
    }

    private List<Candidate> candidatesFor(final LineEditor.SlashCompleter completer,
                                          final List<String> words, final int wordIndex) {
        ParsedLine line = new ParsedLine() {
            @Override
            public String word() {
                return wordIndex < words.size() ? words.get(wordIndex) : "";
            }

            @Override
            public int wordCursor() {
                return word().length();
            }

            @Override
            public int wordIndex() {
                return wordIndex;
            }

            @Override
            public List<String> words() {
                return words;
            }

            @Override
            public String line() {
                return join();
            }

            @Override
            public int cursor() {
                return line().length();
            }

            private String join() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(words.get(i));
                }
                return sb.toString();
            }
        };
        List<Candidate> out = new ArrayList<>();
        completer.complete(null, line, out);
        return out;
    }
}
