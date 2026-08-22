package com.heima.notification.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.notification.service.ImService;
import com.heima.notification.websocket.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
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
 */
@DisplayName("WebSocketMessageController WebSocket 消息接口")
class WebSocketMessageControllerTest {

    private ImService imService;
    private SessionManager sessionManager;
    private SimpMessagingTemplate messagingTemplate;
    private WebSocketMessageController controller;

    @BeforeEach
    void setUp() {
        imService = mock(ImService.class);
        sessionManager = mock(SessionManager.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        controller = new WebSocketMessageController();
        try {
            inject("imService", imService);
            inject("sessionManager", sessionManager);
            inject("messagingTemplate", messagingTemplate);
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

    private Principal principal() {
        return () -> "100";
    }

    private void sendTo(String user) {
        verify(messagingTemplate).convertAndSendToUser(eq(user), eq("/queue/messages"), any());
    }

    private void neverSendTo(String user) {
        verify(messagingTemplate, never()).convertAndSendToUser(eq(user), eq("/queue/messages"), any());
    }

    @Test
    @DisplayName("发送成功且接收者在线 → 推送 ACK 与实时消息")
    void testHandleMessageSuccessOnline() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(sessionManager.isOnline(200L)).thenReturn(true);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hello"), principal());

        sendTo("100");
        sendTo("200");
    }

    @Test
    @DisplayName("发送成功但接收者离线 → 仅推送 ACK")
    void testHandleMessageSuccessOffline() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(sessionManager.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hello"), principal());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("发送失败（code 非 200）→ 推送错误消息给发送者")
    void testHandleMessageError() {
        when(imService.sendMessage(any(Long.class), any()))
                .thenReturn(ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR));

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), principal());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("发送成功但 data 为空 → 视为失败走错误分支")
    void testHandleMessageNullData() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(ResponseResult.okResult());
        when(sessionManager.isOnline(200L)).thenReturn(true);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), principal());

        sendTo("100");
        neverSendTo("200");
    }

    @Test
    @DisplayName("不传 msg_type → 默认类型 1")
    void testDefaultMsgType() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(sessionManager.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi"), principal());

        verify(imService).sendMessage(eq(100L), argThat(dto -> dto.getMsgType() != null && dto.getMsgType() == 1));
    }

    @Test
    @DisplayName("传 msg_type=3 → 透传给 DTO")
    void testExplicitMsgType() {
        when(imService.sendMessage(any(Long.class), any())).thenReturn(successResult());
        when(sessionManager.isOnline(200L)).thenReturn(false);

        controller.handleMessage(payload("sender_id", 100, "receiver_id", 200, "content", "hi", "msg_type", 3), principal());

        verify(imService).sendMessage(eq(100L), argThat(dto -> dto.getMsgType() == 3));
    }

    @Test
    @DisplayName("已读回执 → 发送者在线时推送回执")
    void testHandleReadReceiptOnline() {
        when(sessionManager.isOnline(150L)).thenReturn(true);

        controller.handleReadReceipt(
                payload("reader_id", 100, "session_id", 9, "last_read_id", 50, "sender_id", 150),
                principal());

        verify(messagingTemplate).convertAndSendToUser(eq("150"), eq("/queue/messages"), any(Object.class));
    }

    @Test
    @DisplayName("已读回执 → 发送者离线时不推送")
    void testHandleReadReceiptOffline() {
        when(sessionManager.isOnline(150L)).thenReturn(false);

        controller.handleReadReceipt(
                payload("reader_id", 100, "session_id", 9, "last_read_id", 50, "sender_id", 150),
                principal());

        neverSendTo("150");
    }
}