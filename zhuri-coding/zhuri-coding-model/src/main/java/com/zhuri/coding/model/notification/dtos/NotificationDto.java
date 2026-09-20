package com.heima.model.notification.dtos;

import lombok.Data;

@Data
public class NotificationDto {
    private String type;     // comment/digg/follow/system
    private Integer page;    // 页码（默认1）
    private Integer size;    // 每页大小（默认20，最大50）
    private String cursor;   // 游标（created_at + id组合）
}