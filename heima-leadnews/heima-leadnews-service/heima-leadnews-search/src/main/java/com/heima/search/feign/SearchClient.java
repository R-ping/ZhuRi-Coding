package com.heima.search.feign;

import com.heima.apis.search.ISearchClient;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.Bm25RecallDto;
import com.heima.model.search.vos.SearchArticleVo;
import com.heima.search.service.ArticleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchClient implements ISearchClient {

    @Autowired
    private ArticleSearchService articleSearchService;
    /** 同步文章到 ES 索引 */
    @PostMapping("/api/v1/search/sync/article")
    public ResponseResult syncArticle(@RequestBody SearchArticleVo searchArticleVo){
        return articleSearchService.syncArticle(searchArticleVo);
    }

    /**
     * BM25 关键词召回（内部接口，供 content 侧 RAG 混合检索做 RRF 融合）。
     * 仅按相关度返回前 topK 篇 id + score，不做时间窗过滤/搜索历史/语义兜底。
     */
    @PostMapping("/api/v1/search/bm25-recall")
    public ResponseResult bm25Recall(@RequestBody Bm25RecallDto dto){
        return articleSearchService.bm25Recall(dto);
    }

}
