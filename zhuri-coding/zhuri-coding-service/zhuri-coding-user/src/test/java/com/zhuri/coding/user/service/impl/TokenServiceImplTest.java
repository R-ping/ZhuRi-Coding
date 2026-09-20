package com.heima.user.service.impl;

import com.heima.common.redis.CacheService;
import com.heima.model.user.dtos.LoginResultVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService 双Token认证")
class TokenServiceImplTest {

    @Mock
    private CacheService cacheService;

    @InjectMocks
    private TokenServiceImpl tokenService;

    private static final Integer TEST_USER_ID = 1001;
    private static final String TEST_NICKNAME = "测试用户";
    private static final String TEST_PHONE = "13800138000";
    private static final String TEST_IMAGE = "avatar_head_1";

    @Captor
    private ArgumentCaptor<String> redisKeyCaptor;
    @Captor
    private ArgumentCaptor<String> redisValueCaptor;
    @Captor
    private ArgumentCaptor<Long> ttlCaptor;
    @Captor
    private ArgumentCaptor<TimeUnit> timeUnitCaptor;

    @Nested
    @DisplayName("生成双Token")
    class GenerateDualToken {

        @Test
        @DisplayName("生成成功：返回包含access_token和refresh_token的LoginResultVo")
        void testGenerateSuccess() {
            // Act
            LoginResultVo result = tokenService.generateDualToken(
                    TEST_USER_ID, TEST_NICKNAME, TEST_PHONE, TEST_IMAGE);

            // Assert
            assertNotNull(result);
            assertEquals("login", result.getStatus());
            assertNotNull(result.getAccessToken(), "access_token不能为空");
            assertNotNull(result.getRefreshToken(), "refresh_token不能为空");
            assertEquals(TEST_USER_ID, result.getUserId());
            assertEquals(TEST_NICKNAME, result.getNickName());
            assertEquals(TEST_PHONE, result.getPhone());
            assertEquals(TEST_IMAGE, result.getAvatar());

            // 验证Redis存储
            verify(cacheService).setEx(
                    redisKeyCaptor.capture(),
                    redisValueCaptor.capture(),
                    ttlCaptor.capture(),
                    timeUnitCaptor.capture());

            assertTrue(redisKeyCaptor.getValue().startsWith("refresh_token:"),
                    "Redis key应以refresh_token:开头");
            assertEquals(7, ttlCaptor.getValue(), "TTL应为7天");
            assertEquals(TimeUnit.DAYS, timeUnitCaptor.getValue());
            assertTrue(redisValueCaptor.getValue().contains("\"userId\":\"1001\""),
                    "Redis值应包含用户ID");
        }

        @Test
        @DisplayName("生成成功：手机号为空时仍可正常生成")
        void testGenerateWithNullPhone() {
            // Act
            LoginResultVo result = tokenService.generateDualToken(
                    TEST_USER_ID, TEST_NICKNAME, null, TEST_IMAGE);

            // Assert
            assertNotNull(result);
            assertEquals("login", result.getStatus());
            assertNull(result.getPhone());
            assertNotNull(result.getAccessToken());
            assertNotNull(result.getRefreshToken());
        }
    }

    @Nested
    @DisplayName("刷新Token")
    class RefreshToken {

        private static final String VALID_REFRESH_TOKEN = "valid-refresh-token-123";
        private static final String INVALID_REFRESH_TOKEN = "invalid-refresh-token";
        private static final String USER_INFO_JSON =
                "{\"userId\":\"1001\",\"nickName\":\"测试用户\",\"phone\":\"13800138000\",\"image\":\"avatar_head_1\"}";

        @Test
        @DisplayName("刷新成功：返回新的双Token")
        void testRefreshSuccess() {
            // Arrange
            // 实现为原子 GET+DEL 消费（防止同一 refresh_token 并发刷新出多个新 token）
            when(cacheService.getAndDelete("refresh_token:" + VALID_REFRESH_TOKEN))
                    .thenReturn(USER_INFO_JSON);

            // Act
            LoginResultVo result = tokenService.refreshToken(VALID_REFRESH_TOKEN);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAccessToken());
            assertNotNull(result.getRefreshToken());

            // 验证旧的refresh_token被原子消费删除
            verify(cacheService).getAndDelete("refresh_token:" + VALID_REFRESH_TOKEN);

            // 验证新的refresh_token被存储
            verify(cacheService).setEx(
                    anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("刷新失败：refresh_token为空")
        void testRefreshWithNullToken() {
            // Act
            LoginResultVo result = tokenService.refreshToken(null);

            // Assert
            assertNull(result);
            verify(cacheService, never()).get(anyString());
        }

        @Test
        @DisplayName("刷新失败：refresh_token为空白字符串")
        void testRefreshWithBlankToken() {
            // Act
            LoginResultVo result = tokenService.refreshToken("   ");

            // Assert
            assertNull(result);
            verify(cacheService, never()).get(anyString());
        }

        @Test
        @DisplayName("刷新失败：refresh_token无效或已过期")
        void testRefreshWithExpiredToken() {
            // Arrange
            // 无效 token：getAndDelete 返回 null（无值 = 无效/过期/已消费）
            when(cacheService.getAndDelete("refresh_token:" + INVALID_REFRESH_TOKEN))
                    .thenReturn(null);

            // Act
            LoginResultVo result = tokenService.refreshToken(INVALID_REFRESH_TOKEN);

            // Assert
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("吊销Token")
    class RevokeToken {

        @Test
        @DisplayName("吊销成功：删除Redis中的记录")
        void testRevokeSuccess() {
            // Arrange
            String refreshToken = "token-to-revoke";

            // Act
            tokenService.revokeRefreshToken(refreshToken);

            // Assert
            verify(cacheService).delete("refresh_token:" + refreshToken);
        }

        @Test
        @DisplayName("吊销空Token：不执行任何操作")
        void testRevokeNullToken() {
            // Act
            tokenService.revokeRefreshToken(null);

            // Assert
            verify(cacheService, never()).delete(anyString());
        }

        @Test
        @DisplayName("吊销空白Token：不执行任何操作")
        void testRevokeBlankToken() {
            // Act
            tokenService.revokeRefreshToken("");

            // Assert
            verify(cacheService, never()).delete(anyString());
        }
    }
}