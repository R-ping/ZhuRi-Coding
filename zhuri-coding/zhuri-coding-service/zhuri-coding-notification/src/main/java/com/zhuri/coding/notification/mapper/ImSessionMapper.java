package com.zhuri.coding.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhuri.coding.model.notification.pojos.ImSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ImSessionMapper extends BaseMapper<ImSession> {

    List<ImSession> selectByUserId(@Param("userId") Long userId);

    ImSession selectBySessionKey(@Param("sessionKey") String sessionKey);

    /**
     * 收到一条新消息：未读计数在 SQL 层 +1，并刷新会话预览。
     *
     * <p>递增下推到 SQL 是为了避免「读出来 +1 再写回」的丢失更新——两个事务并发给同一会话
     * 发消息时会各自读到旧值、各自写回 +1，结果只涨 1。计数方向由 {@code receiverId} 与
     * {@code user1_id}/{@code user2_id} 比对得出，调用方无需自己分支。
     *
     * <p>注意带 {@code last_message_at} 不回退条件：并发下两条消息几乎同时到达时，
     * 先发的那条若后落库，会把新消息的预览覆盖回旧内容。条件不满足则本条不更新（影响行数 0）。
     *
     * @return 影响行数；0 表示会话不存在或已有更新的消息（属正常并发，不是错误）
     */
    int applyIncomingMessage(@Param("sessionId") Long sessionId,
                             @Param("receiverId") Long receiverId,
                             @Param("lastMessage") String lastMessage,
                             @Param("lastMessageAt") java.time.LocalDateTime lastMessageAt);

    /**
     * 标记已读：只清零 {@code userId} 这一侧的未读，另一侧保持不变。
     *
     * @return 影响行数
     */
    int resetUnread(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}