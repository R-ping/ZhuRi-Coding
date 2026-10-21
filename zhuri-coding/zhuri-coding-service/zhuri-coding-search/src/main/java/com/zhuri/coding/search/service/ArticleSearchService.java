package com.zhuri.coding.search.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import com.zhuri.coding.model.search.dtos.UserSearchDto;

import com.zhuri.coding.model.search.vos.SearchArticleVo;
import java.io.IOException;
import java.util.List;

public interface ArticleSearchService {

    /**
     * es文章分页检索
     * @param dto
     * @return
     */
    public ResponseResult search(UserSearchDto dto) throws IOException;

    /**
     * BM25 关键词召回（内部接口，供 RAG 混合检索做 RRF 融合）。
     *
     * <p>与面向用户的 search 不同：不加发布时间窗过滤、不记搜索历史、不做语义兜底，
     * 仅按相关度返回前 topK 篇的 id + score。
     */
    ResponseResult bm25Recall(Bm25RecallDto dto);

    ResponseResult syncArticle(SearchArticleVo searchArticleVo);

    /**
     * DB↔ES 索引对账：返回给定 id 中**不在索引里**的那些。
     *
     * <p>供 content 侧「已发布但索引缺失」的巡检做补推。单靠发布事件的失败重试覆盖不了
     * 「同步返回成功、文档实际没落库」或「索引被误删」这类情况 —— 只有真的问一次 ES 才知道。
     *
     * <p>查询失败时**抛出异常**（而不是返回"没有缺失"）：把故障伪装成正常会让对账彻底失效。
     */
    List<Long> missingArticleIds(List<Long> candidateIds);

    /**
     * 反向对账取数：列出索引里发布时间不早于 {@code since} 的文章 id。
     *
     * <p>正向对账问「库里的已发布文章哪些不在索引里」，反向对账问「索引里哪些文章库里
     * 已经不是可见态」—— 后者的候选集只有 ES 自己知道，所以要由索引侧先报清单。
     *
     * <p>查询失败时抛出异常，理由同 {@link #missingArticleIds}。
     */
    List<Long> indexedArticleIds(java.util.Date since, int limit);

    /**
     * 从索引中移除一篇文章（内容被下架 / 被作者删除时调用）。
     *
     * <p>实现是两步：先给文档打 {@code isDeleted=true} 的可见性墓碑（检索侧据此过滤），
     * 再按文档 _id 物理删除。因此墓碑成功后文档就已搜不到，删除只是收尾。
     * 两步都幂等，重放安全。
     */
    ResponseResult removeArticleIndex(Long articleId);

    /**
     * 批量移除索引文档（反向对账清理幽灵文档时调用）。
     *
     * <p>与单篇移除同一套语义，只是把「打墓碑」和「删除」各自收敛成一次批量请求：
     * 几十篇残留逐篇调用的往返开销，会把单线程的定时任务拖长，进而挤掉别的补偿任务。
     *
     * <p>失败必须抛出（理由同单篇移除）；幂等，重放安全。
     */
    ResponseResult removeArticleIndexBatch(List<Long> articleIds);
}
