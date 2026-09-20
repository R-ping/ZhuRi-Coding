package com.zhuri.coding.notification.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class UserInterceptor implements ChannelInterceptor {

    private final SessionManager sessionManager;

    public UserInterceptor(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // 从握手阶段由 AuthHandshakeInterceptor 校验 token 后写入的 sessionAttributes 读取真实身份，
            // 不再信任客户端连接帧携带的裸 userId，防止伪装他人身份订阅 /user/queue 定向推送。
            Object userId = accessor.getSessionAttributes() != null
                    ? accessor.getSessionAttributes().get("userId")
                    : null;
            if (userId != null) {
                accessor.setUser(new StompPrincipal(userId.toString()));
            }
        }
        return message;
    }

    public static class StompPrincipal implements Principal {
        private final String name;
        public StompPrincipal(String name) { this.name = name; }
        @Override public String getName() { return name; }
    }
}