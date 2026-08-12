package com.heima.notification.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.apis.article.ICommentClient;
import com.heima.apis.article.IFollowClient;
import com.heima.model.comment.dtos.CommentDto;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.notification.dtos.NotificationDto;
import com.heima.model.notification.pojos.Notification;
import com.heima.model.user.pojos.ApUser;
import com.heima.notification.mapper.NotificationMapper;
import com.heima.notification.service.NotificationService;
import com.heima.utils.thread.AppThreadLocalUtil;
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
        Map<String, Object> result = new HashMap<>();
        int total = 0;
        Map<String, Integer> typeCounts = new HashMap<>();
        
        // 优先从Redis缓存读取
        if (stringRedisTemplate != null) {
            String cached = stringRedisTemplate.opsForValue().get(REDIS_UNREAD_KEY + userId);
            if (cached != null) {
                total = Integer.parseInt(cached);
            }
        }
        
        // 从数据库按类型分组查询
        List<Map<String, Object>> groupResults = notificationMapper.countUnreadGroupByType(userId);
        for (Map<String, Object> row : groupResults) {
            Integer type = ((Number) row.get("type")).intValue();
            Integer count = ((Number) row.get("count")).intValue();
            total += count; // 累加（如果Redis没有的话，这里就是总数）
            typeCounts.put(getTypeName(type), count);
        }
        
        // 如果 Redis 有总数，优先用 Redis 的（因为 Redis 是实时的）
        if (stringRedisTemplate != null) {
            String cached = stringRedisTemplate.opsForValue().get(REDIS_UNREAD_KEY + userId);
            if (cached != null) {
                total = Integer.parseInt(cached);
            } else {
                // 如果 Redis 没有缓存，则写回
                stringRedisTemplate.opsForValue().set(REDIS_UNREAD_KEY + userId, String.valueOf(total), 5, TimeUnit.MINUTES);
            }
        }

        result.put("total", total);
        result.put("comment", typeCounts.getOrDefault("comment", 0));
        result.put("digg", typeCounts.getOrDefault("digg", 0));
        result.put("follow", typeCounts.getOrDefault("follow", 0));
        result.put("system", typeCounts.getOrDefault("system", 0));
        
        return ResponseResult.okResult(result);
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
        // 统计该类型未读数量（标记前），用于从 Redis 总未读计数中扣除
        int count = notificationMapper.countUnreadByType(userId, typeCode);
        if (count > 0) {
            notificationMapper.markTypeRead(userId, typeCode);
            // Redis 仅维护单一总未读计数，扣除该类型未读数，保持与其他类型计数的正确性
            if (stringRedisTemplate != null) {
                String key = REDIS_UNREAD_KEY + userId;
                // 仅当 key 存在时扣减，避免对不存在的 key 产生负数缓存
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                    stringRedisTemplate.opsForValue().increment(key, -count);
                }
            }
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
        if (stringRedisTemplate != null && userId != null) {
            String key = REDIS_UNREAD_KEY + userId;
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, 5, TimeUnit.MINUTES);
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