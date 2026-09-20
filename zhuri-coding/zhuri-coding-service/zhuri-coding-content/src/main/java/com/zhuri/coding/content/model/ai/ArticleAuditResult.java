package com.heima.content.model.ai;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 文章综合 AI 审核结果（强类型 DTO）
 *
 * 对应 LLM 一次调用输出的完整 JSON 结构，字段命名稳定，
 * 通过 @JSONField 将 snake_case 的模型字段映射到驼峰 Java 属性，保证可被直接反序列化。
 */
@Data
public class ArticleAuditResult {

    /** 是否违规 */
    @JSONField(name = "is_violation")
    private Boolean isViolation;

    /** 违规类型，无违规时为空字符串 */
    @JSONField(name = "violation_type")
    private String violationType;

    /** 违规原因，100 字以内，无违规时为空字符串 */
    @JSONField(name = "violation_reason")
    private String violationReason;

    /** 标题相关性得分 0-100 */
    @JSONField(name = "title_relevance_score")
    private Integer titleRelevanceScore;

    /** 标题相关性理由 */
    @JSONField(name = "title_relevance_reason")
    private String titleRelevanceReason;

    /** 内容质量综合评分 */
    @JSONField(name = "quality_score")
    private Integer qualityScore;

    /** 原创性得分 */
    @JSONField(name = "originality_score")
    private Integer originalityScore;

    /** 逻辑性得分 */
    @JSONField(name = "logic_score")
    private Integer logicScore;

    /** 表达清晰度得分 */
    @JSONField(name = "clarity_score")
    private Integer clarityScore;

    /** 质量评价 */
    @JSONField(name = "comment")
    private String comment;

    /** 是否属于技术内容 */
    @JSONField(name = "is_tech")
    private Boolean isTech;

    /** 判定置信度 0.0-1.0 */
    @JSONField(name = "confidence")
    private Double confidence;

    /** 技术相关性理由 */
    @JSONField(name = "tech_reason")
    private String techReason;
}