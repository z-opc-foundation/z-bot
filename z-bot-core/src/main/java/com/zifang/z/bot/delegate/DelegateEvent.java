package com.zifang.z.bot.delegate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 委托面事件枚举（对齐 hermes {@code tools/delegate_tool.py:624} 的
 * {@code class DelegateEvent(str, enum.Enum)}：她给每个成员一个点分线名，
 * 外部消费者（SSE / ACP / CLI）吃线名，内部吃枚举）。
 *
 * <p>z-bot 侧原来只有一把裸字符串旗子（{@code "QUEUED"/"RUNNING"/"DONE"/"FAILED"}），
 * 拼错不报错、也没有"这一条事件到底改不改状态"的说法。本枚举是那张旗子的封闭替代：
 * <ul>
 *   <li>{@link #wireName()} —— 落盘/外发用的稳定字符串，禁止再手写；</li>
 *   <li>{@link #fromWireName(String)} / {@link #fromLegacyStatus(String)} —— 入站归一化，
 *       <b>未知字面量一律 {@link IllegalArgumentException}</b>（不静默吞成 null）；</li>
 *   <li>哪些事件能动哪个状态，全在 {@link DelegateTransitions} 里，本枚举不藏迁移。</li>
 * </ul>
 *
 * <p>两个"只记账不迁移"的事件写明白，免得被当成死符号：
 * {@link #DISPATCH} 是建账事件（目录 + header 先落盘，此刻还没有状态可迁移），
 * {@link #LEDGER_PRUNED} 是回收事件（现场目录随即被删，行只出现在回收计数里）。</p>
 */
public enum DelegateEvent {

    /** 建账：父把任务交进来，现场目录先落盘（此刻无状态可迁移）。 */
    DISPATCH("delegate.dispatch", Role.LEDGER_ONLY),

    /** 子代理真的跑起来了。 */
    TASK_SPAWNED("delegate.task_spawned", Role.LIFECYCLE),

    /** 子代理进度心跳（自迁移，不改状态）。 */
    TASK_PROGRESS("delegate.task_progress", Role.LIFECYCLE),

    /** 子代理正常出结果。 */
    TASK_COMPLETED("delegate.task_completed", Role.LIFECYCLE),

    /** 子代理异常收场。 */
    TASK_FAILED("delegate.task_failed", Role.LIFECYCLE),

    /** 用户/父侧叫停（{@code /stop}）。 */
    TASK_STOPPED("delegate.task_stopped", Role.LIFECYCLE),

    /** 子预算没裁到（父引用未回填）：只记账，不改状态。 */
    BUDGET_UNCLAMPED("delegate.budget_unclamped", Role.LIFECYCLE),

    /** 认领孤儿现场：非终态且心跳超期 ⇒ UNKNOWN。 */
    ORPHAN_ADOPTED("delegate.orphan_adopted", Role.LIFECYCLE),

    /** 投递被某个消费者 claim 走（一次 claim 烧一次尝试数）。 */
    RESULT_CLAIMED("delegate.result_claimed", Role.DELIVERY),

    /** 消费者确认接住 —— 必须先有 claim 凭证。 */
    RESULT_DELIVERED("delegate.result_delivered", Role.DELIVERY),

    /** 一次没走完的投递被释放回队列（还没到上限时才允许，到顶就只能收敛成 DROPPED）。 */
    RESULT_RETRY("delegate.result_retry", Role.DELIVERY),

    /** 投递预算烧完或确认无对端 ⇒ 终态丢弃（结果仍可查，但账上不记成功）。 */
    RESULT_DROPPED("delegate.result_dropped", Role.DELIVERY),

    /** 台账回收（此刻无状态可迁移）。 */
    LEDGER_PRUNED("delegate.ledger_pruned", Role.LEDGER_ONLY);

    /** 事件归属于哪条轴。 */
    public enum Role {
        /** 生命周期轴。 */
        LIFECYCLE,
        /** 投递轴。 */
        DELIVERY,
        /** 只记账，两条轴都不动。 */
        LEDGER_ONLY
    }

    private final String wireName;
    private final Role role;

    DelegateEvent(String wireName, Role role) {
        this.wireName = wireName;
        this.role = role;
    }

    public String wireName() {
        return wireName;
    }

    public Role role() {
        return role;
    }

    /** 线名反查；未知 ⇒ 大声失败。 */
    public static DelegateEvent fromWireName(String name) {
        for (DelegateEvent e : values()) {
            if (e.wireName.equals(name)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知委托事件线名: " + name + "（合法集 " + wireNames() + "）");
    }

    /**
     * 把历史裸字符串状态归一化成事件（对齐她的 {@code _LEGACY_EVENT_MAP}）。
     * 未知字面量 ⇒ 抛，绝不回 null 让上层"当成没出错"。
     */
    public static DelegateEvent fromLegacyStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("QUEUED".equals(s)) {
            return DISPATCH;
        }
        if ("RUNNING".equals(s)) {
            return TASK_SPAWNED;
        }
        if ("DONE".equals(s) || "COMPLETED".equals(s)) {
            return TASK_COMPLETED;
        }
        if ("FAILED".equals(s) || "ERROR".equals(s)) {
            return TASK_FAILED;
        }
        if ("STOPPED".equals(s) || "CANCELLED".equals(s)) {
            return TASK_STOPPED;
        }
        if ("UNKNOWN".equals(s) || "ORPHANED".equals(s)) {
            return ORPHAN_ADOPTED;
        }
        throw new IllegalArgumentException("未知委托状态字面量: " + raw
                + "（合法集 [QUEUED, RUNNING, DONE, FAILED, STOPPED, UNKNOWN]）");
    }

    /** 全量线名（复算/断言用，顺序即枚举声明序）。 */
    public static Set<String> wireNames() {
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        for (DelegateEvent e : values()) {
            seen.put(e.wireName, Boolean.TRUE);
        }
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(seen.keySet()));
    }
}
