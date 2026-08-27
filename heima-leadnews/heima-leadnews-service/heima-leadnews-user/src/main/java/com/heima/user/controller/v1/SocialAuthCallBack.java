package com.heima.user.controller.v1;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dtos.SocialAuthDto;
import com.heima.user.service.SocialAuthService;
import com.heima.user.service.SocialLoginService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社交登录的授权回调接口
 */
@Slf4j
@RestController
@RequestMapping("/oauth2/code")
public class SocialAuthCallBack {

    @Autowired
    private SocialAuthService socialAuthService;
    @Autowired
    private SocialLoginService socialLoginService;

    /**
     * GitHub OAuth 回调
     * <p>
     * 第1步：用 code 换取 access_token POST https://github.com/login/oauth/access_token 参数：client_id, client_secret, code,
     * redirect_uri 响应：{"access_token":"...", "scope":"...", "token_type":"bearer"}
     */
    @GetMapping("/github")
    public ResponseResult github(@RequestParam("code") String code) {
        log.info("=== GitHub OAuth 回调 ===");
        log.info("收到 code: {}", code);
        try {
            String accessToken = socialAuthService.getAccessToken2Github(code);
            Map<String, Object> platFormUserInfo = socialAuthService.getPlatFormUserInfo(accessToken);
            String platFormUid = String.valueOf(platFormUserInfo.get("id"));
            SocialAuthDto socialAuthDto = getSocialAuthDto(platFormUid,"github");
            return socialLoginService.socialAuth(socialAuthDto);
        } catch (Exception e) {
            log.warn("github OAuth 异常", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 微博 OAuth 回调
     * <p>
     * 第1步：用 code 换取 access_token POST https://api.weibo.com/oauth2/access_token 参数：client_id, client_secret,
     * grant_type=authorization_code, code, redirect_uri 响应：{"access_token":"...", "expires_in":7200,
     * "uid":"1404376560"}
     */
    @GetMapping("/weibo")
    public ResponseResult weibo(@RequestParam("code") String code) {
        log.info("=== 微博 OAuth 回调 ===");
        log.info("收到 code: {}", code);
        try {
            String uid = socialAuthService.getStraightUid2Weibo(code);
            SocialAuthDto socialAuthDto = getSocialAuthDto(uid, "weibo");
            return socialLoginService.socialAuth(socialAuthDto);
        } catch (Exception e) {
            log.warn("微博 OAuth 异常", e);
            throw new RuntimeException(e);
        }
    }

    @NotNull
    private static SocialAuthDto getSocialAuthDto(String platFormUid, String tag) {
        SocialAuthDto socialAuthDto = new SocialAuthDto();
        socialAuthDto.setPlatform(tag);
        socialAuthDto.setPlatformUid(platFormUid);
        return socialAuthDto;
    }
}
