package com.heima.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.heima.model.article.dtos.ArticleDto;
import com.heima.model.article.dtos.ArticleHomeDto;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.mess.ArticleVisitStreamMess;
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

    boolean generateArticleEvent(ApArticle article, Long taskId, long lastExecuteInterval);

    public void updateScore(ArticleVisitStreamMess message);

    /**
     * 根据行为变更更新文章热度分数
     * @param articleId 文章ID
     * @param type 行为类型（LIKES/VIEWS/COLLECTION/COMMENT）
     * @param add 增量值
     */
    void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add);

    List<Map<String, Object>> listByAuthorId(ArticleDto dto);

    /**
     * 更新文章发布状态（延迟任务到期后调用）
     * 更新 DB 文章状态为 PUBLISHED，同步更新 ES 状态，更新本地消息表
     * @param articleId 文章ID
     */
    void updateArticleStatus(Long articleId);
}