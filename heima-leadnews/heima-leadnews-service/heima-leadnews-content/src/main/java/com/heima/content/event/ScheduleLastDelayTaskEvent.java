package com.heima.content.event;

import lombok.Getter;

/**
 * 调度最终延迟任务事件
 * ArticleBuildCompleteEventListener 在需要延迟发布时发布此事件，
 * 由 LastDelayTaskScheduler 处理实际添加到 Redisson 延迟队列的逻辑，
 * 从而打破 ArticleBuildCompleteEventListener → RedissonDelayQueue 的循环依赖。
 */
@Getter
public class ScheduleLastDelayTaskEvent {

    private final Long articleId;
    private final long lastExecuteInterval;

    public ScheduleLastDelayTaskEvent(Long articleId, long lastExecuteInterval) {
        this.articleId = articleId;
        this.lastExecuteInterval = lastExecuteInterval;
    }
}