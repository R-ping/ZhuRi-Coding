package com.heima.content.event;

import lombok.Getter;

/**
 * Redisson 延迟队列任务事件
 * RedissonDelayQueue 消费到延迟任务后发布此事件，
 * 由监听器处理具体的业务逻辑（调用 ApArticleService 和 TaskService），
 * 从而打破 RedissonDelayQueue ↔ ApArticleService ↔ TaskService 的循环依赖。
 */
@Getter
public class RedissonDelayTaskEvent {

    private final String queueName;
    private final String taskJson;

    public RedissonDelayTaskEvent(String queueName, String taskJson) {
        this.queueName = queueName;
        this.taskJson = taskJson;
    }
}