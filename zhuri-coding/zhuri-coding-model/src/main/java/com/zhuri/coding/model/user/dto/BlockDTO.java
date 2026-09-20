package com.heima.model.user.dto;

import lombok.Data;

@Data
public class BlockDTO {
    private Integer type; // 1-作者, 2-标签
    private Long targetId;
}