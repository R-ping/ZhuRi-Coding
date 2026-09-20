package com.zhuri.coding.notification.service;

import com.zhuri.coding.apis.article.IFollowClient;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.notification.pojos.ImSession;
import com.zhuri.coding.notification.mapper.ImMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ImStateMachine {

    @Autowired
    private ImMessageMapper imMessageMapper;

    @Autowired(required = false)
    private IFollowClient followClient;

    /**
     * 检查用户A是否可以向用户B发送消息
     * 限制规则（不对称）：取决于 B（接收方）是否关注了 A（发送方）
     * S3: B曾回复过A (is_active=true) → 无限发送
     * S0: B关注了A → 无限发送（与A是否关注B无关）
     * S2: 上述均不满足且A已发送1条待回复 → 禁止发送
     * S1: 上述均不满足且未发送 → 允许发送1条
     */
    public SendPermission checkPermission(Long senderId, Long receiverId, ImSession session) {
        // S3: is_active=true，B曾回复过A，永久有效
        if (session != null && session.getIsActive() != null && session.getIsActive() == 1) {
            return SendPermission.ALLOWED;
        }

        // S0: B（接收方）关注了A（发送方）→ 无限发送；关注服务不可用时降级
        if (receiverFollowsSender(senderId, receiverId)) {
            return SendPermission.ALLOWED;
        }

        // S2: 检查是否已发送消息等待回复
        if (session != null) {
            int sentCount = imMessageMapper.countSentAfterLastReply(session.getId(), senderId, receiverId);
            if (sentCount >= 1) {
                return SendPermission.LIMIT_REACHED;
            }
        }

        // S1: 允许发送1条
        return SendPermission.ALLOWED_ONCE;
    }

    /**
     * 判断接收方(receiverId)是否关注了发送方(senderId)
     */
    private boolean receiverFollowsSender(Long senderId, Long receiverId) {
        return isFollow(receiverId, senderId);
    }

    /**
     * 查询 userId 是否已关注 targetId；关注服务不可用时返回 false（降级为按普通限制处理）
     */
    private boolean isFollow(Long userId, Long targetId) {
        if (userId == null || targetId == null || followClient == null) {
            return false;
        }
        try {
            ResponseResult res = followClient.isFollowing(userId, targetId);
            if (res != null && res.getCode() == 200 && res.getData() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) res.getData();
                Object v = data.get("isFollowing");
                return v != null && Boolean.parseBoolean(String.valueOf(v));
            }
        } catch (Exception e) {
            log.warn("查询关注关系失败, userId={}, targetId={}", userId, targetId, e);
        }
        return false;
    }

    public enum SendPermission {
        ALLOWED,         // 无限发送
        ALLOWED_ONCE,    // 允许发送1条（S1）
        LIMIT_REACHED    // 已达限制（S2）
    }
}