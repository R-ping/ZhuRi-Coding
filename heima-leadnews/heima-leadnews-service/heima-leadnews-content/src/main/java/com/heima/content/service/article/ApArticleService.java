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

    /**
     * 创建文章发布事件（延迟任务消费的同步部分）：
     * 参数/文章存在性校验 + 本地消息表落锚（status=INIT），成功后发布 ArticlePublishEvent。
     * 发布执行（置 DB 发布态 + ES 同步）由 ArticlePublishEventListener 异步完成，
     * 未完成事件由 20s 扫描补偿收敛——本方法不承担发布结果。
     *
     * @param article 待发布文章（来自延迟任务参数）
     * @return true=锚点已落库且事件已发布；false=参数非法/文章不存在/落库失败（文章滞留待人工排查）
     */
    boolean createArticleEvent(ApArticle article);

    /**
     * 根据行为变更更新文章热度分数
     * @param articleId 文章ID
     * @param type 行为类型（LIKES/VIEWS/COLLECTION/COMMENT）
     * @param add 增量值
     */
    void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add);

    List<Map<String, Object>> listByAuthorId(ArticleDto dto);
}