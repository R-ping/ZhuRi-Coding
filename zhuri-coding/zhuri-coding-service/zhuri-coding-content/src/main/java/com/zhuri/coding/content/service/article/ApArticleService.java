package com.zhuri.coding.content.service.article;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhuri.coding.model.article.dtos.ArticleDto;
import com.zhuri.coding.model.article.dtos.ArticleHomeDto;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.mess.UpdateArticleMess;
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
     * 提交文章发布（延迟任务消费的同步部分）：参数/文章存在性校验 + 落 Outbox 锚点。
     *
     * <p>锚点由被 {@code @LocalMessage} 标注的 {@code ArticlePublishExecutor#publishArticle}
     * 承担——在业务事务里调用它，切面把 articleId 存进消息表（与业务同事务提交）；
     * 方法体（置发布态 + 同步 ES）由 OutboxDispatcher 在提交后反射重放。
     *
     * @param article 待发布文章（来自延迟任务参数）
     * @return true=锚点已落库；false=参数非法/文章不存在/落库失败（文章滞留待人工排查）
     */
    boolean submitPublish(ApArticle article);

    /**
     * 根据行为变更更新文章热度分数
     * @param articleId 文章ID
     * @param type 行为类型（LIKES/VIEWS/COLLECTION/COMMENT）
     * @param add 增量值
     */
    void updateScoreByBehavior(Long articleId, UpdateArticleMess.UpdateArticleType type, Integer add);

    List<Map<String, Object>> listByAuthorId(ArticleDto dto);
}