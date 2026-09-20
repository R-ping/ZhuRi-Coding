package com.heima.content.service.article;

import com.heima.model.article.dtos.ArticleRecommendDto;
import com.heima.model.common.dtos.ResponseResult;

public interface ApArticleRecommendService {
    /**
     * 通用推荐入口（兼容旧端点 /recommend，等价于推荐分栏的全站推荐）
     */
    ResponseResult recommend(ArticleRecommendDto dto);

    /**
     * 综合频道推荐入口：/recommend_all
     * 推荐/最新分栏通过 dto.subTab 区分（recommend-推荐 / latest-最新）
     */
    ResponseResult recommendAll(ArticleRecommendDto dto);

    /**
     * 关注分栏推荐入口：/recommend_follow
     * 仅返回当前登录用户所关注作者发布的文章
     */
    ResponseResult recommendFollow(ArticleRecommendDto dto);

    /**
     * 分类频道推荐入口：/recommend_cate
     * 通过 dto.channel 指定频道ID，推荐/最新分栏通过 dto.subTab 区分
     */
    ResponseResult recommendCate(ArticleRecommendDto dto);
}