package com.heima.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.mapper.ai.AiQuotaWalletMapper;
import com.heima.model.ai.pojos.AiQuotaWallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AI 额度钱包单测。
 *
 * <p>覆盖：余额查询（空行/空余额兜底为 0）、入账（无行建行 / 有行累加 / 非法参数忽略）、
 * 原子扣 1（`update ... where balance>0` 返回行数决定成败，异常 fail-open 返回 false 不超扣）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 额度钱包（AiWalletService）")
class AiWalletServiceImplTest {

    @Mock
    private AiQuotaWalletMapper walletMapper;

    @InjectMocks
    private AiWalletServiceImpl service;

    private AiQuotaWallet wallet(int balance) {
        AiQuotaWallet w = new AiQuotaWallet();
        w.setUserId(7);
        w.setBalance(balance);
        return w;
    }

    // ==================== 余额查询 ====================

    @Test
    @DisplayName("userId 为空返回 0")
    void balanceOfNullUser() {
        assertEquals(0, service.balanceOf(null));
        verify(walletMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("无钱包行或余额为空兜底返回 0")
    void balanceOfMissingRowOrNull() {
        when(walletMapper.selectById(7)).thenReturn(null);
        assertEquals(0, service.balanceOf(7));

        AiQuotaWallet w = new AiQuotaWallet();
        w.setUserId(7);
        when(walletMapper.selectById(7)).thenReturn(w);
        assertEquals(0, service.balanceOf(7));
    }

    @Test
    @DisplayName("返回钱包余额")
    void balanceOfReturnsBalance() {
        when(walletMapper.selectById(7)).thenReturn(wallet(30));
        assertEquals(30, service.balanceOf(7));
    }

    // ==================== 入账 ====================

    @Test
    @DisplayName("非法入参（userId 空 / add<=0）忽略")
    void grantInvalidArgs() {
        service.grant(null, 10);
        service.grant(7, 0);
        service.grant(7, -1);
        verify(walletMapper, never()).insert(any(AiQuotaWallet.class));
        verify(walletMapper, never()).updateById(any(AiQuotaWallet.class));
    }

    @Test
    @DisplayName("无钱包行则新建并写入 balance=add")
    void grantCreatesRow() {
        when(walletMapper.selectById(7)).thenReturn(null);

        service.grant(7, 50);

        verify(walletMapper).insert(any(AiQuotaWallet.class));
        verify(walletMapper, never()).updateById(any(AiQuotaWallet.class));
    }

    @Test
    @DisplayName("已有行则累加余额并更新")
    void grantAccumulates() {
        when(walletMapper.selectById(7)).thenReturn(wallet(10));

        service.grant(7, 5);

        verify(walletMapper, never()).insert(any(AiQuotaWallet.class));
        verify(walletMapper).updateById(any(AiQuotaWallet.class));
    }

    @Test
    @DisplayName("余额为空视作 0 累加")
    void grantWithNullBalance() {
        AiQuotaWallet w = new AiQuotaWallet();
        w.setUserId(7);
        when(walletMapper.selectById(7)).thenReturn(w);

        service.grant(7, 3);

        verify(walletMapper).updateById(any(AiQuotaWallet.class));
    }

    // ==================== 原子扣减 ====================

    @Test
    @DisplayName("userId 为空扣减失败")
    void deductOneNullUser() {
        assertFalse(service.deductOne(null));
        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("余额足够时扣减成功（update 返回 1 行）")
    void deductOneSuccess() {
        when(walletMapper.update(any(), any())).thenReturn(1);
        assertTrue(service.deductOne(7));
    }

    @Test
    @DisplayName("余额不足时 update 影响 0 行 → 扣减失败不超扣")
    void deductOneInsufficient() {
        when(walletMapper.update(any(), any())).thenReturn(0);
        assertFalse(service.deductOne(7));
    }

    @Test
    @DisplayName("扣减异常 fail-open 返回 false")
    void deductOneExceptionFailsOpen() {
        when(walletMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));
        assertFalse(service.deductOne(7));
    }
}