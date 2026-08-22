package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dtos.LoginDto;
import com.heima.model.user.dtos.SocialBindDto;
import com.heima.user.service.ApUserService;
import com.heima.user.service.SocialLoginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ApUserLoginController 单元测试（登录/社交绑定/验证码）
 *
 * 覆盖 login 的三种流程判定（邮箱密码/手机密码/手机验证码），socialBind 参数校验，
 * getCode 的空参数与绑定冲突分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApUserLoginController 登录")
class ApUserLoginControllerTest {

    @Mock
    private ApUserService apUserService;
    @Mock
    private SocialLoginService socialLoginService;

    @InjectMocks
    private ApUserLoginController apUserLoginController;

    private LoginDto dto(String phoneOrEmail, String password) {
        LoginDto d = new LoginDto();
        d.setPhoneOrEmail(phoneOrEmail);
        d.setPassword(password);
        return d;
    }

    @Test
    @DisplayName("邮箱+密码 → tag=emailPass")
    void testEmailPass() {
        when(apUserService.allLoginAuth(any(), anyString())).thenReturn(ResponseResult.okResult("ok"));

        apUserLoginController.login(dto("a@b.com", "123456"));

        ArgumentCaptor<String> tag = ArgumentCaptor.forClass(String.class);
        verify(apUserService).allLoginAuth(any(), tag.capture());
        assertEquals("emailPass", tag.getValue());
    }

    @Test
    @DisplayName("手机号+密码 → tag=phonePass")
    void testPhonePass() {
        when(apUserService.allLoginAuth(any(), anyString())).thenReturn(ResponseResult.okResult("ok"));

        apUserLoginController.login(dto("13800138000", "123456"));

        ArgumentCaptor<String> tag = ArgumentCaptor.forClass(String.class);
        verify(apUserService).allLoginAuth(any(), tag.capture());
        assertEquals("phonePass", tag.getValue());
    }

    @Test
    @DisplayName("手机号无密码 → tag=phoneCode")
    void testPhoneCode() {
        when(apUserService.allLoginAuth(any(), anyString())).thenReturn(ResponseResult.okResult("ok"));

        apUserLoginController.login(dto("13800138000", ""));

        ArgumentCaptor<String> tag = ArgumentCaptor.forClass(String.class);
        verify(apUserService).allLoginAuth(any(), tag.capture());
        assertEquals("phoneCode", tag.getValue());
    }

    @Test
    @DisplayName("socialBind 参数缺失 → PARAM_REQUIRE，不调用 service")
    void testSocialBindBlank() {
        SocialBindDto dto = new SocialBindDto();
        dto.setPlatform("github");
        dto.setPlatformUid("");
        dto.setPhone("");
        dto.setCode("1234");

        ResponseResult r = apUserLoginController.socialBind(dto);

        assertEquals(AppHttpCodeEnum.PARAM_REQUIRE.getCode(), r.getCode());
        verifyNoInteractions(socialLoginService);
    }

    @Test
    @DisplayName("socialBind 参数齐全 → 委托 service")
    void testSocialBindOk() {
        SocialBindDto dto = new SocialBindDto();
        dto.setPlatform("github");
        dto.setPlatformUid("uid");
        dto.setPhone("13800138000");
        dto.setCode("1234");
        when(socialLoginService.socialBind(dto)).thenReturn(ResponseResult.okResult(null));

        apUserLoginController.socialBind(dto);

        verify(socialLoginService).socialBind(dto);
    }

    @Test
    @DisplayName("getCode 手机号/平台为空 → PARAM_INVALID")
    void testGetCodeBlank() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
                apUserLoginController.getCode("", "github", "login").getCode());
    }

    @Test
    @DisplayName("getCode 返回空 → 手机号已绑定其他账号错误")
    void testGetCodeBlankResult() {
        when(socialLoginService.checkSocialBind("13800138000", "github", "bind")).thenReturn("");

        assertEquals(AppHttpCodeEnum.SOCIAL_PHONE_BOUND_OTHER.getCode(),
                apUserLoginController.getCode("13800138000", "github", "bind").getCode());
    }

    @Test
    @DisplayName("getCode 成功 → 透传验证码")
    void testGetCodeOk() {
        when(socialLoginService.checkSocialBind("13800138000", "github", "login")).thenReturn("abcd");

        ResponseResult r = apUserLoginController.getCode("13800138000", "github", "login");
        assertEquals("abcd", r.getData());
    }
}