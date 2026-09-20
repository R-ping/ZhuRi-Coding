package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.model.article.dtos.AiPrecheckVo;

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
}
