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
    /** 今日免费已用 tokens（与次数分离计数：次数防刷，tokens 控成本） */
    private static final String TOKEN_KEY_PREFIX = "ai:quota:tokens:";

    /** 每日免费次数（配置可调；默认 20） */
    @org.springframework.beans.factory.annotation.Value("${ai-quota.daily-requests:20}")
    private int dailyRequestLimit = DEFAULT_DAILY_QUOTA;

    /** 每日免费 tokens（配置可调；默认 2 万，按主模型折算约 0.4 元/用户/天） */
    @org.springframework.beans.factory.annotation.Value("${ai-quota.daily-tokens:20000}")
    private long dailyTokenLimit = DEFAULT_DAILY_TOKEN_QUOTA;

    @Override
    public int dailyRequestLimit() {
        return dailyRequestLimit;
    }

    @Override
    public long dailyTokenLimit() {
        return dailyTokenLimit;
    }

    @Autowired
    private CacheService cacheService;

    @Autowired
    private com.heima.content.service.ai.AiWalletService walletService;

    private String keyOf(Integer userId) {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return QUOTA_KEY_PREFIX + userId + ":" + day;
    }

    private String tokenKeyOf(Integer userId) {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return TOKEN_KEY_PREFIX + userId + ":" + day;
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
        // 先做 token 维度预检（不消耗计数）：tokens 已用尽即拒，避免白扣一次次数
        if (!precheckTokens(userId)) {
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
            if (count != null && count <= dailyRequestLimit) {
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

    // ==================== token 维度（新计费口径） ====================

    @Override
    public boolean precheckTokens(Integer userId) {
        if (userId == null) {
            return false;
        }
        try {
            long used = tokensUsedToday(userId);
            if (used < dailyTokenLimit) {
                return true; // 仍在今日免费 tokens 内
            }
            // 免费 tokens 用尽 → 需要钱包有 token 余额
            return walletService.tokenBalanceOf(userId) > 0;
        } catch (Exception e) {
            log.warn("[AiQuota] token 预检异常，放行, userId={}", userId, e);
            return true;
        }
    }

    @Override
    public void settleTokens(Integer userId, long tokens) {
        if (userId == null || tokens <= 0) {
            return;
        }
        try {
            String key = tokenKeyOf(userId);
            Long usedAfter = redis().opsForValue().increment(key, tokens);
            if (usedAfter != null && usedAfter == tokens) {
                redis().expire(key, secondsUntilEndOfDay(), TimeUnit.SECONDS);
            }
            // 免费额度内部分无需扣钱包；超出部分（含本次跨过阈值的量）扣 token 钱包
            long usedBefore = usedAfter == null ? 0L : usedAfter - tokens;
            long freeForThisCall = Math.max(0L, Math.min(tokens, dailyTokenLimit - usedBefore));
            long over = tokens - freeForThisCall;
            if (over > 0) {
                long deducted = walletService.deductTokens(userId, over);
                if (deducted < over) {
                    // 欠费：token 钱包已清零，本次超出量未完全收回（fail-open 放行已完成回答）
                    log.warn("[AiQuota] token 钱包余额不足，本次超出未全部扣回, userId={}, over={}, deducted={}",
                        userId, over, deducted);
                }
            }
        } catch (Exception e) {
            // 结算失败不影响已完成回答的返回；下次用量继续累计
            log.warn("[AiQuota] token 结算异常, userId={}, tokens={}", userId, tokens, e);
        }
    }

    @Override
    public long tokensUsedToday(Integer userId) {
        if (userId == null) {
            return 0L;
        }
        try {
            String v = redis().opsForValue().get(tokenKeyOf(userId));
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public long tokensRemainToday(Integer userId) {
        return Math.max(0L, dailyTokenLimit - tokensUsedToday(userId));
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
        return Math.max(0, dailyRequestLimit - usedToday(userId));
    }

    private long secondsUntilEndOfDay() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate tomorrow = now.plusDays(1);
        long nowEpoch = now.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        long tomorrowEpoch = tomorrow.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        return tomorrowEpoch - nowEpoch + 5;
    }
}
