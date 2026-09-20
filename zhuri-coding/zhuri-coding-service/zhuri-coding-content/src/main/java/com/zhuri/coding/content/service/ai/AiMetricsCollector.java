package com.zhuri.coding.content.service.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 专用指标采集器（轻量内存版）
 *
 * <p>记录各 AI 功能调用次数，供"哪个 AI 功能真的有人用/用量多少"做迭代判断；
 * 与既有 Zipkin 链路追踪互补（这里存的是业务语义指标，不是请求级 trace）。
 * 生产可平滑迁移到 Micrometer/Prometheus（计数器语义一致）。
 */
@Component
public class AiMetricsCollector {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 计时类指标：次数（P2-3 TTFT 等延迟观测） */
    private final ConcurrentHashMap<String, AtomicLong> timerCounts = new ConcurrentHashMap<>();
    /** 计时类指标：累计毫秒（与 timerCounts 配对求均值） */
    private final ConcurrentHashMap<String, AtomicLong> timerTotalMs = new ConcurrentHashMap<>();

    /** 功能调用次数 +1 */
    public void incr(String feature) {
        counters.computeIfAbsent(feature, k -> new AtomicLong()).incrementAndGet();
    }

    /** 记录一次耗时（毫秒）：按 feature 聚合次数与总量，snapshot 输出均值 */
    public void record(String feature, long ms) {
        if (ms < 0) {
            return;
        }
        timerCounts.computeIfAbsent(feature, k -> new AtomicLong()).incrementAndGet();
        timerTotalMs.computeIfAbsent(feature, k -> new AtomicLong()).addAndGet(ms);
    }

    /** 快照（有序输出，便于排障）：计数器 + 计时器（含 avg_ms） */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        timerCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                long count = e.getValue().get();
                long total = timerTotalMs.getOrDefault(e.getKey(), new AtomicLong()).get();
                out.put(e.getKey() + "_count", count);
                out.put(e.getKey() + "_avg_ms", count == 0 ? 0L : total / count);
            });
        return out;
    }
}
