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

    /** 问答类每日免费次数（可后续改为配置项） */
    int DAILY_QUOTA = 20;

    /**
     * 尝试消费一次问答额度（免费额度优先 → 钱包额度包兜底）。
     *
     * @return true=放行；false=免费次数与钱包余额均用尽
     */
    boolean tryConsume(Integer userId);

    /** 今日已用次数（无记录返回 0） */
    long usedToday(Integer userId);

    /** 今日剩余次数（无记录返回 DAILY_QUOTA） */
    long remainToday(Integer userId);
}
