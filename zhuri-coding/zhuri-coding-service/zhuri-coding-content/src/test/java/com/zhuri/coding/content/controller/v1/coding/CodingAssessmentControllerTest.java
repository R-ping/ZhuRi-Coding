package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingAssessmentService;
import com.zhuri.coding.model.coding.dtos.CodingAssessmentSubmitDTO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodingAssessmentController 单元测试（Coding 延展第二层 · Stage B）
 *
 * 覆盖：登录门禁（5 个端点均需登录）与登录后按 ThreadLocal 用户委托服务。
 */
@ExtendWith(MockitoExtension.class)
class CodingAssessmentControllerTest {

    @Mock
    private CodingAssessmentService assessmentService;

    @InjectMocks
    private CodingAssessmentController controller;

    private ApUser loggedUser() {
        ApUser user = new ApUser();
        user.setId(1);
        user.setNickname("张三");
        return user;
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Test
    @DisplayName("未登录 - 5 个端点均返回 NEED_LOGIN，不触达服务")
    void testNeedLogin() {
        AppThreadLocalUtil.clear();

        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.start().getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.current().getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
            controller.submit(new CodingAssessmentSubmitDTO()).getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.latest().getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.history(1, 10).getCode());
        verify(assessmentService, never()).start(any());
        verify(assessmentService, never()).submit(any(), any());
    }

    @Test
    @DisplayName("已登录 - 开卷/进行中/最近成绩委托当前用户")
    void testDelegation() {
        AppThreadLocalUtil.setUser(loggedUser());
        when(assessmentService.start(1)).thenReturn(ResponseResult.okResult());
        when(assessmentService.current(1)).thenReturn(ResponseResult.okResult());
        when(assessmentService.latest(1)).thenReturn(ResponseResult.okResult());
        when(assessmentService.history(1, 2, 5)).thenReturn(ResponseResult.okResult());

        assertEquals(200, controller.start().getCode().intValue());
        assertEquals(200, controller.current().getCode().intValue());
        assertEquals(200, controller.latest().getCode().intValue());
        assertEquals(200, controller.history(2, 5).getCode().intValue());

        verify(assessmentService).start(1);
        verify(assessmentService).current(1);
        verify(assessmentService).latest(1);
        verify(assessmentService).history(1, 2, 5);
    }

    @Test
    @DisplayName("已登录 - 交卷委托当前用户与入参")
    void testSubmitDelegation() {
        AppThreadLocalUtil.setUser(loggedUser());
        CodingAssessmentSubmitDTO dto = new CodingAssessmentSubmitDTO();
        dto.setAssessmentId(9L);
        when(assessmentService.submit(1, dto)).thenReturn(ResponseResult.okResult());

        assertEquals(200, controller.submit(dto).getCode().intValue());
        verify(assessmentService).submit(1, dto);
    }
}