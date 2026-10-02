package com.zhuri.coding.notification.config;

import com.zhuri.coding.notification.websocket.ImPushBroadcaster;
import com.zhuri.coding.notification.websocket.ImPushListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 私信推送的 Redis 订阅。
 *
 * <p>只有当容器里真有 {@link RedisConnectionFactory} 时才注册，避免没有 Redis 的环境启动失败——
 * 那种情况下 {@link ImPushBroadcaster} 会退化为本地直投（单实例行为不变）。
 */
@Configuration
public class ImPushConfig {

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisMessageListenerContainer imPushListenerContainer(RedisConnectionFactory connectionFactory,
                                                                 ImPushListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(ImPushBroadcaster.CHANNEL));
        return container;
    }
}
