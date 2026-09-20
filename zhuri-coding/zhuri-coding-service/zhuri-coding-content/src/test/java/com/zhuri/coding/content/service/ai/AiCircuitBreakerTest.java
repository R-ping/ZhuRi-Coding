package com.zhuri.coding.content.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        lenient().when(valueOps.increment(anyString())).thenReturn(1L);
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
    @DisplayName("首次失败：计数 +1 并设置窗口 TTL（避免历史陈账累积）")
    void testFirstFailureSetsWindowTtl() {
        when(valueOps.increment(FAIL_KEY)).thenReturn(1L);
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(false);

        breaker.onFailure(TARGET);

        verify(redisTemplate).expire(eq(FAIL_KEY), eq(60L), eq(TimeUnit.SECONDS));
        verify(valueOps, never()).set(eq(OPEN_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("窗口内失败达阈值 → 打开熔断并设置打开时长")
    void testOpenWhenThresholdReached() {
        when(valueOps.increment(FAIL_KEY)).thenReturn(5L);   // = threshold
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(false);

        breaker.onFailure(TARGET);

        verify(valueOps).set(eq(OPEN_KEY), eq("1"), eq(30L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("未达阈值 → 不打开")
    void testNotOpenBelowThreshold() {
        when(valueOps.increment(FAIL_KEY)).thenReturn(4L);

        breaker.onFailure(TARGET);

        verify(valueOps, never()).set(eq(OPEN_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("已打开时不重复写 open（避免反复续期导致永不半开）")
    void testNoDuplicateOpen() {
        when(valueOps.increment(FAIL_KEY)).thenReturn(9L);
        when(redisTemplate.hasKey(OPEN_KEY)).thenReturn(true);

        breaker.onFailure(TARGET);

        verify(valueOps, never()).set(eq(OPEN_KEY), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("计数写入异常 → 吞掉不抛")
    void testFailureCountErrorSwallowed() {
        when(valueOps.increment(FAIL_KEY)).thenThrow(new RuntimeException("redis down"));
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
