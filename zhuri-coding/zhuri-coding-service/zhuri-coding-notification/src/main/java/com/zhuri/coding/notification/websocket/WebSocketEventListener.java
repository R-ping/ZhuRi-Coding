package com.zhuri.coding.notification.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * 监听 STOMP 连接生命周期，维护用户在 {@link SessionManager} 中的在线状态。
 * <p>
 * 此前 userOnline/userOffline 没有调用入口，导致「接收者在线实时推送」永远走离线分支，
 * 实时私信推送形同虚设。此处以握手阶段 AuthHandshakeInterceptor 写入的已验证 userId 建立/回收在线会话。
 */
@Slf4j
@Component
public class WebSocketEventListener {

    private final SessionManager sessionManager;

    public WebSocketEventListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** STOMP 连接建立成功：登记在线。 */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(event.getMessage(), StompHeaderAccessor.class);
        Long userId = authUserId(accessor != null ? accessor.getSessionAttributes() : null);
        if (userId != null) {
            sessionManager.userOnline(userId, accessor.getSessionId());
        }
    }

    /** STOMP 连接断开：按 sessionId 精确移除对应在线会话（多端在线时仅掉线一端） */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Long userId = null;
        // 优先取断开事件的 sessionId 精确移除
        String sessionId = event.getSessionId();
        // 断开事件不再携带 sessionAttributes，取握手时 UserInterceptor 设置并随会话保存的 Principal
        if (event.getUser() != null) {
            userId = parseUserId(event.getUser().getName());
        }
        if (userId == null) {
            return;
        }
        sessionManager.userOffline(userId, sessionId);
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long authUserId(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object uid = attributes.get("userId");
        return uid == null ? null : ((Number) uid).longValue();
    }
}