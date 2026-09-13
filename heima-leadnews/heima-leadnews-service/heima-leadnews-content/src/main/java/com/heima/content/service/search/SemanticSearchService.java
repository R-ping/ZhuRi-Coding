package com.heima.content.service.search;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.SemanticSearchDto;

/**
 * 语义搜索服务（向量化增强）：query 向量化 → pgvector 召回 → 过滤已发布且非 AIGC 水文。
 * 供 search 服务在 BM25 关键词命中不足时做向量兜底召回。
 */
public interface SemanticSearchService {

    /**
     * @return data = List&lt;Map&lt;String,Object&gt;&gt;（字段与 ES 搜索结果 map 对齐）
     *         向量化服务不可用返回 503（调用方 Feign fallback 降级为纯关键词）
     */
    ResponseResult semanticSearch(SemanticSearchDto dto);
}
