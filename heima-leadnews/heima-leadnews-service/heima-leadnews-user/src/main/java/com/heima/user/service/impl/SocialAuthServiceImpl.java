package com.heima.user.service.impl;

import static org.springframework.http.HttpMethod.GET;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.heima.model.user.pojos.ApUserSocial;
import com.heima.user.config.OAuthProperties;
import com.heima.user.mapper.ApUserSocialMapper;
import com.heima.user.service.SocialAuthService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class SocialAuthServiceImpl implements SocialAuthService {

    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_URL = "https://api.github.com/user";
    private static final String WEIBO_TOKEN_URL = "https://api.weibo.com/oauth2/access_token";
    private static final String WEIBO_USER_URL = "https://api.weibo.com/2/users/show.json";

    @Autowired
    private OAuthProperties oAuthProperties;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ApUserSocialMapper apUserSocialMapper;

    @Override
    public String getAccessToken2Github(String code) {
        // 1. 从系统环境变量读取 client_secret
        String clientSecret = System.getenv("GITHUB_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) {
            log.error("系统环境变量 GITHUB_CLIENT_SECRET 未设置");
        }

        OAuthProperties.Github githubConfig = oAuthProperties.getGithub();

        // 2. 构建请求参数 (form-urlencoded)
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", githubConfig.getClientId());
        params.add("client_secret", clientSecret);
        params.add("code", code);
        params.add("redirect_uri", githubConfig.getRedirectHttp());
        // 如果reqParams不为空，将其中的值全追加到params中
        // 3. 设置请求头，要求返回 JSON
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        // 4. 换取 access_token
        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(
            GITHUB_TOKEN_URL, request, Map.class);
        Map<String, Object> tokenBody = tokenResponse.getBody();
        log.info("GitHub token 响应: {}", tokenBody);
        if (tokenBody == null || tokenBody.containsKey("error")) {
            log.error("GitHub 换取 token 失败: {}", tokenBody);
            return null;
        }
        String accessToken = (String) tokenBody.get("access_token");
        log.info("GitHub access_token: {}", accessToken);
        return accessToken;
    }

    @Override
    public String getStraightUid2Weibo(String code) {
        // 1. 从系统环境变量读取 client_secret
        String clientSecret = System.getenv("WEIBO_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) {
            log.error("系统环境变量 WEIBO_CLIENT_SECRET 未设置");
        }
        OAuthProperties.Weibo weiboConfig = oAuthProperties.getWeibo();
        // 2. 构建请求参数 (form-urlencoded)
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", weiboConfig.getClientId());
        params.add("client_secret", clientSecret);
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", weiboConfig.getRedirectHttp() + weiboConfig.getRedirectUri());

        // 3. 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        // 4. 换取 access_token
        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(
            WEIBO_TOKEN_URL, request, Map.class);
        Map<String, Object> tokenBody = tokenResponse.getBody();
        log.info("微博 token 响应: {}", tokenBody);

        if (tokenBody == null || tokenBody.containsKey("error_code")) {
            log.error("微博换取 token 失败: {}", tokenBody);
            return null;
        }

        String accessToken = (String) tokenBody.get("access_token");
        String uid = String.valueOf(tokenBody.get("uid"));
        log.info("微博 access_token: {}, uid: {}", accessToken, uid);

        return uid;
    }

    @Override
    public Map<String, Object> getPlatFormUserInfo(String accessToken) {
        // 5. 用 access_token 获取用户信息
        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.set("Authorization", "Bearer " + accessToken);
        userHeaders.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        HttpEntity<Void> userRequest = new HttpEntity<>(userHeaders);
        ResponseEntity<Map> userResponse = restTemplate.exchange(
            GITHUB_USER_URL, GET, userRequest, Map.class);
        Map<String, Object> userInfo = userResponse.getBody();
        log.info("GitHub 用户信息: {}", userInfo);
        return userInfo;
    }

    /**
     * 检查 uid 是否绑定
     */
    public boolean checkUidBound(String uid, String platform) {
        ApUserSocial apUserSocial = apUserSocialMapper.selectOne(
            Wrappers.<ApUserSocial>lambdaQuery()
                .eq(ApUserSocial::getPlatformUid, uid)
                .eq(ApUserSocial::getPlatform, platform));
        return apUserSocial != null;
    }
}
