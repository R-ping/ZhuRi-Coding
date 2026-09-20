package com.heima.user.interceptor;

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
 * UserTokenInterceptor 单元测试（内部 HMAC 签名校验）
 */
@DisplayName("UserTokenInterceptor 内部身份校验")
class UserTokenInterceptorTest {

    private static final String SECRET = "test-internal-secret";

    private UserTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new UserTokenInterceptor(SECRET);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Test
    @DisplayName("携带有效签名 → 写入线程本地并放行")
    void testPreHandleWithValidSign() throws Exception {
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
    @DisplayName("携带 userId 但签名无效 → 按匿名处理")
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
        interceptor = new UserTokenInterceptor(null);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("userId")).thenReturn("7");
        when(request.getHeader("nickName")).thenReturn("");

        boolean ok = interceptor.preHandle(request, mock(HttpServletResponse.class), new Object());

        assertTrue(ok);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("afterCompletion → 清理线程本地")
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
