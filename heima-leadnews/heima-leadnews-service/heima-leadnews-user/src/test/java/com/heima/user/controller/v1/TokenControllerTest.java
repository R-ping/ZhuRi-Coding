package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.user.dtos.LoginResultVo;
import com.heima.model.user.dtos.RefreshTokenDto;
import com.heima.user.service.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TokenController 单元测试（刷新/登出）
 *
 * refresh 成功与 refreshToken 过期（返回 null 兜底），登出委托 revokeRefreshToken。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenController Token管理")
class TokenControllerTest {

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private TokenController tokenController;

    private RefreshTokenDto dto(String token) {
        RefreshTokenDto d = new RefreshTokenDto();
        d.setRefreshToken(token);
        return d;
    }

    @Test
    @DisplayName("刷新成功 → 返回新双Token")
    void testRefreshOk() {
        when(tokenService.refreshToken("rt-1"))
                .thenReturn(LoginResultVo.builder().status("login").build());

        ResponseResult r = tokenController.refreshToken(dto("rt-1"));

        assertNotNull(r.getData());
        assertInstanceOf(LoginResultVo.class, r.getData());
    }

    @Test
    @DisplayName("refresh_token 无效 → TOKEN_INVALID")
    void testRefreshInvalid() {
        when(tokenService.refreshToken("rt-expired")).thenReturn(null);

        ResponseResult r = tokenController.refreshToken(dto("rt-expired"));

        assertEquals(AppHttpCodeEnum.TOKEN_INVALID.getCode(), r.getCode());
    }

    @Test
    @DisplayName("登出 → 吊销 refresh_token 并返回成功")
    void testLogout() {
        ResponseResult r = tokenController.logout(dto("rt-1"));

        assertNotNull(r);
        verify(tokenService).revokeRefreshToken("rt-1");
    }
}