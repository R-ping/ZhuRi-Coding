package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiFunnelMeter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费漏斗计实现：Redis 日 Hash 累计（跨进程/跨重启）+ 内存快照。
 *
 * <p>存储结构：{@code HASH ai:funnel:{yyyy-MM-dd}}，field = {@code feature:stage}，value = 次数；
 * TTL 40 天（与 token 计量一致，保留月度对比）。
 *
 * <p>两个守住边界的点：
 * <ul>
 *   <li>加载阶段只做计数，<b>任何异常不得影响问答主链路</b>（Redis 写/读均有 try-catch fail-open）；</li>
 *   <li>field 解析：feature/stage 均不含 ':'（feature 用 {@link AiFeatures} 常量保证），
 *       非法 field（缺 stage）跳过不污染聚合。</li>
 * </ul>
 */
@Service
@Slf4j
public class AiFunnelMeterImpl implements AiFunnelMeter {

    private static final String KEY_PREFIX = "ai:funnel:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 保留天数：够做月度环比，且不至于无限增长 */
    private static final Duration TTL = Duration.ofDays(40);

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /** 进程内计数（feature:stage → 次数） */
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public void incr(String feature, String stage) {
        String safeFeature = sanitize(feature);
        String safeStage = (stage == null || stage.isBlank()) ? STAGE_STARTED : stage;
        counters.computeIfAbsent(safeFeature + ":" + safeStage, k -> new AtomicLong()).incrementAndGet();
        if (redisTemplate == null) {
            return;
        }
        String key = KEY_PREFIX + LocalDate.now().format(DAY_FMT);
        try {
            redisTemplate.opsForHash().increment(key, safeFeature + ":" + safeStage, 1);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            // 埋点旁路：Redis 故障只告警，绝不影响问答主链路
            log.warn("[AiFunnelMeter] 漏斗计数写入失败(忽略): feature={}, stage={}, err={}",
                    safeFeature, safeStage, e.getMessage());
        }
    }

    @Override
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    @Override
    public Map<String, Object> summary(int days) {
        int span = Math.max(1, Math.min(days, 30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", span);

        // date → feature → stage → 次数（保持插入序：近→远）
        Map<String, Map<String, Map<String, Long>>> byDay = new LinkedHashMap<>();
        // feature → stage → 次数（跨天求和）
        Map<String, Map<String, Long>> byFeature = new TreeMap<>();
        // stage → 次数（全 feature 汇总）
        Map<String, Long> totals = new TreeMap<>();

        LocalDate today = LocalDate.now();
        for (int i = span - 1; i >= 0; i--) {
            String dayKey = today.minusDays(i).format(DAY_FMT);
            Map<Object, Object> entries = readHash(KEY_PREFIX + dayKey);
            if (entries.isEmpty()) {
                continue;
            }
            Map<String, Map<String, Long>> dayAgg = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String fieldName = String.valueOf(e.getKey());
                String[] parts = fieldName.split(":");
                if (parts.length < 2) {
                    continue; // 非法 field（缺 stage）不污染聚合
                }
                String feature = parts[0];
                String stage = parts[1];
                long value = parseLong(e.getValue());
                add(dayAgg, feature, stage, value);
                addByFeature(byFeature, feature, stage, value);
                totals.merge(stage, value, Long::sum);
            }
            if (!dayAgg.isEmpty()) {
                byDay.put(dayKey, dayAgg);
            }
        }

        out.put("snapshot", snapshot());
        out.put("byFeature", byFeature);
        out.put("byDay", byDay);
        out.put("totals", totals);
        out.put("rates", rates(totals));
        return out;
    }

    /** 转化率（分母为 0 归 0，避免除零；保留 4 位小数） */
    private Map<String, Object> rates(Map<String, Long> totals) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("askToGenerated", ratio(totals, STAGE_GENERATED, STAGE_STARTED));
        r.put("generatedToFeedback",
                rateSum(totals, STAGE_FEEDBACK_UP, STAGE_FEEDBACK_DOWN, STAGE_GENERATED));
        r.put("cacheHitRate", ratio(totals, STAGE_CACHE_HIT, STAGE_STARTED));
        return r;
    }

    private static double ratio(Map<String, Long> totals, String stage, String base) {
        long b = totals.getOrDefault(base, 0L);
        return b == 0 ? 0.0 : round4(totals.getOrDefault(stage, 0L) * 1.0 / b);
    }

    private static double rateSum(Map<String, Long> totals, String a, String b, String base) {
        long s = totals.getOrDefault(a, 0L) + totals.getOrDefault(b, 0L);
        long baseCount = totals.getOrDefault(base, 0L);
        return baseCount == 0 ? 0.0 : round4(s * 1.0 / baseCount);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** byDay 结构：date → feature → stage → 次数 */
    private static void add(Map<String, Map<String, Long>> dayAgg, String feature, String stage, long value) {
        dayAgg.computeIfAbsent(feature, k -> new LinkedHashMap<>())
                .merge(stage, value, Long::sum);
    }

    /** byFeature 结构：feature → stage → 次数 */
    private static void addByFeature(Map<String, Map<String, Long>> byFeature, String feature, String stage, long value) {
        byFeature.computeIfAbsent(feature, k -> new TreeMap<>())
                .merge(stage, value, Long::sum);
    }

    private Map<Object, Object> readHash(String key) {
        if (redisTemplate == null) {
            return new HashMap<>();
        }
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            return entries != null ? entries : new HashMap<>();
        } catch (Exception e) {
            log.warn("[AiFunnelMeter] 读取漏斗数据失败(忽略): key={}, err={}", key, e.getMessage());
            return new HashMap<>();
        }
    }

    private static String sanitize(String feature) {
        return (feature == null || feature.isBlank()) ? AiFeatures.OTHER : feature;
    }

    private static long parseLong(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}