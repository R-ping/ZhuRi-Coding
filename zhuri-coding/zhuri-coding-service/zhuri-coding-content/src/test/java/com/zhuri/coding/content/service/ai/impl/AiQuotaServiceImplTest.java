package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.ai.AiWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * AI 每日免费配额（**token 单一口径**）单测。
 *
 * <p>覆盖：准入判定（免费 tokens 未用尽 / 钱包兜底 / 双尽拒绝）、Redis 异常 fail-open、
 * 以及「准入不做预扣」这一设计决定（防回归：不得再出现按次计数）、流式额度快照 availableTokens。
 *
 * <p>原「次数」维度的用例已随该维度下线一并移除——频次防刷由分层限流承担，不再由配额承担。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 每日免费配额（token 单一口径）")
class AiQuotaServiceImplTest {

    private static final int UID = 7;

    @Mock
    private CacheService cacheService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private AiWalletService walletService;

    @InjectMocks
    private AiQuotaServiceImpl service;

    @BeforeEach
    void templateAvailable() {
        // 装配 CacheService → StringRedisTemplate → ValueOperations 调用链
        lenient().when(cacheService.getstringRedisTemplate()).thenReturn(stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 模拟"今日已用免费 tokens"（<= 20000 视为免费额度内） */
    private void stubUsedTokens(String used) {
        when(valueOps.get(anyString())).thenReturn(used);
    }

    // ==================== tryConsume：token 准入 ====================

    @Test
    @DisplayName("userId 为空 → 不放行")
    void tryConsumeNullUser() {
        assertFalse(service.tryConsume(null));
        verify(walletService, never()).tokenBalanceOf(any());
    }

    @Test
    @DisplayName("免费 tokens 未用尽 → 放行，且不触碰钱包")
    void tryConsumeWithinFreeTokens() {
        stubUsedTokens("100");

        assertTrue(service.tryConsume(UID));
        verify(walletService, never()).tokenBalanceOf(any());
    }

    @Test
    @DisplayName("免费 tokens 已用尽但钱包有余额 → 放行")
    void tryConsumeFallsBackToWallet() {
        stubUsedTokens("99999");
        when(walletService.tokenBalanceOf(UID)).thenReturn(500L);

        assertTrue(service.tryConsume(UID));
    }

    @Test
    @DisplayName("免费 tokens 与钱包均用尽 → 拒绝")
    void tryConsumeExhaustedAll() {
        stubUsedTokens("99999");
        when(walletService.tokenBalanceOf(UID)).thenReturn(0L);

        assertFalse(service.tryConsume(UID));
    }

    @Test
    @DisplayName("Redis 异常 fail-open 放行（不阻断问答主链路）")
    void tryConsumeRedisFailOpen() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.tryConsume(UID));
    }

    @Test
    @DisplayName("准入不做预扣：放行路径不写任何计数（只读判定）")
    void tryConsumeDoesNotReserve() {
        stubUsedTokens("100");

        assertTrue(service.tryConsume(UID));

        // 关键：准入阶段不得有任何 INCRBY/EXPIRE（Lua 脚本）写入——
        // 这是"去掉预扣"的设计决定，避免"预扣 + 跨存储找零"的复杂度
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    // ==================== availableTokens（流式「到线即停」的额度快照）====================

    @Test
    @DisplayName("availableTokens：今日免费剩余 + 钱包 token 余额")
    void availableTokensSumsFreeAndWallet() {
        // 今日已用 5000 → 免费剩余 20000-5000=15000；钱包 3000 → 合计 18000
        stubUsedTokens("5000");
        when(walletService.tokenBalanceOf(UID)).thenReturn(3000L);

        assertEquals(18000L, service.availableTokens(UID));
    }

    @Test
    @DisplayName("availableTokens：免费已超用且钱包为 0 → 0（而非负数）")
    void availableTokensNeverNegative() {
        stubUsedTokens("99999");
        when(walletService.tokenBalanceOf(UID)).thenReturn(0L);

        assertEquals(0L, service.availableTokens(UID));
    }

    @Test
    @DisplayName("availableTokens：无用户上下文 → 不限（Long.MAX_VALUE）")
    void availableTokensAnonymousUnlimited() {
        assertEquals(Long.MAX_VALUE, service.availableTokens(null));
    }

    @Test
    @DisplayName("availableTokens：查询异常 fail-open → 不限，不阻断生成")
    void availableTokensFailOpen() {
        // 注意：tokensUsedToday 内部已 fail-open（异常即返回 0），因此要让「钱包侧」抛异常
        // 才能真正走到 availableTokens 自己的兜底分支
        when(walletService.tokenBalanceOf(UID)).thenThrow(new RuntimeException("db down"));

        assertEquals(Long.MAX_VALUE, service.availableTokens(UID));
    }

    // ==================== 配置口径 ====================

    @Test
    @DisplayName("每日额度：token 上限从配置读取（次数维度已下线）")
    void dailyTokenLimitFromConfig() {
        assertEquals(AiQuotaService.DEFAULT_DAILY_TOKEN_QUOTA, service.dailyTokenLimit());
    }
}
