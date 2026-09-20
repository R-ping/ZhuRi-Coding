package com.zhuri.coding.notification.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.notification.dtos.ImMessageDto;
import com.zhuri.coding.model.notification.dtos.ImReadDto;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.notification.service.ImService;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImController 单元测试
 *
 * 验证 5 个私信接口正确从线程本地取出 userId 并向 ImService 透传参数，
 * 以及未登录（无用户）时传递 null userId 的兜底行为。
 */
@DisplayName("ImController 私信接口")
class ImControllerTest {

    private ImService imService;
    private ImController controller;

    @BeforeEach
    void setUp() {
        imService = mock(ImService.class);
        controller = new ImController();
        // 反射注入私有依赖
        try {
            var field = ImController.class.getDeclaredField("imService");
            field.setAccessible(true);
            field.set(controller, imService);
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

    @Test
    @DisplayName("listSessions → 携带当前 userId 调用服务")
    void testListSessions() {
        login(100L);
        ResponseResult expected = ResponseResult.okResult();
        when(imService.listSessions(100L)).thenReturn(expected);

        ResponseResult actual = controller.listSessions();
        assertSame(expected, actual);
        verify(imService).listSessions(100L);
    }

    @Test
    @DisplayName("getOrCreateSession → 透传 peerId 调用服务")
    void testGetOrCreateSession() {
        login(7L);
        when(imService.getOrCreateSession(7L, 200L)).thenReturn(ResponseResult.okResult());

        controller.getOrCreateSession(200L);
        verify(imService).getOrCreateSession(7L, 200L);
    }

    @Test
    @DisplayName("listMessages → 透传会话/游标/分页参数")
    void testListMessages() {
        login(3L);
        when(imService.listMessages(3L, 9L, 5L, 20)).thenReturn(ResponseResult.okResult());

        controller.listMessages(9L, 5L, 20);
        verify(imService).listMessages(3L, 9L, 5L, 20);
    }

    @Test
    @DisplayName("sendMessage → 透传 ImMessageDto 调用服务")
    void testSendMessage() {
        login(50L);
        ImMessageDto dto = new ImMessageDto();
        when(imService.sendMessage(eq(50L), any(ImMessageDto.class))).thenReturn(ResponseResult.okResult());

        controller.sendMessage(dto);
        verify(imService).sendMessage(50L, dto);
    }

    @Test
    @DisplayName("markRead → 透传 ImReadDto 调用服务")
    void testMarkRead() {
        login(11L);
        ImReadDto dto = new ImReadDto();
        when(imService.markRead(eq(11L), any(ImReadDto.class))).thenReturn(ResponseResult.okResult());

        controller.markRead(dto);
        verify(imService).markRead(11L, dto);
    }

    @Test
    @DisplayName("未登录 → userId 传 null 调用服务")
    void testNotLoggedIn() {
        ResponseResult expected = ResponseResult.okResult();
        when(imService.listSessions(null)).thenReturn(expected);

        ResponseResult actual = controller.listSessions();
        assertSame(expected, actual);
        verify(imService).listSessions(null);
    }
}