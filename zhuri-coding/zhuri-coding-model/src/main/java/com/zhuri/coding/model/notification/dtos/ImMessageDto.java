package com.heima.model.notification.dtos;

import lombok.Data;

@Data
public class ImMessageDto {
    private Long sessionId;
    private Long receiverId;
    private String content;
    private Integer msgType;   // 1-文本
    private String clientId;   // 客户端去重ID
}