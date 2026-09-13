package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.ai.AiQuotaWalletMapper;
import com.heima.content.service.ai.AiWalletService;
import com.heima.model.ai.pojos.AiQuotaWallet;
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
}
