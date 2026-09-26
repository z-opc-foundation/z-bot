package com.zifang.z.bot.context;

/**
 * 压缩引擎的「外部件」：跨进程的抢锁 + 跨进程重启仍然有效的冷却/防抖状态。
 *
 * <p>存在的唯一理由：{@link CompressorEngine} 里的 {@code AtomicBoolean} 只能管住一个 JVM。
 * P14 要求 {@code compression_locks} 表真的有消费者、失败冷却真的入库，所以那些状态一律
 * 由实现本接口的 {@link CompressionLedger}（背后是 {@code StateStore}/sqlite）落盘，
 * 引擎只在内存里留副本做快速判断。</p>
 *
 * <p>为 null 的 gate 表示「无库可写」（纯测试桩），此时引擎退化为内存锁并在
 * {@link CompressorEngine#describe()} 里明说自己退化 —— 退化的那条路径不允许出现在 config 模式。</p>
 */
public interface CompressionGate {

    /** 抢同会话的压缩锁（DB 权威）。false = 别的线程/进程正在压，本次必须原样返回历史。 */
    boolean tryAcquire(String sessionId, String holder, long ttlMillis);

    /** 释放自己持有的锁；不是自己持有的就不动。 */
    void release(String sessionId, String holder);

    /** 读冷却截止时刻（毫秒）；0 = 无冷却。实现必须从库里读，不能只读内存。 */
    long loadCooldownUntilMillis(String sessionId);

    /** 写冷却截止时刻（毫秒）；0 = 清除。 */
    void storeCooldownUntilMillis(String sessionId, long untilMillis);

    /** 读「连续无效压缩」计数；实现从库里读，重启后仍成立。 */
    int loadIneffectiveStreak(String sessionId);

    /** 写「连续无效压缩」计数。 */
    void storeIneffectiveStreak(String sessionId, int streak);

    /** 当前会话 id（冷却与锁的键）；null = 无会话，此时按进程级键退避。 */
    String currentSessionId();
}
