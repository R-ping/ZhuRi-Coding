package com.zhuri.coding.notification.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 订阅推送广播，把事件投递给**本实例**持有的连接。
 *
 * <p>每条事件会被所有实例收到（包括发出它的那个），各实例只认自己手上的连接，
 * 所以不需要判断"是不是自己发的"——没有自己在别的实例上的连接，投递就是空操作。
 */
@Slf4j
@Component
public class ImPushListener implements MessageListener {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ImPushBroadcaster broadcaster;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> event = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object targetRaw = event.get("targetId");
            Object payloadRaw = event.get("payload");
            if (targetRaw == null || payloadRaw == null) {
                log.warn("推送事件字段缺失，忽略: {}", body);
                return;
            }
            long targetId = ((Number) targetRaw).longValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) payloadRaw;
            broadcaster.deliverLocal(targetId, payload);
        } catch (Exception e) {
            // 一条坏事件不该影响订阅循环，记日志即可
            log.error("处理推送事件失败", e);
        }
    }
}
