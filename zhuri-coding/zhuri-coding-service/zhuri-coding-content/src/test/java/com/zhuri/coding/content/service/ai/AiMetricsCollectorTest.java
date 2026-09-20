package com.zhuri.coding.content.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 指标采集器单测（P2-3 新增 record 计时口径）。
 */
@DisplayName("AI 指标采集器测试")
class AiMetricsCollectorTest {

    @Test
    @DisplayName("incr 计数与 snapshot 有序输出")
    void incrShouldAccumulate() {
        AiMetricsCollector m = new AiMetricsCollector();
        m.incr("b_feature");
        m.incr("b_feature");
        m.incr("a_feature");

        var snap = m.snapshot();
        assertEquals(2L, snap.get("b_feature"));
        assertEquals(1L, snap.get("a_feature"));
        // 有序输出：a 在 b 前
        var keys = snap.keySet().stream().toList();
        assertTrue(keys.indexOf("a_feature") < keys.indexOf("b_feature"));
    }

    @Test
    @DisplayName("record 计时：snapshot 输出 count 与 avg_ms，负值忽略")
    void recordShouldAggregateCountAndAvg() {
        AiMetricsCollector m = new AiMetricsCollector();
        m.record("aiask_stream_ttft", 100);
        m.record("aiask_stream_ttft", 300);
        m.record("aiask_stream_ttft", -5);

        var snap = m.snapshot();
        assertEquals(2L, snap.get("aiask_stream_ttft_count"));
        assertEquals(200L, snap.get("aiask_stream_ttft_avg_ms"));
    }

    @Test
    @DisplayName("未记录过的计时 feature 不出现在 snapshot；全空 snapshot 非空 map")
    void snapshotShouldBeSparseAndNonNull() {
        AiMetricsCollector m = new AiMetricsCollector();
        assertFalse(m.snapshot().containsKey("nothing_count"));
        assertTrue(m.snapshot().isEmpty());
    }
}
