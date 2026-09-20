package com.zhuri.coding.user.service.impl;

import com.alibaba.fastjson.JSON;
import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.model.user.dtos.LoginResultVo;
import com.zhuri.coding.user.service.TokenService;
import com.zhuri.coding.utils.common.AppJwtUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 双Token认证服务实现
 */
@Slf4j
@Service
public class TokenServiceImpl implements TokenService {

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    /** refresh_token 有效期：7天 */
    private static final long REFRESH_TOKEN_TTL = 7;
    private static final TimeUnit REFRESH_TOKEN_UNIT = TimeUnit.DAYS;

    @Autowired
    private CacheService cacheService;

    @Override
    public LoginResultVo generateDualToken(Integer userId, String nickName, String phone, String image) {
        // 1. 生成 JWT access_token（1小时有效期）
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("nickName", nickName);
        extraClaims.put("image", image);
//        extraClaims.put("phone", phone != null ? phone : "");
        String accessToken = AppJwtUtil.getToken(userId.longValue(), extraClaims);
        // 2. 生成 UUID refresh_token
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        // 3. 将 refresh_token → 用户信息存入Redis
        Map<String, String> userInfo = new HashMap<>();
        userInfo.put("userId", String.valueOf(userId));
        userInfo.put("nickName", nickName);
        userInfo.put("phone", phone);
        userInfo.put("image", image);

        String redisKey = REFRESH_TOKEN_PREFIX + refreshToken;
        cacheService.setEx(redisKey, JSON.toJSONString(userInfo),
                REFRESH_TOKEN_TTL, REFRESH_TOKEN_UNIT);
        log.info("生成双token成功: userId={}, refreshToken={}", userId, refreshToken);
        // 4. 构建返回结果
        return LoginResultVo.builder()
                .status("login")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(userId)
                .nickName(nickName)
                .phone(phone)
                .avatar(image)
                .build();
    }

    @Override
    public LoginResultVo refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("refresh_token为空");
            return null;
        }

        String redisKey = REFRESH_TOKEN_PREFIX + refreshToken;
        // 原子 GET+DEL：一次性消费，并发刷新时只有一个请求能取到值，防止同一 refresh_token 被轮换出多个新 token
        String userInfoJson = cacheService.getAndDelete(redisKey);

        if (userInfoJson == null) {
            log.warn("refresh_token无效、已过期或已被消费: {}", refreshToken);
            return null;
        }

        // 解析Redis中的用户信息
        @SuppressWarnings("unchecked")
        Map<String, String> userInfo = JSON.parseObject(userInfoJson, Map.class);
        Integer userId = Integer.valueOf(userInfo.get("userId"));
        String nickName = userInfo.get("nickName");
        String phone = userInfo.get("phone");
        String image = userInfo.get("image");

        // 生成新的双token（新的 refresh_token 重新写入 Redis 并重置 7 天 TTL，实现滑动续期）
        LoginResultVo result = generateDualToken(userId, nickName, phone, image);
        log.info("刷新token成功: userId={}", userId);
        return result;
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String redisKey = REFRESH_TOKEN_PREFIX + refreshToken;
        cacheService.delete(redisKey);
        log.info("吊销refresh_token成功: {}", refreshToken);
    }
}
