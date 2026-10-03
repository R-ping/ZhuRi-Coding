package com.zhuri.coding.content.controller.v1.coding;

import com.zhuri.coding.content.service.coding.CodingProfileService;
import com.zhuri.coding.model.coding.dtos.CodingProfileSettingDTO;
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
 * CodingProfileController 单元测试（Coding 延展第二层 · Stage A）
 *
 * 覆盖：登录门禁（me/setting 需登录，公开档案匿名放行）、
 * 本人/访客视角参数传递（viewerUserId 取 ThreadLocal 当前用户）。
 */
@ExtendWith(MockitoExtension.class)
class CodingProfileControllerTest {

    @Mock
    private CodingProfileService profileService;

    @InjectMocks
    private CodingProfileController controller;

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
    @DisplayName("未登录 - me/setting 读写均返回 NEED_LOGIN，不触达服务")
    void testNeedLogin() {
        AppThreadLocalUtil.clear();

        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.me().getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(), controller.getSetting().getCode());
        assertEquals(AppHttpCodeEnum.NEED_LOGIN.getCode(),
            controller.updateSetting(new CodingProfileSettingDTO()).getCode());
        verify(profileService, never()).profile(any(), any());
        verify(profileService, never()).getSetting(any());
        verify(profileService, never()).updateSetting(any(), any());
    }

    @Test
    @DisplayName("已登录 - me 委托本人视角（target=viewer=当前用户）")
    void testMeDelegatesSelf() {
        AppThreadLocalUtil.setUser(loggedUser());
        when(profileService.profile(1, 1)).thenReturn(ResponseResult.okResult());

        ResponseResult result = controller.me();

        assertEquals(200, result.getCode().intValue());
        verify(profileService).profile(1, 1);
    }

    @Test
    @DisplayName("公开档案 - 匿名访问 viewerUserId 为空（白名单匿名可达）")
    void testAbilityAnonymous() {
        AppThreadLocalUtil.clear();
        when(profileService.profile(5, null)).thenReturn(ResponseResult.okResult());

        ResponseResult result = controller.ability(5);

        assertEquals(200, result.getCode().intValue());
        verify(profileService).profile(5, null);
    }

    @Test
    @DisplayName("公开档案 - 携带登录身份时 viewerUserId 为当前用户（本人可看未公开档案）")
    void testAbilityWithLogin() {
        AppThreadLocalUtil.setUser(loggedUser());
        when(profileService.profile(5, 1)).thenReturn(ResponseResult.okResult());

        controller.ability(5);

        verify(profileService).profile(5, 1);
    }

    @Test
    @DisplayName("隐私开关 - 读取/保存委托当前登录用户")
    void testSettingDelegation() {
        AppThreadLocalUtil.setUser(loggedUser());
        when(profileService.getSetting(1)).thenReturn(ResponseResult.okResult());
        CodingProfileSettingDTO dto = new CodingProfileSettingDTO();
        dto.setIsPublic(true);
        when(profileService.updateSetting(1, dto)).thenReturn(ResponseResult.okResult());

        assertEquals(200, controller.getSetting().getCode().intValue());
        assertEquals(200, controller.updateSetting(dto).getCode().intValue());

        verify(profileService).getSetting(1);
        verify(profileService).updateSetting(1, dto);
    }
}