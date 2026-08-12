package com.heima.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.notification.pojos.Notification;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NotificationMapper extends BaseMapper<Notification> {

    List<Notification> selectByTypeAndCursor(
            @Param("userId") Long userId,
            @Param("type") Integer type,
            @Param("cursor") Long cursor,
            @Param("size") Integer size);

    int countUnread(@Param("userId") Long userId);

    java.util.List<java.util.Map<String, Object>> countUnreadGroupByType(@Param("userId") Long userId);

    int markAllRead(@Param("userId") Long userId);

    int countUnreadByType(@Param("userId") Long userId, @Param("type") Integer type);

    int markTypeRead(@Param("userId") Long userId, @Param("type") Integer type);
}