package com.zhuri.coding.notification.service;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.notification.dtos.ImMessageDto;
import com.zhuri.coding.model.notification.dtos.ImReadDto;

public interface ImService {

    ResponseResult listSessions(Long userId);

    /**
     * 获取（不存在则创建）当前用户与指定用户的私信会话，用于从作者卡片跳转打开聊天区
     */
    ResponseResult getOrCreateSession(Long userId, Long peerId);

    ResponseResult listMessages(Long userId, Long sessionId, Long cursor, Integer size);

    ResponseResult sendMessage(Long senderId, ImMessageDto dto);

    ResponseResult markRead(Long userId, ImReadDto dto);

    /**
     * 校验指定会话是否属于该用户，属于则返回会话对端用户ID，否则返回 null（用于越权防护）。
     */
    Long getPeerUserId(Long sessionId, Long userId);
}