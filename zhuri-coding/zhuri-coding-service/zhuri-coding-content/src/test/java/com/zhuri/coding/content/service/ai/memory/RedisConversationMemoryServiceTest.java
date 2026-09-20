package com.zhuri.coding.content.service.ai.memory.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.redis.CacheService;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.memory.AiConversationMemoryService;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private AiLlmGateway llmGateway;

    @Mock
    private java.util.concurrent.Executor compressExecutor;

    @InjectMocks
    private RedisConversationMemoryService service;

    @BeforeEach
    void templateAvailable() {
        lenient().when(cacheService.getstringRedisTemplate()).thenReturn(stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        // 异步压缩任务同步执行，便于断言
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(compressExecutor).execute(any(Runnable.class));
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

    // ===== P2-3b 摘要压缩 =====

    private void enableCompress(boolean enabled) {
        ReflectionTestUtils.setField(service, "compressEnabled", enabled);
    }

    private List<String> msgs(int n) {
        List<String> raws = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            raws.add("{\"role\":\"user\",\"content\":\"q" + i + "\"}");
            raws.add("{\"role\":\"assistant\",\"content\":\"a" + i + "\"}");
        }
        return raws.subList(0, n);
    }

    @Test
    @DisplayName("开关关闭 / gateway 未装配 / 未达阈值 时不触发压缩")
    void compressSkippedWhenDisabledOrBelowThreshold() {
        enableCompress(false);
        assertFalse(service.compressIfNeeded(UID));

        enableCompress(true);
        // gateway mock 已注入（非 null），走长度检查
        when(listOps.size(KEY)).thenReturn((long) AiConversationMemoryService.COMPRESS_THRESHOLD_MSGS - 1);
        assertFalse(service.compressIfNeeded(UID));
        // 未达阈值不应抢互斥锁
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("达到阈值且抢锁成功：LLM 摘要后 LTRIM 裁旧头部 + LPUSH 摘要 + 刷 TTL")
    void compressShouldTrimAndPushSummary() {
        enableCompress(true);
        int threshold = (int) AiConversationMemoryService.COMPRESS_THRESHOLD_MSGS;
        when(listOps.size(KEY)).thenReturn((long) threshold);
        when(valueOps.setIfAbsent(contains(":lock:"), anyString(), any(Duration.class))).thenReturn(true);
        when(listOps.range(KEY, 0, -1)).thenReturn(msgs(threshold));
        when(llmGateway.generateOrNull(anyString(), anyString(), contains("q0"), any(), any()))
            .thenReturn("用户在关注 Java 并发与锁优化。");

        assertTrue(service.compressIfNeeded(UID));

        int batch = (int) AiConversationMemoryService.COMPRESS_BATCH_MSGS;
        verify(listOps).trim(eq(KEY), eq((long) batch), eq(-1L));
        verify(listOps).leftPush(eq(KEY), contains("早期对话摘要"));
        verify(listOps).leftPush(eq(KEY), contains("Java 并发"));
        verify(stringRedisTemplate).expire(eq(KEY), any(Duration.class));
    }

    @Test
    @DisplayName("锁被占用（并发压缩）时不重复提交任务")
    void compressSkippedWhenLockHeld() {
        enableCompress(true);
        when(listOps.size(KEY)).thenReturn((long) AiConversationMemoryService.COMPRESS_THRESHOLD_MSGS);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(service.compressIfNeeded(UID));
        verify(listOps, never()).range(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("LLM 摘要返回 null（fail-open）：放弃本次压缩，不改动会话数据")
    void compressFailOpenWhenSummaryNull() {
        enableCompress(true);
        int threshold = (int) AiConversationMemoryService.COMPRESS_THRESHOLD_MSGS;
        when(listOps.size(KEY)).thenReturn((long) threshold);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(listOps.range(KEY, 0, -1)).thenReturn(msgs(threshold));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any())).thenReturn(null);

        assertTrue(service.compressIfNeeded(UID));

        verify(listOps, never()).trim(eq(KEY), anyLong(), anyLong());
        verify(listOps, never()).leftPush(eq(KEY), anyString());
    }
}