package com.zhuri.coding.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.notification.pojos.ImMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ImMessageMapper extends BaseMapper<ImMessage> {

    /**
     * 按会话向前翻消息，并过滤掉「调用方这一侧已删除」的记录。
     *
     * @param userId 当前查看者，决定按 {@code is_deleted_for_sender} 还是
     *                {@code is_deleted_for_receiver} 过滤
     */
    List<ImMessage> selectBySessionId(@Param("sessionId") Long sessionId,
                                      @Param("userId") Long userId,
                                      @Param("cursor") Long cursor,
                                      @Param("size") Integer size);

    int countSentAfterLastReply(@Param("sessionId") Long sessionId,
                                @Param("senderId") Long senderId,
                                @Param("receiverId") Long receiverId);

    int markRead(@Param("sessionId") Long sessionId,
                 @Param("lastReadId") Long lastReadId,
                 @Param("receiverId") Long receiverId);

    /**
     * 按客户端去重 ID 查已落库的消息，用于发送侧的幂等判断。
     *
     * @return 命中返回该消息，未命中返回 null
     */
    ImMessage selectByClientId(@Param("sessionId") Long sessionId, @Param("clientId") String clientId);
}