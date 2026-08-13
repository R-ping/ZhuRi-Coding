package com.heima.notification.service;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.notification.dtos.ImMessageDto;
import com.heima.model.notification.dtos.ImReadDto;

public interface ImService {

    ResponseResult listSessions(Long userId);

    /**
     * 获取（不存在则创建）当前用户与指定用户的私信会话，用于从作者卡片跳转打开聊天区
     */
    ResponseResult getOrCreateSession(Long userId, Long peerId);

    ResponseResult listMessages(Long userId, Long sessionId, Long cursor, Integer size);

    ResponseResult sendMessage(Long senderId, ImMessageDto dto);

    ResponseResult markRead(Long userId, ImReadDto dto);
}