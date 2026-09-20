package com.heima.search.interceptor;

import com.heima.common.auth.InternalAuthSigner;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AppTokenInterceptor 单元测试（请求头 userId/nickName → 线程本地登录态，含内部签名校验）
 *
 * 模拟 Servlet 请求头并驱动 preHandle/afterCompletion，验证登录态写入、URL 解码、
 * HMAC 签名校验与清理逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppTokenInterceptor 登录态拦截")
class AppTokenInterceptorTest {

    private static final String SECRET = "test-internal-secret";

    private AppTokenInterceptor interceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AppTokenInterceptor(SECRET);
    }

    @AfterEach
    void tearDown() {
        AppThreadLocalUtil.clear();
    }

    private void stubUserHeaders(String userId, String nickName, String image, String sign) {
        when(request.getHeader("userId")).thenReturn(userId);
        when(request.getHeader("nickName")).thenReturn(nickName);
        when(request.getHeader("image")).thenReturn(image);
        // lenient：密钥为空（降级信任）的用例不会读取签名头，避免严格模式下 UnnecessaryStubbing
        lenient().when(request.getHeader(InternalAuthSigner.HEADER_SIGN)).thenReturn(sign);
    }

    @Test
    @DisplayName("携带有效签名 → 解析并写入登录态")
    void testPreHandleWithValidSign() throws Exception {
        String sign = InternalAuthSigner.sign(SECRET, "1001", "测试用户", "");
        stubUserHeaders("1001", "%E6%B5%8B%E8%AF%95%E7%94%A8%E6%88%B7", "", sign);

        boolean allowed = interceptor.preHandle(request, response, null);

        assertTrue(allowed);
        ApUser user = AppThreadLocalUtil.getUser();
        assertNotNull(user);
        assertEquals(1001, user.getId());
        assertEquals("测试用户", user.getNickname());
    }

    @Test
    @DisplayName("携带 userId 但签名无效 → 按匿名处理（不信任伪造身份）")
    void testPreHandleWithInvalidSign() throws Exception {
        stubUserHeaders("1001", "%E6%B5%8B%E8%AF%95%E7%94%A8%E6%88%B7", "", "forged-signature");

        boolean allowed = interceptor.preHandle(request, response, null);

        assertTrue(allowed);
        assertNull(AppThreadLocalUtil.getUser());
    }

    @Test
    @DisplayName("密钥未配置 → fail-closed，拒绝信任身份头（防绕过网关伪造 userId）")
    void testPreHandleWithoutSecret() throws Exception {
        interceptor = new AppTokenInterceptor(null);
        stubUserHeaders("1001", "%E6%B5%8B%E8%AF%95%E7%94%A8%E6%88%B7", "", null);

        boolean allowed = interceptor.preHandle(request, response, null);

        assertTrue(allowed);
        assertNull(AppThreadLocalUtil.getUser());
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
        String sign = InternalAuthSigner.sign(SECRET, "1", "", "");
        stubUserHeaders("1", null, "", sign);

        interceptor.preHandle(request, response, null);

        assertEquals("", AppThreadLocalUtil.getUser().getNickname());
    }

    @Test
    @DisplayName("afterCompletion → 清理线程本地登录态")
    void testAfterCompletion() throws Exception {
        String sign = InternalAuthSigner.sign(SECRET, "2", "", "");
        stubUserHeaders("2", null, "", sign);
        interceptor.preHandle(request, response, null);
        assertNotNull(AppThreadLocalUtil.getUser());

        interceptor.afterCompletion(request, response, null, null);

        assertNull(AppThreadLocalUtil.getUser());
    }
}
