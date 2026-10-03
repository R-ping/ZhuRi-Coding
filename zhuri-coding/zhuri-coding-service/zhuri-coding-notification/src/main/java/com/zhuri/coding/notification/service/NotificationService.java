package com.zhuri.coding.notification.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.notification.dtos.NotificationDto;

public interface NotificationService {

    ResponseResult list(NotificationDto dto);

    ResponseResult reply(Long userId, Long commentId, String content);

    ResponseResult toggleLike(Long userId, Long commentId);

    ResponseResult followBack(Long userId, Long followerId);

    ResponseResult unreadCount(Long userId);

    ResponseResult markAllRead(Long userId);

    ResponseResult markTypeRead(Long userId, String type);

    ResponseResult createNotification(Long userId, Integer type, String sourceId, String content);

    /**
     * 创建「收藏文章更新」提醒：同一用户同一天内的多条更新合并为一条聚合站内信。
     *
     * <p>与 {@link #createNotification} 的差异：聚合键不是「来源」而是「用户 × 自然日」——
     * 用户当天收藏的多篇文章先后被更新时，只累加同一条记录（agg_count / 内容取最新），
     * 避免一次更新刷一条把通知中心刷屏。</p>
     *
     * @param userId  接收用户ID
     * @param dayKey  自然日键（yyyy-MM-dd），同一天的多条更新合并到同一条记录
     * @param content 内容 JSON（含 notification_type/message 展示文案、标题、跳转链接、当日累计篇数）
     */
    ResponseResult createCollectUpdateNotification(Long userId, String dayKey, String content);

    void incrUnreadCache(Long userId);

    ResponseResult sendActivityNotification(Long userId, String title, String content, String link);
}