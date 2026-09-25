package com.zifang.z.bot.agent;

import com.zifang.z.agent.kernel.agent.IterationBudget;

/**
 * 预算账本（P12）：kernel {@link IterationBudget} 之上补「压缩后退还」这一笔。
 *
 * <p>kernel 的口径是<b>累计</b> tokens：每次响应回传的 promptTokens 一律加到 {@code tokensUsed()} 上，
 * 到 {@code maxTokens()} 就判预算耗尽。问题是上下文被压缩之后，那些 token 其实<b>已经不在
 * 发给模型的请求里</b>了 —— 继续按累计记账，压缩就白做：省下来的空间一秒都没还回来。
 * hermes 的 {@code iteration_budget.py} 正是为此在压缩后做 {@code refund}。</p>
 *
 * <p>本类只做一件事：把「退还量」记在 z-bot 侧，并用它折算出净占用
 * （{@code effectiveTokensUsed = tokensUsed - refunded}）来判还能不能再调一次。
 * 迭代次数上限和 grace 通道的判定仍然交回 kernel，不在这里另立一套。</p>
 */
public final class BudgetLedger {

    private final IterationBudget budget;
    /** 已累计退还的 token 数；永远被夹到 [0, tokensUsed] 之内，所以净占用不会为负。 */
    private long refundedTokens;

    public BudgetLedger(IterationBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("budget 不能为 null");
        }
        this.budget = budget;
    }

    /** 被包装的 kernel 预算（{@code /usage} 之类只读展示用）。 */
    public IterationBudget budget() {
        return budget;
    }

    public void onCall() {
        budget.onCall();
    }

    public void recordTokens(long promptTokens, long completionTokens) {
        budget.recordTokens(promptTokens, completionTokens);
        clampRefund();
    }

    /**
     * 压缩之后把省下来的 token 退还给预算。
     *
     * @param tokens 估算的净节省（&le; 0 时什么都不做）
     * @return 实际入账的退还量（可能被夹到当前累计 token 以内）
     */
    public synchronized long refundTokens(long tokens) {
        if (tokens <= 0) {
            return 0L;
        }
        long room = budget.tokensUsed() - refundedTokens;
        if (room <= 0) {
            return 0L;
        }
        long applied = Math.min(tokens, room);
        refundedTokens += applied;
        return applied;
    }

    /**
     * 还能不能再打一次 LLM：净占用没超上限就允许（哪怕累计值已经超），
     * 否则交回 kernel —— 它的 grace 通道仍然只在次数与 token 都判死时给一次收尾机会。
     */
    public boolean canCall() {
        if (budget.apiCalls() < budget.maxIterations() && effectiveTokensUsed() < budget.maxTokens()) {
            return true;
        }
        return budget.canCall();
    }

    /** 已退还的 token 总数（账面值，用于 /usage 展示与单测对账）。 */
    public synchronized long refundedTokens() {
        return refundedTokens;
    }

    /** 折算退还之后的净占用；不会为负。 */
    public synchronized long effectiveTokensUsed() {
        long net = budget.tokensUsed() - refundedTokens;
        return net < 0 ? 0L : net;
    }

    public long tokensUsed() {
        return budget.tokensUsed();
    }

    public int apiCalls() {
        return budget.apiCalls();
    }

    public int maxIterations() {
        return budget.maxIterations();
    }

    public long maxTokens() {
        return budget.maxTokens();
    }

    private synchronized void clampRefund() {
        long room = budget.tokensUsed();
        if (refundedTokens > room) {
            refundedTokens = Math.max(0L, room);
        }
    }
}
