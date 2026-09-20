package com.heima.notification.interceptor;

import com.heima.common.auth.InternalAuthSigner;
import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 验证从请求头解析 userId/nickName 写入线程本地（仅当通过内部 HMAC 签名校验）、
 * 未带 userId 或签名无效时按匿名处理、以及 afterCompletion 清理线程本地等逻辑；
 * 同时覆盖 nickName 的 URL 解码。
 */
@DisplayName("AppTokenInterceptor 用户令牌拦截器")
class AppTokenInterceptorTest {

    private static final String SECRET = "test-internal-secret";

    private AppTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AppTokenInterceptor(SECRET);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Test
    @DisplayName("带有效签名的 userId/nickName → 写入线程本地并放行")
    void testPreHandleWithUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        String encoded = URLEncoder.encode("张三", StandardCharsets.UTF_8);
        when(request.getHeader("userId")).thenReturn("100");
        when(request.getHeader("nickName")).thenReturn(encoded);
        when(request.getHeader("image")).thenReturn("");
        when(request.getHeader(InternalAuthSigner.HEADER_SIGN))
            .thenReturn(InternalAuthSigner.sign(SECRET, "100", "张三", ""));

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        ApUser user = AppThreadLocalUtil.getUser();
        assertNotNull(user);
        assertEquals(100, user.getId());
        assertEquals("张三", user.getNickname());
    }

    @Test
    @DisplayName("带 userId 但签名无效 → 按匿名处理（不信任伪造身份）")
    void testPreHandleWithForgedUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("100");
        when(request.getHeader("nickName")).thenReturn("张三");
        when(request.getHeader("image")).thenReturn("");
        when(request.getHeader(InternalAuthSigner.HEADER_SIGN)).thenReturn("forged");

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("密钥未配置 → fail-closed，拒绝信任身份头（防绕过网关伪造 userId）")
    void testPreHandleWithoutSecret() throws Exception {
        interceptor = new AppTokenInterceptor(null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("100");
        when(request.getHeader("nickName")).thenReturn("张三");
        when(request.getHeader("image")).thenReturn("");

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("无 userId → 放行且不写入登录态")
    void testPreHandleWithoutUser() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn(null);

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("afterCompletion → 清理线程本地登录态")
    void testAfterCompletion() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("2");
        when(request.getHeader("nickName")).thenReturn("");
        when(request.getHeader("image")).thenReturn("");
        when(request.getHeader(InternalAuthSigner.HEADER_SIGN))
            .thenReturn(InternalAuthSigner.sign(SECRET, "2", "", ""));

        interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());
        assertNotNull(AppThreadLocalUtil.getUser());

        interceptor.afterCompletion(request, mock(HttpServletResponse.class), new Object(), null);
        assertNull(AppThreadLocalUtil.getUser());
    }
}
