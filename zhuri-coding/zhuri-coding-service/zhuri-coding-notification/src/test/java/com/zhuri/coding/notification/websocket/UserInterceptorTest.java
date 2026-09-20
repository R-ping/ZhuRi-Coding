package com.heima.notification.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.security.Principal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserInterceptor 单元测试
 *
 * 覆盖 CONNECT 帧携带 userId 时设置 Principal、无 userId 或非 CONNECT 帧不设置，
 * 且返回原始消息不受影响。
 */
@DisplayName("UserInterceptor WebSocket 用户拦截器")
class UserInterceptorTest {

    private SessionManager sessionManager;
    private UserInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        interceptor = new UserInterceptor(sessionManager);
    }

    @Test
    @DisplayName("CONNECT 且 sessionAttributes 含 userId → 为 accessor 设置 Principal")
    void testPreSendWithUserId() {
        Message<?> message = mock(Message.class);
        MessageChannel channel = mock(MessageChannel.class);
        StompHeaderAccessor accessor = mock(StompHeaderAccessor.class);
        when(accessor.getCommand()).thenReturn(StompCommand.CONNECT);
        when(accessor.getSessionAttributes()).thenReturn(Map.of("userId", 100L));

        try (MockedStatic<MessageHeaderAccessor> mha = mockStatic(MessageHeaderAccessor.class)) {
            when(MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)).thenReturn(accessor);

            assertSame(message, interceptor.preSend(message, channel));

            ArgumentCaptor<Principal> captor = ArgumentCaptor.forClass(Principal.class);
            verify(accessor).setUser(captor.capture());
            assertEquals("100", captor.getValue().getName());
        }
    }

    @Test
    @DisplayName("CONNECT 但 sessionAttributes 无 userId → 不设置 Principal（不信任裸 header）")
    void testPreSendNoUserId() {
        Message<?> message = mock(Message.class);
        MessageChannel channel = mock(MessageChannel.class);
        StompHeaderAccessor accessor = mock(StompHeaderAccessor.class);
        when(accessor.getCommand()).thenReturn(StompCommand.CONNECT);
        // 模拟携带了非法的裸 userId header，但因未经过握手 token 鉴权，拦截器不应据此设置身份
        when(accessor.getFirstNativeHeader("userId")).thenReturn("999");
        when(accessor.getSessionAttributes()).thenReturn(Map.of());

        try (MockedStatic<MessageHeaderAccessor> mha = mockStatic(MessageHeaderAccessor.class)) {
            when(MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)).thenReturn(accessor);

            assertSame(message, interceptor.preSend(message, channel));
            verify(accessor, never()).setUser(any());
        }
    }

    @Test
    @DisplayName("非 CONNECT 帧 → 不处理")
    void testPreSendNonConnect() {
        Message<?> message = mock(Message.class);
        MessageChannel channel = mock(MessageChannel.class);
        StompHeaderAccessor accessor = mock(StompHeaderAccessor.class);
        when(accessor.getCommand()).thenReturn(StompCommand.SUBSCRIBE);

        try (MockedStatic<MessageHeaderAccessor> mha = mockStatic(MessageHeaderAccessor.class)) {
            when(MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)).thenReturn(accessor);

            assertSame(message, interceptor.preSend(message, channel));
            verify(accessor, never()).setUser(any());
        }
    }
}