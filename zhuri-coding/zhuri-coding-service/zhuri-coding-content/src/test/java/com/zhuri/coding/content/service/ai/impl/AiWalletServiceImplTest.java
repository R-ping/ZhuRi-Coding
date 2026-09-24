package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.mapper.ai.AiQuotaWalletMapper;
import com.zhuri.coding.model.ai.pojos.AiQuotaWallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * AI 额度钱包单测（**token 单一口径**）。
 *
 * <p>原「次数」维度的用例已随该维度下线一并移除，本类改为覆盖 token 三件事：
 * 余额查询兜底、入账（含并发首建）、**原子扣减**。
 *
 * <p>其中原子扣减是本工程"不做预扣"的基础：{@code update ... where token_balance >= n}
 * 由数据库保证不超扣，因此准入阶段无需再预扣一笔来防并发——本类把该行为固化为契约。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 额度钱包（token 单一口径）")
class AiWalletServiceImplTest {

    @Mock
    private AiQuotaWalletMapper walletMapper;

    @InjectMocks
    private AiWalletServiceImpl service;

    private AiQuotaWallet wallet(Long tokenBalance) {
        AiQuotaWallet w = new AiQuotaWallet();
        w.setUserId(7);
        w.setTokenBalance(tokenBalance);
        return w;
    }

    // ==================== tokenBalanceOf ====================

    @Test
    @DisplayName("tokenBalanceOf：userId 为空 → 0")
    void tokenBalanceNullUser() {
        assertEquals(0L, service.tokenBalanceOf(null));
    }

    @Test
    @DisplayName("tokenBalanceOf：无钱包行 / 余额为空 → 兜底 0")
    void tokenBalanceMissingRowOrNull() {
        when(walletMapper.selectById(7)).thenReturn(null);
        assertEquals(0L, service.tokenBalanceOf(7));

        when(walletMapper.selectById(7)).thenReturn(wallet(null));
        assertEquals(0L, service.tokenBalanceOf(7));
    }

    @Test
    @DisplayName("tokenBalanceOf：返回钱包 token 余额")
    void tokenBalanceReturnsValue() {
        when(walletMapper.selectById(7)).thenReturn(wallet(1500L));
        assertEquals(1500L, service.tokenBalanceOf(7));
    }

    // ==================== grantTokens ====================

    @Test
    @DisplayName("grantTokens：非法入参（userId 空 / add<=0）忽略，不碰库")
    void grantTokensIgnoresInvalidArgs() {
        service.grantTokens(null, 100L);
        service.grantTokens(7, 0L);
        service.grantTokens(7, -5L);

        verify(walletMapper, never()).update(any(), any());
        verify(walletMapper, never()).insert(any(AiQuotaWallet.class));
    }

    @Test
    @DisplayName("grantTokens：已有行 → 原子累加（不先查后写）")
    void grantTokensAccumulates() {
        when(walletMapper.update(any(), any())).thenReturn(1);

        service.grantTokens(7, 2000L);

        verify(walletMapper, never()).insert(any(AiQuotaWallet.class));
        verify(walletMapper).update(any(), any());
    }

    @Test
    @DisplayName("grantTokens：无行 → 新建钱包并写入 token 余额")
    void grantTokensInsertsWhenAbsent() {
        when(walletMapper.update(any(), any())).thenReturn(0);
        when(walletMapper.insert(any(AiQuotaWallet.class))).thenReturn(1);

        service.grantTokens(7, 2000L);

        ArgumentCaptor<AiQuotaWallet> captor = ArgumentCaptor.forClass(AiQuotaWallet.class);
        verify(walletMapper).insert(captor.capture());
        AiQuotaWallet inserted = captor.getValue();
        assertEquals(7, inserted.getUserId());
        assertEquals(2000L, inserted.getTokenBalance());
        assertNotNull(inserted.getUpdateTime());
    }

    @Test
    @DisplayName("grantTokens：并发首建冲突 → 捕获 DuplicateKeyException 后改为累加（不丢额度）")
    void grantTokensHandlesConcurrentInsert() {
        when(walletMapper.update(any(), any())).thenReturn(0, 1);
        when(walletMapper.insert(any(AiQuotaWallet.class)))
                .thenThrow(new DuplicateKeyException("uk_user_id"));

        service.grantTokens(7, 2000L);

        // 第一次 update 未命中 → insert 冲突 → 再次 update 累加，共 2 次
        verify(walletMapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    // ==================== deductTokens（原子扣减：不超扣的地基）====================

    @Test
    @DisplayName("deductTokens：非法入参 → 0，不碰库")
    void deductTokensInvalidArgs() {
        assertEquals(0L, service.deductTokens(null, 100L));
        assertEquals(0L, service.deductTokens(7, 0L));

        verify(walletMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("deductTokens：余额充足 → 原子扣满并返回 want")
    void deductTokensSucceedsWhenEnough() {
        when(walletMapper.update(any(), any())).thenReturn(1);

        assertEquals(500L, service.deductTokens(7, 500L));
    }

    @Test
    @DisplayName("deductTokens：余额不足 → 扣光剩余并返回实际扣减量（便于调用方区分欠费）")
    void deductTokensClearsRemainderWhenInsufficient() {
        when(walletMapper.update(any(), any())).thenReturn(0, 1);
        when(walletMapper.selectById(7)).thenReturn(wallet(120L));

        assertEquals(120L, service.deductTokens(7, 500L));
    }

    @Test
    @DisplayName("deductTokens：余额为 0 → 返回 0（不产生负数）")
    void deductTokensZeroBalance() {
        when(walletMapper.update(any(), any())).thenReturn(0);
        when(walletMapper.selectById(7)).thenReturn(wallet(0L));

        assertEquals(0L, service.deductTokens(7, 500L));
    }

    @Test
    @DisplayName("deductTokens：并发下未清成功 → 返回 0，绝不超扣")
    void deductTokensConcurrentClearFailsSafely() {
        // 第一步条件扣减未命中、第二步"按剩余值清零"也被并发改掉（影响 0 行）
        when(walletMapper.update(any(), any())).thenReturn(0);
        when(walletMapper.selectById(7)).thenReturn(wallet(120L));

        assertEquals(0L, service.deductTokens(7, 500L));
    }

    @Test
    @DisplayName("deductTokens：异常 fail-open 返回 0")
    void deductTokensFailsOpenOnException() {
        when(walletMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));

        assertEquals(0L, service.deductTokens(7, 500L));
    }

    @Test
    @DisplayName("deductTokens：无钱包行 → 返回 0")
    void deductTokensNoWalletRow() {
        when(walletMapper.update(any(), any())).thenReturn(0);
        when(walletMapper.selectById(7)).thenReturn(null);

        assertEquals(0L, service.deductTokens(7, 500L));
    }
}
