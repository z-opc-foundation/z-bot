package com.zifang.z.bot.acp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * stdout 方向的 ACP 通道：UTF-8、一行一帧、每帧立刻 flush。
 *
 * <p>立刻 flush 不是讲究，是必需 —— 客户端（IDE / acp-bridge）在等 {@code initialize}
 * 的回包，攒在 {@code BufferedWriter} 里就等于协议卡死。</p>
 *
 * <p>写串行化用显式的 {@code writeLock} 而不是方法级 {@code synchronized}：
 * {@code session/prompt} 的工作线程在推 {@code session/update} 通知的同时，
 * 读线程可能正在回 {@code initialize} 响应，两帧交错会让对端 JSON 解析失败。</p>
 */
public final class StdioAcpTransport implements AcpTransport {

    /** ACP 规定 stdio 上的 JSON 帧是 UTF-8（她的 SDK 用 {@code sys.stdin.buffer}）。 */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    public StdioAcpTransport(OutputStream out) {
        this.writer = new BufferedWriter(new OutputStreamWriter(out, UTF_8));
    }

    @Override
    public void sendLine(String jsonLine) {
        if (closed) {
            return;
        }
        synchronized (writeLock) {
            try {
                writer.write(jsonLine);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                // 对端把管道关了（IDE 退出）⇒ 通道视为关闭，不把 IO 异常往上抛成 INTERNAL_ERROR。
                closed = true;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (writeLock) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // 已经在关闭路径上，再报只会盖掉真正的退出原因。
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
