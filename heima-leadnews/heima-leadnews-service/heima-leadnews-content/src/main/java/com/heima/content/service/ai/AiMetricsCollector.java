package com.heima.content.service.ai;

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

    /** 功能调用次数 +1 */
    public void incr(String feature) {
        counters.computeIfAbsent(feature, k -> new AtomicLong()).incrementAndGet();
    }

    /** 快照（有序输出，便于排障） */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }
}
