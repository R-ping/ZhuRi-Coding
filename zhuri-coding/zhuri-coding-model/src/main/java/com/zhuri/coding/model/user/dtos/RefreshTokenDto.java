package com.heima.model.user.dtos;

import lombok.Data;

/**
 * Token刷新请求 DTO
 */
@Data
public class RefreshTokenDto {

    /**
     * UUID refresh_token
     */
    private String refreshToken;
}
