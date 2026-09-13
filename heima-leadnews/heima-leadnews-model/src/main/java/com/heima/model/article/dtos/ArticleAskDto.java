package com.heima.model.article.dtos;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单篇文章 AI 问答请求体（Step3·① 文章速览 + 单篇问答）
 *
 * <p>答案只来自该文章正文：articleId 定位文章，question 为提问，
 * history 为可选的多轮会话上下文（最近若干轮 user/assistant，后端滑窗注入）。
 */
@Data
public class ArticleAskDto {

    /** 文章ID（前端传字符串雪花ID，Jackson 可无损解析为 Long） */
    private Long articleId;

    /** 用户问题（≤200 字） */
    private String question;

    /** 多轮会话历史：[{role:user|assistant, content}]，最近 ≤6 轮 */
    private List<Map<String, String>> history;
}
