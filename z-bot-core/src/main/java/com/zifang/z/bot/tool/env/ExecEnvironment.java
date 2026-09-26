package com.zifang.z.bot.tool.env;

import java.io.Closeable;
import java.io.IOException;

/**
 * 执行后端 SPI（P22）。
 *
 * <p>抽这一层之前，仓里「跑外部进程」这件事至少三套各写各的（复算见
 * {@code _doc/acceptance/p22/EVIDENCE.md} §0.3：{@code tool/BuiltinTools.java:455}、
 * {@code checkpoint/CheckpointManager.java:226}、{@code channel/DeliveryLedger.java:298}，
 * 外加工单没列的第四套 {@code ui/RawTerminalReader.java:238/:252}），
 * 各自的超时/取输出/杀进程口径都不一样。</p>
 *
 * <p>本期只做 {@link #name()} 为 {@code local / docker / ssh} 的三个后端
 * （她那边 {@code tools/environments/} 是 11 文件 6,486 行，modal/managed_modal/daytona/
 * singularity/file_sync 明确不做，理由见 EVIDENCE §12）。</p>
 *
 * <p>语义对齐 hermes {@code base.py:289 class ProcessHandle(Protocol)}
 * 与 {@code base.py:54 _BoundedOutputCollector}：输出有界、超时端掉整棵进程树、
 * 后端不可用就显式报错 —— <b>不许静默退回 local</b>。</p>
 */
public interface ExecEnvironment extends Closeable {

    String LOCAL = "local";
    String DOCKER = "docker";
    String SSH = "ssh";

    /** {@code local} / {@code docker} / {@code ssh}。 */
    String name();

    /**
     * 跑一条命令。
     *
     * @param request argv 形态的命令 + cwd/env/stdin/超时/输出上界
     * @return 带 {@code truncated} 与全量字节数的结果
     * @throws IOException            后端不可用（docker daemon 连不上、ssh 目标不可达/无凭据）——
     *                                必须是异常，不许吞掉之后换后端
     * @throws ExecEnvException       配置/能力问题（见 {@code ExecEnvException.Code}）
     */
    ExecResult exec(ExecRequest request) throws IOException;

    /** 在后端的沙箱根内写文件（{@code path} 是相对沙箱根的路径，越界必须拒绝）。 */
    void writeFile(String path, byte[] content) throws IOException;

    /** 读后端沙箱根内的文件。 */
    byte[] readFile(String path) throws IOException;

    /** 沙箱根（local = {@code Sandbox#root()}；docker/ssh = 后端里对应的目录）。 */
    String sandboxRoot();

    /** 这个后端能不能收 stdin（hermes 的 {@code _pipe_stdin} 只有子进程类后端做得到）。 */
    boolean supportsStdin();

    /** 调用方没给上界时用的字节上界提示（缺省走 {@link ExecEnvConfig#DEFAULT_OUTPUT_MAX_BYTES}）。 */
    default int maxOutputBytesHint() {
        return ExecEnvConfig.DEFAULT_OUTPUT_MAX_BYTES;
    }

    /** 调用方没给超时时的缺省超时（0 = 不设时间上限）。 */
    default long defaultTimeoutMillisHint() {
        return 0L;
    }

    /** 给日志/台账的配置摘要：只出键名与取值形态，绝不出任何密钥值。 */
    String describe();

    /** 释放后端资源；docker 后端必须在这里保证「不留 running 容器」。 */
    @Override
    void close() throws IOException;
}
