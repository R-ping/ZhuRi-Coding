package com.zhuri.coding.search.feign;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.vos.SearchArticleVo;
import com.zhuri.coding.search.service.ArticleSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * DB↔ES 索引对账（内部接口）：返回给定 id 中不在索引里的那些，供 content 侧巡检补推。
     */
    @PostMapping("/api/v1/search/article/missing")
    public ResponseResult missingArticleIds(@RequestBody List<Long> candidateIds){
        return ResponseResult.okResult(articleSearchService.missingArticleIds(candidateIds));
    }

    /**
     * 索引移除（内部接口）：内容被平台下架时，把文档从 ES 删掉，使其不再出现在检索结果里。
     */
    @PostMapping("/api/v1/search/article/remove")
    public ResponseResult removeArticleIndex(@RequestBody Long articleId){
        return articleSearchService.removeArticleIndex(articleId);
    }

}
