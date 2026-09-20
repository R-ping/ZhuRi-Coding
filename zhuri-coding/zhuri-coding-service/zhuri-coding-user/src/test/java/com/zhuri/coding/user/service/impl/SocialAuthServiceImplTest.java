package com.heima.user.service.impl;

import com.heima.user.config.OAuthProperties;
import com.heima.user.mapper.ApUserSocialMapper;
import com.heima.user.service.SocialAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SocialAuthServiceImpl 单元测试（GitHub/微博 OAuth 换取 token 与用户信息）
 *
 * 纯 @Service，经 @InjectMocks 注入 RestTemplate Mock；OAuthProperties 用真实实例设置 clientId/redirectUri。
 * 覆盖：换取 token 成功/失败/空响应、用户信息获取、uid 绑定检查。
 * （GITHUB_CLIENT_SECRET 等环境变量在测试环境通常为空，仅触发日志分支，不影响断言。）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialAuthService 第三方 OAuth")
class SocialAuthServiceImplTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ApUserSocialMapper apUserSocialMapper;

    private SocialAuthService socialAuthService;

    private OAuthProperties oAuthProperties = new OAuthProperties();

    private void buildService() {
        SocialAuthServiceImpl impl = new SocialAuthServiceImpl();
        setField(impl, "oAuthProperties", oAuthProperties);
        setField(impl, "restTemplate", restTemplate);
        setField(impl, "apUserSocialMapper", apUserSocialMapper);
        socialAuthService = impl;
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = SocialAuthServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> body(String k, Object v) {
        Map<String, Object> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    @Nested
    @DisplayName("GitHub 换取 access_token")
    class GithubToken {

        @Test
        @DisplayName("成功：返回 access_token")
        void testGithubSuccess() {
            buildService();
            oAuthProperties.getGithub().setClientId("github-client");
            oAuthProperties.getGithub().setRedirectHttp("https://cb/github");
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(body("access_token", "gh-token-1"), HttpStatus.OK));

            String token = ((SocialAuthServiceImpl) socialAuthService).getAccessToken2Github("code-1");

            assertEquals("gh-token-1", token);
        }

        @Test
        @DisplayName("响应含 error 字段 → 返回 null")
        void testGithubError() {
            buildService();
            Map<String, Object> err = new HashMap<>();
            err.put("error", "bad_verification_code");
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(err, HttpStatus.OK));

            assertNull(((SocialAuthServiceImpl) socialAuthService).getAccessToken2Github("bad"));
        }

        @Test
        @DisplayName("响应体为空 → 返回 null")
        void testGithubNullBody() {
            buildService();
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            assertNull(((SocialAuthServiceImpl) socialAuthService).getAccessToken2Github("code"));
        }
    }

    @Nested
    @DisplayName("微博换取 uid")
    class WeiboUid {

        @Test
        @DisplayName("成功：返回 uid")
        void testWeiboSuccess() {
            buildService();
            oAuthProperties.getWeibo().setClientId("weibo-client");
            oAuthProperties.getWeibo().setRedirectHttp("https://cb/weibo");
            Map<String, Object> b = new HashMap<>();
            b.put("access_token", "wb-token");
            b.put("uid", "12345");
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(b, HttpStatus.OK));

            String uid = ((SocialAuthServiceImpl) socialAuthService).getStraightUid2Weibo("code");

            assertEquals("12345", uid);
        }

        @Test
        @DisplayName("响应含 error_code → 返回 null")
        void testWeiboError() {
            buildService();
            when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(body("error_code", 21327), HttpStatus.OK));

            assertNull(((SocialAuthServiceImpl) socialAuthService).getStraightUid2Weibo("code"));
        }
    }

    @Nested
    @DisplayName("获取用户信息与绑定检查")
    class UserInfo {

        @Test
        @DisplayName("getUserInfo：返回 GitHub 用户信息")
        void testGetUserInfo() {
            buildService();
            Map<String, Object> info = new HashMap<>();
            info.put("login", "octocat");
            when(restTemplate.exchange(anyString(), any(), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));

            Map<String, Object> r = socialAuthService.getPlatFormUserInfo("gh-token");

            assertEquals("octocat", r.get("login"));
        }

        @Test
        @DisplayName("checkUidBound：已绑定 → true，未绑定 → false")
        void testCheckUidBound() {
            buildService();
            when(apUserSocialMapper.selectOne(any()))
                    .thenReturn(new com.heima.model.user.pojos.ApUserSocial())
                    .thenReturn(null);

            assertTrue(socialAuthService.checkUidBound("uid-1", "github"));
            assertFalse(socialAuthService.checkUidBound("uid-2", "weibo"));
        }
    }
}