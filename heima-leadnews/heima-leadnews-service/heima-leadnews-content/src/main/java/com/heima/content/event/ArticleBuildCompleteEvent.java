package com.heima.content.event;

import lombok.Getter;

/**
 * 文章ES同步完成事件
 * ArticleFreemarkerService 完成 ES 同步后发布此事件，
 * 由监听器统一处理置发布态与任务完成逻辑，
 * 从而打破 RedissonDelayQueue ↔ ApArticleService ↔ ArticleFreemarkerService 的循环依赖。
 */
@Getter
public class ArticleBuildCompleteEvent {

    private final Long articleId;
    /** 任务ID，用于更新任务状态 */
    private final Long taskId;

    public ArticleBuildCompleteEvent(Long articleId, Long taskId) {
        this.articleId = articleId;
        this.taskId = taskId;
    }
}
