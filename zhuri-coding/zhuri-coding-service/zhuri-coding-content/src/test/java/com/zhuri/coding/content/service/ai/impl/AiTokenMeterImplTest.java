package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.content.service.ai.AiFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiTokenMeterImpl 单元测试（token 计量）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>正常 usage → prompt/completion 分别累加，Redis 日 Hash 按 feature:model:type 落两条；</li>
 *   <li>usage 缺失 / 响应为 null → <b>计入 missingUsageCalls</b>（数据缺口可见，不静默当 0 成本）；</li>
 *   <li>估算写入 → estimatedCalls 计数（流式网关不回传 usage 时可追溯）；</li>
 *   <li><b>Redis 故障必须 fail-open</b>：计量失败绝不影响主链路（不抛异常）；</li>
 *   <li>summary 按 feature 聚合跨天求和。</li>
 * </ol>
 */
class AiTokenMeterImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private AiTokenMeterImpl meter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meter = new AiTokenMeterImpl();
        ReflectionTestUtils.setField(meter, "redisTemplate", redisTemplate);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(hashOperations.increment(anyString(), any(), anyLong())).thenReturn(1L);
        lenient().when(redisTemplate.expire(anyString(), any())).thenReturn(true);
    }

    private ChatResponse response(Integer promptTokens, Integer completionTokens, String model) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder().model(model);
        if (promptTokens != null || completionTokens != null) {
            Usage usage = promptTokens != null && completionTokens != null
                    ? new DefaultUsage(promptTokens, completionTokens)
                    : new DefaultUsage(promptTokens == null ? 0 : promptTokens,
                                       completionTokens == null ? 0 : completionTokens);
            builder = builder.usage(usage);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage("hi"))), builder.build());
    }

    @Test
    @DisplayName("正常 usage：内存累计 + Redis 按 feature:model:prompt/completion 两条落库")
    void testRecordNormalUsage() {
        meter.record(AiFeatures.ASK, response(120, 80, "qwen-plus"));

        Map<String, Long> snap = meter.snapshot();
        assertEquals(1L, snap.get("calls"));
        assertEquals(120L, snap.get("promptTokens"));
        assertEquals(80L, snap.get("completionTokens"));
        assertEquals(200L, snap.get("totalTokens"));
        assertEquals(0L, snap.get("missingUsageCalls"));

        // 输入/输出分开落库（单价不同，合并后无法折算金额）
        verify(hashOperations).increment(anyString(), eq("ask:qwen-plus:prompt"), eq(120L));
        verify(hashOperations).increment(anyString(), eq("ask:qwen-plus:completion"), eq(80L));
        verify(redisTemplate).expire(anyString(), any());
    }

    @Test
    @DisplayName("usage 缺失：计入 missingUsageCalls（缺口可见，不静默当 0 成本）")
    void testRecordMissingUsage() {
        meter.record(AiFeatures.RERANK, response(null, null, "qwen-turbo"));

        Map<String, Long> snap = meter.snapshot();
        assertEquals(1L, snap.get("calls"));
        assertEquals(0L, snap.get("totalTokens"));
        assertEquals(1L, snap.get("missingUsageCalls"));
        // 无 token 时不写 Redis（避免产生无意义的 0 字段）
        verify(hashOperations, never()).increment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("响应为 null：计入 missingUsageCalls，不抛异常")
    void testRecordNullResponse() {
        meter.record(AiFeatures.ASK, null);

        assertEquals(1L, meter.snapshot().get("missingUsageCalls"));
    }

    @Test
    @DisplayName("显式估算写入：estimatedCalls 计数（流式 usage 缺失时的可追溯口径）")
    void testRecordEstimated() {
        meter.record(AiFeatures.ASK_STREAM, "qwen-plus", 40, 60, true);

        Map<String, Long> snap = meter.snapshot();
        assertEquals(100L, snap.get("totalTokens"));
        assertEquals(1L, snap.get("estimatedCalls"));
        assertEquals(0L, snap.get("missingUsageCalls"));
    }

    @Test
    @DisplayName("feature 为空 → 归入 other（出现即说明有新调用点未归类）")
    void testFeatureFallback() {
        meter.record(null, "qwen-plus", 10, 10, false);

        verify(hashOperations).increment(anyString(), eq("other:qwen-plus:prompt"), eq(10L));
    }

    @Test
    @DisplayName("负数 token 归零（防御上游异常数据）")
    void testNegativeTokensClamped() {
        meter.record(AiFeatures.ASK, "qwen-plus", -5, -3, false);

        assertEquals(0L, meter.snapshot().get("totalTokens"));
        verify(hashOperations, never()).increment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Redis 故障 fail-open：计量写入失败不抛异常，内存指标仍累计")
    void testRedisFailureFailOpen() {
        when(hashOperations.increment(anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        meter.record(AiFeatures.ASK, response(10, 20, "qwen-plus"));   // 不应抛

        assertEquals(30L, meter.snapshot().get("totalTokens"));
    }

    @Test
    @DisplayName("summary 按 feature 聚合跨天求和")
    void testSummaryAggregatesByFeature() {
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String yesterday = java.time.LocalDate.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        Map<Object, Object> todayMap = new HashMap<>();
        todayMap.put("ask:qwen-plus:prompt", "100");
        todayMap.put("ask:qwen-plus:completion", "50");
        todayMap.put("rerank:qwen-turbo:prompt", "20");
        Map<Object, Object> yesterdayMap = new HashMap<>();
        yesterdayMap.put("ask:qwen-plus:prompt", "30");

        when(hashOperations.entries("ai:tokens:" + today)).thenReturn(todayMap);
        when(hashOperations.entries("ai:tokens:" + yesterday)).thenReturn(yesterdayMap);

        Map<String, Object> summary = meter.summary(2);

        assertEquals(2, summary.get("days"));
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) summary.get("total");
        // ask: 100+30(prompt) + 50(completion) = 180；rerank: 20 → 合计 200
        assertEquals(200L, total.get("total"));

        @SuppressWarnings("unchecked")
        Map<String, Object> byFeature = (Map<String, Object>) summary.get("byFeature");
        @SuppressWarnings("unchecked")
        Map<String, Object> ask = (Map<String, Object>) byFeature.get("ask");
        assertEquals(130L, ask.get("prompt"));
        assertEquals(50L, ask.get("completion"));
        assertEquals(180L, ask.get("total"));

        @SuppressWarnings("unchecked")
        Map<String, Object> byDay = (Map<String, Object>) summary.get("byDay");
        assertNotNull(byDay.get(today));
        assertNotNull(byDay.get(yesterday));
    }

    @Test
    @DisplayName("summary 天数越界自动收敛到 1~30")
    void testSummaryDaysClamped() {
        when(hashOperations.entries(anyString())).thenReturn(Collections.emptyMap());

        assertEquals(1, meter.summary(0).get("days"));
        assertEquals(30, meter.summary(999).get("days"));
    }

    @Test
    @DisplayName("Redis 未装配（本地无 Redis）：内存指标可用，不抛异常")
    void testNoRedisConfigured() {
        ReflectionTestUtils.setField(meter, "redisTemplate", null);

        meter.record(AiFeatures.ASK, response(5, 5, "qwen-plus"));

        assertEquals(10L, meter.snapshot().get("totalTokens"));
        // 无 Redis 时不发起任何交互（此前已 verify 过的 mock 不再被调用）
        verify(hashOperations, times(0)).increment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("累计多次调用：计数与 token 顺序累加")
    void testAccumulatesAcrossCalls() {
        meter.record(AiFeatures.ASK, response(10, 10, "m"));
        meter.record(AiFeatures.ASK, response(20, 20, "m"));

        Map<String, Long> snap = meter.snapshot();
        assertEquals(2L, snap.get("calls"));
        assertEquals(30L, snap.get("promptTokens"));
        assertEquals(30L, snap.get("completionTokens"));

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(hashOperations, times(4)).increment(anyString(), anyString(), captor.capture());
        assertTrue(captor.getAllValues().containsAll(List.of(10L, 20L)));
        assertFalse(captor.getAllValues().isEmpty());
    }
}
