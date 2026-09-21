package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.content.service.ai.agent.workflow.StageType;

/**
 * AI 发布助手（precheck）
 *
 * <p>作者提交审核前调用：一次大模型调用产出 违规预检 + 质量分 + 优化建议 + 推荐标签 + 摘要，
 * 叠加向量相似度预警，返回"发布前报告"供作者修改/采纳。
 */
public interface PublishAssistantService {

    /**
     * 发布前预检。
     *
     * @param title     标题
     * @param content   正文
     * @param articleId 已入库文章 ID（相似度排除自身），可空
     * @return 预检报告；模型/服务不可用时返回 null
     */
    AiPrecheckVo precheck(String title, String content, Long articleId, String coverImageUrl);

    /**
     * 发布前预检测（SSE 流式可观测版）：在既有显式工作流 {@link PrecheckWorkflow} 上以事件回调
     * 实时回传各阶段进度（SAFETY/QUALITY/SEO/DUPLICATE/CRITIC/FORMAT 的 running/done/degraded），
     * 便于前端展示预检进行态。
     *
     * <p>仅用于推流阶段事件，不走封面多模态审核（该审核为独立 LLM 调用，详见非流式 {@link #precheck}）。
     *
     * @param title      标题
     * @param content    正文
     * @param articleId  已入库文章 ID（相似度排除自身），可空
     * @param coverImageUrl 封面图 URL（当前流式路径不消费，为接口对称性保留）
     * @param onEvent    阶段事件回调：{@code (stage, detail)}，detail 为 running/done/degraded；回调须快速非阻塞
     * @param onDone     预检结束回调：携带最终 {@link AiPrecheckVo}；若流程降级返回 null，仍会以 null 回调（由调用方决定发 done 还是 error）
     */
    void precheckStream(String title, String content, Long articleId, String coverImageUrl,
                        java.util.function.BiConsumer<StageType, String> onEvent,
                        java.util.function.Consumer<AiPrecheckVo> onDone);
}
