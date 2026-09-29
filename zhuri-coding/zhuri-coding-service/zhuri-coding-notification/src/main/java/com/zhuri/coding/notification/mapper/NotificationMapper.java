package com.zhuri.coding.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.notification.pojos.Notification;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 分页列表。游标是 (last_event_at, id) 二元组而不是单个 id：
     * 聚合行被新事件顶起来时 id 不变、last_event_at 变新，
     * 只用 id 做游标会让被顶起来的行从后续页里消失。
     */
    List<Notification> selectByTypeAndCursor(
            @Param("userId") Long userId,
            @Param("type") Integer type,
            @Param("cursorAt") LocalDateTime cursorAt,
            @Param("cursorId") Long cursorId,
            @Param("size") Integer size);

    /**
     * 聚合写入：命中 uk_agg 就合并进已有行，否则插入新行。
     *
     * @return 1=插入了新行；2=合并进了已有行（MySQL 对 ODKU 的约定：
     *         新增记 1，更新记 2）。调用方据此决定是否要动未读缓存。
     */
    int upsertAggregated(Notification notification);

    /**
     * 把已读的聚合行翻回未读。WHERE 里带 is_read = 1，所以返回值是"命中了几行"：
     * 1 表示这一行原本是已读、现在变未读；0 表示它本来就是未读或不存在。
     */
    int flipToUnread(@Param("userId") Long userId, @Param("aggKey") String aggKey);

    int countUnread(@Param("userId") Long userId);

    java.util.List<java.util.Map<String, Object>> countUnreadGroupByType(@Param("userId") Long userId);

    int markAllRead(@Param("userId") Long userId);

    int countUnreadByType(@Param("userId") Long userId, @Param("type") Integer type);

    int markTypeRead(@Param("userId") Long userId, @Param("type") Integer type);
}