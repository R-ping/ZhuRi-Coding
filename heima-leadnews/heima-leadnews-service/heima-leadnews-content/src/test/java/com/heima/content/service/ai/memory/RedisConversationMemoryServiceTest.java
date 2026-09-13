package com.heima.content.service.ai.memory.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.common.redis.CacheService;
import com.heima.content.service.ai.memory.AiConversationMemoryService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 会话记忆持久化单测。
 *
 * <p>验证：按用户 key 追加一轮（user+assistant）、读取按序解析、清空删 key；
 * 以及 Redis 异常时 fail-open（问答主链路不受影响）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Redis 会话记忆持久化测试")
class RedisConversationMemoryServiceTest {

    private static final int UID = 1001;
    private static final String KEY = "ai:memory:conv:1001";

    @Mock
    private CacheService cacheService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @InjectMocks
    private RedisConversationMemoryService service;

    @BeforeEach
    void templateAvailable() {
        lenient().when(cacheService.getstringRedisTemplate()).thenReturn(stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForList()).thenReturn(listOps);
    }

    @Test
    @DisplayName("appendTurn 以 user/assistant 两条 JSON 追加并滚动裁剪 + 刷新 TTL")
    void appendTurnShouldPushPairAndTrim() {
        service.appendTurn(UID, "q1", "a1");

        verify(listOps).rightPushAll(eq(KEY),
            eq("{\"role\":\"user\",\"content\":\"q1\"}"),
            eq("{\"role\":\"assistant\",\"content\":\"a1\"}"));
        verify(listOps).trim(eq(KEY), eq(-AiConversationMemoryService.MAX_PERSISTED_MSGS), eq(-1L));
        verify(stringRedisTemplate).expire(eq(KEY), any(Duration.class));
    }

    @Test
    @DisplayName("load 按存储序解析为 [{role,content}] 列表")
    void loadShouldParsePersistedTurns() {
        when(listOps.range(KEY, 0, -1)).thenReturn(List.of(
            "{\"role\":\"user\",\"content\":\"q1\"}",
            "{\"role\":\"assistant\",\"content\":\"a1\"}"));

        List<Map<String, String>> turns = service.load(UID);

        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).get("role"));
        assertEquals("q1", turns.get(0).get("content"));
        assertEquals("assistant", turns.get(1).get("role"));
        assertEquals("a1", turns.get(1).get("content"));
    }

    @Test
    @DisplayName("load 容忍脏数据（坏 JSON 跳过）")
    void loadShouldSkipCorruptedMessages() {
        when(listOps.range(KEY, 0, -1)).thenReturn(List.of("not-json", "{\"role\":\"user\",\"content\":\"ok\"}"));

        List<Map<String, String>> turns = service.load(UID);

        assertEquals(1, turns.size());
        assertEquals("ok", turns.get(0).get("content"));
    }

    @Test
    @DisplayName("Redis 异常时 load fail-open 返回空列表（不阻断问答主链路）")
    void loadShouldFailOpenOnRedisError() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.load(UID).isEmpty());
        // 非法参直接短路
        assertTrue(service.load(null).isEmpty());
    }

    @Test
    @DisplayName("clear 删除用户 key")
    void clearShouldDeleteKey() {
        service.clear(UID);
        verify(stringRedisTemplate).delete(KEY);
    }
}