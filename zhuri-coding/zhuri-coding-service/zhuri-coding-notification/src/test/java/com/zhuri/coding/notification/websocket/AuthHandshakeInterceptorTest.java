package com.heima.notification.websocket;

import com.heima.utils.common.AppJwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthHandshakeInterceptor 单元测试
 *
 * 覆盖 WebSocket 握手鉴权：缺失/空 token、token 无效/过期、claims 无 userId、
 * JWT 解析异常以及鉴权成功写入 attributes 各分支。
 */
@DisplayName("AuthHandshakeInterceptor WebSocket 握手鉴权")
class AuthHandshakeInterceptorTest {

    private final AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor();

    private ServerHttpResponse response() {
        return mock(ServerHttpResponse.class);
    }

    private ServerHttpRequest request(String query) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(query == null ? URI.create("ws://host/path") : URI.create("ws://host/path?" + query));
        return request;
    }

    private Map<String, Object> attributes() {
        return new HashMap<>();
    }

    @Test
    @DisplayName("缺少 token 参数 → 拒绝并返回 401")
    void testMissingToken() {
        ServerHttpResponse response = response();
        boolean ok = interceptor.beforeHandshake(request("other=1"), response, mock(WebSocketHandler.class), attributes());
        assertFalse(ok);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("query 为 null → 拒绝并返回 401")
    void testNullQuery() {
        ServerHttpResponse response = response();
        boolean ok = interceptor.beforeHandshake(request(null), response, mock(WebSocketHandler.class), attributes());
        assertFalse(ok);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("token 为空字符串 → 拒绝")
    void testEmptyToken() {
        ServerHttpResponse response = response();
        boolean ok = interceptor.beforeHandshake(request("token="), response, mock(WebSocketHandler.class), attributes());
        assertFalse(ok);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("token 有效 → 放行并写入 attributes.userId")
    void testSuccess() {
        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("id")).thenReturn(100L);
            when(AppJwtUtil.getClaimsBody("abc")).thenReturn(claims);

            ServerHttpResponse response = response();
            Map<String, Object> attrs = attributes();
            boolean ok = interceptor.beforeHandshake(request("token=abc"), response, mock(WebSocketHandler.class), attrs);

            assertTrue(ok);
            assertEquals(100L, attrs.get("userId"));
        }
    }

    @Test
    @DisplayName("token 后带其他参数 → 截取首个 token 值")
    void testExtractTokenTrailingParams() {
        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("id")).thenReturn(200L);
            when(AppJwtUtil.getClaimsBody("abc")).thenReturn(claims);

            ServerHttpResponse response = response();
            boolean ok = interceptor.beforeHandshake(request("token=abc&foo=1"), response, mock(WebSocketHandler.class), attributes());
            assertTrue(ok);
        }
    }

    @Test
    @DisplayName("claims 为 null（无效/过期）→ 拒绝")
    void testNullClaims() {
        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            when(AppJwtUtil.getClaimsBody("xyz")).thenReturn(null);

            ServerHttpResponse response = response();
            boolean ok = interceptor.beforeHandshake(request("token=xyz"), response, mock(WebSocketHandler.class), attributes());
            assertFalse(ok);
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("claims 无 userId → 拒绝")
    void testNoUserId() {
        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            Claims claims = mock(Claims.class);
            when(claims.get("id")).thenReturn(null);
            when(AppJwtUtil.getClaimsBody("abc")).thenReturn(claims);

            ServerHttpResponse response = response();
            boolean ok = interceptor.beforeHandshake(request("token=abc"), response, mock(WebSocketHandler.class), attributes());
            assertFalse(ok);
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("JWT 解析抛出异常 → 拒绝")
    void testParseException() {
        try (MockedStatic<AppJwtUtil> jwt = mockStatic(AppJwtUtil.class)) {
            when(AppJwtUtil.getClaimsBody(any())).thenThrow(new RuntimeException("boom"));

            ServerHttpResponse response = response();
            boolean ok = interceptor.beforeHandshake(request("token=abc"), response, mock(WebSocketHandler.class), attributes());
            assertFalse(ok);
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        }
    }
}