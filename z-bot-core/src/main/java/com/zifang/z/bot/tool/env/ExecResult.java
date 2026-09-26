package com.zifang.z.bot.tool.env;

/**
 * 一次执行的结果（P22 执行后端 SPI）。
 *
 * <p>对齐 hermes {@code tools/environments/base.py:54 _BoundedOutputCollector} 的语义：
 * 输出<b>必须有界</b>，但截断不能是隐式的 —— 除了渲染后的文本，还要把
 * {@code truncated} 标志和 {@code total*Bytes}（真实全量字节数）一起带回来，
 * 上层才能分辨「命令只产出了这么多」和「被我们砍掉了这么多」。</p>
 *
 * <p>{@link #timedOut()} / {@link #killedDescendants()} 是超时端掉整棵进程树的取证位：
 * 只杀 bash 本身、留下孤儿 {@code sleep}/{@code mvn}，在 hermes 那边同样是不可接受的
 * （见她 {@code base.py:289 ProcessHandle} 的 poll/kill/wait 约定）。</p>
 */
public final class ExecResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final long stdoutTotalBytes;
    private final long stderrTotalBytes;
    private final boolean timedOut;
    private final int killedDescendants;
    private final String backend;

    ExecResult(int exitCode, String stdout, String stderr,
               boolean stdoutTruncated, boolean stderrTruncated,
               long stdoutTotalBytes, long stderrTotalBytes,
               boolean timedOut, int killedDescendants, String backend) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
        this.stdoutTotalBytes = stdoutTotalBytes;
        this.stderrTotalBytes = stderrTotalBytes;
        this.timedOut = timedOut;
        this.killedDescendants = killedDescendants;
        this.backend = backend == null ? "?" : backend;
    }

    public static Builder builder(String backend) {
        return new Builder(backend);
    }

    public int exitCode() {
        return exitCode;
    }

    /** 渲染后的 stdout（已按上界裁剪；合并流模式下 stderr 也在里面）。 */
    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    public boolean stderrTruncated() {
        return stderrTruncated;
    }

    /** 任何裁剪发生之前的全量字节数（对应她 {@code total_chars}）。 */
    public long stdoutTotalBytes() {
        return stdoutTotalBytes;
    }

    public long stderrTotalBytes() {
        return stderrTotalBytes;
    }

    /** 超时：整棵进程树被端掉，退出码不是命令自己跑出来的。 */
    public boolean timedOut() {
        return timedOut;
    }

    /** 超时收尾时额外端掉的后代进程数（=0 且命令有名下单 ⇒ 只杀了父）。 */
    public int killedDescendants() {
        return killedDescendants;
    }

    public String backend() {
        return backend;
    }

    /** 合并视图：{@code stdout}（合并流时已含 stderr）。给只关心一份输出的上层。 */
    public String output() {
        return stdout;
    }

    @Override
    public String toString() {
        return "ExecResult[backend=" + backend + " exit=" + exitCode
                + " stdout=" + stdout.length() + "/" + stdoutTotalBytes + "B"
                + (stdoutTruncated ? "(truncated)" : "")
                + " stderr=" + stderr.length() + "/" + stderrTotalBytes + "B"
                + (stderrTruncated ? "(truncated)" : "")
                + (timedOut ? " timedOut killedDescendants=" + killedDescendants : "") + "]";
    }

    public static final class Builder {
        private final String backend;
        private int exitCode;
        private String stdout = "";
        private String stderr = "";
        private boolean stdoutTruncated;
        private boolean stderrTruncated;
        private long stdoutTotalBytes;
        private long stderrTotalBytes;
        private boolean timedOut;
        private int killedDescendants;

        public Builder(String backend) {
            this.backend = backend;
        }

        public Builder exitCode(int v) {
            this.exitCode = v;
            return this;
        }

        public Builder stdout(String v) {
            this.stdout = v;
            return this;
        }

        public Builder stderr(String v) {
            this.stderr = v;
            return this;
        }

        public Builder stdoutTruncated(boolean v) {
            this.stdoutTruncated = v;
            return this;
        }

        public Builder stderrTruncated(boolean v) {
            this.stderrTruncated = v;
            return this;
        }

        public Builder stdoutTotalBytes(long v) {
            this.stdoutTotalBytes = v;
            return this;
        }

        public Builder stderrTotalBytes(long v) {
            this.stderrTotalBytes = v;
            return this;
        }

        public Builder timedOut(boolean v) {
            this.timedOut = v;
            return this;
        }

        public Builder killedDescendants(int v) {
            this.killedDescendants = v;
            return this;
        }

        public ExecResult build() {
            return new ExecResult(exitCode, stdout, stderr, stdoutTruncated, stderrTruncated,
                    stdoutTotalBytes, stderrTotalBytes, timedOut, killedDescendants, backend);
        }
    }
}
