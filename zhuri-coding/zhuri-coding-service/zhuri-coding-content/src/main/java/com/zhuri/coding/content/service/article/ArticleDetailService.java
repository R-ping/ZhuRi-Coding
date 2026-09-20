package com.zhuri.coding.content.service.article;

import com.zhuri.coding.model.common.dtos.ResponseResult;

public interface ArticleDetailService {

    /**
     * 获取文章详情
     */
    ResponseResult getArticleDetail(Long id);

    /**
     * 获取文章专栏信息
     */
    ResponseResult getArticleColumn(Long id);

    /**
     * 获取相关推荐（同频道文章）
     */
    ResponseResult getRelatedArticles(Long id, Long cursor, Integer size);

    /**
     * 获取精选内容（同标签文章）
     */
    ResponseResult getFeaturedArticles(Long id, Long cursor, Integer size);

    /**
     * 获取为你推荐（热点文章）
     */
    ResponseResult getRecommendArticles(Long id, Long cursor, Integer size);
}