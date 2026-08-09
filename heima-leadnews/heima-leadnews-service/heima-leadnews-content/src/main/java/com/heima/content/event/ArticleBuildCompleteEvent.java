package com.heima.content.event;

import lombok.Getter;

/**
 * 文章HTML构建完成事件
 * ArticleFreemarkerService 构建完 HTML 后发布此事件，
 * 由监听器处理后续的任务完成或延迟发布逻辑，
 * 从而打破 RedissonDelayQueue ↔ ApArticleService ↔ ArticleFreemarkerService 的循环依赖。
 */
@Getter
public class ArticleBuildCompleteEvent {

    private final Long articleId;
    /** 任务ID，非延迟发布时用于更新任务状态 */
    private final Long taskId;
    /** 延迟执行间隔，>0 表示需要延迟发布 */
    private final long lastExecuteInterval;

    public ArticleBuildCompleteEvent(Long articleId, Long taskId, long lastExecuteInterval) {
        this.articleId = articleId;
        this.taskId = taskId;
        this.lastExecuteInterval = lastExecuteInterval;
    }

}