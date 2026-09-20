package com.heima.model.pins.dtos;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞/取消点赞请求 DTO
 */
@Data
@NoArgsConstructor
public class PinsLikeDTO {

    private Long pinsId;

    private Boolean liked;
}