package com.zhuri.coding.notification.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PresenceRegistry 单元测试
 *
 * 覆盖在线登记/摘除/查询/续期，以及 Redis 不可用或抛异常时的降级（不把异常抛给调用方）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceRegistry 全局在线状态")
class PresenceRegistryTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @InjectMocks
    private PresenceRegistry registry;

    private void clearRedis() throws Exception {
        Field f = PresenceRegistry.class.getDeclaredField("redis");
        f.setAccessible(true);
        f.set(registry, null);
    }

    @Nested
    @DisplayName("markOnline 登记在线")
    class MarkOnline {
        @Test
        @DisplayName("正常：写 Hash 字段并刷新 TTL")
        void testOk() {
            when(redis.opsForHash()).thenReturn(hashOps);

            registry.markOnline(100L);

            verify(hashOps).put(eq("im:presence:100"), anyString(), anyString());
            verify(redis).expire(eq("im:presence:100"), any(Duration.class));
        }

        @Test
        @DisplayName("Redis 抛异常 → 吞掉，不打断连接建立")
        void testRedisError() {
            when(redis.opsForHash()).thenThrow(new RuntimeException("redis down"));

            assertDoesNotThrow(() -> registry.markOnline(100L));
        }

        @Test
        @DisplayName("Redis 未注入 → 静默跳过")
        void testNoRedis() throws Exception {
            clearRedis();
            assertDoesNotThrow(() -> registry.markOnline(100L));
        }

        @Test
        @DisplayName("userId 为空 → 静默跳过")
        void testNullUser() {
            registry.markOnline(null);
            verify(redis, never()).opsForHash();
        }
    }

    @Nested
    @DisplayName("markOffline 摘除在线")
    class MarkOffline {
        @Test
        @DisplayName("只删本实例这一端，不影响其他实例")
        void testOk() {
            when(redis.opsForHash()).thenReturn(hashOps);

            registry.markOffline(100L);

            verify(hashOps).delete(eq("im:presence:100"), any());
        }

        @Test
        @DisplayName("Redis 抛异常 → 吞掉")
        void testRedisError() {
            when(redis.opsForHash()).thenReturn(hashOps);
            doThrow(new RuntimeException("redis down")).when(hashOps).delete(anyString(), any());

            assertDoesNotThrow(() -> registry.markOffline(100L));
        }
    }

    @Nested
    @DisplayName("isOnline 查询")
    class IsOnline {
        @Test
        @DisplayName("有在线端 → true")
        void testOnline() {
            when(redis.opsForHash()).thenReturn(hashOps);
            when(hashOps.size("im:presence:100")).thenReturn(1L);

            assertTrue(registry.isOnline(100L));
        }

        @Test
        @DisplayName("无在线端 → false")
        void testOffline() {
            when(redis.opsForHash()).thenReturn(hashOps);
            when(hashOps.size("im:presence:100")).thenReturn(0L);

            assertFalse(registry.isOnline(100L));
        }

        @Test
        @DisplayName("Redis 未注入 → false（宁可走离线分支，不基于残缺状态判断）")
        void testNoRedis() throws Exception {
            clearRedis();
            assertFalse(registry.isOnline(100L));
        }

        @Test
        @DisplayName("Redis 抛异常 → false")
        void testRedisError() {
            when(redis.opsForHash()).thenThrow(new RuntimeException("redis down"));
            assertFalse(registry.isOnline(100L));
        }
    }

    @Nested
    @DisplayName("touch 续期")
    class Touch {
        @Test
        @DisplayName("刷新 key 的 TTL")
        void testOk() {
            registry.touch(100L);
            verify(redis).expire(eq("im:presence:100"), any(Duration.class));
        }

        @Test
        @DisplayName("Redis 未注入 → 静默跳过")
        void testNoRedis() throws Exception {
            clearRedis();
            assertDoesNotThrow(() -> registry.touch(100L));
        }
    }
}
