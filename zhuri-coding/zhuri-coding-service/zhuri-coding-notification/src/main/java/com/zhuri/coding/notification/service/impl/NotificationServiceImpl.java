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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String REDIS_UNREAD_KEY = "notif:unread:";
    private static final String FIELD_TOTAL = "total";
    /** 缓存里的类型明细字段，与 getTypeName 的输出一一对应 */
    private static final String[] TYPE_FIELDS = {"comment", "digg", "follow", "system"};
    /** 未读计数缓存 TTL。到期后由 DB 全量重建，顺带校正增量累积的偏差 */
    private static final long UNREAD_TTL_MINUTES = 5;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 参与聚合的通知类型：2-赞/收藏、3-粉丝。
     *
     * <p>评论(1) 与系统(4) 刻意不进来：这两类的价值在于"说了什么/发了什么"，
     * 压成"3 人评论了你"等于把内容丢了。系统通知的 source_id 本来也常为空。
     *
     * <p>已知取舍：赞和收藏都是 type=2、source_id 都是作品 ID，会被合并成同一行，
     * 文案取最近一次事件。要拆开只要让写入方把 source_id 带上动作前缀即可，不用改表。
     */
    private static final Set<Integer> AGGREGATABLE_TYPES = Set.of(2, 3);

    /** 「收藏文章更新」聚合键前缀，完整键为 {@code collect_update:{yyyy-MM-dd}}（用户 × 自然日） */
    private static final String COLLECT_UPDATE_AGG_PREFIX = "collect_update:";

    /** 复合游标分隔符：{@code last_event_at + "_" + id} */
    private static final String CURSOR_SEP = "_";
    /**
     * 游标里时间的固定格式。不能用 {@code LocalDateTime.toString()}——
     * 秒为 0 时它会输出 {@code 2026-09-01T10:00} 而不是 {@code ...T10:00:00}，
     * 同一个时刻能产出两种字符串。游标是要回传给服务端的，格式必须唯一。
     */
    private static final DateTimeFormatter CURSOR_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

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

        // 游标是 (last_event_at, id) 二元组。旧格式是单个 id，解析不了就退回第一页，
        // 不要因为一个过期的游标让整个列表 500。
        LocalDateTime cursorAt = null;
        Long cursorId = null;
        if (dto.getCursor() != null && dto.getCursor().contains(CURSOR_SEP)) {
            String[] parts = dto.getCursor().split(CURSOR_SEP, 2);
            try {
                cursorAt = LocalDateTime.parse(parts[0], CURSOR_TIME_FMT);
                cursorId = Long.valueOf(parts[1]);
            } catch (Exception e) {
                log.warn("通知游标解析失败，退回第一页, userId={}, cursor={}", userId, dto.getCursor());
                cursorAt = null;
                cursorId = null;
            }
        }

        List<Notification> notifications =
                notificationMapper.selectByTypeAndCursor(userId, type, cursorAt, cursorId, size);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Notification n : notifications) {
            list.add(assembleNotificationData(n, userId));
        }

        boolean hasMore = notifications.size() == size;
        String nextCursor = hasMore && !notifications.isEmpty()
                ? buildCursor(notifications.get(notifications.size() - 1))
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
        // 1) 缓存命中直接返回。写入侧已按类型原子增量维护，不必回库。
        Map<String, Object> cached = readUnreadCache(userId);
        if (cached != null) {
            return ResponseResult.okResult(cached);
        }
        // 2) 未命中：DB 是唯一事实源，重建整包缓存（各类型明细齐全，作为后续增量的基线）
        Map<String, Object> result = loadUnreadFromDb(userId);
        writeUnreadCache(userId, result);
        return ResponseResult.okResult(result);
    }

    /**
     * 读未读计数缓存（Hash：total + 各类型明细）。不存在返回 null，由调用方回库重建。
     */
    private Map<String, Object> readUnreadCache(Long userId) {
        if (stringRedisTemplate == null || userId == null) {
            return null;
        }
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(REDIS_UNREAD_KEY + userId);
            if (entries.isEmpty()) {
                return null;
            }
            Map<String, Object> result = new HashMap<>();
            result.put(FIELD_TOTAL, toInt(entries.get(FIELD_TOTAL)));
            for (String field : TYPE_FIELDS) {
                result.put(field, toInt(entries.get(field)));
            }
            return result;
        } catch (Exception e) {
            log.warn("未读计数缓存读取失败，回退 DB 查询, userId={}", userId, e);
            return null;
        }
    }

    /** Redis Hash 取回的值可能是 String 也可能是数字，统一转成 int */
    private int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void writeUnreadCache(Long userId, Map<String, Object> result) {
        if (stringRedisTemplate == null || userId == null) {
            return;
        }
        try {
            String key = REDIS_UNREAD_KEY + userId;
            Map<String, Object> flat = new HashMap<>();
            flat.put(FIELD_TOTAL, result.get(FIELD_TOTAL));
            for (String field : TYPE_FIELDS) {
                flat.put(field, result.get(field));
            }
            stringRedisTemplate.opsForHash().putAll(key, flat);
            // TTL 只在重建时设置。增量刻意不刷新它——到期后由 DB 全量重建，
            // 顺带校正增量期间累积的偏差。
            stringRedisTemplate.expire(key, UNREAD_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("未读计数缓存写入失败, userId={}", userId, e);
        }
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
    @Transactional
    public ResponseResult createNotification(Long userId, Integer type, String sourceId, String content) {
        if (userId == null || type == null) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID);
        }
        if (!AGGREGATABLE_TYPES.contains(type) || sourceId == null || sourceId.isBlank()) {
            return insertPlain(userId, type, sourceId, content);
        }

        String aggKey = type + ":" + sourceId;
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setSourceId(sourceId);
        notification.setAggKey(aggKey);
        notification.setContent(content);
        notification.setAggCount(1);
        notification.setLastEventAt(LocalDateTime.now());

        // 顺序不能反。flipToUnread 的 WHERE 里带 is_read = 1，它的返回值就是
        // "这一行原本是不是已读" —— 这是判断未读数要不要 +1 的唯一依据，
        // 一旦先 upsert 就再也问不出来了（upsert 之后这行必然是未读）。
        int flipped = notificationMapper.flipToUnread(userId, aggKey);
        int affected = notificationMapper.upsertAggregated(notification);

        // 三种结局，只有前两种要动未读缓存：
        //   ① flipped > 0  合并进了一条已读行，它重新变回未读 → 未读 +1
        //   ② affected = 1 插了新行（此前没有这条聚合记录）→ 未读 +1
        //   ③ affected = 2 合并进一条本来就没读的行 → 未读数不该变
        // ①②互斥：flip 命中要求行已存在，插入要求行不存在，不会重复计数。
        if (flipped > 0 || affected == 1) {
            incrUnreadCache(userId, type);
        }

        return ResponseResult.okResult(notification.getId());
    }

    /** 不参与聚合的通知（评论、系统）：一行一事件，插入即未读。 */
    private ResponseResult insertPlain(Long userId, Integer type, String sourceId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setSourceId(sourceId);
        notification.setContent(content);
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        incrUnreadCache(userId, type);
        return ResponseResult.okResult(notification.getId());
    }

    @Override
    @Transactional
    public ResponseResult createCollectUpdateNotification(Long userId, String dayKey, String content) {
        if (userId == null || dayKey == null || dayKey.isBlank()) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "参数不能为空");
        }
        // 聚合维度是「用户 × 自然日」而不是「来源」：同一个用户当天被更新的多篇文章合并进同一条，
        // 后续事件只推进 agg_count 并用最新内容覆盖（读者点进最新一篇即可，避免逐条刷屏）。
        // 刻意不复用 createNotification：那里聚合键固定为 type:sourceId（按来源聚合），
        // 也不把 type=4 放进 AGGREGATABLE_TYPES（那会顺带改变既有系统通知"一行一事件"的行为）。
        String aggKey = COLLECT_UPDATE_AGG_PREFIX + dayKey;
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(4); // 系统类：内容型提醒，不冒充某人的互动行为
        notification.setSourceId(dayKey);
        notification.setAggKey(aggKey);
        notification.setContent(content);
        notification.setAggCount(1);
        notification.setLastEventAt(LocalDateTime.now());

        // 顺序不能反：flipToUnread 的 WHERE 依赖 is_read = 1，必须在 upsert 之前判定"是否由已读变回未读"
        int flipped = notificationMapper.flipToUnread(userId, aggKey);
        int affected = notificationMapper.upsertAggregated(notification);
        if (flipped > 0 || affected == 1) {
            incrUnreadCache(userId, 4);
        }
        return ResponseResult.okResult(notification.getId());
    }

    private String buildCursor(Notification n) {
        LocalDateTime at = n.getLastEventAt() != null ? n.getLastEventAt() : n.getCreatedAt();
        if (at == null) {
            return null;
        }
        return at.format(CURSOR_TIME_FMT) + CURSOR_SEP + n.getId();
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

            // 系统通知固定 type=4
            incrUnreadCache(userId, 4);

            return ResponseResult.okResult(notification.getId());
        } catch (Exception e) {
            log.error("sendActivityNotification error: userId={}, title={}", userId, title, e);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "发送活动通知失败");
        }
    }

    @Override
    public void incrUnreadCache(Long userId) {
        // 无类型的入口（外部 Feign 调用）维持"整包失效"语义：下次读取按 DB 重建
        evictUnreadCache(userId);
    }

    /**
     * 新来一条通知：按类型原子增量未读计数，不再整包失效。
     *
     * <p>为什么必须要求缓存已存在：缓存里存的是"各类型明细 + total"，增量只认得这一种类型。
     * 如果缓存不存在时就开始增量，会得到一份残缺的计数——用户明明有 3 条未读评论，
     * 缓存里却只有刚来的 1 条点赞。所以没有基线就跳过，让缓存保持"不存在"，
     * 下次读取自然走 DB 全量重建。
     *
     * <p>为什么用 Hash 而不是整包 JSON：整包要先读出来、改完再写回，两个并发写入会互相覆盖；
     * HINCRBY 让数据库自己加，没有丢失更新的窗口。
     */
    void incrUnreadCache(Long userId, Integer type) {
        if (stringRedisTemplate == null || userId == null || type == null) {
            return;
        }
        try {
            String key = REDIS_UNREAD_KEY + userId;
            Long size = stringRedisTemplate.opsForHash().size(key);
            if (size == null || size == 0) {
                return;
            }
            stringRedisTemplate.opsForHash().increment(key, FIELD_TOTAL, 1);
            stringRedisTemplate.opsForHash().increment(key, getTypeName(type), 1);
            // 刻意不刷新 TTL：到期后由 DB 全量重建，校正增量累积的偏差
        } catch (Exception e) {
            log.warn("未读计数增量失败，回退为整包失效, userId={}", userId, e);
            evictUnreadCache(userId);
        }
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
        // 聚合行：这行合并了多少个事件、最后一次事件什么时候发生。
        // 前端据此显示"张三 等 N 人赞了你的作品"；不聚合的行 agg_count 恒为 1。
        data.put("agg_count", n.getAggCount() == null ? 1 : n.getAggCount());
        data.put("last_event_at",
                n.getLastEventAt() != null ? n.getLastEventAt().format(CURSOR_TIME_FMT) : null);

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