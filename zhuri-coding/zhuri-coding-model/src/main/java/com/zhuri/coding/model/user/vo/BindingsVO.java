package com.heima.model.user.vo;

import lombok.Data;

@Data
public class BindingsVO {
    private String phone; // 脱敏手机号，如 13****1129
    private OAuthBinding wechat;
    private OAuthBinding weibo;
    private OAuthBinding github;

    @Data
    public static class OAuthBinding {
        private Boolean bound;   // 是否已绑定
        private String nickname; // 三方昵称
        private String avatar;   // 三方头像
    }
}