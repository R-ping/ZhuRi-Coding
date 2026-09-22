package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

/**
 * AI 每日免费配额实现（Redis 计数，key 按自然日过期）
 */
@Slf4j
@Service
public class AiQuotaServiceImpl implements AiQuotaService {

    /**
     * 今日免费已用 tokens 计数 key 前缀。
     *
     * <p><b>为什么只剩 token 一个口径</b>：原先为「次数防刷 + tokens 控成本」双轨，但频次防刷
     * 早已由分层限流承担（每个 AI 端点 USER 5/分 + IP 20/分），"次"在闸门上是重复手段；
     * 而且它无法表达真实成本（一次短问答与一次多智能体预检的开销差一个量级）。
     * 故统一为 token 单一口径：准入看 token、结算算 token、流式按 token 到线即停。
     */
    private static final String TOKEN_KEY_PREFIX = "ai:quota:tokens:";

    /**
     * 原子「INCRBY + 补过期」脚本。
     *
     * <p><b>为什么必须用 Lua</b>：若分两步（先 INCR 再 EXPIRE），当进程在两步之间中断 / Redis 抖动时，
     * 计数 key 会**永久没有 TTL** —— 当日免费额度从此不再重置，用户被长期计费且无从自愈。
     * 收敛为单条脚本后，计数与过期同生共死；判据用 {@code TTL < 0} 而非 {@code n == 增量}，
     * 因此也能顺带把历史上已产生的"无 TTL 存量 key"在下次计数时修回。
     */
    private static final DefaultRedisScript<Long> INCRBY_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
        "local n = redis.call('INCRBY', KEYS[1], ARGV[2]) "
            + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
            + "return n",
        Long.class);

    /** 每日免费 tokens（配置可调；默认 2 万，按主模型折算约 0.4 元/用户/天） */
    @org.springframework.beans.factory.annotation.Value("${ai-quota.daily-tokens:20000}")
    private long dailyTokenLimit = DEFAULT_DAILY_TOKEN_QUOTA;

    @Override
    public long dailyTokenLimit() {
        return dailyTokenLimit;
    }

    @Autowired
    private CacheService cacheService;

    @Autowired
    private com.zhuri.coding.content.service.ai.AiWalletService walletService;

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
        // 单一口径准入：今日免费 tokens 未用尽，或钱包还有 token 余额。
        //
        // 这里**刻意不做预扣**，理由有据：
        //  ① 钱包扣减本身就是原子的（WHERE token_balance >= N，不足则扣光）→ 不可能扣成负数，
        //     "超支"不可能发生在钱包侧；
        //  ② 免费额度侧的并发重叠上界被限流（USER 5/分）压到极小；
        //  ③ 若为此引入"预扣 + 找零"（免费 Redis 计数 ↔ 钱包 DB 列之间做双向差额补偿），
        //     复杂度与出错面远大于收益。
        // 真正的超额由两步兜住：结算按真实用量扣减（settleTokens）+ 流式"到线即停"（availableTokens）。
        return precheckTokens(userId);
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
            // 同上：原子累加 + 补过期，避免 token 计数 key 永不过期导致免费额度不重置
            Long usedAfter = redis().execute(INCRBY_WITH_TTL_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(secondsUntilEndOfDay()), String.valueOf(tokens));
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
    public long availableTokens(Integer userId) {
        if (userId == null) {
            // 无用户上下文（如未登录的公开只读接口）→ 不做流式限额
            return Long.MAX_VALUE;
        }
        try {
            long free = Math.max(0L, tokensRemainToday(userId));
            long wallet = Math.max(0L, walletService.tokenBalanceOf(userId));
            return free + wallet;
        } catch (Exception e) {
            // fail-open：额度查询故障不应阻断生成（与 tryConsume / precheckTokens 的降级口径一致）
            log.warn("[AiQuota] 可用额度查询异常，本次不做流式限额（fail-open）, userId={}", userId, e);
            return Long.MAX_VALUE;
        }
    }

    private long secondsUntilEndOfDay() {
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate tomorrow = now.plusDays(1);
        long nowEpoch = now.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        long tomorrowEpoch = tomorrow.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();
        return tomorrowEpoch - nowEpoch + 5;
    }
}
