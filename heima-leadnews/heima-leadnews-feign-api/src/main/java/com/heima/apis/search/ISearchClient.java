package com.heima.apis.search;

import com.heima.apis.search.fallback.ISearchClientFallback;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.Bm25RecallDto;
import com.heima.model.search.vos.SearchArticleVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(value = "leadnews-search", contextId = "leadnews-search-searchClient", fallback = ISearchClientFallback.class)
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
}