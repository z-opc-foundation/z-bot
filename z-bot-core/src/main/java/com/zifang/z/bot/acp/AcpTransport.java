package com.zifang.z.bot.acp;

/**
 * ACP 的一条出向通道：一行一条 JSON（换行分帧）+ 立刻 flush。
 *
 * <p>为什么单独抽这一层：ACP 是 stdio 协议，stdout 只能放协议帧（她的
 * {@code acp_adapter/entry.py} 把日志全推 stderr，正是为此）。测试要能在内存里
 * 收帧、生产要走真 stdout，两者共用同一套分派逻辑，故把"怎么写"剥成接口。</p>
 */
public interface AcpTransport {

    /** 写一条完整报文（不含行尾换行，由实现补）。实现必须自带串行化，避免两线程交错。 */
    void sendLine(String jsonLine);

    /** 关闭通道：stdio 实现会关掉输出流；内存实现只置标志。幂等。 */
    void close();
}
