package com.heima.content.service.ai;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.Map;

/**
 * AI token 用量计量器（成本可观测）。
 *
 * <p><b>为什么需要</b>：`AiQuotaService` 只按「次数」计量，而不同功能的 token 成本差 10 倍以上
 * （rerank 是短 prompt、创作复盘是长输出），导致：
 * <ul>
 *   <li>无法回答"哪个功能最烧钱"；</li>
 *   <li>模型路由（{@link com.heima.content.service.ai.router.AiModelRouter}）无法按成本决策；</li>
 *   <li>额度包只能按次数卖，对用户不公平、对平台不可控。</li>
 * </ul>
 *
 * <p><b>计量口径</b>：按 {@code feature:model:type} 三维落库（type ∈ prompt/completion），
 * 因为输入输出单价通常不同，混在一起就无法折算金额。
 *
 * <p><b>可靠性约定</b>：计量是旁路能力，<b>任何异常都必须内部消化</b>（fail-open），
 * 绝不能因为"统计失败"而影响 LLM 主链路返回。
 */
public interface AiTokenMeter {

    /**
     * 记录一次模型调用的用量（从 Spring AI 响应中提取，缺失时按估算兜底）。
     *
     * @param feature  功能标识（见 {@link AiFeatures}）
     * @param response Spring AI 响应；为 null 时等价于一次「未知用量」调用
     */
    void record(String feature, ChatResponse response);

    /**
     * 记录一次模型调用的用量（显式数值，供流式路径在 usage 缺失时按字符估算）。
     *
     * @param feature          功能标识
     * @param model            模型名（null 归为 unknown）
     * @param promptTokens     输入 token（负数按 0 处理）
     * @param completionTokens 输出 token（负数按 0 处理）
     * @param estimated        true=该数字为估算值（计入 estimatedCalls 计数，便于判断数据可信度）
     */
    void record(String feature, String model, int promptTokens, int completionTokens, boolean estimated);

    /** 内存快照（进程级累计，快速自检用） */
    Map<String, Long> snapshot();

    /**
     * 查询最近 days 天的 token 汇总（按 feature 聚合）。
     *
     * @param days 天数（1~30，越界自动收敛）
     * @return { "days": n, "total": {prompt, completion, total}, "byFeature": {feature: {...}}, "byDay": {date: {...}} }
     */
    Map<String, Object> summary(int days);

    /**
     * 按 {@code feature → model} 两级聚合（成本折算专用：金额 = Σ tokens × 各模型单价）。
     *
     * <p>{@link #summary(int)} 把 model 维度合并了，只能看"哪个功能用得多"；
     * 要回答"这个功能花了多少钱、换便宜模型能省多少"，必须保留 model 维度。
     *
     * @return { feature: { model: {prompt, completion, total} } }
     */
    Map<String, Map<String, Map<String, Object>>> summaryByFeatureModel(int days);
}
