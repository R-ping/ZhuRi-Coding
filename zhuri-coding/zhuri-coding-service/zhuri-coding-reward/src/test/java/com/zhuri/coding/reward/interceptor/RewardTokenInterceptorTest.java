package com.zhuri.coding.reward.interceptor;

import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.common.AppJwtUtil;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * RewardTokenInterceptor 回归测试
 *
 * 核心安全诉求：阻断"身份伪造"导致的越权。
 * - 只信任 accToken 中解析出的可信 userId，忽略可被伪造的 header/path 参数；
 * - 无效/过期/缺失 token 一律不注入用户线程（按匿名处理）。
 * 测试通过 MockedStatic 桩化 AppJwtUtil（避免依赖 JWT_SECRET 运行环境）与 AppThreadLocalUtil。
 */
class RewardTokenInterceptorTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final RewardTokenInterceptor interceptor = new RewardTokenInterceptor();

    private MockedStatic<AppJwtUtil> jwtMock;
    private MockedStatic<AppThreadLocalUtil> threadLocalMock;

    @AfterEach
    void tearDown() {
        if (jwtMock != null) {
            jwtMock.close();
        }
        if (threadLocalMock != null) {
            threadLocalMock.close();
        }
    }

    @Test
    @DisplayName("有效 accToken(含userId) - 注入可信用户线程")
    void testValidTokenInjectsUser() {
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(100L);
        when(claims.get("nickName")).thenReturn("测试昵称");

        jwtMock = Mockito.mockStatic(AppJwtUtil.class);
        jwtMock.when(() -> AppJwtUtil.getClaimsBody("valid-token")).thenReturn(claims);
        jwtMock.when(() -> AppJwtUtil.verifyToken(claims)).thenReturn(-1);

        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn("valid-token");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        ArgumentCaptor<ApUser> captor = ArgumentCaptor.forClass(ApUser.class);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(captor.capture()));
        ApUser user = captor.getValue();
        assertEquals(100, user.getId());
        assertEquals("测试昵称", user.getNickname());
    }

    @Test
    @DisplayName("有效 token 但无 userId claim - 不注入用户")
    void testTokenWithoutUserIdDoesNotInject() {
        Claims claims = mock(Claims.class);
        when(claims.get("userId")).thenReturn(null);

        jwtMock = Mockito.mockStatic(AppJwtUtil.class);
        jwtMock.when(() -> AppJwtUtil.getClaimsBody("no-user-token")).thenReturn(claims);
        jwtMock.when(() -> AppJwtUtil.verifyToken(claims)).thenReturn(-1);

        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn("no-user-token");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(any(ApUser.class)), never());
    }

    @Test
    @DisplayName("过期/无效 token(verifyToken>0) - 不注入用户")
    void testExpiredTokenDoesNotInject() {
        Claims claims = mock(Claims.class);

        jwtMock = Mockito.mockStatic(AppJwtUtil.class);
        jwtMock.when(() -> AppJwtUtil.getClaimsBody("expired-token")).thenReturn(claims);
        jwtMock.when(() -> AppJwtUtil.verifyToken(claims)).thenReturn(1);

        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn("expired-token");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(any(ApUser.class)), never());
    }

    @Test
    @DisplayName("getClaimsBody 返回 null - 不注入用户")
    void testNullClaimsDoesNotInject() {
        jwtMock = Mockito.mockStatic(AppJwtUtil.class);
        jwtMock.when(() -> AppJwtUtil.getClaimsBody("bad-token")).thenReturn(null);

        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn("bad-token");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(any(ApUser.class)), never());
    }

    @Test
    @DisplayName("解析抛异常(garbage token) - 按匿名处理不注入，不影响后续放行")
    void testParseExceptionDoesNotInject() {
        jwtMock = Mockito.mockStatic(AppJwtUtil.class);
        jwtMock.when(() -> AppJwtUtil.getClaimsBody("garbage")).thenThrow(new RuntimeException("bad jwt"));

        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn("garbage");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(any(ApUser.class)), never());
    }

    @Test
    @DisplayName("缺失 accToken 头(服务间 Feign 直连) - 不注入用户")
    void testMissingTokenDoesNotInject() {
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);
        when(request.getHeader("accToken")).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        threadLocalMock.verify(() -> AppThreadLocalUtil.setUser(any(ApUser.class)), never());
        // Feign 直连场景线程无用户，各 Controller 据此按"内部调用"放行
        threadLocalMock.verifyNoInteractions();
    }

    @Test
    @DisplayName("请求完成后清理线程用户")
    void testAfterCompletionClearsThreadLocal() {
        threadLocalMock = Mockito.mockStatic(AppThreadLocalUtil.class);

        interceptor.afterCompletion(request, response, new Object(), null);

        threadLocalMock.verify(AppThreadLocalUtil::clear);
    }
}