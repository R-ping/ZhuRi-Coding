package com.zhuri.coding.model.article.dtos;

import lombok.Data;

/**
 * AI 回答的来源文章卡片
 */
@Data
public class AiSourceVo {

    private Long articleId;

    private String title;

    private String author;

    private Integer likes;

    /** 与问题的向量相似度（余弦，0~1） */
    private Double similarity;

    /** 是否为锚点原文（前端来源卡片显示“原文”） */
    private Boolean anchor;
}
