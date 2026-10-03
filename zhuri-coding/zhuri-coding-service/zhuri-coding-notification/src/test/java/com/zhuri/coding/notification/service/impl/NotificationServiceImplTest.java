package com.zhuri.coding.notification.service.impl;

import com.zhuri.coding.apis.article.ICommentClient;
import com.zhuri.coding.apis.article.IFollowClient;
import com.zhuri.coding.model.comment.pojos.ApComment;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.notification.dtos.NotificationDto;
import com.zhuri.coding.model.notification.pojos.Notification;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.notification.mapper.NotificationMapper;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
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
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private final Long userId = 100L;

    @BeforeEach
    void setUp() {
        // 部分测试不触碰 Redis，故对 opsForValue() 打桩放宽为 lenient，避免 UnnecessaryStubbing
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
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
            // "50" 是旧格式的纯 id 游标，没有分隔符 → 解析失败退回第一页
            when(notificationMapper.selectByTypeAndCursor(userId, 1, null, null, 10)).thenReturn(List.of(n));

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
            when(notificationMapper.selectByTypeAndCursor(eq(userId), eq(2), isNull(), isNull(), anyInt()))
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
        @DisplayName("Redis 无缓存 → 用 DB 分组总数并写回 Hash 缓存")
        void testFromDb() {
            when(hashOps.entries("notif:unread:100")).thenReturn(Map.of());
            Map<String, Object> row = new HashMap<>();
            row.put("type", 1);
            row.put("count", 3);
            when(notificationMapper.countUnreadGroupByType(userId)).thenReturn(List.of(row));

            Map<String, Object> data = (Map<String, Object>) notificationService.unreadCount(userId).getData();
            assertEquals(3, data.get("total"));
            assertEquals(3, data.get("comment"));
            assertEquals(0, data.get("digg"));
            // 写回的是各类型明细齐全的基线——后续按类型增量才有得可加
            verify(hashOps).putAll(eq("notif:unread:100"), argThat(m ->
                    Integer.valueOf(3).equals(m.get("total")) && Integer.valueOf(3).equals(m.get("comment"))));
            verify(stringRedisTemplate).expire("notif:unread:100", 5, TimeUnit.MINUTES);
        }

        @Test
        @DisplayName("Redis 有 Hash 缓存 → 直接返回，不触达 DB")
        void testFromRedis() {
            Map<Object, Object> entries = new HashMap<>();
            entries.put("total", "10");
            entries.put("comment", "0");
            when(hashOps.entries("notif:unread:100")).thenReturn(entries);

            Map<String, Object> data = (Map<String, Object>) notificationService.unreadCount(userId).getData();
            assertEquals(10, data.get("total"));
            assertEquals(0, data.get("comment"));
            // 缺失的类型字段补 0
            assertEquals(0, data.get("digg"));
            // 命中缓存，不再查询 DB（防止 UnnecessaryStubbing 不再打桩 countUnreadGroupByType）
            verify(notificationMapper, never()).countUnreadGroupByType(anyLong());
        }
    }

    @Nested
    @DisplayName("incrUnreadCache 按类型增量")
    class IncrUnread {
        @Test
        @DisplayName("已有基线 → 按类型原子增量，不再整包失效")
        void testIncrement() {
            when(hashOps.size("notif:unread:100")).thenReturn(5L);

            notificationService.incrUnreadCache(userId, 1);

            verify(hashOps).increment("notif:unread:100", "total", 1L);
            verify(hashOps).increment("notif:unread:100", "comment", 1L);
            verify(stringRedisTemplate, never()).delete("notif:unread:100");
        }

        @Test
        @DisplayName("没有基线 → 跳过增量，避免写出只含部分类型的残缺计数")
        void testSkipWhenNoBaseline() {
            when(hashOps.size("notif:unread:100")).thenReturn(0L);

            notificationService.incrUnreadCache(userId, 1);

            verify(hashOps, never()).increment(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("Redis 抛异常 → 回退为整包失效，下次按 DB 重建")
        void testFallbackOnError() {
            when(hashOps.size("notif:unread:100")).thenThrow(new RuntimeException("redis down"));

            notificationService.incrUnreadCache(userId, 1);

            verify(stringRedisTemplate).delete("notif:unread:100");
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
        @DisplayName("createNotification：评论类不聚合 → 直接插入并按类型增量未读")
        void testOk() {
            when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
            when(hashOps.size("notif:unread:100")).thenReturn(5L);

            assertEquals(200, notificationService.createNotification(userId, 1, "src", "content").getCode());
            verify(notificationMapper).insert(ArgumentMatchers.<Notification>argThat(
                    n -> n.getType() == 1 && n.getIsRead() == 0 && n.getAggKey() == null));
            verify(notificationMapper, never()).upsertAggregated(any());
            // type=1 → comment。按类型增量而非整包失效——整包失效会让活跃用户的缓存一直被冲掉
            verify(hashOps).increment("notif:unread:100", "comment", 1L);
            verify(hashOps).increment("notif:unread:100", "total", 1L);
            verify(stringRedisTemplate, never()).delete("notif:unread:100");
        }

        @Test
        @DisplayName("createNotification：聚合类型但 sourceId 为空 → 退化为普通插入")
        void testAggregatableButNoSource() {
            when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
            when(hashOps.size("notif:unread:100")).thenReturn(5L);

            assertEquals(200, notificationService.createNotification(userId, 2, null, "content").getCode());
            verify(notificationMapper).insert(any(Notification.class));
            verify(notificationMapper, never()).upsertAggregated(any());
        }

        @Test
        @DisplayName("sendActivityNotification：参数缺失 → 参数错误")
        void testActivityParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), notificationService.sendActivityNotification(userId, null, "c", null).getCode());
        }

        @Test
        @DisplayName("sendActivityNotification：成功写入 JSON 内容并按类型增量未读")
        void testActivityOk() {
            when(notificationMapper.insert(any(Notification.class))).thenReturn(1);
            when(hashOps.size("notif:unread:100")).thenReturn(5L);

            ResponseResult r = notificationService.sendActivityNotification(userId, "促销", "看看", "/link");
            assertEquals(200, r.getCode());
            verify(notificationMapper).insert(ArgumentMatchers.<Notification>argThat(n -> n.getType() == 4));
            // 系统通知固定 type=4 → system
            verify(hashOps).increment("notif:unread:100", "system", 1L);
        }
    }

    @Nested
    @DisplayName("通知聚合：同一 (user, type, source) 只占一行")
    class Aggregate {

        private void givenCacheExists() {
            when(hashOps.size("notif:unread:100")).thenReturn(5L);
        }

        @Test
        @DisplayName("首次事件：upsert 返回 1（新行）→ 未读 +1")
        void testFirstEvent() {
            givenCacheExists();
            when(notificationMapper.flipToUnread(userId, "2:art-1")).thenReturn(0);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(1);

            assertEquals(200, notificationService.createNotification(userId, 2, "art-1", "{}").getCode());

            verify(notificationMapper).upsertAggregated(ArgumentMatchers.<Notification>argThat(
                    n -> "2:art-1".equals(n.getAggKey()) && n.getLastEventAt() != null));
            verify(notificationMapper, never()).insert(any(Notification.class));
            verify(hashOps).increment("notif:unread:100", "digg", 1L);
        }

        @Test
        @DisplayName("合并进未读行：upsert 返回 2、flip 0 → 未读数不该变")
        void testMergeIntoUnread() {
            when(notificationMapper.flipToUnread(userId, "2:art-1")).thenReturn(0);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(2);

            assertEquals(200, notificationService.createNotification(userId, 2, "art-1", "{}").getCode());

            // 合并进本来就没读的行：未读数一个字都不该动，连缓存都不该碰
            verifyNoInteractions(hashOps);
            verify(stringRedisTemplate, never()).delete("notif:unread:100");
        }

        @Test
        @DisplayName("合并进已读行：flip 命中 1 → 翻回未读，未读 +1（最新的也冒出来）")
        void testMergeIntoRead() {
            givenCacheExists();
            when(notificationMapper.flipToUnread(userId, "2:art-1")).thenReturn(1);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(2);

            assertEquals(200, notificationService.createNotification(userId, 2, "art-1", "{}").getCode());

            // flip 必须在 upsert 之前：upsert 之后这行必然是未读，就再也问不出"原来是不是已读"
            InOrder inOrder = inOrder(notificationMapper);
            inOrder.verify(notificationMapper).flipToUnread(userId, "2:art-1");
            inOrder.verify(notificationMapper).upsertAggregated(any(Notification.class));

            verify(hashOps).increment("notif:unread:100", "digg", 1L);
            verify(hashOps).increment("notif:unread:100", "total", 1L);
        }

        @Test
        @DisplayName("粉丝通知同样聚合，且 agg_key 用 type 前缀与点赞区分开")
        void testFollowAggKey() {
            givenCacheExists();
            when(notificationMapper.flipToUnread(userId, "3:9527")).thenReturn(0);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(1);

            notificationService.createNotification(userId, 3, "9527", "{}");

            verify(notificationMapper).upsertAggregated(ArgumentMatchers.<Notification>argThat(
                    n -> "3:9527".equals(n.getAggKey())));
            verify(hashOps).increment("notif:unread:100", "follow", 1L);
        }
    }

    @Nested
    @DisplayName("收藏文章更新提醒：按「用户 × 自然日」聚合")
    class CollectUpdate {

        @Test
        @DisplayName("参数缺失 → 参数错误")
        void testParam() {
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                    notificationService.createCollectUpdateNotification(null, "2026-10-03", "{}").getCode());
            assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                    notificationService.createCollectUpdateNotification(userId, " ", "{}").getCode());
        }

        @Test
        @DisplayName("首次事件：agg_key=collect_update:{day}、type=4 系统类 → 未读 +1")
        void testFirstEvent() {
            when(hashOps.size("notif:unread:100")).thenReturn(5L);
            when(notificationMapper.flipToUnread(userId, "collect_update:2026-10-03")).thenReturn(0);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(1);

            assertEquals(200, notificationService
                    .createCollectUpdateNotification(userId, "2026-10-03", "{\"title\":\"t\"}").getCode());

            verify(notificationMapper).upsertAggregated(ArgumentMatchers.<Notification>argThat(
                    n -> "collect_update:2026-10-03".equals(n.getAggKey())
                            && n.getType() == 4
                            && "2026-10-03".equals(n.getSourceId())
                            && n.getLastEventAt() != null));
            // 刻意不走 insertPlain：同一天的多条更新必须合并进同一行
            verify(notificationMapper, never()).insert(any(Notification.class));
            verify(hashOps).increment("notif:unread:100", "system", 1L);
        }

        @Test
        @DisplayName("合并进已读行：flip 命中 → 翻回未读，未读 +1")
        void testMergeIntoRead() {
            when(hashOps.size("notif:unread:100")).thenReturn(5L);
            when(notificationMapper.flipToUnread(userId, "collect_update:2026-10-03")).thenReturn(1);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(2);

            notificationService.createCollectUpdateNotification(userId, "2026-10-03", "{}");

            // flip 必须早于 upsert，否则再也问不出"原来是不是已读"
            InOrder inOrder = inOrder(notificationMapper);
            inOrder.verify(notificationMapper).flipToUnread(userId, "collect_update:2026-10-03");
            inOrder.verify(notificationMapper).upsertAggregated(any(Notification.class));
            verify(hashOps).increment("notif:unread:100", "system", 1L);
        }

        @Test
        @DisplayName("合并进未读行：未读数不该变，不触碰缓存")
        void testMergeIntoUnread() {
            when(notificationMapper.flipToUnread(userId, "collect_update:2026-10-03")).thenReturn(0);
            when(notificationMapper.upsertAggregated(any(Notification.class))).thenReturn(2);

            notificationService.createCollectUpdateNotification(userId, "2026-10-03", "{}");

            verifyNoInteractions(hashOps);
            verify(stringRedisTemplate, never()).delete("notif:unread:100");
        }
    }

    @Nested
    @DisplayName("复合游标：(last_event_at, id)")
    class Cursor {

        @Test
        @DisplayName("合法复合游标 → 拆成 last_event_at + id 传给 Mapper")
        void testCompositeCursor() {
            NotificationDto dto = new NotificationDto();
            dto.setType("digg");
            dto.setSize(1);
            dto.setCursor("2026-09-01T10:00:00_50");

            Notification n = notif(50L, 2, 0, "{}");
            n.setLastEventAt(LocalDateTime.parse("2026-09-01T10:00:00"));
            when(notificationMapper.selectByTypeAndCursor(
                    eq(userId), eq(2), eq(LocalDateTime.parse("2026-09-01T10:00:00")), eq(50L), eq(1)))
                    .thenReturn(List.of(n));

            Map<String, Object> data = (Map<String, Object>) notificationService.list(dto).getData();
            assertEquals("2026-09-01T10:00:00_50", data.get("next_cursor"));
        }

        @Test
        @DisplayName("返回体带 agg_count 与 last_event_at，供前端显示「等 N 人」")
        void testAggCountInPayload() {
            NotificationDto dto = new NotificationDto();
            dto.setType("digg");
            dto.setSize(1);

            Notification n = notif(70L, 2, 0, "{}");
            n.setAggCount(37);
            n.setLastEventAt(LocalDateTime.parse("2026-09-28T20:00:00"));
            when(notificationMapper.selectByTypeAndCursor(eq(userId), eq(2), isNull(), isNull(), eq(1)))
                    .thenReturn(List.of(n));

            Map<String, Object> data = (Map<String, Object>) notificationService.list(dto).getData();
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            assertEquals(37, list.get(0).get("agg_count"));
            assertEquals("2026-09-28T20:00:00", list.get(0).get("last_event_at"));
        }
    }
}