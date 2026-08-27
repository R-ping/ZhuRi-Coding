package com.heima.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 第三方登录 OAuth 配置属性
 * 对应 application.yml 中的 oauth 配置段
 */
@Data
@Component
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {

    private Weibo weibo = new Weibo();
    private Github github = new Github();
    private Wechat wechat = new Wechat();

    @Data
    public static class Weibo {
        /** 微博应用 Client ID */
        private String clientId;
        /** 微博回调地址 */
        private String redirectHttp;// https://8d58228.r40.cpolar.top
        private String redirectUri;// /oauth/callback
        /** 微博授权地址 */
        private String authorizeUrl = "https://api.weibo.com/oauth2/authorize";
    }

    @Data
    public static class Github {
        /** GitHub 应用 Client ID */
        private String clientId;
        /** GitHub 回调地址 */
        private String redirectHttp;
        private String redirectUri;
        /** GitHub 授权地址 */
        private String authorizeUrl = "https://github.com/login/oauth/authorize";
    }

    @Data
    public static class Wechat {
        /** 微信公众号二维码图片 URL */
        private String qrcodeUrl;
    }
}
