package com.zhuri.coding.notification.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ImPushListener 单元测试
 *
 * 覆盖事件解析投递、字段缺失与坏 JSON 的容错（一条坏事件不能打断订阅循环）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImPushListener 推送事件订阅")
class ImPushListenerTest {

    @Mock
    private ImPushBroadcaster broadcaster;

    @InjectMocks
    private ImPushListener listener;

    private DefaultMessage message(String body) {
        return new DefaultMessage("im:push".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正常事件 → 按 targetId 投递给本实例连接")
    void testDeliver() {
        listener.onMessage(message("{\"targetId\":100,\"payload\":{\"type\":\"MESSAGE_RECEIVED\"}}"), null);

        verify(broadcaster).deliverLocal(eq(100L),
                argThat(p -> "MESSAGE_RECEIVED".equals(p.get("type"))));
    }

    @Test
    @DisplayName("字段缺失 → 忽略，不投递")
    void testMissingField() {
        assertDoesNotThrow(() -> listener.onMessage(message("{\"targetId\":100}"), null));

        verify(broadcaster, never()).deliverLocal(any(Long.class), any(Map.class));
    }

    @Test
    @DisplayName("坏 JSON → 记日志不抛出，订阅循环继续")
    void testBrokenJson() {
        assertDoesNotThrow(() -> listener.onMessage(message("not-a-json"), null));

        verify(broadcaster, never()).deliverLocal(any(Long.class), any(Map.class));
    }
}
