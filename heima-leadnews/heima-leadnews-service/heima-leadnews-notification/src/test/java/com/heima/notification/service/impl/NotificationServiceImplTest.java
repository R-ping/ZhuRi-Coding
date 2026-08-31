package com.heima.notification.service.impl;

import com.heima.apis.article.ICommentClient;
import com.heima.apis.article.IFollowClient;
import com.heima.model.comment.pojos.ApComment;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.notification.dtos.NotificationDto;
import com.heima.model.notification.pojos.Notification;
import com.heima.model.user.pojos.ApUser;
import com.heima.notification.mapper.NotificationMapper;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NotificationServiceImpl 单元测试
 *
 * 覆盖通知列表（游标分页/类型映射）、回复评论、点赞、关注回关、未读计数
 * （Redis 缓存 + DB 分组）、按类型/全部标记已读、创建通知（含 Redis 未读递增）等分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl 通知服务")
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ICommentClient commentClient;
    @Mock
    private IFollowClient followClient;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        // 部分测试不触碰 Redis，故对 opsForValue() 打桩放宽为 lenient，避免 UnnecessaryStubbing
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        ApUser u = new ApUser();
        u.setId(userId.intValue());
        AppThreadLocalUtil.setUser(u);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private Notification notif(Long id, Integer type, Integer isRead, String content) {
        Notification n = new Notification();
        n.setId(id);
        n.setUserId(userId);
        n.setType(type);
        n.setIsRead(isRead);
        n.setContent(content);
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }

    @Nested
    @DisplayName("list 通知列表")
    class NotificationList {
        @Test
        @DisplayName("类型非法 → 参数错误")
        void testBadType() {
            NotificationDto dto = new NotificationDto();
            dto.setType("unknown");
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.list(dto).getCode());
        }

        @Test
        @DisplayName("正常：游标分页 + JSON content 展开")
        void testOk() {
            NotificationDto dto = new NotificationDto();
            dto.setType("comment");
            dto.setSize(10);
            dto.setCursor("50");
            Notification n = notif(60L, 1, 0, "{\"title\":\"t\",\"content\":\"c\",\"link\":\"l\",\"notification_type\":\"activity\"}");
            when(notificationMapper.selectByTypeAndCursor(userId, 1, 50L, 10)).thenReturn(List.of(n));

            Map<String, Object> data = (Map<String, Object>) notificationService.list(dto).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals(1, list.size());
            assertEquals("comment", list.get(0).get("type"));
            assertEquals("t", list.get(0).get("title"));
            assertEquals(false, list.get(0).get("is_read"));
        }

        @Test
        @DisplayName("content 非 JSON → 兜底 content_preview")
        void testJsonFallback() {
            NotificationDto dto = new NotificationDto();
            dto.setType("digg");
            Notification n = notif(61L, 2, 1, "plain-text");
            when(notificationMapper.selectByTypeAndCursor(eq(userId), eq(2), isNull(), anyInt()))
                    .thenReturn(List.of(n));

            Map<String, Object> data = (Map<String, Object>) notificationService.list(dto).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals("plain-text", list.get(0).get("content_preview"));
        }
    }

    @Nested
    @DisplayName("reply 回复评论")
    class Reply {
        @Test
        @DisplayName("参数缺失 → 参数错误")
        void testParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.reply(userId, null, "").getCode());
        }

        @Test
        @DisplayName("评论不存在 → DATA_NOT_EXIST")
        void testCommentNotExist() {
            when(commentClient.getCommentById(5L)).thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.DATA_NOT_EXIST));
            assertEquals(AppHttpCodeEnum.DATA_NOT_EXIST.getCode(), notificationService.reply(userId, 5L, "hi").getCode());
        }

        @Test
        @DisplayName("成功：转发 addComment")
        void testOk() {
            ApComment comment = new ApComment();
            comment.setArticleId(888L);
            when(commentClient.getCommentById(5L)).thenReturn(ResponseResult.okResult(comment));
            when(commentClient.addComment(any())).thenReturn(ResponseResult.okResult(null));

            ResponseResult r = notificationService.reply(userId, 5L, "  hello ");
            assertEquals(200, r.getCode());
            verify(commentClient).addComment(argThat(d -> d.getParentId() == 5L && d.getArticleId() == 888L));
        }
    }

    @Nested
    @DisplayName("toggleLike / followBack")
    class Actions {
        @Test
        @DisplayName("点赞：参数缺失 → 参数错误")
        void testLikeParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.toggleLike(userId, null).getCode());
        }

        @Test
        @DisplayName("点赞：转发 likeComment")
        void testLikeOk() {
            when(commentClient.likeComment(any())).thenReturn(ResponseResult.okResult(null));
            assertEquals(200, notificationService.toggleLike(userId, 7L).getCode());
            verify(commentClient).likeComment(any());
        }

        @Test
        @DisplayName("关注回关：不能关注自己")
        void testFollowSelf() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.followBack(userId, userId).getCode());
        }

        @Test
        @DisplayName("关注回关：转发 follow")
        void testFollowOk() {
            when(followClient.follow(userId, 300L)).thenReturn(ResponseResult.okResult(null));
            assertEquals(200, notificationService.followBack(userId, 300L).getCode());
            verify(followClient).follow(userId, 300L);
        }
    }

    @Nested
    @DisplayName("unreadCount 未读计数")
    class UnreadCount {
        @Test
        @DisplayName("Redis 无缓存 → 用 DB 分组总数并写回整包缓存")
        void testFromDb() {
            when(valueOps.get("notif:unread:100")).thenReturn(null);
            Map<String, Object> row = new HashMap<>();
            row.put("type", 1);
            row.put("count", 3);
            when(notificationMapper.countUnreadGroupByType(userId)).thenReturn(List.of(row));

            Map<String, Object> data = (Map<String, Object>) notificationService.unreadCount(userId).getData();
            assertEquals(3, data.get("total"));
            assertEquals(3, data.get("comment"));
            assertEquals(0, data.get("digg"));
            // 写回的是整包 JSON，而非单个总数
            verify(valueOps).set(eq("notif:unread:100"),
                    argThat(json -> json.contains("\"total\":3") && json.contains("\"comment\":3")),
                    eq(5L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("Redis 有整包缓存 → 直接返回，不触达 DB")
        void testFromRedis() {
            when(valueOps.get("notif:unread:100"))
                    .thenReturn("{\"total\":10,\"comment\":0,\"digg\":0,\"follow\":0,\"system\":0}");

            Map<String, Object> data = (Map<String, Object>) notificationService.unreadCount(userId).getData();
            assertEquals(10, data.get("total"));
            assertEquals(0, data.get("comment"));
            // 命中缓存，不再查询 DB（防止 UnnecessaryStubbing 不再打桩 countUnreadGroupByType）
            verify(notificationMapper, never()).countUnreadGroupByType(anyLong());
        }
    }

    @Nested
    @DisplayName("markTypeRead / markAllRead")
    class MarkRead {
        @Test
        @DisplayName("markTypeRead：类型非法 → 参数错误")
        void testBadType() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.markTypeRead(userId, "x").getCode());
        }

        @Test
        @DisplayName("markTypeRead：有未读 → 标记并失效缓存")
        void testOk() {
            when(notificationMapper.countUnreadByType(userId, 1)).thenReturn(2);

            ResponseResult r = notificationService.markTypeRead(userId, "comment");
            assertEquals(200, r.getCode());
            verify(notificationMapper).markTypeRead(userId, 1);
            // 缓存整体失效，由下一次读取按 DB 重建
            verify(stringRedisTemplate).delete("notif:unread:100");
        }

        @Test
        @DisplayName("markTypeRead：无未读 → 不标记不扣减")
        void testZero() {
            when(notificationMapper.countUnreadByType(userId, 2)).thenReturn(0);

            notificationService.markTypeRead(userId, "digg");
            verify(notificationMapper, never()).markTypeRead(anyLong(), anyInt());
            verify(valueOps, never()).increment(anyString(), anyLong());
        }

        @Test
        @DisplayName("markAllRead：清除 Redis")
        void testMarkAll() {
            when(notificationMapper.markAllRead(userId)).thenReturn(1);
            notificationService.markAllRead(userId);
            verify(notificationMapper).markAllRead(userId);
            verify(stringRedisTemplate).delete("notif:unread:100");
        }
    }

    @Nested
    @DisplayName("createNotification / sendActivityNotification")
    class Create {
        @Test
        @DisplayName("createNotification：参数缺失 → 参数错误")
        void testParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.createNotification(null, 1, "", "").getCode());
        }

        @Test
        @DisplayName("createNotification：成功插入并使未读缓存失效")
        void testOk() {
            when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

            assertEquals(200, notificationService.createNotification(userId, 3, "src", "content").getCode());
            verify(notificationMapper).insert(ArgumentMatchers.<Notification>argThat(n -> n.getType() == 3 && n.getIsRead() == 0));
            // 未读计数以 DB 为唯一源，插入后仅失效整包缓存
            verify(stringRedisTemplate).delete("notif:unread:100");
        }

        @Test
        @DisplayName("sendActivityNotification：参数缺失 → 参数错误")
        void testActivityParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.sendActivityNotification(userId, null, "c", null).getCode());
        }

        @Test
        @DisplayName("sendActivityNotification：成功写入 JSON 内容并使未读缓存失效")
        void testActivityOk() {
            when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

            ResponseResult r = notificationService.sendActivityNotification(userId, "促销", "看看", "/link");
            assertEquals(200, r.getCode());
            verify(notificationMapper).insert(ArgumentMatchers.<Notification>argThat(n -> n.getType() == 4));
            verify(stringRedisTemplate).delete("notif:unread:100");
        }
    }
}