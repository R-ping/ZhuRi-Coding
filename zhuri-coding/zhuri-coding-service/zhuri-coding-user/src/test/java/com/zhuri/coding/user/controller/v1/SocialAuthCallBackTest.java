package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dtos.SocialAuthDto;
import com.heima.user.service.SocialAuthService;
import com.heima.user.service.SocialLoginService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SocialAuthCallBack 单元测试（GitHub/微博 OAuth 授权回调）
 *
 * 验证拿到 code 后换取 token → 拉取用户信息 → 组装 SocialAuthDto → 调用登录全链路，
 * 以及异常时抛出 RuntimeException 并终止。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialAuthCallBack OAuth回调")
class SocialAuthCallBackTest {

    @Mock
    private SocialAuthService socialAuthService;
    @Mock
    private SocialLoginService socialLoginService;

    @InjectMocks
    private SocialAuthCallBack socialAuthCallBack;

    @Test
    @DisplayName("GitHub 回调：换取token→拉取用户信息→登录")
    void testGithub() {
        when(socialAuthService.getAccessToken2Github("code-g")).thenReturn("at");
        Map<String, Object> info = new HashMap<>();
        info.put("id", 12345L);
        when(socialAuthService.getPlatFormUserInfo("at")).thenReturn(info);
        when(socialLoginService.socialAuth(any(SocialAuthDto.class)))
                .thenReturn(ResponseResult.okResult(null));

        ResponseResult r = socialAuthCallBack.github("code-g");

        assertNotNull(r);
        ArgumentCaptor<SocialAuthDto> captor = ArgumentCaptor.forClass(SocialAuthDto.class);
        verify(socialLoginService).socialAuth(captor.capture());
        assertEquals("github", captor.getValue().getPlatform());
        assertEquals("12345", captor.getValue().getPlatformUid());
    }

    @Test
    @DisplayName("GitHub 回调异常 → 抛出 RuntimeException")
    void testGithubException() {
        when(socialAuthService.getAccessToken2Github("code-g")).thenThrow(new RuntimeException("bad code"));

        assertThrows(RuntimeException.class, () -> socialAuthCallBack.github("code-g"));
    }

    @Test
    @DisplayName("微博回调：getStraightUid2Weibo → 登录")
    void testWeibo() {
        when(socialAuthService.getStraightUid2Weibo("code-w")).thenReturn("uid-1");
        when(socialLoginService.socialAuth(any(SocialAuthDto.class)))
                .thenReturn(ResponseResult.okResult(null));

        ResponseResult r = socialAuthCallBack.weibo("code-w");

        assertNotNull(r);
        ArgumentCaptor<SocialAuthDto> captor = ArgumentCaptor.forClass(SocialAuthDto.class);
        verify(socialLoginService).socialAuth(captor.capture());
        assertEquals("weibo", captor.getValue().getPlatform());
        assertEquals("uid-1", captor.getValue().getPlatformUid());
    }

    @Test
    @DisplayName("微博回调异常 → 抛出 RuntimeException")
    void testWeiboException() {
        when(socialAuthService.getStraightUid2Weibo("code-w")).thenThrow(new RuntimeException("err"));

        assertThrows(RuntimeException.class, () -> socialAuthCallBack.weibo("code-w"));
    }
}