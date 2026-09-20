package com.zhuri.coding.user.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.user.service.LoginCodeService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * LoginCodeServiceImpl 单元测试
 *
 * 重点验证两个安全语义：验证码一次性消费（防重放）、错误次数超限作废（防枚举）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginCodeServiceImpl 验证码校验与消费")
class LoginCodeServiceImplTest {

    private static final String PLATFORM = "app";
    private static final String PHONE = "13800000000";
    private static final String SCENE_KEY = "socialBind:app:13800000000";
    private static final String ATTEMPTS_KEY = SCENE_KEY + ":attempts";

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private LoginCodeServiceImpl loginCodeService;

    @Test
    @DisplayName("发放验证码 → 写入 Redis 并重置错误计数")
    void testIssueCode() {
        loginCodeService.issueCode(PLATFORM, PHONE, "1234");

        verify(cacheService).setEx(SCENE_KEY, "1234", LoginCodeService.CODE_TTL_MINUTES, TimeUnit.MINUTES);
        verify(cacheService).delete(ATTEMPTS_KEY);
    }

    @Test
    @DisplayName("校验通过 → 原子消费（GETDEL）并清理错误计数")
    void testVerifyAndConsumeSuccess() {
        when(cacheService.get(SCENE_KEY)).thenReturn("1234");
        when(cacheService.getAndDelete(SCENE_KEY)).thenReturn("1234");

        assertTrue(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "1234"));

        verify(cacheService).getAndDelete(SCENE_KEY);
        verify(cacheService).delete(ATTEMPTS_KEY);
    }

    @Test
    @DisplayName("同一验证码不能二次使用（防重放）")
    void testVerifyAndConsumeReplayRejected() {
        // 第一次：校验通过并消费
        when(cacheService.get(SCENE_KEY)).thenReturn("1234");
        when(cacheService.getAndDelete(SCENE_KEY)).thenReturn("1234");
        assertTrue(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "1234"));

        // 第二次：验证码已从 Redis 删除，必然失败
        when(cacheService.get(SCENE_KEY)).thenReturn(null);
        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "1234"));
    }

    @Test
    @DisplayName("并发下已被他人消费 → 返回失败（不重复放行）")
    void testVerifyAndConsumeConcurrentRejected() {
        when(cacheService.get(SCENE_KEY)).thenReturn("1234");
        when(cacheService.getAndDelete(SCENE_KEY)).thenReturn(null);

        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "1234"));
    }

    @Test
    @DisplayName("未申请/已过期的验证码 → 返回失败")
    void testVerifyWithoutIssuedCode() {
        when(cacheService.get(SCENE_KEY)).thenReturn(null);

        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "1234"));
    }

    @Test
    @DisplayName("验证码错误 → 失败并累计错误次数，未达上限不删除")
    void testWrongCodeCountsAttempt() {
        when(cacheService.get(SCENE_KEY)).thenReturn("1234");
        when(cacheService.incrBy(ATTEMPTS_KEY, 1)).thenReturn(4L);

        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "0000"));

        verify(cacheService).expire(ATTEMPTS_KEY, LoginCodeService.CODE_TTL_MINUTES, TimeUnit.MINUTES);
        verify(cacheService, times(0)).delete(SCENE_KEY);
    }

    @Test
    @DisplayName("错误次数达上限 → 作废验证码（防 4 位验证码被暴力枚举）")
    void testTooManyAttemptsInvalidatesCode() {
        when(cacheService.get(SCENE_KEY)).thenReturn("1234");
        when(cacheService.incrBy(ATTEMPTS_KEY, 1)).thenReturn((long) LoginCodeService.MAX_ATTEMPTS);

        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, "0000"));

        verify(cacheService).delete(SCENE_KEY);
        verify(cacheService).delete(ATTEMPTS_KEY);
    }

    @Test
    @DisplayName("入参为空 → 直接失败，不访问 Redis")
    void testBlankInputRejected() {
        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, ""));
        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, PHONE, null));
        assertFalse(loginCodeService.verifyAndConsume(null, PHONE, "1234"));
        assertFalse(loginCodeService.verifyAndConsume(PLATFORM, "", "1234"));
    }
}
