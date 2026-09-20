package com.heima.notification.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.notification.dtos.NotificationDto;
import com.heima.model.user.pojos.ApUser;
import com.heima.notification.service.NotificationService;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationController 单元测试
 *
 * 覆盖通知列表、互动操作（回复/点赞/回关）、未读/已读、以及 Feign 内部接口。
 * 重点验证参数缺失时的参数校验兜底，以及未登录时的 NEED_LOGIN 拦截。
 */
@DisplayName("NotificationController 通知接口")
class NotificationControllerTest {

    private NotificationService notificationService;
    private NotificationController controller;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        controller = new NotificationController();
        try {
            var field = NotificationController.class.getDeclaredField("notificationService");
            field.setAccessible(true);
            field.set(controller, notificationService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void login(Long userId) {
        ApUser user = new ApUser();
        user.setId(userId.intValue());
        AppThreadLocalUtil.setUser(user);
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i].toString(), kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("list → 透传查询 DTO 调用服务")
    void testList() {
        NotificationDto dto = new NotificationDto();
        ResponseResult expected = ResponseResult.okResult();
        when(notificationService.list(dto)).thenReturn(expected);

        ResponseResult actual = controller.list(dto);
        assertSame(expected, actual);
        verify(notificationService).list(dto);
    }

    @Test
    @DisplayName("reply → 参数齐全时调用服务")
    void testReplyOk() {
        login(1L);
        when(notificationService.reply(1L, 99L, "hi")).thenReturn(ResponseResult.okResult());

        controller.reply(body("comment_id", 99L, "content", "hi"));
        verify(notificationService).reply(1L, 99L, "hi");
    }

    @Test
    @DisplayName("reply → comment_id 缺失返回参数错误")
    void testReplyMissingCommentId() {
        login(1L);
        ResponseResult actual = controller.reply(body("content", "hi"));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
        verify(notificationService, org.mockito.Mockito.never()).reply(any(), any(), any());
    }

    @Test
    @DisplayName("reply → content 缺失返回参数错误")
    void testReplyMissingContent() {
        login(1L);
        ResponseResult actual = controller.reply(body("comment_id", 5L));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("like → 参数齐全时调用服务")
    void testLikeOk() {
        login(2L);
        when(notificationService.toggleLike(2L, 7L)).thenReturn(ResponseResult.okResult());

        controller.like(body("comment_id", 7L));
        verify(notificationService).toggleLike(2L, 7L);
    }

    @Test
    @DisplayName("like → comment_id 缺失返回参数错误")
    void testLikeMissingCommentId() {
        login(2L);
        ResponseResult actual = controller.like(body());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("followBack → 参数齐全时调用服务")
    void testFollowBackOk() {
        login(3L);
        when(notificationService.followBack(3L, 10L)).thenReturn(ResponseResult.okResult());

        controller.followBack(body("follower_id", 10L));
        verify(notificationService).followBack(3L, 10L);
    }

    @Test
    @DisplayName("followBack → follower_id 缺失返回参数错误")
    void testFollowBackMissing() {
        login(3L);
        ResponseResult actual = controller.followBack(body());
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("unreadCount → 已登录时调用服务")
    void testUnreadCountOk() {
        login(4L);
        when(notificationService.unreadCount(4L)).thenReturn(ResponseResult.okResult());

        controller.unreadCount();
        verify(notificationService).unreadCount(4L);
    }

    @Test
    @DisplayName("unreadCount → 未登录返回 NEED_LOGIN")
    void testUnreadCountNoLogin() {
        ResponseResult actual = controller.unreadCount();
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("markAllRead → 已登录时调用服务")
    void testMarkAllReadOk() {
        login(5L);
        when(notificationService.markAllRead(5L)).thenReturn(ResponseResult.okResult());

        controller.markAllRead();
        verify(notificationService).markAllRead(5L);
    }

    @Test
    @DisplayName("markAllRead → 未登录返回 NEED_LOGIN")
    void testMarkAllReadNoLogin() {
        ResponseResult actual = controller.markAllRead();
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("markTypeRead → 已登录时透传类型")
    void testMarkTypeReadOk() {
        login(6L);
        when(notificationService.markTypeRead(6L, "comment")).thenReturn(ResponseResult.okResult());

        controller.markTypeRead("comment");
        verify(notificationService).markTypeRead(6L, "comment");
    }

    @Test
    @DisplayName("markTypeRead → 未登录返回 NEED_LOGIN")
    void testMarkTypeReadNoLogin() {
        ResponseResult actual = controller.markTypeRead("comment");
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("createNotification → 参数齐全时调用服务")
    void testCreateNotificationOk() {
        when(notificationService.createNotification(8L, 1, "abc", "content"))
                .thenReturn(ResponseResult.okResult());

        controller.createNotification(body("userId", 8L, "type", 1, "sourceId", "abc", "content", "content"));
        verify(notificationService).createNotification(8L, 1, "abc", "content");
    }

    @Test
    @DisplayName("createNotification → userId/type 缺失返回参数错误")
    void testCreateNotificationMissing() {
        ResponseResult actual = controller.createNotification(body("userId", 8L));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("createNotification → sourceId/content 缺失时传 null")
    void testCreateNotificationNullExtras() {
        when(notificationService.createNotification(eq(8L), eq(2), any(), any()))
                .thenReturn(ResponseResult.okResult());

        controller.createNotification(body("userId", 8L, "type", 2));
        verify(notificationService).createNotification(8L, 2, null, null);
    }

    @Test
    @DisplayName("incrUnread → 透传 userId 调用服务")
    void testIncrUnread() {
        doNothing().when(notificationService).incrUnreadCache(9L);
        controller.incrUnread(9L);
        verify(notificationService).incrUnreadCache(9L);
    }

    @Test
    @DisplayName("sendActivityNotification → 参数齐全时调用服务")
    void testSendActivityOk() {
        when(notificationService.sendActivityNotification(12L, "t", "c", "l"))
                .thenReturn(ResponseResult.okResult());

        controller.sendActivityNotification(body("userId", 12L, "title", "t", "content", "c", "link", "l"));
        verify(notificationService).sendActivityNotification(12L, "t", "c", "l");
    }

    @Test
    @DisplayName("sendActivityNotification → userId 缺失返回参数错误")
    void testSendActivityMissingUserId() {
        ResponseResult actual = controller.sendActivityNotification(body("title", "t"));
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(), actual.getCode());
    }

    @Test
    @DisplayName("sendActivityNotification → title/content/link 缺失时用空串兜底")
    void testSendActivityDefaultBlanks() {
        when(notificationService.sendActivityNotification(eq(12L), any(), any(), any()))
                .thenReturn(ResponseResult.okResult());

        controller.sendActivityNotification(body("userId", 12L));
        verify(notificationService).sendActivityNotification(12L, "", "", "");
    }
}