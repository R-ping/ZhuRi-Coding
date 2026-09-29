package com.zhuri.coding.apis.search;

import com.zhuri.coding.apis.search.fallback.ISearchClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(value = "zhuri-coding-search", contextId = "zhuri-coding-search-searchClient", fallback = ISearchClientFallback.class)
public interface ISearchClient {

    /** 同步文章到 ES 索引 */
    @PostMapping("/api/v1/search/sync/article")
    ResponseResult syncArticle(@RequestBody SearchArticleVo searchArticleVo);

    /**
     * BM25 关键词召回（供 content 侧 RAG 混合检索做 RRF 融合）。
     * 返回 data = List&lt;Map&gt;，元素为 {id: String, score: Double}，按相关度降序。
     */
    @PostMapping("/api/v1/search/bm25-recall")
    ResponseResult bm25Recall(@RequestBody Bm25RecallDto dto);

    /**
     * DB↔ES 索引对账：返回给定 id 中**不在索引里**的那些（供 content 侧巡检补推）。
     *
     * <p>对账语义要求「宁可不补、不可误补」，所以本接口不做降级：search 不可用时抛异常，
     * 由调用方跳过本轮，而不是返回"没有缺失"。
     */
    @PostMapping("/api/v1/search/article/missing")
    ResponseResult missingArticleIds(@RequestBody List<Long> candidateIds);
}