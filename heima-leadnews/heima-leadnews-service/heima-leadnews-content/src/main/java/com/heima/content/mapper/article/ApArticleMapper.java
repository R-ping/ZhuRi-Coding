package com.heima.content.mapper.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApArticleMapper extends BaseMapper<ApArticle> {

    /**
     * 加载文章列表
     * @param dto
     * @param type  1  加载更多   2记载最新
     * @return
     */
    public List<ApArticle> loadArticleList(ArticleHomeDto dto,Short type);

    List<ApArticle> selectRecommendCandidates(@Param("channelId") Integer channelId, @Param("maxCandidates") int maxCandidates, @Param("tagName") String tagName, @Param("windowDays") int windowDays);

    /**
     * 更新文章评论数（原子递增）
     * @param articleId 文章ID
     * @param increment 增量（+1 或 -1）
     */
    void updateCommentCount(@Param("articleId") Long articleId, @Param("increment") int increment);

    /**
     * 查询推荐文章列表（is_recommend=1），需关联 ap_article_config 表
     */
    List<ApArticle> selectRecommendArticles(@Param("excludeId") Long excludeId, @Param("cursor") Long cursor, @Param("size") int size);

}