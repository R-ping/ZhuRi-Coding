package com.heima.notification.config;

import com.heima.notification.websocket.AuthHandshakeInterceptor;
import com.heima.notification.websocket.UserInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocketConfig 单元测试
 *
 * 验证消息代理配置、STOMP 端点注册（含握手拦截器与 SockJS）、
 * 以及入站通道用户拦截器注册。
 */
@DisplayName("WebSocketConfig WebSocket 配置")
class WebSocketConfigTest {

    private WebSocketConfig config;
    private AuthHandshakeInterceptor authHandshakeInterceptor;
    private UserInterceptor userInterceptor;

    @BeforeEach
    void setUp() {
        authHandshakeInterceptor = mock(AuthHandshakeInterceptor.class);
        userInterceptor = mock(UserInterceptor.class);
        config = new WebSocketConfig();
        try {
            inject("authHandshakeInterceptor", authHandshakeInterceptor);
            inject("userInterceptor", userInterceptor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void inject(String name, Object value) throws Exception {
        var field = WebSocketConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }

    @Test
    @DisplayName("configureMessageBroker → 启用代理并设置前后缀")
    void testConfigureMessageBroker() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic", "/queue");
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
    }

    @Test
    @DisplayName("registerStompEndpoints → 注册 /ws 端点并启用 SockJS")
    void testRegisterStompEndpoints() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpoint = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(endpoint);
        when(endpoint.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpoint);
        when(endpoint.addInterceptors(authHandshakeInterceptor)).thenReturn(endpoint);
        when(endpoint.withSockJS()).thenReturn(mock(SockJsServiceRegistration.class));

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(endpoint).setAllowedOriginPatterns("http://localhost:*", "https://localhost:*");
        verify(endpoint).addInterceptors(authHandshakeInterceptor);
        verify(endpoint).withSockJS();
    }

    @Test
    @DisplayName("configureClientInboundChannel → 注册用户拦截器")
    void testConfigureClientInboundChannel() {
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(userInterceptor);
    }
}