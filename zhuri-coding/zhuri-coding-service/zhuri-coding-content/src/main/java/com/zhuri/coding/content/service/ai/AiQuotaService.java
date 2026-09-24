package com.zhuri.coding.content.service.ai;

/**
 * AI 每日免费配额（**token 单一口径**）
 *
 * <p>问答类为最贵功能（单问 1~3 次模型调用），对登录用户按日配额：
 * 免费每日 N <b>tokens</b>，用尽后扣减钱包额度包，两者均尽返回
 * {@link com.zhuri.coding.model.common.enums.AppHttpCodeEnum#AI_QUOTA_EXHAUSTED}，次日 Redis key 自然过期重置。
 * 有缓存兜底的摘要/读完想问暂不入配额（IP 频控 + 24h 缓存已可控），后续可并入。
 *
 * <p><b>为什么不再有"次数"维度</b>：原先"次数防刷 + tokens 控成本"双轨，但频次防刷早已由分层限流
 * 承担（每个 AI 端点 USER 5/分 + IP 20/分），"次"在闸门上是重复手段，且无法表达真实成本
 * （一次短问答与一次多智能体预检的开销差一个量级）。统一为 token 后，语义也更一致：
 * 准入看 token、结算算 token、流式按 token 到线即停。
 */
public interface AiQuotaService {

    /**
     * 每日免费 token 额度默认值（实际生效值见 {@link #dailyTokenLimit()}）。
     */
    long DEFAULT_DAILY_TOKEN_QUOTA = 20_000L;

    /** 实际生效的每日免费 tokens（配置 {@code ai-quota.daily-tokens}） */
    long dailyTokenLimit();

    /**
     * 尝试消费一次问答额度（**准入判定**，不做预扣）。
     *
     * <p>判据：今日免费 tokens 未用尽，或钱包尚有 token 余额。
     * 这里只做只读判定、不预扣数字——钱包扣减本身是原子的（{@code WHERE token_balance >= N}，不足则扣光），
     * 免费额度的并发重叠被限流压到极小；引入"预扣 + 找零"的复杂度不值得。
     * 真实扣减发生在 {@link #settleTokens(Integer, long)}（按实际用量）。
     *
     * @return true=放行；false=免费 tokens 与钱包余额均用尽
     */
    boolean tryConsume(Integer userId);

    // ==================== token 维度 ====================

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

    /**
     * 当前可用 token 额度 = 今日免费剩余 + 钱包 token 余额。
     *
     * <p><b>用途</b>：流式生成的「到线即停」。LLM 用量在生成完成前无法预知，
     * 因此不在调用前预测，而是在流式过程中累计估算、触达该额度即中断（已生成内容按估算结算）。
     *
     * <p><b>只在流开始前取一次快照</b>，之后由调用方在内存里递减，避免逐 chunk 查 Redis/DB。
     * 代价：并发请求可能各持同一份快照（<b>并发超支</b>）——这需要靠闸门侧的原子预扣收敛，
     * 不在本方法的职责范围内。
     *
     * @return 可用 token；{@code userId} 为空或查询异常时返回 {@link Long#MAX_VALUE}
     *         （视为不限，fail-open —— 不因额度查询故障阻断生成）
     */
    long availableTokens(Integer userId);
}
