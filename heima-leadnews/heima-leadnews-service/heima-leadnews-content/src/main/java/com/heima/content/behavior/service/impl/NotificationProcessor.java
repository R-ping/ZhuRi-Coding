package com.heima.content.behavior.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.notification.INotificationClient;
import com.heima.content.behavior.service.BehaviorPostProcessor;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 站内信通知后置处理器
 * 用户行为触发后，向目标用户发送站内信通知
 */
@Slf4j
@Component
public class NotificationProcessor implements BehaviorPostProcessor {

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Autowired(required = false)
    private ApArticleMapper apArticleMapper;

    @Autowired(required = false)
    private ApPinsMapper apPinsMapper;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void postProcess(BehaviorContext context, BehaviorResult result) {
        if (notificationClient == null) {
            log.warn("INotificationClient not available, skip notification");
            return;
        }

        Integer targetUserId = context.getTargetUserId();
        if (targetUserId == null) {
            return;
        }

        BehaviorType type = context.getBehaviorType();

        try {
            switch (type) {
                case COMMENT_ARTICLE:
                case COMMENT_PIN:
                    sendCommentNotification(context);
                    break;
                case LIKE_ARTICLE:
                case LIKE_PIN:
                    sendLikeNotification(context);
                    break;
                case COLLECT_ARTICLE:
                    sendCollectNotification(context);
                    break;
                case FOLLOW_USER:
                    sendFollowNotification(context);
                    break;
                default:
                    // 其他行为不发送通知
                    break;
            }
        } catch (Exception e) {
            log.error("发送通知失败: type={}, userId={}, targetUserId={}",
                type.getCode(), context.getUserId(), targetUserId, e);
        }
    }

    @Override
    public int getOrder() {
        return 4; // 在等级和文章热度之后执行
    }

    /**
     * 发送评论通知
     * content JSON 字段与前端 notification/index.vue#mapNotificationItem 对齐：
     * trigger_user{name,avatar} / action_type / message / target_title / target_type / target_id / comment_id / notification_type
     */
    private void sendCommentNotification(BehaviorContext context) {
        try {
            Map<String, Object> triggerUser = new HashMap<>();
            triggerUser.put("name", context.getUserName() != null ? context.getUserName() : "用户");
            triggerUser.put("avatar", context.getUserAvatar() != null ? context.getUserAvatar() : "");

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("trigger_user", triggerUser);
            contentMap.put("action_type", "评论了你的作品");
            contentMap.put("message", context.getExtraString("commentContent") != null
                ? truncate(context.getExtraString("commentContent"), 20) : "");
            contentMap.put("target_title", resolveTargetTitle(context));
            contentMap.put("target_type", context.getTargetType() == 1 ? "article" : "pin");
            contentMap.put("target_id", context.getTargetId());
            contentMap.put("comment_id", context.getExtraLong("commentId"));
            contentMap.put("notification_type", "comment");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", context.getTargetUserId().longValue());
            params.put("type", 1); // 评论通知
            params.put("sourceId", String.valueOf(context.getTargetId()));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            log.info("评论通知已发送: to={}, from={}, targetId={}",
                context.getTargetUserId(), context.getUserId(), context.getTargetId());
        } catch (Exception e) {
            log.error("发送评论通知失败: to={}, from={}", context.getTargetUserId(), context.getUserId(), e);
        }
    }

    /**
     * 发送点赞通知
     */
    private void sendLikeNotification(BehaviorContext context) {
        try {
            Map<String, Object> triggerUser = new HashMap<>();
            triggerUser.put("name", context.getUserName() != null ? context.getUserName() : "用户");
            triggerUser.put("avatar", context.getUserAvatar() != null ? context.getUserAvatar() : "");

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("trigger_user", triggerUser);
            contentMap.put("action_type", "赞了你的作品");
            contentMap.put("target_title", resolveTargetTitle(context));
            contentMap.put("target_type", context.getTargetType() == 1 ? "article" : "pin");
            contentMap.put("target_id", context.getTargetId());
            contentMap.put("notification_type", "digg");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", context.getTargetUserId().longValue());
            params.put("type", 2); // 赞/收藏通知
            params.put("sourceId", String.valueOf(context.getTargetId()));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            log.info("点赞通知已发送: to={}, from={}, targetId={}",
                context.getTargetUserId(), context.getUserId(), context.getTargetId());
        } catch (Exception e) {
            log.error("发送点赞通知失败: to={}, from={}", context.getTargetUserId(), context.getUserId(), e);
        }
    }

    /**
     * 发送收藏通知
     */
    private void sendCollectNotification(BehaviorContext context) {
        try {
            Map<String, Object> triggerUser = new HashMap<>();
            triggerUser.put("name", context.getUserName() != null ? context.getUserName() : "用户");
            triggerUser.put("avatar", context.getUserAvatar() != null ? context.getUserAvatar() : "");

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("trigger_user", triggerUser);
            contentMap.put("action_type", "收藏了你的作品");
            contentMap.put("target_title", resolveTargetTitle(context));
            contentMap.put("target_type", context.getTargetType() == 1 ? "article" : "column");
            contentMap.put("target_id", context.getTargetId());
            contentMap.put("notification_type", "digg");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", context.getTargetUserId().longValue());
            params.put("type", 2); // 赞/收藏通知
            params.put("sourceId", String.valueOf(context.getTargetId()));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);
            log.info("收藏通知已发送: to={}, from={}, targetId={}",
                context.getTargetUserId(), context.getUserId(), context.getTargetId());
        } catch (Exception e) {
            log.error("发送收藏通知失败: to={}, from={}", context.getTargetUserId(), context.getUserId(), e);
        }
    }

    /**
     * 发送新增粉丝通知
     */
    private void sendFollowNotification(BehaviorContext context) {
        try {
            Map<String, Object> triggerUser = new HashMap<>();
            triggerUser.put("name", context.getUserName() != null ? context.getUserName() : "用户");
            triggerUser.put("avatar", context.getUserAvatar() != null ? context.getUserAvatar() : "");

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("trigger_user", triggerUser);
            contentMap.put("action_type", "关注了你");
            contentMap.put("notification_type", "follow");

            Map<String, Object> params = new HashMap<>();
            params.put("userId", context.getTargetUserId().longValue());
            params.put("type", 3); // 粉丝通知
            params.put("sourceId", String.valueOf(context.getUserId()));
            params.put("content", objectMapper.writeValueAsString(contentMap));

            notificationClient.createNotification(params);

            // 更新未读计数
            notificationClient.incrUnread(context.getTargetUserId().longValue());

            log.info("粉丝通知已发送: to={}, from={}",
                context.getTargetUserId(), context.getUserId());
        } catch (Exception e) {
            log.error("发送粉丝通知失败: to={}, from={}", context.getTargetUserId(), context.getUserId(), e);
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * 解析目标标题（文章标题或沸点内容），用于通知展示
     */
    private String resolveTargetTitle(BehaviorContext context) {
        try {
            if (context.getTargetType() != null && context.getTargetType() == 1) {
                ApArticle article = apArticleMapper != null
                    ? apArticleMapper.selectById(context.getTargetId()) : null;
                return article != null && article.getTitle() != null ? article.getTitle() : "";
            } else {
                ApPins pins = apPinsMapper != null
                    ? apPinsMapper.selectById(context.getTargetId()) : null;
                return pins != null && pins.getContent() != null
                    ? truncate(pins.getContent(), 30) : "";
            }
        } catch (Exception e) {
            log.warn("解析目标标题失败 targetType={}, targetId={}",
                context.getTargetType(), context.getTargetId(), e);
            return "";
        }
    }
}