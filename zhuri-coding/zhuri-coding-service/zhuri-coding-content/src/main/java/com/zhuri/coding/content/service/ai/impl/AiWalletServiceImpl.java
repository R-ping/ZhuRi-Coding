package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.mapper.ai.AiQuotaWalletMapper;
import com.zhuri.coding.content.service.ai.AiWalletService;
import com.zhuri.coding.model.ai.pojos.AiQuotaWallet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * AI 额度钱包实现：DB 为真源，原子扣减（update ... where balance>0）保证不超扣
 */
@Slf4j
@Service
public class AiWalletServiceImpl implements AiWalletService {

    @Autowired
    private AiQuotaWalletMapper walletMapper;

    @Override
    public int balanceOf(Integer userId) {
        if (userId == null) {
            return 0;
        }
        AiQuotaWallet w = walletMapper.selectById(userId);
        return w == null || w.getBalance() == null ? 0 : w.getBalance();
    }

    @Override
    public void grant(Integer userId, int add) {
        if (userId == null || add <= 0) {
            return;
        }
        AiQuotaWallet w = walletMapper.selectById(userId);
        Date now = new Date();
        if (w == null) {
            AiQuotaWallet nw = new AiQuotaWallet();
            nw.setUserId(userId);
            nw.setBalance(add);
            nw.setUpdateTime(now);
            walletMapper.insert(nw);
            log.info("[AiWallet] 新建钱包入账, userId={}, +{}", userId, add);
            return;
        }
        int newBalance = w.getBalance() != null ? w.getBalance() + add : add;
        w.setBalance(newBalance);
        w.setUpdateTime(now);
        walletMapper.updateById(w);
        log.info("[AiWallet] 钱包入账, userId={}, +{} -> {}", userId, add, newBalance);
    }

    @Override
    public boolean deductOne(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            int rows = walletMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiQuotaWallet>()
                    .eq(AiQuotaWallet::getUserId, userId)
                    .gt(AiQuotaWallet::getBalance, 0)
                    .setSql("balance = balance - 1"));
            return rows > 0;
        } catch (Exception e) {
            log.warn("[AiWallet] 扣减异常, userId={}", userId, e);
            return false;
        }
    }

    // ==================== token 维度（新计费口径） ====================

    @Override
    public long tokenBalanceOf(Integer userId) {
        if (userId == null) {
            return 0L;
        }
        AiQuotaWallet w = walletMapper.selectById(userId);
        return w == null || w.getTokenBalance() == null ? 0L : w.getTokenBalance();
    }

    @Override
    public void grantTokens(Integer userId, long add) {
        if (userId == null || add <= 0) {
            return;
        }
        // 原子累加（避免读改写丢失）；行不存在则先建行再累加
        int rows = walletMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiQuotaWallet>()
                .eq(AiQuotaWallet::getUserId, userId)
                .setSql("token_balance = token_balance + " + add + ", update_time = NOW()"));
        if (rows > 0) {
            log.info("[AiWallet] token 入账, userId={}, +{} -> {}", userId, add, tokenBalanceOf(userId));
            return;
        }
        AiQuotaWallet nw = new AiQuotaWallet();
        nw.setUserId(userId);
        nw.setBalance(0);
        nw.setTokenBalance(add);
        nw.setUpdateTime(new Date());
        try {
            walletMapper.insert(nw);
            log.info("[AiWallet] 新建钱包并 token 入账, userId={}, +{}", userId, add);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发首建：另一线程已插入，改为原子累加
            walletMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiQuotaWallet>()
                    .eq(AiQuotaWallet::getUserId, userId)
                    .setSql("token_balance = token_balance + " + add + ", update_time = NOW()"));
            log.info("[AiWallet] 并发首建冲突，已改为原子累加, userId={}, +{}", userId, add);
        }
    }

    @Override
    public long deductTokens(Integer userId, long want) {
        if (userId == null || want <= 0) {
            return 0L;
        }
        try {
            // 1. 优先原子扣满
            int rows = walletMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiQuotaWallet>()
                    .eq(AiQuotaWallet::getUserId, userId)
                    .ge(AiQuotaWallet::getTokenBalance, want)
                    .setSql("token_balance = token_balance - " + want + ", update_time = NOW()"));
            if (rows > 0) {
                return want;
            }
            // 2. 余额不足：扣光剩余（清零），返回实际扣减量，便于调用方区分"欠费"
            AiQuotaWallet w = walletMapper.selectById(userId);
            Long remain = w == null ? null : w.getTokenBalance();
            if (remain == null || remain <= 0) {
                return 0L;
            }
            int cleared = walletMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiQuotaWallet>()
                    .eq(AiQuotaWallet::getUserId, userId)
                    .eq(AiQuotaWallet::getTokenBalance, remain)
                    .setSql("token_balance = 0, update_time = NOW()"));
            return cleared > 0 ? remain : 0L;
        } catch (Exception e) {
            log.warn("[AiWallet] token 扣减异常, userId={}, want={}", userId, want, e);
            return 0L;
        }
    }
}
