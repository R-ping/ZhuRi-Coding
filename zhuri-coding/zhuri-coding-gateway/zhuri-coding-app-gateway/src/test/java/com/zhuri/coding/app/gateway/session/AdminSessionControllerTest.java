package com.zhuri.coding.app.gateway.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营会话入口（登录/登出/当前会话）的回归。
 *
 * <p>重点是<b>登出</b>：{@code WebSession#invalidate()} 返回的是「删掉 Redis 会话」的冷 Mono，
 * 丢掉它不会有任何报错 —— 会话仍然标着 EXPIRED、Cookie 也会被框架清掉，看起来一切正常，
 * 只是服务端那份还在，一直到空闲 TTL 到期。这类"静默降级"靠人工点一遍发现不了，所以钉在这里。
 */
@DisplayName("AdminSessionController 运营会话入口")
class AdminSessionControllerTest {

    private final AdminCredentialClient credentialClient = mock(AdminCredentialClient.class);

    private final AdminSessionController controller = new AdminSessionController(credentialClient);

    @Test
    @DisplayName("登出必须真的删掉服务端会话：invalidate() 的 Mono 要被订阅，不能只丢 Cookie")
    void logoutActuallyDeletesServerSideSession() {
        WebSession session = mock(WebSession.class);
        when(session.getAttribute(AdminSessionKeys.ATTR_ACCOUNT_ID)).thenReturn(1001);
        AtomicBoolean deleted = new AtomicBoolean(false);
        // 与 Spring Session 的 invalidate() 同形：返回冷 Mono，只有被订阅才会去删 Redis 键
        when(session.invalidate()).thenReturn(Mono.fromRunnable(() -> deleted.set(true)).then());

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));

        ResponseEntity<Map<String, Object>> response = controller.logout(exchange).block();

        assertTrue(deleted.get(),
            "invalidate() 的返回值没被订阅：会话只在内存里标了 EXPIRED，Redis 键还在，"
                + "登出退化成『前端把 Cookie 丢了』");
        assertEquals(200, response.getBody().get("code"));
    }

    @Test
    @DisplayName("登录成功：身份进会话、空闲时长被设上，响应体里不带任何 token（与双 token 的分界点）")
    void loginStoresIdentityAndReturnsNoToken() {
        when(credentialClient.verify("admin", "pwd")).thenReturn(Mono.just(
            AdminCredentialClient.CredentialResult.success(1, "admin", "超级管理员", 1)));

        Map<String, Object> attributes = new HashMap<>();
        WebSession session = mock(WebSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));

        ResponseEntity<Map<String, Object>> response =
            controller.login(Map.of("username", "admin", "password", "pwd"), exchange).block();

        assertEquals(1, attributes.get(AdminSessionKeys.ATTR_ACCOUNT_ID));
        assertEquals("admin", attributes.get(AdminSessionKeys.ATTR_USERNAME));
        assertEquals("超级管理员", attributes.get(AdminSessionKeys.ATTR_NICK_NAME));
        // mustChangePassword=1 → 会话里要落成 true，网关据此做强制改密拦截
        assertEquals(Boolean.TRUE, attributes.get(AdminSessionKeys.ATTR_MUST_CHANGE_PASSWORD));
        verify(session).setMaxIdleTime(AdminSessionKeys.MAX_IDLE);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertFalse(data.containsKey("accToken"), "运营侧不该出现 accToken");
        assertFalse(data.containsKey("refToken"), "运营侧不该出现 refToken");
    }

    @Test
    @DisplayName("口令或登录名留空 → 不调用 user 服务，直接回参数错误（避免空口令去打一次校验）")
    void loginRejectsBlankInput() {
        WebSession session = mock(WebSession.class);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));

        ResponseEntity<Map<String, Object>> response =
            controller.login(Map.of("username", "  ", "password", ""), exchange).block();

        assertEquals(500, response.getBody().get("code"));
        verify(credentialClient, never()).verify(anyString(), anyString());
    }

    @Test
    @DisplayName("没有会话时 /me 回『需要登录』(code=1)，前端据此跳登录页")
    void meWithoutSessionNeedsLogin() {
        WebSession session = mock(WebSession.class);
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getSession()).thenReturn(Mono.just(session));

        ResponseEntity<Map<String, Object>> response = controller.me(exchange).block();

        assertEquals(1, response.getBody().get("code"));
        assertEquals("需要登录", response.getBody().get("message"));
    }
}
