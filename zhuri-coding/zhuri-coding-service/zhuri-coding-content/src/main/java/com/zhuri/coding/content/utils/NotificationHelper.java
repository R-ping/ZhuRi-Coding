package com.heima.content.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知发送工具类
 * 统一处理审核失败通知、评论通知等常见通知场景
 */
@Slf4j
public class NotificationHelper {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationHelper() {
        // 工具类禁止实例化
    }

    /**
     * 发送审核失败通知
     *
     * @param notificationClient 通知客户端
     * @param userId            目标用户ID
     * @param sourceId          源实体ID
     * @param entityType        实体类型描述（如"沸点"、"专栏"）
     * @param entityTitle       实体标题（可为空）
     * @param reason            失败原因
     */
    public static void sendModerationFailNotification(
            INotificationClient notificationClient,
            Long userId, String sourceId,
            String entityType, String entityTitle, String reason) {
        if (notificationClient == null) {
            log.warn("通知服务不可用，跳过发送{}审核失败通知, sourceId={}", entityType, sourceId);
            return;
        }
        try {
            String titlePart = entityTitle != null ? "《" + entityTitle + "》" : "";
            String message = String.format(
                    "你的%s%s因违反社区规范已被删除。原因: %s",
                    titlePart, entityType, reason != null ? reason : "违反社区规范"
            );

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("sourceId", sourceId);
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");
            contentMap.put("entity_type", entityType);

            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("type", 4); // 系统通知
            params.put("sourceId", sourceId);
            params.put("content", objectMapper.writeValueAsString(contentMap));

            ResponseResult result = notificationClient.createNotification(params);
            if (result != null && result.getCode() == 200) {
                log.info("{}审核失败通知已发送, sourceId={}, userId={}", entityType, sourceId, userId);
            } else {
                log.warn("{}审核失败通知发送失败, sourceId={}, result={}", entityType, sourceId, result);
            }
        } catch (Exception e) {
            log.error("发送{}审核失败通知异常, sourceId={}", entityType, sourceId, e);
        }
    }

    /**
     * 发送评论通知给内容作者（"仅过审通知"：审核通过后由审核服务调用）
     *
     * @param notificationClient 通知客户端
     * @param targetUserId       目标用户ID（内容作者）
     * @param fromUserId         操作用户ID（评论者）
     * @param content            评论内容
     * @param targetType         目标类型：1-文章, 2-沸点
     * @param targetId           目标实体ID
     */
    public static void sendCommentNotification(
            INotificationClient notificationClient,
            Integer targetUserId, Integer fromUserId,
            String content, Integer targetType, Long targetId) {
        if (notificationClient == null) {
            log.warn("通知服务不可用，跳过发送评论通知");
            return;
        }
        try {
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("action_user_id", fromUserId);
            contentMap.put("content", truncate(content, 20));
            contentMap.put("target_type", targetType == 1 ? "article" : "pin");
            contentMap.put("target_id", targetId);
            contentMap.put("notification_type", "comment");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", targetUserId.longValue());
            params.put("type", 1); // 评论通知
            params.put("sourceId", String.valueOf(targetId));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            log.info("评论通知已发送, to={}, targetId={}", targetUserId, targetId);
        } catch (Exception e) {
            log.error("发送评论通知失败, to={}", targetUserId, e);
        }
    }

    /**
     * 发送违规通知给评论者
     *
     * @param notificationClient 通知客户端
     * @param userId             用户ID
     * @param commentId          评论ID
     * @param content            评论内容
     * @param reason             违规原因
     */
    public static void sendViolationNotification(
            INotificationClient notificationClient,
            Long userId, Long commentId, String content, String reason) {
        if (notificationClient == null) {
            log.warn("通知服务不可用，跳过发送违规通知");
            return;
        }
        try {
            String message = String.format(
                    "你的评论因违反社区规范已被删除。内容: %s",
                    truncate(content != null ? content : "", 50)
            );

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("commentId", String.valueOf(commentId));
            contentMap.put("message", message);
            contentMap.put("notification_type", "system");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("type", 4); // 系统通知
            params.put("sourceId", String.valueOf(commentId));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            log.info("评论违规删除通知已发送, userId={}, commentId={}", userId, commentId);
        } catch (Exception e) {
            log.error("发送评论违规通知失败, commentId={}", commentId, e);
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}