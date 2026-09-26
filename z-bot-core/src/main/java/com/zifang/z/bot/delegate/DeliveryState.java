package com.zifang.z.bot.delegate;

/**
 * 投递轴状态（对齐 hermes {@code async_delegations.delivery_state}：
 * {@code pending} → {@code delivered} / {@code dropped}，见 {@code async_delegation.py:109,303,354}）。
 *
 * <p>她的判词很硬：一次 claim 就烧一次 {@code delivery_attempts}，
 * 烧到 {@code _MAX_DELIVERY_ATTEMPTS = 8} 之后行收敛到终态 {@code dropped}
 * 而<b>不是</b>回到 {@code pending} —— 否则一个没人接的完成事件会在每次重启后
 * 永远重放。z-bot 侧原来这根轴完全不存在。</p>
 */
public enum DeliveryState {

    /** 结果已就绪（或即将就绪），还没人 claim。 */
    PENDING,

    /** 被某个消费者凭票据 claim 走，尚未 ack。 */
    CLAIMED,

    /** 消费者已 ack 接住 —— 只有这条才允许对外说"投递成功"。 */
    DELIVERED,

    /** 投递预算烧完 / 确认无对端 ⇒ 终态丢弃（结果仍可查，账上不记成功）。 */
    DROPPED;

    /** 投递终态：任何事件都不再能动它。 */
    public boolean terminal() {
        return this == DELIVERED || this == DROPPED;
    }
}
