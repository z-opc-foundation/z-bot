package com.zifang.z.bot.delegate;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 委托面状态机：两条正交轴（生命周期 / 投递）各一张迁移表，<b>非法迁移大声失败</b>。
 *
 * <p>为什么两条轴而不是根一根字符串旗子：{@code DONE} 只说明子代理自己收工了，
 * 不说明结果有人接住；一根旗子同时表达这两件事，就必然出现
 * "投给拉模式控制台却被记成 ok"（P16 抓过的那个真身）。分成
 * {@link DelegateState} + {@link DeliveryState} 之后，"跑完但没人接"是
 * {@code (DONE, PENDING)} 这个合法组合，而不是一个自相矛盾的字面量。</p>
 *
 * <p>本表刻意<b>不</b>提供"未知事件就原地不动"的宽容分支：
 * {@link #next(DelegateState, DelegateEvent)} / {@link #nextDelivery(DeliveryState, DelegateEvent)}
 * 对表里没有的边一律抛 {@link IllegalTransitionException}。
 * 全量（状态 × 事件）的判定由测试穷举复算（见
 * {@code DelegateStateMachineTest#lifecycleMatrixIsExhaustive}）。</p>
 */
public final class DelegateTransitions {

    /** 非法迁移（大声失败）。 */
    public static final class IllegalTransitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final transient Object from;
        private final transient DelegateEvent event;

        IllegalTransitionException(String axis, Object from, DelegateEvent event) {
            super("非法委托迁移[" + axis + "]: " + from + " --" + event.wireName() + "--> ?"
                    + "（该事件在此状态下没有出口，合法事件集见 legalEventsFrom/legalDeliveryEventsFrom）");
            this.from = from;
            this.event = event;
        }

        public Object fromState() {
            return from;
        }

        public DelegateEvent event() {
            return event;
        }
    }

    private static final Map<DelegateState, Map<DelegateEvent, DelegateState>> LIFECYCLE =
            new EnumMap<DelegateState, Map<DelegateEvent, DelegateState>>(DelegateState.class);
    private static final Map<DeliveryState, Map<DelegateEvent, DeliveryState>> DELIVERY =
            new EnumMap<DeliveryState, Map<DelegateEvent, DeliveryState>>(DeliveryState.class);

    static {
        // ---- 生命周期轴 ----
        putLife(DelegateState.QUEUED, DelegateEvent.TASK_SPAWNED, DelegateState.RUNNING);
        putLife(DelegateState.QUEUED, DelegateEvent.TASK_PROGRESS, DelegateState.QUEUED);
        putLife(DelegateState.QUEUED, DelegateEvent.BUDGET_UNCLAMPED, DelegateState.QUEUED);
        putLife(DelegateState.QUEUED, DelegateEvent.TASK_FAILED, DelegateState.FAILED);
        putLife(DelegateState.QUEUED, DelegateEvent.TASK_STOPPED, DelegateState.STOPPED);
        putLife(DelegateState.QUEUED, DelegateEvent.ORPHAN_ADOPTED, DelegateState.UNKNOWN);

        putLife(DelegateState.RUNNING, DelegateEvent.TASK_COMPLETED, DelegateState.DONE);
        putLife(DelegateState.RUNNING, DelegateEvent.TASK_PROGRESS, DelegateState.RUNNING);
        putLife(DelegateState.RUNNING, DelegateEvent.BUDGET_UNCLAMPED, DelegateState.RUNNING);
        putLife(DelegateState.RUNNING, DelegateEvent.TASK_FAILED, DelegateState.FAILED);
        putLife(DelegateState.RUNNING, DelegateEvent.TASK_STOPPED, DelegateState.STOPPED);
        putLife(DelegateState.RUNNING, DelegateEvent.ORPHAN_ADOPTED, DelegateState.UNKNOWN);
        // DONE / FAILED / STOPPED / UNKNOWN 是终态：一条出口都不给。

        // ---- 投递轴 ----
        putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_CLAIMED, DeliveryState.CLAIMED);
        putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);
        // CLAIMED 可被"偷走"（hermes: delivery_claimed_at < now-300 才允许别的消费者再 claim，
        // 偷一次同样烧一次尝试数），也可直接确认丢弃。
        putDeliv(DeliveryState.CLAIMED, DelegateEvent.RESULT_DELIVERED, DeliveryState.DELIVERED);
        putDeliv(DeliveryState.CLAIMED, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);
        putDeliv(DeliveryState.CLAIMED, DelegateEvent.RESULT_CLAIMED, DeliveryState.CLAIMED);
        putDeliv(DeliveryState.CLAIMED, DelegateEvent.RESULT_RETRY, DeliveryState.PENDING);
        // ★ 关键守卫：PENDING --RESULT_DELIVERED--> 不在表里。
        //   没有 claim 凭证就想记 "delivered"，必炸 —— 这就是 P16 那个同型口子。
        // DELIVERED / DROPPED 是投递终态：一条出口都不给（重启后也只认 pending 行）。
    }

    private DelegateTransitions() {
    }

    private static void putLife(DelegateState from, DelegateEvent e, DelegateState to) {
        axis(LIFECYCLE, from).put(e, to);
    }

    private static void putDeliv(DeliveryState from, DelegateEvent e, DeliveryState to) {
        axis(DELIVERY, from).put(e, to);
    }

    private static <S extends Enum<S>> Map<DelegateEvent, S> axis(
            Map<S, Map<DelegateEvent, S>> table, S from) {
        Map<DelegateEvent, S> row = table.get(from);
        if (row == null) {
            row = new EnumMap<DelegateEvent, S>(DelegateEvent.class);
            table.put(from, row);
        }
        return row;
    }

    /** 生命周期迁移；非法 ⇒ {@link IllegalTransitionException}。 */
    public static DelegateState next(DelegateState from, DelegateEvent event) {
        DelegateState to = row(LIFECYCLE, from).get(event);
        if (to == null) {
            throw new IllegalTransitionException("lifecycle", from, event);
        }
        return to;
    }

    /** 投递迁移；非法 ⇒ {@link IllegalTransitionException}。 */
    public static DeliveryState nextDelivery(DeliveryState from, DelegateEvent event) {
        DeliveryState to = row(DELIVERY, from).get(event);
        if (to == null) {
            throw new IllegalTransitionException("delivery", from, event);
        }
        return to;
    }

    public static boolean canTransition(DelegateState from, DelegateEvent event) {
        return row(LIFECYCLE, from).containsKey(event);
    }

    public static boolean canDeliveryTransition(DeliveryState from, DelegateEvent event) {
        return row(DELIVERY, from).containsKey(event);
    }

    /** 某状态下合法的生命周期事件集（穷举测试用来数分母）。 */
    public static Set<DelegateEvent> legalEventsFrom(DelegateState from) {
        return Collections.unmodifiableSet(new LinkedHashSet<DelegateEvent>(row(LIFECYCLE, from).keySet()));
    }

    /** 某状态下合法的投递事件集。 */
    public static Set<DelegateEvent> legalDeliveryEventsFrom(DeliveryState from) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<DelegateEvent>(row(DELIVERY, from).keySet()));
    }

    private static <S extends Enum<S>> Map<DelegateEvent, S> row(
            Map<S, Map<DelegateEvent, S>> table, S from) {
        Map<DelegateEvent, S> r = table.get(from);
        return r == null ? Collections.<DelegateEvent, S>emptyMap() : r;
    }
}
