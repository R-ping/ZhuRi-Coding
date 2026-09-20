package com.zhuri.coding.content.service.ai;

/**
 * AI 额度钱包（免费额度优先兜底：每日免费额度用尽后扣减钱包余额）
 */
public interface AiWalletService {

    /** 余额查询（无记录返回 0） */
    int balanceOf(Integer userId);

    /** 入账（用户不存在则建行；正数累加） */
    void grant(Integer userId, int add);

    /** 原子扣 1 次：余额不足返回 false */
    boolean deductOne(Integer userId);

    // ==================== token 维度（新计费口径） ====================

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
