package com.heima.content.service.ai;

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
}
