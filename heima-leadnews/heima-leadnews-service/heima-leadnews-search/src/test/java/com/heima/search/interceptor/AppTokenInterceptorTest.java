package com.heima.search.interceptor;

import com.heima.model.user.pojos.ApUser;
import com.heima.utils.thread.AppThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * AppTokenInterceptor 单元测试（请求头 userId/nickName → 线程本地登录态）
 *
 * 模拟 Servlet 请求头并驱动 preHandle/afterCompletion，验证登录态写入、URL 解码与清理逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppTokenInterceptor 登录态拦截")
class AppTokenInterceptorTest {

    private AppTokenInterceptor interceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AppTokenInterceptor();
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    @Test
    @DisplayName("preHandle 携带 userId → 解析并写入登录态")
    void testPreHandleWithUserId() throws Exception {
        when(request.getHeader("userId")).thenReturn("1001");
        when(request.getHeader("nickName")).thenReturn("%E6%B5%8B%E8%AF%95%E7%94%A8%E6%88%B7");

        boolean allowed = interceptor.preHandle(request, response, null);

        assertTrue(allowed);
        ApUser user = AppThreadLocalUtil.getUser();
        assertNotNull(user);
        assertEquals(1001, user.getId());
        assertEquals("测试用户", user.getNickname());
    }

    @Test
    @DisplayName("preHandle 无 userId → 不写入登录态，放行")
    void testPreHandleWithoutUserId() throws Exception {
        when(request.getHeader("userId")).thenReturn(null);

        boolean allowed = interceptor.preHandle(request, response, null);

        assertTrue(allowed);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("nickName 为 null → 昵称兜底为空串")
    void testDecodeNickNameNull() throws Exception {
        when(request.getHeader("userId")).thenReturn("1");
        when(request.getHeader("nickName")).thenReturn(null);

        interceptor.preHandle(request, response, null);

        assertEquals("", AppThreadLocalUtil.getUser().getNickname());
    }

    @Test
    @DisplayName("afterCompletion → 清理线程本地登录态")
    void testAfterCompletion() throws Exception {
        when(request.getHeader("userId")).thenReturn("2");
        interceptor.preHandle(request, response, null);
        assertNotNull(AppThreadLocalUtil.getUser());

        interceptor.afterCompletion(request, response, null, null);

        assertNull(AppThreadLocalUtil.getUser());
    }
}