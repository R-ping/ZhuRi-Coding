package com.heima.search.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.Bm25RecallDto;
import com.heima.model.search.dtos.UserSearchDto;

import com.heima.model.search.vos.SearchArticleVo;
import java.io.IOException;

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
}
