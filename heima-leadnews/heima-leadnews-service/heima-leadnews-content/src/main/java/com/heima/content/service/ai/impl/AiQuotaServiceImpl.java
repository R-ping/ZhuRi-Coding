package com.heima.content.service.ai.impl;

import com.heima.common.redis.CacheService;
import com.heima.content.service.ai.AiQuotaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * AI 每日免费配额实现（Redis 计数，key 按自然日过期）
 */
@Slf4j
@Service
public class AiQuotaServiceImpl implements AiQuotaService {

    private static final String QUOTA_KEY_PREFIX = "ai:quota:daily:";

    @Autowired
    private CacheService cacheService;

    @Autowired
    private com.heima.content.service.ai.AiWalletService walletService;

    private String keyOf(Integer userId) {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return QUOTA_KEY_PREFIX + userId + ":" + day;
    }

    private StringRedisTemplate redis() {
        return cacheService.getstringRedisTemplate();
    }

    @Override
    public boolean tryConsume(Integer userId) {
        if (userId == null) {
            // 防御：接口层已要求登录；未登录不放行也不计（由调用方决定）
            return false;
        }
        // 免费额度优先（产品决策）：每日免费次数用尽后才扣减钱包（购买的额度包）
        try {
            String key = keyOf(userId);
            Long count = redis().opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 首次计数：设置当日剩余秒数过期，次日自动重置
                long seconds = secondsUntilEndOfDay();
                redis().expire(key, seconds, TimeUnit.SECONDS);
            }
            if (count != null && count <= DAILY_QUOTA) {
                return true; // 仍处于今日免费额度内，放行
            }
            if (count != null) {
                // 免费已用尽：回补一次计数，保证 usedToday 展示不虚高（封顶 DAILY_QUOTA）
                redis().opsForValue().decrement(key);
            }
        } catch (Exception e) {
            // Redis 异常 fail-open：不因配额故障阻断 AI 主链路
            log.warn("[AiQuota] 配额计数异常，放行, userId={}", userId, e);
            return true;
        }
        // 免费额度用尽 → 扣减钱包余额（余额不足返回 false）
        return walletService.deductOne(userId);
    }

    @Override
    public long usedToday(Integer userId) {
        if (userId == null) {
            return 0;
        }
        try {
            String v = redis().opsForValue().get(keyOf(userId));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long remainToday(Integer userId) {
        return Math.max(0, DAILY_QUOTA - usedToday(userId));
    }

    private long secondsUntilEndOfDay() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate tomorrow = now.plusDays(1);
        long nowEpoch = now.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        long tomorrowEpoch = tomorrow.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        return tomorrowEpoch - nowEpoch + 5;
    }
}
