package com.heima.model.user.dtos;

import lombok.Data;

/**
 * 社交登录绑定已有账号请求 DTO
 * <p>
 * "已有账号，请绑定" 场景使用
 */
@Data
public class SocialBindDto {

    /**
     * 平台: github / weibo / wechat
     */
    private String platform;

    /**
     * 三方平台唯一标识（uid / openId）
     */
    private String platformUid;

    /**
     * 已有账号用户名（手机号）
     */
    private String phone;

    /**
     * 验证码
     */
    private String code;
}
