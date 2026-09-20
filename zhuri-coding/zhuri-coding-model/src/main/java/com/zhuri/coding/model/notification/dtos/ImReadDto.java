package com.heima.model.notification.dtos;

import lombok.Data;

@Data
public class ImReadDto {
    private Long sessionId;
    private Long lastReadId;
}