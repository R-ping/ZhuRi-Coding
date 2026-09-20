package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiFunnelMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiFunnelMeterImpl 单元测试（消费漏斗计数）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>{@code incr} 内存 +1 且按 feature:stage 落 Redis 日 Hash（key=ai:funnel:{yyyy-MM-dd}）；</li>
 *   <li>{@code snapshot} 返回有序进程内计数；</li>
 *   <li>{@code summary} 按 date/feature/stage 三级聚合 + totals + 转化率（分母为 0 归 0）；</li>
 *   <li><b>Redis 故障 fail-open</b>：写/读异常只告警，进程内计数照常；</li>
 *   <li>非法 field（缺 stage）与空数据不污染聚合。</li>
 * </ol>
 */
class AiFunnelMeterImplTest {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private AiFunnelMeterImpl meter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meter = new AiFunnelMeterImpl();
        ReflectionTestUtils.setField(meter, "redisTemplate", redisTemplate);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(1L);
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(true);
    }

    private static String todayKey() {
        return "ai:funnel:" + LocalDate.now().format(DAY_FMT);
    }

    @Test
    @DisplayName("incr：内存 +1 + Redis 日 Hash 按 feature:stage 落库并设置过期")
    void testIncrWritesRedisAndMemory() {
        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_STARTED);

        verify(hashOperations).increment(todayKey(), "ask:started", 1L);
        verify(redisTemplate).expire(anyString(), any());
        assertEquals(1L, meter.snapshot().get("ask:started"));
    }

    @Test
    @DisplayName("feature/stage 空白 → 分别降级 other 与 started 兜底")
    void testBlankFeatureAndStageFallback() {
        meter.incr(null, null);
        meter.incr("  ", AiFunnelMeter.STAGE_GENERATED);

        Map<String, Long> snap = meter.snapshot();
        assertEquals(1L, snap.get("other:started"));
        assertEquals(1L, snap.get("other:generated"));
    }

    @Test
    @DisplayName("snapshot：跨 feature/stage 有序累计")
    void testSnapshotAccumulates() {
        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_STARTED);
        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_GENERATED);
        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_GENERATED);
        meter.incr(AiFeatures.ASK_STREAM, AiFunnelMeter.STAGE_STARTED);

        Map<String, Long> snap = meter.snapshot();
        assertEquals(1L, snap.get("ask:started"));
        assertEquals(2L, snap.get("ask:generated"));
        assertEquals(1L, snap.get("ask_stream:started"));
    }

    @Test
    @DisplayName("summary：按 date/feature/stage 三级聚合 + totals + 转化率")
    void testSummaryAggregatesAndRates() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("ask:started", "10");
        entries.put("ask:recall_done", "9");
        entries.put("ask:generated", "6");
        entries.put("ask:cache_hit", "5");
        entries.put("ask:feedback_up", "2");
        entries.put("ask:feedback_down", "1");
        when(hashOperations.entries(todayKey())).thenReturn(entries);

        Map<String, Object> summary = meter.summary(1);

        assertEquals(1, summary.get("days"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        assertEquals(10L, totals.get("started"));
        assertEquals(9L, totals.get("recall_done"));
        assertEquals(6L, totals.get("generated"));
        assertEquals(5L, totals.get("cache_hit"));
        assertEquals(2L, totals.get("feedback_up"));
        assertEquals(1L, totals.get("feedback_down"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) summary.get("rates");
        assertEquals(0.6, (Double) rates.get("askToGenerated"), 1e-9);
        assertEquals(0.5, (Double) rates.get("cacheHitRate"), 1e-9);
        assertEquals(0.5, (Double) rates.get("generatedToFeedback"), 1e-9);
    }

    @Test
    @DisplayName("转化率分母为 0 → 归 0（不除零）")
    void testRatesZeroOnEmptyBase() {
        Map<String, Object> summary = meter.summary(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) summary.get("rates");
        assertEquals(0.0, (Double) rates.get("askToGenerated"), 1e-9);
        assertEquals(0.0, (Double) rates.get("generatedToFeedback"), 1e-9);
        assertEquals(0.0, (Double) rates.get("cacheHitRate"), 1e-9);
        assertEquals(0L, ((Map<String, Object>) summary.get("totals")).getOrDefault("started", 0L));
    }

    @Test
    @DisplayName("days 越界收敛到 1~30")
    void testDaysClamped() {
        assertEquals(1, meter.summary(0).get("days"));
        assertEquals(30, meter.summary(999).get("days"));
    }

    @Test
    @DisplayName("非法 field（缺 stage）跳过，不污染聚合")
    void testInvalidFieldSkipped() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("ask:started", "3");
        entries.put("ask:cache_hit", "1");
        entries.put("ask:generated", "2");
        entries.put("broken-field", "99"); // 缺 stage，应跳过
        when(hashOperations.entries(todayKey())).thenReturn(entries);

        Map<String, Object> summary = meter.summary(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        assertEquals(3L, totals.get("started"));
        // 3 个合法 stage（started/cache_hit/generated），非法 field 未混入聚合
        assertEquals(3, totals.size());
    }

    @Test
    @DisplayName("Redis 写失败 fail-open：进程内计数照常、不抛异常")
    void testRedisWriteFailureFailOpen() {
        lenient().when(hashOperations.increment(anyString(), any(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_STARTED); // 不应抛
        assertEquals(1L, meter.snapshot().get("ask:started"));
    }

    @Test
    @DisplayName("Redis 读失败 fail-open：summary 返回空聚合不抛")
    void testRedisReadFailureFailOpen() {
        when(hashOperations.entries(anyString())).thenThrow(new RuntimeException("redis down"));

        Map<String, Object> summary = meter.summary(7); // 不应抛
        assertEquals(7, summary.get("days"));
        assertEquals(0L, ((Map<String, Object>) summary.get("totals")).getOrDefault("started", 0L));
    }

    @Test
    @DisplayName("redisTemplate 未装配（null）→ 仅内存计数，不碰 Redis")
    void testNoRedisTemplateDegrades() {
        ReflectionTestUtils.setField(meter, "redisTemplate", null);

        meter.incr(AiFeatures.ASK, AiFunnelMeter.STAGE_STARTED);
        verify(hashOperations, never()).increment(anyString(), any(), anyLong());
        assertEquals(1L, meter.snapshot().get("ask:started"));
        assertTrue(((Map<String, Object>) meter.summary(1).get("totals")).isEmpty());
    }
}