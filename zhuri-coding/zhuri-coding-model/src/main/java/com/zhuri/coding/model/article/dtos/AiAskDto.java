package com.zhuri.coding.model.article.dtos;

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
    @Deprecated
    private Boolean fast;

    /** 回答范围：global=全站，article=仅当前文章；缺省 global */
    private String scope;

    /** 回答模式：fast=快速，deep=深度；scope=article 时归一为 fast */
    private String mode;

    /** 单篇范围必填；雪花 ID 前端以字符串传入也可被 Jackson 解析 */
    private Long articleId;

    /** 全站范围的锚点文章：优先召回，不限制其他来源 */
    private Long anchorArticleId;

    /** 请求来源：normal / scope_switch，仅用于观测 */
    private String source;

    /** 会话上下文（最近 ≤6 轮）：[{role: user|assistant, content}]，支持追问指代 */
    private java.util.List<java.util.Map<String, String>> history;
}
