package com.heima.model.search.dtos;

import lombok.Data;

/**
 * 语义搜索请求（向量化增强）
 *
 * <p>search 服务 BM25 关键词命中不足时，调用 content 语义检索端点做向量召回兜底。
 * topK 为向量候选数（内部接口，服务端限制上限）。
 */
@Data
public class SemanticSearchDto {

    /** 搜索词 */
    private String searchWords;

    /** 向量召回候选数（≤20） */
    private Integer topK;
}
