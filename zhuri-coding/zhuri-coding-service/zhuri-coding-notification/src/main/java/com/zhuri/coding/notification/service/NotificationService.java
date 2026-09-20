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

    void incrUnreadCache(Long userId);

    ResponseResult sendActivityNotification(Long userId, String title, String content, String link);
}