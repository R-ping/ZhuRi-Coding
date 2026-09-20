package com.heima.model.user.dtos;

import lombok.Data;

/**
 * 社交登录认证请求 DTO
 * <p>
 * 前端拿到OAuth callback返回的用户信息后，调用此接口进行登录/绑定判断
 */
@Data
public class SocialAuthDto {
    /**
     * 业务状态：
     * "login"     - 已登录，直接返回token
     * "need_bind" - 新用户，需选择"绑定已有账号"或"注册新号"
     */
    private String status;
    /**
     * 平台: github / weibo / wechat
     */
    private String platform;

    /**
     * 三方平台唯一标识（uid / openId）
     */
    private String platformUid;

}
