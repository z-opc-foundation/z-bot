package com.zifang.z.bot.delegate;

import java.util.Locale;

/**
 * 委托生命周期状态（对齐 hermes {@code async_delegations.state} 列：
 * {@code running} → 终态，孤儿被判成 {@code unknown}，见 {@code async_delegation.py:262}）。
 *
 * <p>z-bot 侧原来把"子代理跑完了"和"结果有人接住了"塞进同一根字符串旗子，
 * 于是 {@code DONE} 其实只说明子代理自己收工了 —— 这就是 P16 抓过的
 * "投给拉模式控制台却被记成 ok" 同型洞。本枚举只管<b>生命周期轴</b>，
 * 投递轴在 {@link DeliveryState}，两轴各自有迁移表（{@link DelegateTransitions}）。</p>
 */
public enum DelegateState {

    /** 已建账，还没进线程池。 */
    QUEUED,

    /** 子代理在飞。 */
    RUNNING,

    /** 子代理正常出结果（<b>不代表</b>有人接住了结果）。 */
    DONE,

    /** 子代理抛异常。 */
    FAILED,

    /** 被 {@code /stop} 叫停。 */
    STOPPED,

    /** 委托方进程死了、现场留在盘上且没人能再推进它。 */
    UNKNOWN;

    /** 终态：不会再被任何生命周期事件推进，也只有终态现场允许被回收。 */
    public boolean terminal() {
        return this == DONE || this == FAILED || this == STOPPED || this == UNKNOWN;
    }

    /** 历史裸字符串（{@code AsyncDelegation.status}）→ 枚举；未知 ⇒ 抛。 */
    public static DelegateState fromLegacy(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("QUEUED".equals(s)) {
            return QUEUED;
        }
        if ("RUNNING".equals(s)) {
            return RUNNING;
        }
        if ("DONE".equals(s) || "COMPLETED".equals(s)) {
            return DONE;
        }
        if ("FAILED".equals(s) || "ERROR".equals(s)) {
            return FAILED;
        }
        if ("STOPPED".equals(s) || "CANCELLED".equals(s)) {
            return STOPPED;
        }
        if ("UNKNOWN".equals(s) || "ORPHANED".equals(s)) {
            return UNKNOWN;
        }
        throw new IllegalArgumentException("未知委托状态字面量: " + raw);
    }
}
