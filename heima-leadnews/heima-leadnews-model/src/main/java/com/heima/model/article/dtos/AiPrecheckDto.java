package com.heima.model.article.dtos;

import lombok.Data;

/**
 * AI 发布预检请求（作者提交审核前调用）
 */
@Data
public class AiPrecheckDto {

    /** 标题（≤120 字） */
    private String title;

    /** 正文（≤20000 字，超出截断） */
    private String content;

    /** 文章 ID（草稿已入库时传，用于相似度排除自身；可空） */
    private Long articleId;

    /** 封面图 URL（可空；提供时做多模态封面审核） */
    private String coverImageUrl;
}
