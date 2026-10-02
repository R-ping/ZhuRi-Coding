package com.zhuri.coding.notification.controller.v1;

import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import com.zhuri.coding.notification.service.ImService;
import com.zhuri.coding.notification.websocket.ImPushBroadcaster;
import com.zhuri.coding.notification.websocket.PresenceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebSocketMessageController 单元测试
 *
 * 覆盖私信发送（成功分发给发送者 ACK / 接收者在线实时推送 / 发送失败推送错误）、
 * 默认消息类型兜底，以及已读回执的推送逻辑。
 *
 * <p>控制器只负责"该推给谁"，投递由 ImPushBroadcaster 完成：在线判据取自 PresenceRegistry
 * （Redis 上的全局在线表），不能用本实例的连接表判断，否则多实例下连在别处的接收者会被当成离线。
 */
@DisplayName("WebSocketMessageController WebSocket 消息接口")
class WebSocketMessageControllerTest {

    private ImService imService;
    private PresenceRegistry presenceRegistry;
    private ImPushBroadcaster broadcaster;
    private WebSocketMessageController controller;

    @BeforeEach
    void setUp() {
        imService = mock(ImService.class);
        presenceRegistry = mock(PresenceRegistry.class);
        broadcaster = mock(ImPushBroadcaster.class);
        controller = new WebSocketMessageController();
        try {
            inject("imService", imService);
            inject("presenceRegistry", presenceRegistry);
            inject("broadcaster", broadcaster);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void inject(String name, Object value) throws Exception {
        var field = WebSocketMessageController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private Map<String, Object> payload(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i].toString(), kv[i + 1]);
        }
        return map;
    }

    private ResponseResult successResult() {
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", "m1");
        data.put("created_at", "2026-01-01 00:00:00");
        return ResponseResult.okResult(data);
    }

    /**
     * 模拟经握手鉴权后写入的会话身份：userId=100。
     * 发送者/已读人一律取自此认证身份，而非客户端 payload。
     */
    private SimpMessageHeaderAccessor accessor() {
        SimpMessageHeaderAccessor accessor = mock(SimpMessageHeaderAccessor.class);
        when(accessor.getSessionAttributes()).thenReturn(Map.of("userId", 100L));
        return accessor;
    }

    private void sendTo(String user) {
        verify(broadcaster).broadcast(eq(Long.valueOf(user)), any());
    }

    private void neverSendTo(String user) {
        verify(broadcaster, never()).broadcast(eq(Long.valueOf(user)), any());
    }

    @Test
    @DisplayName("发送成功且接收者在线 → 推送 ACK 与实时消息")
    void testHandleMessageSuccessOnline() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(presenceRegistry.isOnline(200L)).thenReturn(true);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hello"), accessor());

        sendTo("100");
        sendTo("200");
    }

    @Test
    @DisplayName("发送成功但接收者离线 → 仅推送 ACK")
    void testHandleMessageSuccessOffline() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(presenceRegistry.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hello"), accessor());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("发送失败（code 非 200）→ 推送错误消息给发送者")
    void testHandleMessageError() {
        when(imService.sendMessage(any(Long.class), any()))
                .thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR));

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), accessor());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("发送成功但 data 为空 → 视为失败走错误分支")
    void testHandleMessageNullData() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(ResponseResult.okResult());
        when(presenceRegistry.isOnline(200L)).thenReturn(true);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), accessor());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("不传 msg_type → 默认类型 1")
    void testDefaultMsgType() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(presenceRegistry.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), accessor());

        verify(imService).sendMessage(eq(100L), argThat(dto -> dto.getMsgType() != null && dto.getMsgType() == 1));
    }

    @Test
    @DisplayName("传 msg_type=3 → 透传给 DTO")
    void testExplicitMsgType() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(presenceRegistry.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi", "msg_type", 3), accessor());

        verify(imService).sendMessage(eq(100L), argThat(dto -> dto.getMsgType() == 3));
    }

    @Test
    @DisplayName("已读回执 → 对端在线时推送回执")
    void testHandleReadReceiptOnline() {
        when(imService.getPeerUserId(9L, 100L)).thenReturn(150L);
        when(presenceRegistry.isOnline(150L)).thenReturn(true);

        controller.handleReadReceipt(
                payload("reader_id", 100, "session_id", 9, "last_read_id", 50, "sender_id", 150),
                accessor());

        sendTo("150");
    }

    @Test
    @DisplayName("已读回执 → 对端离线时不推送")
    void testHandleReadReceiptOffline() {
        when(imService.getPeerUserId(9L, 100L)).thenReturn(150L);
        when(presenceRegistry.isOnline(150L)).thenReturn(false);

        controller.handleReadReceipt(
                payload("reader_id", 100, "session_id", 9, "last_read_id", 50, "sender_id", 150),
                accessor());

        neverSendTo("150");
    }
}
