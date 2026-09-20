package com.heima.user.service.impl;

import cn.hutool.core.util.StrUtil;
import com.heima.common.redis.CacheService;
import com.heima.user.service.LoginCodeService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 手机验证码服务实现（Redis 存储）。
 */
@Slf4j
@Service
public class LoginCodeServiceImpl implements LoginCodeService {

    @Autowired
    private CacheService cacheService;

    @Override
    public void issueCode(String platform, String phone, String code) {
        String sceneKey = sceneKey(platform, phone);
        cacheService.setEx(sceneKey, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        // 重新发放即重置计数，避免上一次的失败次数"跨验证码"累计把用户锁死
        cacheService.delete(attemptsKey(sceneKey));
    }

    @Override
    public boolean verifyAndConsume(String platform, String phone, String inputCode) {
        if (StrUtil.isBlank(platform) || StrUtil.isBlank(phone) || StrUtil.isBlank(inputCode)) {
            return false;
        }
        String sceneKey = sceneKey(platform, phone);
        String stored = cacheService.get(sceneKey);
        if (stored == null) {
            // 未申请、已消费或已过期
            return false;
        }
        if (!stored.equals(inputCode.trim())) {
            countFailedAttempt(sceneKey);
            return false;
        }
        // 原子消费：GETDEL 保证同一验证码只能成功使用一次，杜绝重放攻击
        String consumed = cacheService.getAndDelete(sceneKey);
        if (consumed == null || !consumed.equals(stored)) {
            // 并发场景下已被其他请求抢先消费
            log.warn("验证码已被消费或已失效, platform={}", platform);
            return false;
        }
        cacheService.delete(attemptsKey(sceneKey));
        return true;
    }

    /**
     * 累计一次错误尝试；达到上限即作废验证码。
     * 4 位数字验证码仅 1 万种组合，若不限次数可被在线枚举。
     */
    private void countFailedAttempt(String sceneKey) {
        String attemptsKey = attemptsKey(sceneKey);
        Long attempts = cacheService.incrBy(attemptsKey, 1);
        cacheService.expire(attemptsKey, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            cacheService.delete(sceneKey);
            cacheService.delete(attemptsKey);
            log.warn("验证码错误次数达上限({}), 已作废该验证码，需重新获取", MAX_ATTEMPTS);
        }
    }

    private String sceneKey(String platform, String phone) {
        return KEY_PREFIX + platform + ":" + phone;
    }

    private String attemptsKey(String sceneKey) {
        return sceneKey + ":attempts";
    }
}
