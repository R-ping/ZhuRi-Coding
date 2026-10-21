package com.zhuri.coding.apis.search;

import com.zhuri.coding.apis.search.fallback.ISearchClientFallback;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.dtos.IndexedArticleQueryDto;
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
     * 反向对账取数：返回索引里发布时间落在窗口内的文章 id（供 content 侧巡检清理幽灵文档）。
     *
     * <p>与 {@link #missingArticleIds} 一起构成双向比对：那个接口补「库里该有、索引没有」，
     * 这个接口抓「索引里有、库里不该有」。同属对账语义，不做降级：search 不可用时抛异常，
     * 由调用方跳过本轮，而不是返回空清单假装索引干净。
     */
    @PostMapping("/api/v1/search/article/indexed")
    ResponseResult indexedArticleIds(@RequestBody IndexedArticleQueryDto dto);

    /**
     * 从 ES 索引中移除一篇文章（内容被下架或被作者删除时调用）。
     *
     * <p><b>服务端会先打可见性墓碑再物理删除</b>：墓碑（{@code isDeleted=true}）是一次单字段
     * 部分更新，检索侧过滤它，所以那一瞬间文档就不再出现在结果里；删除只是清理。
     * 调用方拿到的成功意味着两步都试过了，失败则意味着这轮没送达、需要重试。
     *
     * <p><b>失败必须抛出</b>：调用方经本地消息表重试，吞掉异常等于把事件标成已完成，
     * 下架内容会一直留在检索结果里。
     */
    @PostMapping("/api/v1/search/article/remove")
    ResponseResult removeArticleIndex(@RequestBody Long articleId);

    /**
     * 批量移除索引文档（反向对账清理幽灵文档用）。
     *
     * <p>同属"不可见"语义：先批量打墓碑，再 delete-by-query。批量是必要的 ——
     * 清理量最多到单轮上限，逐篇调用会把单线程的定时任务拖长，挤掉别的补偿任务。
     *
     * <p>失败必须抛出，理由同 {@link #removeArticleIndex}。
     */
    @PostMapping("/api/v1/search/article/remove-batch")
    ResponseResult removeArticleIndexBatch(@RequestBody List<Long> articleIds);
}