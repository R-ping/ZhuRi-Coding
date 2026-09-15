package com.heima.content.service.ai;

/**
 * AI 每日免费配额（成本止血：计量先行，订阅解锁后置）
 *
 * <p>问答类为最贵功能（单问 1~3 次模型调用），对登录用户按日配额：
 * 免费每日 N 次，免费用尽后扣减钱包额度包，两者均尽返回
 * {@link com.heima.model.common.enums.AppHttpCodeEnum#AI_QUOTA_EXHAUSTED}，次日 Redis key 自然过期重置。
 * 有缓存兜底的摘要/读完想问暂不入配额（IP 频控 + 24h 缓存已可控），后续可并入。
 */
public interface AiQuotaService {

    /** 每日免费次数默认值（实际生效值见 {@link #dailyRequestLimit()}，可由配置覆盖） */
    int DEFAULT_DAILY_QUOTA = 20;

    /**
     * 每日免费 token 额度默认值（实际生效值见 {@link #dailyTokenLimit()}）。
     *
     * <p>与次数闸门<b>双轨并存</b>：次数防刷（便宜、粗粒度），tokens 控成本（真实、细粒度）。
     * 两者任一用尽且钱包无对应余额即拒绝。
     */
    long DEFAULT_DAILY_TOKEN_QUOTA = 20_000L;

    /** 实际生效的每日免费次数（配置 {@code ai-quota.daily-requests}） */
    int dailyRequestLimit();

    /** 实际生效的每日免费 tokens（配置 {@code ai-quota.daily-tokens}） */
    long dailyTokenLimit();

    /**
     * 尝试消费一次问答额度（免费额度优先 → 钱包额度包兜底）。
     *
     * @return true=放行；false=免费次数/免费 tokens 与钱包余额均用尽
     */
    boolean tryConsume(Integer userId);

    /** 今日已用次数（无记录返回 0） */
    long usedToday(Integer userId);

    /** 今日剩余次数（无记录返回 DAILY_QUOTA） */
    long remainToday(Integer userId);

    // ==================== token 维度（新计费口径） ====================

    /**
     * token 维度预检（不消耗）：今日免费 tokens 未用尽，或钱包尚有 tokens 余额。
     * <p>Redis/DB 异常一律放行（fail-open），不因配额故障阻断 AI 主链路。
     */
    boolean precheckTokens(Integer userId);

    /**
     * 按实际用量结算（响应返回后调用）：先计入今日免费已用 tokens，超出部分扣钱包 tokens 余额。
     * <p>旁路能力：任何异常内部消化，绝不影响已完成的 LLM 调用结果返回。
     *
     * @param tokens 本次实际消耗 tokens（prompt + completion）
     */
    void settleTokens(Integer userId, long tokens);

    /** 今日已用免费 tokens（含超出部分，展示时请自行与 DAILY_TOKEN_QUOTA 取 min） */
    long tokensUsedToday(Integer userId);

    /** 今日剩余免费 tokens */
    long tokensRemainToday(Integer userId);
}
