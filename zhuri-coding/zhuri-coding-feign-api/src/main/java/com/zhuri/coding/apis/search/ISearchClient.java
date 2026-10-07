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

    /**
     * 从 ES 索引中移除一篇文章（内容被平台下架时调用）。
     *
     * <p><b>为什么不是"再同步一次改状态"</b>：面向用户的检索查询不带任何状态过滤
     * （见 {@code ArticleSearchServiceImpl#search} 的查询构造），往索引里写一个 status 字段
     * 不会让文档自动消失，只会留一个"看起来在索引里、实际不该被搜到"的幽灵文档。
     * 下架的语义就是"不该被搜到"，所以要从索引里删掉。
     *
     * <p><b>失败必须抛出</b>：调用方经本地消息表重试，吞掉异常等于把事件标成已完成，
     * 下架内容会永远留在检索结果里。
     */
    @PostMapping("/api/v1/search/article/remove")
    ResponseResult removeArticleIndex(@RequestBody Long articleId);
}