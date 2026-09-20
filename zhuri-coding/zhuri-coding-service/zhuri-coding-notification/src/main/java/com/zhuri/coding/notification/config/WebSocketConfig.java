package com.heima.notification.config;

import com.heima.notification.websocket.AuthHandshakeInterceptor;
import com.heima.notification.websocket.UserInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private AuthHandshakeInterceptor authHandshakeInterceptor;

    @Autowired
    private UserInterceptor userInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 客户端订阅前缀（服务端推送消息的目的地）
        config.enableSimpleBroker("/topic", "/queue");
        // 客户端发送消息的前缀
        config.setApplicationDestinationPrefixes("/app");
        // 用户私有消息前缀
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*", "https://localhost:*")
                .addInterceptors(authHandshakeInterceptor)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(userInterceptor);
    }
}