package com.zhuri.coding.content.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiCircuitBreaker 单元测试（P1-1 熔断）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>打开中 → {@code allow()=false}（调用方快速失败，不再等超时）；</li>
 *   <li>窗口内失败达阈值 → 打开并设置 TTL（到期即半开）；</li>
 *   <li>半开成功 → 关闭并清零（恢复）；</li>
 *   <li><b>Redis 异常/未装配 → fail-open 放行</b>（熔断器自身不能成为新故障点）；</li>
 *   <li>llm 与 embedding 目标互不影响。</li>
 * </ol>
 *
 * <p><b>原子性约定（2026-09-21 修复后）</b>：
 * <ul>
 *   <li>失败计数与窗口过期由 <b>Lua 脚本</b>（INCR+EXPIRE）一次完成 —— 断言脚本调用而非两步命令，
 *       避免"INCR 成功而 EXPIRE 失败导致计数键永不过期"；</li>
 *   <li>打开熔断用 <b>SET NX EX</b>（{@code setIfAbsent}）—— 并发判定下只有首个线程真正打开，
 *       避免反复 {@code set} 刷新 TTL 延长熔断时长。</li>
 * </ul>
 */
class AiCircuitBreakerTest {

    private static final String TARGET = AiCircuitBreaker.TARGET_LLM;
    private static final String FAIL_KEY = "ai:cb:llm:fail";
    private static final String OPEN_KEY = "ai:cb:llm:open";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AiCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        breaker = new AiCircuitBreaker();
        ReflectionTestUtils.setField(breaker, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(breaker, "failureThreshold", 5);
        ReflectionTestUtils.setField(breaker, "windowSeconds", 60L);
        ReflectionTestUtils.setField(breaker, "openSeconds", 30L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 失败计数走 Lua 脚本（INCR+EXPIRE 原子）：默认返回 1（首次失败）
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
    }

    // ==================== allow ====================

    @Test
    @DisplayName("熔断关闭（无 open 标记）→ 放行")
    void testAllowWhenClosed() {
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(false);
        assertTrue(breaker.allow(TARGET));
    }

    @Test
    @DisplayName("熔断打开（open 标记存在）→ 拒绝（快速失败）")
    void testRejectWhenOpen() {
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(true);
        assertFalse(breaker.allow(TARGET));
    }

    @Test
    @DisplayName("Redis 查询异常 → fail-open 放行（熔断器不是新故障点）")
    void testAllowFailOpenOnRedisError() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(breaker.allow(TARGET));
    }

    @Test
    @DisplayName("未装配 Redis（本地无 Redis / 单测）→ 一律放行且不抛")
    void testAllowWithoutRedis() {
        ReflectionTestUtils.setField(breaker, "redisTemplate", null);
        assertTrue(breaker.allow(TARGET));
        breaker.onFailure(TARGET);   // 不应抛
        breaker.onSuccess(TARGET);   // 不应抛
    }

    // ==================== onFailure ====================

    @Test
    @DisplayName("首次失败：经 Lua 脚本计数 +1 并原子设置窗口 TTL（避免历史陈账累积）")
    void testFirstFailureSetsWindowTtl() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        breaker.onFailure(TARGET);

        // INCR 与 EXPIRE 在同一脚本内完成：断言脚本调用携带失败键与窗口秒数
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList(FAIL_KEY)), eq("60"));
        verify(valueOps, never()).setIfAbsent(eq(OPEN_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("窗口内失败达阈值 → 打开熔断并设置打开时长")
    void testOpenWhenThresholdReached() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);   // = threshold
        when(valueOps.setIfAbsent(eq(OPEN_KEY), eq("1"), eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);

        breaker.onFailure(TARGET);

        verify(valueOps).setIfAbsent(eq(OPEN_KEY), eq("1"), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("未达阈值 → 不打开")
    void testNotOpenBelowThreshold() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(4L);

        breaker.onFailure(TARGET);

        verify(valueOps, never()).setIfAbsent(eq(OPEN_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("已打开时 NX 不生效（不覆盖、不续期，避免永不半开）")
    void testNoDuplicateOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(9L);
        // SET NX EX 已存在 → 返回 false，表示本次未打开（也不刷新 TTL）
        when(valueOps.setIfAbsent(eq(OPEN_KEY), eq("1"), eq(30L), eq(TimeUnit.SECONDS))).thenReturn(false);

        breaker.onFailure(TARGET);

        // 断言的是 NX 语义：命令被调用但结果为"未生效"，TTL 不会被续期
        verify(valueOps).setIfAbsent(eq(OPEN_KEY), eq("1"), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("计数写入异常 → 吞掉不抛")
    void testFailureCountErrorSwallowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("redis down"));
        breaker.onFailure(TARGET);   // 不应抛
    }

    // ==================== onSuccess ====================

    @Test
    @DisplayName("成功 → 关闭熔断并清零失败计数（半开试探成功即恢复）")
    void testSuccessClosesAndResets() {
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(true);

        breaker.onSuccess(TARGET);

        verify(redisTemplate).delete(OPEN_KEY);
        verify(redisTemplate).delete(FAIL_KEY);
    }

    // ==================== 多目标隔离 & 快照 ====================

    @Test
    @DisplayName("llm 与 embedding 目标相互独立（向量服务挂了不熔断对话模型）")
    void testTargetsAreIndependent() {
        when(redisTemplate.hasKey("ai:cb:llm:open")).thenReturn(false);
        when(redisTemplate.hasKey("ai:cb:embedding:open")).thenReturn(true);

        assertTrue(breaker.allow(AiCircuitBreaker.TARGET_LLM));
        assertFalse(breaker.allow(AiCircuitBreaker.TARGET_EMBEDDING));
    }

    @Test
    @DisplayName("snapshot：输出阈值配置与各目标状态（观测端点用）")
    void testSnapshot() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("3");

        Map<String, Object> snap = breaker.snapshot();

        assertEquals(5, snap.get("failureThreshold"));
        assertEquals(30L, snap.get("openSeconds"));
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) snap.get("targets");
        assertTrue(targets.containsKey(AiCircuitBreaker.TARGET_LLM));
        assertTrue(targets.containsKey(AiCircuitBreaker.TARGET_EMBEDDING));
        // 新增熔断目标必须同时登记进 ALL_TARGETS，否则会漏出观测端点（本断言即为此护栏）
        assertTrue(targets.containsKey(AiCircuitBreaker.TARGET_SEARCH));
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) targets.get(AiCircuitBreaker.TARGET_LLM);
        assertEquals(3L, llm.get("recentFailures"));
        assertEquals(false, llm.get("open"));
    }

    @Test
    @DisplayName("snapshot：Redis 异常时标记 -1（状态不可读），不抛")
    void testSnapshotHandlesRedisError() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("down"));

        Map<String, Object> snap = breaker.snapshot();

        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) snap.get("targets");
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) targets.get(AiCircuitBreaker.TARGET_LLM);
        assertEquals(-1L, llm.get("recentFailures"));
    }
}
