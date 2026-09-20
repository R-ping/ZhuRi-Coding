package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.ai.AiWalletService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * AI 每日免费配额（Redis 计数）单测。
 *
 * <p>覆盖核心产品决策「免费额度优先」：每日免费 20 次内放行；用尽后回补计数防虚高，
 * 转由钱包兜底扣减；钱包不足返回 false；Redis 异常 fail-open 放行不阻断问答主链路；
 * usedToday/remainToday 的读取与异常兜底。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 每日免费配额（AiQuotaService）")
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

    // ==================== tryConsume：免费额度优先 ====================

    @Test
    @DisplayName("userId 为空不放行")
    void tryConsumeNullUser() {
        assertFalse(service.tryConsume(null));
        verify(walletService, never()).deductOne(any());
    }

    @Test
    @DisplayName("首次计数：设置当日过期并放行（免费内）")
    void tryConsumeFirstCountExpiresKey() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertTrue(service.tryConsume(UID));

        verify(valueOps).increment(anyString());
        verify(stringRedisTemplate).expire(anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(walletService, never()).deductOne(any());
    }

    @Test
    @DisplayName("当日免费额度内直接放行（不触碰钱包）")
    void tryConsumeWithinFreeQuota() {
        when(valueOps.increment(anyString())).thenReturn(19L);

        assertTrue(service.tryConsume(UID));

        verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(walletService, never()).deductOne(any());
    }

    @Test
    @DisplayName("免费用尽：回补计数防虚高，转钱包扣减成功放行")
    void tryConsumeFreeExhaustedFallsBackToWallet() {
        when(valueOps.increment(anyString())).thenReturn(21L);
        when(valueOps.decrement(anyString())).thenReturn(20L);
        when(walletService.deductOne(UID)).thenReturn(true);

        assertTrue(service.tryConsume(UID));

        verify(valueOps).decrement(anyString()); // 计数封顶 DAILY_QUOTA，不虚高
        verify(walletService).deductOne(UID);
    }

    @Test
    @DisplayName("免费与钱包均用尽 → 不放行")
    void tryConsumeExhaustedAll() {
        when(valueOps.increment(anyString())).thenReturn(21L);
        when(walletService.deductOne(UID)).thenReturn(false);

        assertFalse(service.tryConsume(UID));
        verify(walletService).deductOne(UID);
    }

    @Test
    @DisplayName("Redis 计数异常 fail-open 放行（不阻断问答主链路）")
    void tryConsumeRedisFailOpen() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.tryConsume(UID));
        verify(walletService, never()).deductOne(any());
    }

    // ==================== usedToday / remainToday ====================

    @Test
    @DisplayName("usedToday：无记录返回 0")
    void usedTodayNoRecord() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertEquals(0, service.usedToday(UID));
    }

    @Test
    @DisplayName("usedToday：读取计数")
    void usedTodayParses() {
        when(valueOps.get(anyString())).thenReturn("5");
        assertEquals(5, service.usedToday(UID));
    }

    @Test
    @DisplayName("usedToday：异常或非数字兜底 0")
    void usedTodayExceptionOrBadValue() {
        when(valueOps.get(anyString()))
            .thenThrow(new RuntimeException("redis down"))
            .thenReturn("abc");
        assertEquals(0, service.usedToday(UID)); // Redis 异常 → 0
        assertEquals(0, service.usedToday(UID)); // 非数字 → 0

        assertEquals(0, service.usedToday(null));
    }

    @Test
    @DisplayName("remainToday：剩余次数不为负")
    void remainTodayClampsAtZero() {
        when(valueOps.get(anyString())).thenReturn("5");
        // 生效额度取配置（默认 20 次），不再依赖接口常量
        assertEquals(service.dailyRequestLimit() - 5, service.remainToday(UID));

        when(valueOps.get(anyString())).thenReturn("99");
        assertEquals(0, service.remainToday(UID));
    }

    @Test
    @DisplayName("每日额度可配置：默认值与 tokens 上限均从配置读取")
    void dailyLimitsFromConfig() {
        assertEquals(AiQuotaService.DEFAULT_DAILY_QUOTA, service.dailyRequestLimit());
        assertEquals(AiQuotaService.DEFAULT_DAILY_TOKEN_QUOTA, service.dailyTokenLimit());
    }
}