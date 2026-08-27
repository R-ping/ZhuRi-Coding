package com.heima.notification.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.notification.dtos.ImMessageDto;
import com.heima.notification.service.ImService;
import com.heima.notification.websocket.SessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
public class WebSocketMessageController {

    @Autowired
    private ImService imService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 接收客户端通过WebSocket发送的消息
     * 客户端发送到: /app/im/send
     */
    @MessageMapping("/im/send")
    public void handleMessage(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        // 发送者身份以握手阶段 AuthHandshakeInterceptor 校验 token 后写入的身分为准，
        // 忽略客户端 payload 中的 sender_id，防止冒充他人发送消息。
        Long senderId = authUserId(accessor);
        if (senderId == null) {
            log.warn("im send rejected: unauthenticated sender");
            return;
        }
        Object receiverRaw = payload.get("receiver_id");
        if (receiverRaw == null) {
            log.warn("im send rejected: missing receiver_id, senderId={}", senderId);
            return;
        }
        Long receiverId = Long.valueOf(receiverRaw.toString());
        String content = (String) payload.get("content");

        ImMessageDto dto = new ImMessageDto();
        dto.setReceiverId(receiverId);
        dto.setContent(content);
        dto.setMsgType(payload.get("msg_type") != null ? Integer.valueOf(payload.get("msg_type").toString()) : 1);

        // 通过HTTP服务发送消息（存储+状态机校验）
        ResponseResult result = imService.sendMessage(senderId, dto);

        if (result.getCode() == 200 && result.getData() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getData();

            // 推送给发送者ACK
            Map<String, Object> ack = new HashMap<>();
            ack.put("type", "MESSAGE_ACK");
            ack.put("message_id", data.get("message_id"));
            ack.put("status", "sent");
            messagingTemplate.convertAndSendToUser(senderId.toString(), "/queue/messages", ack);

            // 如果接收者在线，实时推送
            if (sessionManager.isOnline(receiverId)) {
                Map<String, Object> push = new HashMap<>();
                push.put("type", "MESSAGE_RECEIVED");
                push.put("message_id", data.get("message_id"));
                push.put("sender_id", senderId);
                push.put("receiver_id", receiverId);
                push.put("content", content);
                push.put("created_at", data.get("created_at"));
                messagingTemplate.convertAndSendToUser(receiverId.toString(), "/queue/messages", push);
            }
        } else {
            // 发送错误消息
            Map<String, Object> error = new HashMap<>();
            error.put("type", "MESSAGE_ERROR");
            error.put("code", result.getCode());
            error.put("message", result.getMessage());
            messagingTemplate.convertAndSendToUser(senderId.toString(), "/queue/messages", error);
        }
    }

    /**
     * 已读回执
     * 客户端发送到: /app/im/read
     */
    @MessageMapping("/im/read")
    public void handleReadReceipt(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        // 已读人以认证身份为准；对端由会话归属校验推导，避免越权推送已读回执。
        Long readerId = authUserId(accessor);
        if (readerId == null) {
            log.warn("im read rejected: unauthenticated reader");
            return;
        }
        Object sessionRaw = payload.get("session_id");
        if (sessionRaw == null) {
            return;
        }
        Long sessionId = Long.valueOf(sessionRaw.toString());
        Long senderId = imService.getPeerUserId(sessionId, readerId);
        if (senderId == null) {
            log.warn("im read rejected: session not owned, sessionId={}, readerId={}", sessionId, readerId);
            return;
        }
        Long lastReadId = payload.get("last_read_id") != null
                ? Long.valueOf(payload.get("last_read_id").toString())
                : Long.MAX_VALUE;

        // 推送已读回执给消息发送者（对端）
        Map<String, Object> readReceipt = new HashMap<>();
        readReceipt.put("type", "READ_RECEIPT");
        readReceipt.put("session_id", sessionId);
        readReceipt.put("reader_id", readerId);
        readReceipt.put("last_read_id", lastReadId);

        if (sessionManager.isOnline(senderId)) {
            messagingTemplate.convertAndSendToUser(senderId.toString(), "/queue/messages", readReceipt);
        }
    }

    /**
     * 从握手阶段写入的 sessionAttributes 读取已验证的用户ID（AuthHandshakeInterceptor 校验 token 后写入）。
     */
    private Long authUserId(SimpMessageHeaderAccessor accessor) {
        if (accessor == null || accessor.getSessionAttributes() == null) {
            return null;
        }
        Object uid = accessor.getSessionAttributes().get("userId");
        return uid == null ? null : ((Number) uid).longValue();
    }
}