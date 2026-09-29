package com.zhuri.coding.notification.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImPushBroadcaster 单元测试
 *
 * 覆盖三条路径：有 Redis 走广播、无 Redis 退化本地投递、广播失败也退化本地投递；
 * 以及"本实例没有该用户连接时不投"与空载荷直接忽略。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImPushBroadcaster 推送广播")
class ImPushBroadcasterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private SessionManager sessionManager;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ImPushBroadcaster broadcaster;

    private Map<String, Object> payload() {
        return Map.of("type", "MESSAGE_RECEIVED", "message_id", "m1");
    }

    private void clearRedis() throws Exception {
        Field f = ImPushBroadcaster.class.getDeclaredField("redis");
        f.setAccessible(true);
        f.set(broadcaster, null);
    }

    @Test
    @DisplayName("有 Redis → 走广播，不在本实例重复投递")
    void testBroadcastViaRedis() {
        broadcaster.broadcast(100L, payload());

        verify(redis).convertAndSend(eq("im:push"), anyString());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("无 Redis → 退化为本地直投（单实例行为不变）")
    void testFallbackToLocalWhenNoRedis() throws Exception {
        clearRedis();
        when(sessionManager.getSessionIds(100L)).thenReturn(Set.of("s1"));

        broadcaster.broadcast(100L, payload());

        verify(messagingTemplate).convertAndSendToUser(eq("100"), eq("/queue/messages"), any());
    }

    @Test
    @DisplayName("广播抛异常 → 同样退化为本地直投")
    void testFallbackWhenRedisFails() {
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));
        when(sessionManager.getSessionIds(100L)).thenReturn(Set.of("s1"));

        broadcaster.broadcast(100L, payload());

        verify(messagingTemplate).convertAndSendToUser(eq("100"), eq("/queue/messages"), any());
    }

    @Test
    @DisplayName("本实例没有该用户的连接 → 不投递")
    void testSkipWhenNoLocalSession() throws Exception {
        clearRedis();
        when(sessionManager.getSessionIds(100L)).thenReturn(Set.of());

        broadcaster.broadcast(100L, payload());

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("载荷为空 → 什么都不做")
    void testEmptyPayloadIgnored() {
        broadcaster.broadcast(100L, Map.of());
        broadcaster.broadcast(null, payload());

        verify(redis, never()).convertAndSend(anyString(), anyString());
    }
}
