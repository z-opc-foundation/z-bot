package com.zifang.z.bot.tool.env;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次执行的请求（P22 执行后端 SPI）。
 *
 * <p>关键契约：<b>命令只有 argv 一种形态</b>（{@link #argv()}），没有 "把命令写成一个字符串"
 * 的入口 —— 后端拿到的是 argv 数组，谁都不许再把不可信文本拼进命令行或 shell 串里。
 * 需要 shell 语义（既有 {@code exec} 工具的 {@code bash -c "<command>"}）时，
 * 由<b>调用方</b>显式把整条脚本作为单个 argv 元素传入（argv = {@code ["bash","-c",script]}），
 * 这个决定必须在调用处看得见，不能藏在后端里。</p>
 *
 * <p>其余字段对齐 hermes：{@code cwd}（她 {@code base.py:182 get_sandbox_dir}）、
 * {@code stdin}（她 {@code base.py:202 _pipe_stdin} + {@code :219} 单独线程写）、
 * {@code timeoutMillis}（她 {@code _wait_for_process} 的超时收尾）、
 * {@code maxOutputBytes} + {@link OutputPolicy}（她 {@code base.py:54} 的有界收集）。</p>
 */
public final class ExecRequest {

    /** 超限之后留哪一段。 */
    public enum OutputPolicy {
        /** 只留头部 {@code maxOutputBytes} 字节（既有 {@code BuiltinTools#bash} 的语义，回归要用它）。 */
        HEAD,
        /** 头 40% + 尾 60% 窗口 + 省略标记（hermes {@code _BoundedOutputCollector.render} 的语义）。 */
        WINDOW
    }

    /**
     * 子进程起来/结束时的回调 —— <b>接线用的缝</b>。
     *
     * <p>P12 的中断语义（{@code InterruptScope.watch(p)} + 看门狗端进程树）住在调用方，
     * 后端只负责把 {@link Process} 递出去。抽层之后这条缝要是断了（后端不再回调），
     * {@code BuiltinToolsExecWiringTest} 必须变红。</p>
     *
     * <p>{@link #interrupted()} 是同一条缝的另一半：读输出/等待收尾的循环每轮问一次，
     * 置位就端掉整棵树 —— 少了这一半，"抽层之后还能按停止键断长命令" 就没人兜着了
     * （既有的 {@code ToolSideInterruptTest} 认的是 2s 内进程表干净）。</p>
     */
    public interface ProcessObserver {
        void started(Process p);

        void finished(Process p);

        /** 调用方自己的中止旗子（没有就返 false）。 */
        default boolean interrupted() {
            return false;
        }
    }

    private static final ProcessObserver NOOP_OBSERVER = new ProcessObserver() {
        @Override
        public void started(Process p) {
        }

        @Override
        public void finished(Process p) {
        }
    };

    private final List<String> argv;
    private final File cwd;
    private final Map<String, String> env;
    private final byte[] stdin;
    private final long timeoutMillis;
    private final int maxOutputBytes;
    private final OutputPolicy policy;
    private final boolean mergeStreams;
    private final ProcessObserver observer;

    private ExecRequest(Builder b) {
        if (b.argv == null || b.argv.isEmpty()) {
            throw new IllegalArgumentException("argv 不能为空：至少要有可执行文件名");
        }
        List<String> copy = new ArrayList<String>(b.argv.size());
        for (String a : b.argv) {
            if (a == null) {
                throw new IllegalArgumentException("argv 里不许有 null（第 " + copy.size() + " 个参数）");
            }
            copy.add(a);
        }
        this.argv = Collections.unmodifiableList(copy);
        this.cwd = b.cwd;
        this.env = b.env == null ? null : Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.env));
        this.stdin = b.stdin == null ? null : b.stdin.clone();
        this.timeoutMillis = Math.max(0L, b.timeoutMillis);
        this.maxOutputBytes = b.maxOutputBytes < 0 ? 0 : b.maxOutputBytes;
        this.policy = b.policy == null ? OutputPolicy.WINDOW : b.policy;
        this.mergeStreams = b.mergeStreams;
        this.observer = b.observer == null ? NOOP_OBSERVER : b.observer;
    }

    public static Builder builder(List<String> argv) {
        return new Builder(argv);
    }

    public static Builder builder(String... argv) {
        List<String> list = new ArrayList<String>(argv == null ? 0 : argv.length);
        if (argv != null) {
            Collections.addAll(list, argv);
        }
        return new Builder(list);
    }

    public List<String> argv() {
        return argv;
    }

    public File cwd() {
        return cwd;
    }

    /** null = 完全继承父进程环境；非 null = 在继承之上叠加这些键（从不清空父环境）。 */
    public Map<String, String> env() {
        return env;
    }

    public byte[] stdin() {
        return stdin == null ? null : stdin.clone();
    }

    /** 0 = 不设时间上限（只受调用方的中断旗子管）。 */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** 0 = 不设字节上限（仍受 {@link #policy()} 约束；后端应当自己套一个全局兜底上界）。 */
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    public OutputPolicy policy() {
        return policy;
    }

    /** true = stderr 并进 stdout（既有 {@code redirectErrorStream(true)} 的语义）。 */
    public boolean mergeStreams() {
        return mergeStreams;
    }

    public ProcessObserver observer() {
        return observer;
    }

    /** 命令行摘要（给日志/台账用）：只出 argv 的段数与首段，绝不整条回显参数值。 */
    public String describe() {
        return "argv=" + argv.size() + " 段, cwd=" + (cwd == null ? "(继承)" : cwd.getPath())
                + ", timeoutMs=" + timeoutMillis + ", maxOut=" + maxOutputBytes + "B/" + policy
                + (mergeStreams ? ", merged" : ", split") + (stdin == null ? "" : ", stdin=" + stdin.length + "B");
    }

    /**
     * POSIX 单引号转义：{@code ssh} 这类「远端还要再过一次 shell」的后端唯一允许的拼法。
     *
     * <p>逐 token 调用，绝不允许拿不可信文本去 {@code +} 出一条命令。单引号内什么都不解释，
     * 所以只需把值里的 {@ '} 换成 {@code '\''}。</p>
     */
    public static String posixQuote(String token) {
        if (token == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder(token.length() + 8);
        sb.append('\'');
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    public static final class Builder {
        private final List<String> argv;
        private File cwd;
        private Map<String, String> env;
        private byte[] stdin;
        private long timeoutMillis;
        private int maxOutputBytes;
        private OutputPolicy policy = OutputPolicy.WINDOW;
        private boolean mergeStreams;
        private ProcessObserver observer;

        Builder(List<String> argv) {
            this.argv = argv;
        }

        public Builder cwd(File v) {
            this.cwd = v;
            return this;
        }

        public Builder env(Map<String, String> v) {
            this.env = v;
            return this;
        }

        public Builder stdin(byte[] v) {
            this.stdin = v;
            return this;
        }

        public Builder stdinText(String v, String charset) {
            if (v == null) {
                return this;
            }
            try {
                this.stdin = v.getBytes(charset);
            } catch (Exception e) {
                throw new IllegalArgumentException("stdin 编码失败：" + e.getMessage(), e);
            }
            return this;
        }

        public Builder timeoutMillis(long v) {
            this.timeoutMillis = v;
            return this;
        }

        public Builder maxOutputBytes(int v) {
            this.maxOutputBytes = v;
            return this;
        }

        public Builder policy(OutputPolicy v) {
            this.policy = v;
            return this;
        }

        public Builder mergeStreams(boolean v) {
            this.mergeStreams = v;
            return this;
        }

        public Builder observer(ProcessObserver v) {
            this.observer = v;
            return this;
        }

        public ExecRequest build() {
            return new ExecRequest(this);
        }
    }
}
