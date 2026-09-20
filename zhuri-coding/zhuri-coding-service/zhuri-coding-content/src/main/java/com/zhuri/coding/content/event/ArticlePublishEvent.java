package com.zhuri.coding.content.event;

import lombok.Getter;

/**
 * 文章发布执行事件
 * 延迟任务消费时本地消息表落锚（INIT）成功后发布此事件，
 * 由 {@link ArticlePublishEventListener} 异步执行「置 DB 发布态 + ES 同步 + 状态机收敛」。
 * 延迟任务状态只表示「到点已触发」，与发布结果彻底解耦；
 * 发布未完成（INIT/DB_SET_FAIL/ES_SYNC_FAIL）由 20s 扫描补偿收敛。
 */
@Getter
public class ArticlePublishEvent {

    private final Long articleId;

    public ArticlePublishEvent(Long articleId) {
        this.articleId = articleId;
    }
}
