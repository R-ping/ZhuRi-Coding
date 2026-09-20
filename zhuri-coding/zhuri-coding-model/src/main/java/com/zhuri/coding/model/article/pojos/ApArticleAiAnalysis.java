package com.heima.model.article.pojos;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("ap_article_ai_analysis")
public class ApArticleAiAnalysis implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("article_id")
    private Long articleId;

    @TableField("title_relevance_score")
    private Integer titleRelevanceScore;

    @TableField("title_relevance_reason")
    private String titleRelevanceReason;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("originality_score")
    private Integer originalityScore;

    @TableField("logic_score")
    private Integer logicScore;

    @TableField("clarity_score")
    private Integer clarityScore;

    @TableField("quality_comment")
    private String qualityComment;

    @TableField("is_tech_content")
    private Boolean isTechContent;

    @TableField("tech_confidence")
    private BigDecimal techConfidence;

    @TableField("is_violation")
    private Boolean isViolation;

    @TableField("violation_type")
    private String violationType;

    @TableField("violation_reason")
    private String violationReason;

    @TableField("raw_response")
    private String rawResponse;

    @TableField("created_time")
    private Date createdTime;
}