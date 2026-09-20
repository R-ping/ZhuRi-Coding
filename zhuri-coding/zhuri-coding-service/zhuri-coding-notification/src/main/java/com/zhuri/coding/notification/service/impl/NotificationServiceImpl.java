package com.zhuri.coding.notification.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.apis.article.ICommentClient;
import com.zhuri.coding.apis.article.IFollowClient;
import com.zhuri.coding.model.comment.dtos.CommentDto;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.NotificationDto;
import com.zhuri.coding.model.notification.pojos.Notification;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.notification.mapper.NotificationMapper;
import com.zhuri.coding.notification.service.NotificationService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String REDIS_UNREAD_KEY = "notif:unread:";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private ICommentClient commentClient;

    @Autowired(required = false)
    private IFollowClient followClient;

    @Override
    public ResponseResult list(NotificationDto dto) {
        Long userId = getCurrentUserId();
        Integer type = mapType(dto.getType());
        if (type == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        int size = (dto.getSize() == null || dto.getSize() <= 0) ? 20 : Math.min(dto.getSize(), 50);
        Long cursor = dto.getCursor() != null ? Long.parseLong(dto.getCursor()) : null;

        List<Notification> notifications = notificationMapper.selectByTypeAndCursor(userId, type, cursor, size);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Notification n : notifications) {
            list.add(assembleNotificationData(n, userId));
        }

        boolean hasMore = notifications.size() == size;
        String nextCursor = hasMore && !notifications.isEmpty()
                ? String.valueOf(notifications.get(notifications.size() - 1).getId())
                : null;

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("next_cursor", nextCursor);
        result.put("has_more", hasMore);

        return ResponseResult.okResult(result);
    }

    @Override
    public ResponseResult reply(Long userId, Long commentId, String content) {
        if (userId == null || commentId == null || content == null || content.trim().isEmpty()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        if (commentClient == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "评论服务暂不可用");
        }
        try {
            ResponseResult commentResult = commentClient.getCommentById(commentId);
            if (commentResult == null || commentResult.getCode() != AppHttpCodeEnum.SUCCESS.getCode()) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论不存在");
            }
            ApComment comment = objectMapper.convertValue(commentResult.getData(), ApComment.class);
            if (comment == null || comment.getArticleId() == null) {
                return ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST, "评论数据异常");
            }
            CommentDto dto = new CommentDto();
            dto.setArticleId(comment.getArticleId());
            dto.setParentId(commentId);
            dto.setContent(content.trim());
            return commentClient.addComment(dto);
        } catch (Exception e) {
            log.error("reply error: userId={}, commentId={}", userId, commentId, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "回复失败");
        }
    }

    @Override
    public ResponseResult toggleLike(Long userId, Long commentId) {
        if (userId == null || commentId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        if (commentClient == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "评论服务暂不可用");
        }
        try {
            CommentDto dto = new CommentDto();
            dto.setCommentId(commentId);
            return commentClient.likeComment(dto);
        } catch (Exception e) {
            log.error("toggleLike error: userId={}, commentId={}", userId, commentId, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "点赞操作失败");
        }
    }

    @Override
    public ResponseResult followBack(Long userId, Long followerId) {
        if (userId == null || followerId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        if (userId.equals(followerId)) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不能自己关注自己");
        }
        if (followClient == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "关注服务暂不可用");
        }
        try {
            return followClient.follow(userId, followerId);
        } catch (Exception e) {
            log.error("followBack error: userId={}, followerId={}", userId, followerId, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "关注操作失败");
        }
    }

    @Override
    public ResponseResult unreadCount(Long userId) {
        String key = REDIS_UNREAD_KEY + userId;
        // 1) 优先读取整包缓存（total + 各类型），一次性命中直接返回
        if (stringRedisTemplate != null) {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isEmpty()) {
                try {
                    Map<String, Object> cachedResult = objectMapper.readValue(
                            cached, new TypeReference<Map<String, Object>>() {});
                    return ResponseResult.okResult(cachedResult);
                } catch (Exception e) {
                    log.warn("未读计数缓存解析失败，回退 DB 查询, userId={}", userId, e);
                }
            }
        }
        // 2) DB 作为唯一事实源，保证 total 恒等于各类型之和
        Map<String, Object> result = loadUnreadFromDb(userId);
        // 3) 写整包缓存
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), 5, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("未读计数缓存写入失败, userId={}", userId, e);
            }
        }
        return ResponseResult.okResult(result);
    }

    /**
     * 以数据库为准统计未读数，保证 total 与各类型之和一致。
     */
    private Map<String, Object> loadUnreadFromDb(Long userId) {
        Map<String, Integer> typeCounts = new HashMap<>();
        int total = 0;
        List<Map<String, Object>> groupResults = notificationMapper.countUnreadGroupByType(userId);
        for (Map<String, Object> row : groupResults) {
            Integer type = ((Number) row.get("type")).intValue();
            Integer count = ((Number) row.get("count")).intValue();
            typeCounts.put(getTypeName(type), count);
            total += count;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("comment", typeCounts.getOrDefault("comment", 0));
        result.put("digg", typeCounts.getOrDefault("digg", 0));
        result.put("follow", typeCounts.getOrDefault("follow", 0));
        result.put("system", typeCounts.getOrDefault("system", 0));
        return result;
    }

    @Override
    public ResponseResult markAllRead(Long userId) {
        notificationMapper.markAllRead(userId);
        // 清除Redis缓存
        if (stringRedisTemplate != null) {
            stringRedisTemplate.delete(REDIS_UNREAD_KEY + userId);
        }
        return ResponseResult.okResult(null);
    }

    @Override
    public ResponseResult markTypeRead(Long userId, String type) {
        if (userId == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.NEED_LOGIN);
        }
        Integer typeCode = mapType(type);
        if (typeCode == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "不支持的通知类型");
        }
        // 标记该类型为已读
        int count = notificationMapper.countUnreadByType(userId, typeCode);
        if (count > 0) {
            notificationMapper.markTypeRead(userId, typeCode);
            // 缓存整体失效，下次按 DB 重建，避免局部扣减导致 total 与各类型之和不一致
            evictUnreadCache(userId);
        }
        return ResponseResult.okResult(count);
    }

    @Override
    public ResponseResult createNotification(Long userId, Integer type, String sourceId, String content) {
        if (userId == null || type == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setSourceId(sourceId);
        notification.setContent(content);
        notification.setIsRead(0);
        notification.setCreatedAt(java.time.LocalDateTime.now());
        notificationMapper.insert(notification);

        // 更新Redis未读计数
        incrUnreadCache(userId);

        return ResponseResult.okResult(notification.getId());
    }

    @Override
    public ResponseResult sendActivityNotification(Long userId, String title, String content, String link) {
        if (userId == null || title == null || content == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        try {
            // 构建content JSON
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("title", title);
            contentMap.put("content", content);
            contentMap.put("link", link);
            contentMap.put("notification_type", "activity");
            String contentJson = objectMapper.writeValueAsString(contentMap);

            Notification notification = new Notification();
            notification.setUserId(userId);
            notification.setType(4); // system类型
            notification.setSourceId(null);
            notification.setContent(contentJson);
            notification.setIsRead(0);
            notification.setCreatedAt(java.time.LocalDateTime.now());
            notificationMapper.insert(notification);

            // 更新Redis未读计数
            incrUnreadCache(userId);

            return ResponseResult.okResult(notification.getId());
        } catch (Exception e) {
            log.error("sendActivityNotification error: userId={}, title={}", userId, title, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "发送活动通知失败");
        }
    }

    @Override
    public void incrUnreadCache(Long userId) {
        // 未读计数以 DB 为唯一事实源，这里仅使缓存失效，下次读取按 DB 重建整包缓存
        evictUnreadCache(userId);
    }

    /**
     * 失效指定用户的未读计数缓存，使其下次按 DB 重新计算。
     */
    private void evictUnreadCache(Long userId) {
        if (stringRedisTemplate != null && userId != null) {
            stringRedisTemplate.delete(REDIS_UNREAD_KEY + userId);
        }
    }

    private Integer mapType(String type) {
        if (type == null) return null;
        switch (type) {
            case "comment": return 1;
            case "digg": return 2;
            case "follow": return 3;
            case "system": return 4;
            default: return null;
        }
    }

    private Map<String, Object> assembleNotificationData(Notification n, Long userId) {
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", String.valueOf(n.getId()));
        data.put("type", getTypeName(n.getType()));
        data.put("is_read", n.getIsRead() == 1);
        data.put("created_at", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);

        // 解析content JSON中的多态数据
        if (n.getContent() != null) {
            try {
                Map<String, Object> contentMap = objectMapper.readValue(n.getContent(), new TypeReference<Map<String, Object>>() {});
                data.putAll(contentMap);
            } catch (Exception e) {
                data.put("content_preview", n.getContent());
            }
        }
        return data;
    }

    private String getTypeName(Integer type) {
        switch (type) {
            case 1: return "comment";
            case 2: return "digg";
            case 3: return "follow";
            case 4: return "system";
            default: return "unknown";
        }
    }

    private Long getCurrentUserId() {
        ApUser user = AppThreadLocalUtil.getUser();
        return user != null ? user.getId().longValue() : null;
    }
}