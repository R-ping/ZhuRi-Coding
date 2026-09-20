package com.heima.model.article.dtos;

import lombok.Data;

/**
 * 社区 AI 问答请求
 */
@Data
public class AiAskDto {

    /** 用户问题（≤200 字） */
    private String question;

    /** 召回文章数（默认 5，上限 8） */
    private Integer topK;

    /** true=fast 快速模式：单次向量召回+生成（跳过 Query Rewrite/Rerank，约 1/3 延迟） */
    private Boolean fast;

    /** 会话上下文（最近 ≤6 轮）：[{role: user|assistant, content}]，支持追问指代 */
    private java.util.List<java.util.Map<String, String>> history;
}
