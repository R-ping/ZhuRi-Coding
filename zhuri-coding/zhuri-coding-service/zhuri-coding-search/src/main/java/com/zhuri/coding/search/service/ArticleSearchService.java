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
     * 从索引中移除一篇文章（内容被平台下架时调用）。
     *
     * <p>与 {@link #syncArticle} 相反：把文档从 ES 删掉，使检索结果里不再出现它。
     * 删除不存在的文档是幂等的（ES delete 对未命中 id 不会报错），因此重放安全。
     */
    ResponseResult removeArticleIndex(Long articleId);
}
