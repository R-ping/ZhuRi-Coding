package com.heima.user.controller.v1;

import com.heima.user.config.OAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 登录页面控制器 - 使用 FreeMarker 模板渲染登录页
 * <p>
 * 三种登录方式： 1. 微信公众号：扫码关注 → 发送"登录" → 获取验证码 → 登录 2. 微博 OAuth：跳转微博授权页 → 回调获取 code → 登录 3. GitHub OAuth：跳转 GitHub 授权页 → 回调获取
 * code → 登录
 */
@Slf4j
//@Controller
//@RequestMapping("/login")
public class LoginPageController {

    @Autowired
    private OAuthProperties oAuthProperties;

    /**
     * 渲染登录页面
     */
    @GetMapping({"", "/"})
    public String loginPage(Model model) {
        // 构建微博授权 URL
        String weiboAuthUrl = buildWeiboAuthUrl();
        // 构建 GitHub 授权 URL
        String githubAuthUrl = buildGithubAuthUrl();
        // 微信二维码地址
        String wechatQrcodeUrl = "/" + oAuthProperties.getWechat().getQrcodeUrl();

        model.addAttribute("weiboAuthUrl", weiboAuthUrl);
        model.addAttribute("githubAuthUrl", githubAuthUrl);
        model.addAttribute("wechatQrcodeUrl", wechatQrcodeUrl);

        log.info("登录页面 - 微博授权URL: {}", weiboAuthUrl);
        log.info("登录页面 - GitHub授权URL: {}", githubAuthUrl);

        return "login";
    }

    /**
     * 构建微博 OAuth 授权 URL
     */
    private String buildWeiboAuthUrl() {
        OAuthProperties.Weibo weibo = oAuthProperties.getWeibo();
        if (isEmpty(weibo.getClientId()) || isEmpty(weibo.getRedirectHttp())) {
            log.warn("微博 OAuth 参数未配置，clientId 或 redirectUri 为空");
            return "#";
        }
        return weibo.getAuthorizeUrl()
            + "?client_id=" + weibo.getClientId()
            + "&response_type=code"
            + "&redirect_uri=" + weibo.getRedirectHttp() + weibo.getRedirectUri();
    }

    /**
     * 构建 GitHub OAuth 授权 URL
     */
    private String buildGithubAuthUrl() {
        OAuthProperties.Github github = oAuthProperties.getGithub();
        if (isEmpty(github.getClientId()) || isEmpty(github.getRedirectHttp())) {
            log.warn("GitHub OAuth 参数未配置，clientId 或 redirectUri 为空");
            return "#";
        }
        return github.getAuthorizeUrl()
            + "?client_id=" + github.getClientId()
            + "&redirect_uri=" + github.getRedirectHttp() + github.getRedirectUri();
//                + "&scope=user:email";
    }

    private boolean isEmpty(String str) {
        return str == null || str.isBlank();
    }
}
