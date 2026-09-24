package com.zhuri.coding.content.service.ai;

/**
 * AI 额度钱包（**token 单一口径**）
 *
 * <p>原「次数」维度（{@code balanceOf} / {@code grant(int)} / {@code deductOne}）已随该维度下线一并移除：
 * 频次防刷由分层限流承担，成本只能由 token 表达。对应的 {@code ap_ai_wallet.balance} 列<b>保留不删</b>，
 * 以便本次改动可回滚（当前代码已不再读写它）。
 */
public interface AiWalletService {

    // ==================== token 维度 ====================

    /** token 余额查询（无记录返回 0） */
    long tokenBalanceOf(Integer userId);

    /** token 入账（用户不存在则建行；正数累加） */
    void grantTokens(Integer userId, long add);

    /**
     * 原子扣减 token（条件更新 {@code WHERE token_balance >= n}，防并发超扣）。
     *
     * @return 实际扣减数：余额充足时为 {@code want}；不足时扣掉剩余全部（把余额清零）并返回实际值，
     *         便于调用方区分"扣满"与"扣光"
     */
    long deductTokens(Integer userId, long want);
}
