package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiTokenMeter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * token 计量实现：Redis 日 Hash 累计（跨进程/跨重启）+ 内存快照（快速自检）。
 *
 * <p>存储结构：{@code HASH ai:tokens:{yyyy-MM-dd}}，field = {@code feature:model:prompt|completion}，
 * value = 累计 token 数；TTL 40 天（保留跨月对比能力，避免 Redis 无限膨胀）。
 *
 * <p>三个必须守住的边界：
 * <ol>
 *   <li><b>usage 可能为 null</b>：部分模型/网关不回传 usage → 记 0 并累加 {@code missingUsageCalls}，
 *       让"数据缺口"可见，而不是静默当成 0 成本；</li>
 *   <li><b>流式 usage 常在末块</b>：由调用方（AiLlmGateway）取末块或按字符估算后走显式重载；</li>
 *   <li><b>Redis 故障不得影响主链路</b>：所有 Redis 操作 try-catch，仅告警。</li>
 * </ol>
 */
@Service
@Slf4j
public class AiTokenMeterImpl implements AiTokenMeter {

    private static final String KEY_PREFIX = "ai:tokens:";
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 保留天数：够做月度环比，且不至于无限增长 */
    private static final Duration TTL = Duration.ofDays(40);
    private static final String UNKNOWN_MODEL = "unknown";

    // 说明：字符→token 的估算口径不在本类 —— 真实估算发生在 AiLlmGateway#estimateTokens
    //（中文约 1 token ≈ 2 字符，宁低估不虚报成本）。本类只负责累计与存储，
    //  避免在两处各留一份常量导致口径漂移。

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final AtomicLong promptTotal = new AtomicLong();
    private final AtomicLong completionTotal = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong missingUsageCalls = new AtomicLong();
    private final AtomicLong estimatedCalls = new AtomicLong();

    @Override
    public void record(String feature, ChatResponse response) {
        String safeFeature = normalizeFeature(feature);
        if (response == null || response.getMetadata() == null) {
            // 该路径不经过 accumulate，故此处自行累计 calls（保持「每次 record 恰好 calls+1」）
            calls.incrementAndGet();
            missingUsageCalls.incrementAndGet();
            return;
        }
        String model = response.getMetadata().getModel() != null
                ? response.getMetadata().getModel() : UNKNOWN_MODEL;
        Usage usage = response.getMetadata().getUsage();
        Integer p = usage != null ? usage.getPromptTokens() : null;
        Integer c = usage != null ? usage.getCompletionTokens() : null;
        // 判定「网关未回传用量」：usage 对象缺失，或 prompt/completion 全为 0
        // （真实模型调用不可能 0 token —— prompt 至少有几十 token，故 0/0 等价于未回传）
        if (p == null && c == null || orZero(p) + orZero(c) == 0) {
            // 记 0 并把缺口计入指标（不静默当成 0 成本）；calls 由 accumulate 统一累计，避免重复计数
            missingUsageCalls.incrementAndGet();
            accumulate(safeFeature, model, 0, 0);
            return;
        }
        accumulate(safeFeature, model, orZero(p), orZero(c));
    }

    @Override
    public void record(String feature, String model, int promptTokens, int completionTokens, boolean estimated) {
        if (estimated) {
            estimatedCalls.incrementAndGet();
        }
        accumulate(normalizeFeature(feature), model != null ? model : UNKNOWN_MODEL,
                Math.max(0, promptTokens), Math.max(0, completionTokens));
    }

    /** 统一累加出口：内存指标 + Redis 日 Hash */
    private void accumulate(String feature, String model, int prompt, int completion) {
        calls.incrementAndGet();
        promptTotal.addAndGet(prompt);
        completionTotal.addAndGet(completion);
        if (redisTemplate == null) {
            return;
        }
        String key = KEY_PREFIX + LocalDate.now().format(DAY_FMT);
        try {
            if (prompt > 0) {
                redisTemplate.opsForHash().increment(key, field(feature, model, "prompt"), prompt);
            }
            if (completion > 0) {
                redisTemplate.opsForHash().increment(key, field(feature, model, "completion"), completion);
            }
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            // 计量旁路：Redis 故障只告警，绝不影响 LLM 主链路
            log.warn("[AiTokenMeter] 计量写入失败(忽略): feature={}, model={}, err={}",
                    feature, model, e.getMessage());
        }
    }

    @Override
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("calls", calls.get());
        m.put("promptTokens", promptTotal.get());
        m.put("completionTokens", completionTotal.get());
        m.put("totalTokens", promptTotal.get() + completionTotal.get());
        m.put("missingUsageCalls", missingUsageCalls.get());
        m.put("estimatedCalls", estimatedCalls.get());
        return m;
    }

    @Override
    public Map<String, Object> summary(int days) {
        int span = Math.max(1, Math.min(days, 30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", span);

        // 按 feature 聚合（跨天求和）
        Map<String, long[]> byFeature = new TreeMap<>();
        Map<String, long[]> byDay = new TreeMap<>();
        long[] total = new long[2];

        LocalDate today = LocalDate.now();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            String dayKey = day.format(DAY_FMT);
            Map<Object, Object> entries = readHash(KEY_PREFIX + dayKey);
            if (entries.isEmpty()) {
                continue;
            }
            long[] dayAgg = new long[2];
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String fieldName = String.valueOf(e.getKey());
                long value = parseLong(e.getValue());
                // field 形如 feature:model:type，feature 不含 ':'，故取第一段即可
                String feature = fieldName.contains(":") ? fieldName.substring(0, fieldName.indexOf(':')) : fieldName;
                boolean isPrompt = fieldName.endsWith(":prompt");
                int idx = isPrompt ? 0 : 1;
                byFeature.computeIfAbsent(feature, k -> new long[2])[idx] += value;
                dayAgg[idx] += value;
                total[idx] += value;
            }
            byDay.put(dayKey, dayAgg);
        }

        out.put("total", tokenBlock(total));
        Map<String, Object> featureOut = new LinkedHashMap<>();
        byFeature.forEach((k, v) -> featureOut.put(k, tokenBlock(v)));
        out.put("byFeature", featureOut);
        Map<String, Object> dayOut = new LinkedHashMap<>();
        byDay.forEach((k, v) -> dayOut.put(k, tokenBlock(v)));
        out.put("byDay", dayOut);
        return out;
    }

    @Override
    public Map<String, Map<String, Map<String, Object>>> summaryByFeatureModel(int days) {
        int span = Math.max(1, Math.min(days, 30));
        // feature → model → [prompt, completion]
        Map<String, Map<String, long[]>> agg = new TreeMap<>();
        LocalDate today = LocalDate.now();
        for (int i = span - 1; i >= 0; i--) {
            String dayKey = today.minusDays(i).format(DAY_FMT);
            for (Map.Entry<Object, Object> e : readHash(KEY_PREFIX + dayKey).entrySet()) {
                String fieldName = String.valueOf(e.getKey());
                String[] parts = fieldName.split(":");
                if (parts.length < 3) {
                    continue;
                }
                String feature = parts[0];
                String model = parts[1];
                boolean isPrompt = "prompt".equals(parts[2]);
                long value = parseLong(e.getValue());
                agg.computeIfAbsent(feature, k -> new TreeMap<>())
                   .computeIfAbsent(model, k -> new long[2])[isPrompt ? 0 : 1] += value;
            }
        }
        Map<String, Map<String, Map<String, Object>>> out = new LinkedHashMap<>();
        agg.forEach((feature, models) -> {
            Map<String, Map<String, Object>> modelOut = new LinkedHashMap<>();
            models.forEach((model, pair) -> modelOut.put(model, tokenBlock(pair)));
            out.put(feature, modelOut);
        });
        return out;
    }

    private Map<String, Object> tokenBlock(long[] pair) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prompt", pair[0]);
        m.put("completion", pair[1]);
        m.put("total", pair[0] + pair[1]);
        return m;
    }

    private Map<Object, Object> readHash(String key) {
        if (redisTemplate == null) {
            return new HashMap<>();
        }
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            return entries != null ? entries : new HashMap<>();
        } catch (Exception e) {
            log.warn("[AiTokenMeter] 读取计量数据失败(忽略): key={}, err={}", key, e.getMessage());
            return new HashMap<>();
        }
    }

    private static String field(String feature, String model, String type) {
        return feature + ":" + model + ":" + type;
    }

    private static String normalizeFeature(String feature) {
        return (feature == null || feature.isBlank()) ? AiFeatures.OTHER : feature;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : Math.max(0, v);
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
