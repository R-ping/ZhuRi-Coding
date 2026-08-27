package com.heima.user.controller.v1;

import com.heima.user.config.OAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoginPageController 单元测试（登录页 OAuth 授权 URL 构建）
 *
 * 用真实 OAuthProperties 装配参数，验证微博/GitHub 授权 URL 拼接，以及
 * clientId/redirectUri 缺失时的"#"回退分支与 loginPage 的 Model 回填。
 */
@DisplayName("LoginPageController 登录页")
class LoginPageControllerTest {

    private final OAuthProperties props = new OAuthProperties();
    private LoginPageController loginPageController = buildController();

    private LoginPageController buildController() {
        LoginPageController c = new LoginPageController();
        try {
            java.lang.reflect.Field f = LoginPageController.class.getDeclaredField("oAuthProperties");
            f.setAccessible(true);
            f.set(c, props);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    /** 构造一个把 addAttribute(name,value) 收集进 map 的 Model mock */
    private Map<String, Object> modelAttrs() {
        Map<String, Object> attrs = new HashMap<>();
        Model model = mock(Model.class);
        when(model.addAttribute(anyString(), any())).thenAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return model;
        });
        loginPageController.loginPage(model);
        return attrs;
    }

    @Test
    @DisplayName("微博/GitHub 均配置 → 拼接授权URL并回填Model")
    void testLoginPageFull() {
        props.getWeibo().setClientId("w-client");
        props.getWeibo().setRedirectHttp("https://cb/weibo");
        props.getGithub().setClientId("g-client");
        props.getGithub().setRedirectHttp("https://cb/github");
        props.getWechat().setQrcodeUrl("img/wechat.png");

        Map<String, Object> attrs = modelAttrs();

        assertEquals("https://api.weibo.com/oauth2/authorize?client_id=w-client&response_type=code&redirect_uri=https://cb/weibo",
                attrs.get("weiboAuthUrl"));
        assertEquals("https://github.com/login/oauth/authorize?client_id=g-client&redirect_uri=https://cb/github",
                attrs.get("githubAuthUrl"));
        assertEquals("/img/wechat.png", attrs.get("wechatQrcodeUrl"));
    }

    @Test
    @DisplayName("微博参数缺失 → 微博URL回退为 #，但GitHub正常")
    void testWeiboMissing() {
        props.getGithub().setClientId("g-client");
        props.getGithub().setRedirectHttp("https://cb/github");

        Map<String, Object> attrs = modelAttrs();

        assertEquals("#", attrs.get("weiboAuthUrl"));
        assertTrue(((String) attrs.get("githubAuthUrl")).startsWith("https://github.com"));
    }

    @Test
    @DisplayName("GitHub参数缺失 → GitHubURL回退为 #")
    void testGithubMissing() {
        props.getWeibo().setClientId("w-client");
        props.getWeibo().setRedirectHttp("https://cb/weibo");

        Map<String, Object> attrs = modelAttrs();

        assertEquals("#", attrs.get("githubAuthUrl"));
        assertTrue(((String) attrs.get("weiboAuthUrl")).startsWith("https://api.weibo.com"));
    }
}