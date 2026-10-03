package com.zhuri.coding.content.service.article;

/**
 * 文章实质更新后的「收藏者更新提醒」投递服务（F5）。
 *
 * <p>由 {@code ArticleRevisionServiceImpl#applyRevision} 在判定为实质更新时登记，
 * 经本地消息表在事务提交后异步执行：把「文章被更新」这件事告诉收藏过它的用户。</p>
 */
public interface ArticleUpdateNotifyService {

    /**
     * 向收藏该文章的用户投递更新提醒（异步重放入口，不要直接调用）。
     *
     * <p><b>为什么参数里有 updateTimeMillis</b>：本地消息表的幂等键需要"一次更新"的唯一标识 ——
     * 同一篇文章在不同时间可以被多次更新，每次都必须能各自登记一条事件；
     * 把更新时刻拼进幂等键（{@code article_update_notify:{articleId}:{updateTimeMillis}}），
     * 既保证重放不重复，又不会把后续的合法更新误判为重复事件。</p>
     *
     * @param articleId         被更新的文章ID
     * @param updateTimeMillis  本次实质更新的时间戳（毫秒）
     */
    void notifyCollectors(Long articleId, Long updateTimeMillis);
}