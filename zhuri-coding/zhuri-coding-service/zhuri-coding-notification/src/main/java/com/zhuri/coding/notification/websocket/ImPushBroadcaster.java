package com.zhuri.coding.notification.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 私信实时推送的广播与投递。
 *
 * <p>发送方不直接投给接收者，而是往 Redis 频道发一条"该推给谁"的事件；每个实例订阅该频道，
 * 收到后只推**自己持有的连接**。这样接收者连在哪个实例上都能收到，且逻辑只有一条，
 * 不用区分"本机直推"和"跨实例转发"两种情况。
 *
 * <p>Redis 不可用时退化为本地直投——单实例部署下行为与改造前一致，不会因为没有 Redis 就不推送。
 * 这是降级不是兜底：推送本来就是"丢了靠下次拉取补"的语义，不能让它反过来影响可用性。
 */
@Slf4j
@Component
public class ImPushBroadcaster {

    public static final String CHANNEL = "im:push";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /** 广播一条推送事件；Redis 不可用或发送失败时退化为本地直投 */
    public void broadcast(Long userId, Map<String, Object> payload) {
        if (userId == null || payload == null || payload.isEmpty()) {
            return;
        }
        if (redis == null) {
            deliverLocal(userId, payload);
            return;
        }
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("targetId", userId);
            event.put("payload", payload);
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("广播推送事件失败，退化为本地投递, userId={}", userId, e);
            deliverLocal(userId, payload);
        }
    }

    /**
     * 只投给本实例持有的连接。
     * 没有本地连接时直接跳过——{@code convertAndSendToUser} 本来也只作用于本实例，
     * 这里提前判断只是省掉一次空转。
     */
    void deliverLocal(Long userId, Map<String, Object> payload) {
        if (userId == null || sessionManager.getSessionIds(userId).isEmpty()) {
            return;
        }
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/messages", payload);
    }
}
