package com.heima.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.mess.UpdateArticleMess;
import java.util.List;
import java.util.Map;

public interface ApArticleService extends IService<ApArticle> {

    /**
     * 加载文章列表
     * @param dto
     * @param type  1 加载更多   2 加载最新
     * @return
     */
    public ResponseResult load(ArticleHomeDto dto,Short type);

    boolean generateArticleEvent(ApArticle article, Long taskId);

    /**
     * 根据行为变更更新文章热度分数
     * @param articleId 文章ID
     * @param type 行为类型（LIKES/VIEWS/COLLECTION/COMMENT）
     * @param add 增量值
     */
    void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add);

    List<Map<String, Object>> listByAuthorId(ArticleDto dto);
}