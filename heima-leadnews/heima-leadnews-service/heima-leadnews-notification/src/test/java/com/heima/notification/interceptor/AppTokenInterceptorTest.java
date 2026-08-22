package com.heima.notification.interceptor;

import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AppTokenInterceptor 单元测试
 *
 * 验证从请求头解析 userId/nickName 写入线程本地、未带 userId 时放行不设用户、
 * 以及 afterCompletion 清理线程本地等逻辑；同时覆盖 nickName 的 URL 解码。
 */
@DisplayName("AppTokenInterceptor 用户令牌拦截器")
class AppTokenInterceptorTest {

    private final AppTokenInterceptor interceptor = new AppTokenInterceptor();

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Test
    @DisplayName("带 userId/nickName → 写入线程本地并放行")
    void testPreHandleWithUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String encoded = URLEncoder.encode("张三", StandardCharsets.UTF_8);
        when(request.getHeader("userId")).thenReturn("100");
        when(request.getHeader("nickName")).thenReturn(encoded);

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        ApUser user = AppThreadLocalUtil.getUser();
        assertNotNull(user);
        assertEquals(100, user.getId());
        assertEquals("张三", user.getNickname());
    }

    @Test
    @DisplayName("nickName 缺失 → 昵称兜底为空串")
    void testPreHandleNoNick() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("7");
        when(request.getHeader("nickName")).thenReturn(null);

        interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        ApUser user = AppThreadLocalUtil.getUser();
        assertNotNull(user);
        assertEquals("", user.getNickname());
    }

    @Test
    @DisplayName("无 userId → 放行且不写入用户")
    void testPreHandleNoUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn(null);

        assertTrue(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("nickName 无需解码的文本 → 原样传递")
    void testDecodeFallback() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("1");
        when(request.getHeader("nickName")).thenReturn("plain");

        interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());
        assertEquals("plain", AppThreadLocalUtil.getUser().getNickname());
    }

    @Test
    @DisplayName("afterCompletion → 清理线程本地")
    void testAfterCompletion() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("5");
        interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());
        assertNotNull(AppThreadLocalUtil.getUser());

        interceptor.afterCompletion(request, mock(HttpServletResponse.class), new Object(), null);

        assertNull(AppThreadLocalUtil.getUser());
    }
}