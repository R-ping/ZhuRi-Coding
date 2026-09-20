package com.zhuri.coding.model.article.dtos;

import java.util.List;
import lombok.Data;

/**
 * AI 发布预检结果（发布助手：违规/质量/建议/标签/摘要/相似预警）
 */
@Data
public class AiPrecheckVo {

    /** 是否疑似违规 */
    private Boolean violation;

    /** 违规类型（无违规为空） */
    private String violationType;

    /** 违规/风险提示（无违规为空） */
    private String violationReason;

    /** 质量分 0-100 */
    private Integer qualityScore;

    /** 是否技术内容 */
    private Boolean tech;

    /** 优化建议（2~4 条） */
    private List<String> suggestions;

    /** 推荐标签（3~5 个） */
    private List<String> tags;

    /** 一句话摘要（≤120 字） */
    private String summary;

    /** 相似文章预警：最相似文章 ID（无高相似为 null） */
    private Long similarArticleId;

    /** 相似文章标题 */
    private String similarTitle;

    /** 最大相似度（余弦） */
    private Double similarity;

    /** 封面图合规审核（多模态，仅提供 coverImageUrl 时非空）。只判定是否违规，不做主题契合度评判 */
    private String imageUrl;
    private Boolean imageViolation;
    private String imageReason;

    /** 总耗时 ms */
    private long latencyMs;
}
