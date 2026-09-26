package com.zifang.z.bot.delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 委托结果的<b>投递账</b>：给"结果已经好了"和"有人接住了"之间立一张可取证的凭证流。
 *
 * <p>锚点是 hermes {@code tools/async_delegation.py:84} 的
 * {@code _MAX_DELIVERY_ATTEMPTS = 8}，以及她 {@code release_completion_delivery}（:339-:372）的判词：
 * <i>"Attempts are counted at claim time, so a row that keeps being claimed and released has burned
 * real delivery attempts. Once the budget is exhausted the row converges to a terminal
 * {@code dropped} state instead of returning to {@code pending} — otherwise an undeliverable
 * completion replays on every gateway restart forever."</i></p>
 *
 * <p>z-bot 修之前的现状（探针实测，见 {@code _doc/acceptance/p27/EVIDENCE.md} §1）：
 * 异步委托的 {@code DONE} 是子代理<b>自己</b>写的旗子，没有任何一根轴表达"有人接过"；
 * {@code /background result <id>} 拉 12 次返回 12 次，无计数、无上限、无终态；
 * 默认 JSON 会话模式下 {@code StateStore} 为 {@code null}，所以这套旗子还<b>一行都不落盘</b>。
 * 本期把这三件事补齐：投递有上限、上限在盘上、烧完收敛到 {@link DeliveryState#DROPPED}。</p>
 *
 * <p>三条硬规矩（都有对应断言）：
 * <ol>
 *   <li>claim 才烧尝试数（她同语义）；</li>
 *   <li>{@code PENDING --RESULT_DELIVERED--> } 是<b>非法迁移</b>：没有凭证就想记成功，
 *       直接抛，不给"静默 ok"留口子（P16 抓过的那一类）；</li>
 *   <li>尝试数到顶 ⇒ 终态 {@code DROPPED}，重启恢复只捞 {@code PENDING}（见
 *       {@link #undeliveredTerminalResults()}），{@code DROPPED} 不再重放。</li>
 * </ol>
 */
public final class DelegationDelivery {

    /** 投递尝试上限 —— 与 hermes {@code async_delegation.py:84} 同值，是取证锚点，不是调优项。 */
    public static final int MAX_DELIVERY_ATTEMPTS = 8;

    /**
     * claim 的租约（毫秒）。她的对应物是 {@code claim_completion_delivery} 里的
     * {@code delivery_claimed_at < now - 300}（:322）：租约没到期的行别人抢不走，
     * 到期的行允许被"偷走"，偷一次同样再烧一次尝试数。
     */
    public static final long CLAIM_LEASE_MILLIS = 300_000L;

    private final DelegationLedger ledger;
    private final AtomicInteger tokens = new AtomicInteger();

    public DelegationDelivery(DelegationLedger ledger) {
        if (ledger == null) {
            throw new IllegalArgumentException("DelegationDelivery 需要一个台账实例（可 disabled，不可 null）");
        }
        this.ledger = ledger;
    }

    public DelegationLedger ledger() {
        return ledger;
    }

    // ================= claim / release / complete =================

    /**
     * 认领一条投递：成功返回凭证 token，并把 {@code delivery_attempts} +1。
     *
     * @return 凭证；{@code null} 表示"现在领不走"（现场不存在 / 已终态 / 别人租约未到期）
     */
    public synchronized String claim(String id, String consumer) {
        return claim(id, consumer, System.currentTimeMillis());
    }

    public synchronized String claim(String id, String consumer, long now) {
        DelegationLedger.Entry e = ledger.load(id);
        if (e == null || e.delivery.terminal()) {
            return null;
        }
        if (e.delivery == DeliveryState.CLAIMED && e.claimExpiresAt > now) {
            return null; // 别人租约内，抢不走
        }
        String token = (consumer == null ? "anon" : DelegationLedger.slug(consumer))
                + ":" + DelegationLedger.currentPid() + ":" + tokens.incrementAndGet();
        e.deliveryAttempts++;
        e.claimToken = token;
        e.claimExpiresAt = now + CLAIM_LEASE_MILLIS;
        ledger.advanceDelivery(e, DelegateEvent.RESULT_CLAIMED,
                "consumer=" + consumer + " attempt=" + e.deliveryAttempts + "/" + MAX_DELIVERY_ATTEMPTS);
        return token;
    }

    /**
     * 释放一次没走完的投递。
     *
     * <p>到顶 ⇒ 走 {@link DelegateEvent#RESULT_DROPPED} 收敛成终态，
     * <b>不</b>回 {@code PENDING}（否则没人接的完成事件会永远重放）；
     * 没到顶 ⇒ 回 {@code PENDING} 等下一个消费者。</p>
     *
     * @return 是否真的动过这条账
     */
    public synchronized boolean release(String id, String token) {
        return release(id, token, System.currentTimeMillis());
    }

    public synchronized boolean release(String id, String token, long now) {
        DelegationLedger.Entry e = requireClaimed(id, token);
        if (e == null) {
            return false;
        }
        if (e.deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {
            e.claimToken = "";
            e.claimExpiresAt = 0L;
            ledger.advanceDelivery(e, DelegateEvent.RESULT_DROPPED,
                    "投递尝试烧完 " + e.deliveryAttempts + "/" + MAX_DELIVERY_ATTEMPTS + "，终态丢弃"
                            + "（结果仍可查，账上不记成功）");
            return true;
        }
        e.claimToken = "";
        e.claimExpiresAt = 0L;
        ledger.advanceDelivery(e, DelegateEvent.RESULT_RETRY,
                "release by " + token + "，attempt=" + e.deliveryAttempts + "/" + MAX_DELIVERY_ATTEMPTS);
        return true;
    }

    /**
     * 消费者确认接住 —— 只有持有有效凭证、且当前真的是 {@code CLAIMED} 才允许。
     *
     * @throws IllegalStateException 这条现场压根没被 claim 过（{@code PENDING}）就想要 ack：
     *         这是 P16 那个同型洞的守门人，不给布尔返回值留"调用方忘了看"的余地
     */
    public synchronized boolean complete(String id, String token) {
        DelegationLedger.Entry e = ledger.load(id);
        if (e == null) {
            return false;
        }
        if (e.delivery == DeliveryState.PENDING) {
            throw new IllegalStateException("拒绝无凭证的 ack: " + id
                    + " 投递态是 PENDING（从没被 claim 过），先 claim 再 complete");
        }
        if (e.delivery != DeliveryState.CLAIMED || !eq(e.claimToken, token)) {
            return false; // 已被别人偷走 / 已是终态
        }
        e.claimToken = "";
        e.claimExpiresAt = 0L;
        ledger.advanceDelivery(e, DelegateEvent.RESULT_DELIVERED,
                "acked by " + token + " after " + e.deliveryAttempts + " attempt(s)");
        return true;
    }

    /** 确认对端永久不在了（会话被用户显式结束）⇒ 直接终态丢弃。 */
    public synchronized boolean drop(String id, String reason) {
        DelegationLedger.Entry e = ledger.load(id);
        if (e == null || e.delivery.terminal()) {
            return false;
        }
        if (e.delivery == DeliveryState.CLAIMED) {
            e.claimToken = "";
            e.claimExpiresAt = 0L;
        }
        ledger.advanceDelivery(e, DelegateEvent.RESULT_DROPPED, "explicit drop: " + reason);
        return true;
    }

    // ================= 读侧 =================

    public synchronized int attempts(String id) {
        DelegationLedger.Entry e = ledger.load(id);
        return e == null ? -1 : e.deliveryAttempts;
    }

    public synchronized DeliveryState stateOf(String id) {
        DelegationLedger.Entry e = ledger.load(id);
        return e == null ? null : e.delivery;
    }

    /** 一条投递的一句话判词，例：{@code PENDING(0/8)} / {@code DROPPED(8/8)}。 */
    public synchronized String describe(String id) {
        DelegationLedger.Entry e = ledger.load(id);
        if (e == null) {
            return "MISSING(-/1)";
        }
        return e.delivery + "(" + e.deliveryAttempts + "/" + MAX_DELIVERY_ATTEMPTS + ")";
    }

    /**
     * 结果好了但从没被接走的现场（<b>只</b>看 {@code PENDING}）。
     * 她的对应物是 {@code restore_undelivered_completions}（:287
     * {@code WHERE state != 'running' AND delivery_state='pending'}）：
     * {@code dropped} 行不参与恢复，这正是上限的意义。
     */
    public synchronized List<DelegationLedger.Entry> undeliveredTerminalResults() {
        List<DelegationLedger.Entry> out = new ArrayList<DelegationLedger.Entry>();
        for (DelegationLedger.Entry e : ledger.list()) {
            if (e.state.terminal() && e.delivery == DeliveryState.PENDING) {
                out.add(e);
            }
        }
        return out;
    }

    /** 台账里所有投递的汇总（{@code /agents} 与 E2E 判词共用）。 */
    public synchronized String summary() {
        int pending = 0;
        int claimed = 0;
        int delivered = 0;
        int dropped = 0;
        int maxAttempts = 0;
        List<DelegationLedger.Entry> all = ledger.list();
        for (DelegationLedger.Entry e : all) {
            switch (e.delivery) {
                case PENDING:
                    pending++;
                    break;
                case CLAIMED:
                    claimed++;
                    break;
                case DELIVERED:
                    delivered++;
                    break;
                case DROPPED:
                    dropped++;
                    break;
                default:
                    break;
            }
            maxAttempts = Math.max(maxAttempts, e.deliveryAttempts);
        }
        return "delegations=" + all.size() + " pending=" + pending + " claimed=" + claimed
                + " delivered=" + delivered + " dropped=" + dropped
                + " maxAttempts=" + maxAttempts + "/" + MAX_DELIVERY_ATTEMPTS;
    }

    // ================= 内部 =================

    private DelegationLedger.Entry requireClaimed(String id, String token) {
        DelegationLedger.Entry e = ledger.load(id);
        if (e == null || e.delivery != DeliveryState.CLAIMED || !eq(e.claimToken, token)) {
            return null;
        }
        return e;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
