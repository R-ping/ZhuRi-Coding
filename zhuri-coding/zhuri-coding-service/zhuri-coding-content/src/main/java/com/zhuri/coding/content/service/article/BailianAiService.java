package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.article.pojos.ApArticle;

import java.util.Map;

public interface BailianAiService {

    /**
     * 对文章进行一次性综合AI审核
     * 合并违规检测、标题相关性、内容质量、技术相关性四项审核为一次调用，降低token消耗
     * @param article 文章对象
     * @param content 文章内容
     * @return 审核结果Map，包含: success(Boolean), is_violation(Boolean), violation_type(String),
     *         violation_reason(String), titleRelevanceScore(Integer), qualityScore(Integer),
     *         isTechContent(Boolean)
     */
    Map<String, Object> comprehensiveAudit(ApArticle article, String content);

    /**
     * 通用AI违规内容检测（不依赖ApArticle对象）
     * @param entityId 实体ID（用于日志）
     * @param title 标题（可为空）
     * @param content 文本内容
     * @return 检测结果Map，包含: success(Boolean), is_violation(Boolean), violation_type(String), violation_reason(String)
     */
    Map<String, Object> checkViolation(Long entityId, String title, String content);
}