package com.heima.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.notification.pojos.ImMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ImMessageMapper extends BaseMapper<ImMessage> {

    List<ImMessage> selectBySessionId(@Param("sessionId") Long sessionId,
                                      @Param("cursor") Long cursor,
                                      @Param("size") Integer size);

    int countSentAfterLastReply(@Param("sessionId") Long sessionId,
                                @Param("senderId") Long senderId,
                                @Param("receiverId") Long receiverId);

    int markRead(@Param("sessionId") Long sessionId,
                 @Param("lastReadId") Long lastReadId,
                 @Param("receiverId") Long receiverId);
}