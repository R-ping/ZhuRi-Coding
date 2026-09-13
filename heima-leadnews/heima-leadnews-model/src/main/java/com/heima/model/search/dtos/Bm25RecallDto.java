package com.heima.model.search.dtos;

import lombok.Data;

/**
 * BM25 关键词召回请求（内部接口，供 RAG 混合检索使用）。
 *
 * <p>与面向用户的 {@code /api/v1/article/search} 不同：本接口不做发布时间窗过滤、不记搜索历史、
 * 不做语义兜底，仅按相关度（_score）返回前 topK 篇文章 id，供调用方做 RRF 融合。
 */
@Data
public class Bm25RecallDto {

    /** 检索词（RAG 场景传 Query Rewrite 后的查询） */
    private String query;

    /** 返回候选数（服务端限制上限） */
    private Integer topK;
}
