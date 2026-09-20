package com.zhuri.coding.content.service.ai;

import java.util.Map;

/**
 * AI 消费漏斗计（按 feature × 阶段 按天聚合）。
 *
 * <p>回答"AI 问答从发起走到被用户认可，每一步的转化/流失"：
 * <ul>
 *   <li><b>发起</b>（{@link #STAGE_STARTED}）→ <b>检索完成</b>（{@link #STAGE_RECALL_DONE}）→
 *       <b>生成成功</b>（{@link #STAGE_GENERATED}）→ <b>用户反馈</b>（{@link #STAGE_FEEDBACK_UP/DOWN}）；</li>
 *   <li>{@link #STAGE_CACHE_HIT} 单独成环：语义缓存命中即"发起后直接返回"，衡量缓存省掉的模型调用。</li>
 * </ul>
 * 存储与 {@code AiTokenMeter} 同构：Redis 日 Hash（{@code ai:funnel:{yyyy-MM-dd}}，field={feature}:{stage}，
 * 累计次数，TTL 40 天）+ 进程内快照；Redis 故障 fail-open，绝不影响问答主链路。
 */
public interface AiFunnelMeter {

    /** 入口：配额通过，真正开始消耗 AI 能力 */
    String STAGE_STARTED = "started";
    /** 语义缓存命中：跳过检索与生成（省模型调用） */
    String STAGE_CACHE_HIT = "cache_hit";
    /** 检索管线执行完毕（含 hits==0，表示"走到了检索"） */
    String STAGE_RECALL_DONE = "recall_done";
    /** 成功产出回答并返回给用户 */
    String STAGE_GENERATED = "generated";
    /** 用户反馈：有帮助 */
    String STAGE_FEEDBACK_UP = "feedback_up";
    /** 用户反馈：没帮助/有误 */
    String STAGE_FEEDBACK_DOWN = "feedback_down";

    /** 阶段计数 +1（feature 用 {@link AiFeatures} 常量；null/空白降级为 {@link AiFeatures#OTHER}） */
    void incr(String feature, String stage);

    /** 进程内快照（feature:stage → 次数，有序） */
    Map<String, Long> snapshot();

    /**
     * 最近 N 天（1~30）漏斗聚合 + 转化率。
     *
     * @return {days, snapshot, byDay:{date:{feature:{stage:n}}}, totals:{stage:n}, rates}
     */
    Map<String, Object> summary(int days);
}