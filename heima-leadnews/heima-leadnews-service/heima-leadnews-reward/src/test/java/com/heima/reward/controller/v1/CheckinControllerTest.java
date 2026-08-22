package com.heima.reward.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.pojos.ApUser;
import com.heima.reward.service.CheckinService;
import com.heima.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CheckinController 回归测试
 *
 * 核心安全诉求：签到接口从可信线程(AppThreadLocalUtil)取当前登录用户，
 * 废弃"未登录缺省为 1L"的旧逻辑，防止未登录匿名冒充他人签到/补签。
 */
class CheckinControllerTest {

    @Mock
    private CheckinService checkinService;

    @InjectMocks
    private CheckinController checkinController;

    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    private final ApUser user = new ApUser();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        user.setId(100);
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
    }

    @AfterEach
    void tearDown() {
        threadLocalMock.close();
    }

    // ==================== 未登录保护 ====================

    @Test
    @DisplayName("status - 未登录返回NEED_LOGIN，不调用服务")
    void testStatusNotLogin() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = checkinController.status();

        assertEquals(1, result.getCode());
        verify(checkinService, never()).getStatus(anyLong());
    }

    @Test
    @DisplayName("doCheckin - 未登录返回NEED_LOGIN")
    void testDoCheckinNotLogin() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = checkinController.doCheckin();

        assertEquals(1, result.getCode());
        verify(checkinService, never()).doCheckin(anyLong());
    }

    @Test
    @DisplayName("doExtra - 未登录返回NEED_LOGIN")
    void testDoExtraNotLogin() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);
        Map<String, String> body = new HashMap<>();
        body.put("date", "2026-08-15");

        ResponseResult result = checkinController.doExtra(body);

        assertEquals(1, result.getCode());
        verify(checkinService, never()).doExtra(anyLong(), eq("2026-08-15"));
    }

    @Test
    @DisplayName("todayStatus - 未登录返回NEED_LOGIN")
    void testTodayStatusNotLogin() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(null);

        ResponseResult result = checkinController.todayStatus();

        assertEquals(1, result.getCode());
        verify(checkinService, never()).getTodayStatus(anyLong());
    }

    // ==================== 已登录委托 ====================

    @Test
    @DisplayName("status - 已登录委托服务并返回结果")
    void testStatusWithUser() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
        ResponseResult expected = ResponseResult.okResult("status-data");
        when(checkinService.getStatus(100L)).thenReturn(expected);

        ResponseResult result = checkinController.status();

        assertEquals(200, result.getCode());
        verify(checkinService).getStatus(100L);
    }

    @Test
    @DisplayName("doCheckin - 已登录委托服务")
    void testDoCheckinWithUser() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
        ResponseResult expected = ResponseResult.okResult("checkin-ok");
        when(checkinService.doCheckin(100L)).thenReturn(expected);

        ResponseResult result = checkinController.doCheckin();

        assertEquals(200, result.getCode());
        verify(checkinService).doCheckin(100L);
    }

    @Test
    @DisplayName("doExtra - 缺少date返回400")
    void testDoExtraMissingDate() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
        Map<String, String> body = new HashMap<>();

        ResponseResult result = checkinController.doExtra(body);

        assertEquals(400, result.getCode());
        verify(checkinService, never()).doExtra(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("doExtra - 已登录且含date委托服务")
    void testDoExtraWithUser() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
        Map<String, String> body = new HashMap<>();
        body.put("date", "2026-08-15");
        ResponseResult expected = ResponseResult.okResult("extra-ok");
        when(checkinService.doExtra(100L, "2026-08-15")).thenReturn(expected);

        ResponseResult result = checkinController.doExtra(body);

        assertEquals(200, result.getCode());
        verify(checkinService).doExtra(100L, "2026-08-15");
    }

    @Test
    @DisplayName("todayStatus - 已登录委托服务")
    void testTodayStatusWithUser() {
        threadLocalMock.when(AppThreadLocalUtil::getUser).thenReturn(user);
        ResponseResult expected = ResponseResult.okResult("today-ok");
        when(checkinService.getTodayStatus(100L)).thenReturn(expected);

        ResponseResult result = checkinController.todayStatus();

        assertEquals(200, result.getCode());
        verify(checkinService).getTodayStatus(100L);
    }
}