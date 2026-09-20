package com.zhuri.coding.content.service.ai.router;

import com.zhuri.coding.content.service.ai.AiMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型路由层（模型注册 + 功能级路由 + 决策观测）
 *
 * <p>后续新增模型（低成本兜底 / 灰度）只需再注册一个 ChatModel Bean，
 * 在配置里声明 feature→model 映射即可，各调用方无感切换（按功能路由：
 * 如高价值问答走强模型、批量/评测走低成本模型）。
 * 当前仅注册了默认模型：resolve() 恒返回默认模型并输出路由决策日志，
 * 保证"第二模型就位"时无需改动任何 Service 的调用代码。
 */
@Slf4j
@Component
public class AiModelRouter {

    /** 默认模型 key（application 可配置 ai.model-router.default） */
    @Value("${ai.model-router.default:primary}")
    private String defaultModelKey;

    /** 功能 → 模型 key 映射（application 可配置 ai.model-router.features.xxx=key） */
    @org.springframework.beans.factory.annotation.Value("#{${ai.model-router.features:{}}}")
    private Map<String, String> featureMapping = Collections.emptyMap();

    /**
     * 模型定价（元 / 千 token）。成本报表按 model key 折算金额；
     * 未配置的模型按 0 计（报表会标注 unpriced，避免把"没配价格"误读成"免费"）。
     */
    @org.springframework.beans.factory.annotation.Value("#{${ai.model-router.pricing-prompt:{}}}")
    private Map<String, Double> promptPrice = Collections.emptyMap();

    @org.springframework.beans.factory.annotation.Value("#{${ai.model-router.pricing-completion:{}}}")
    private Map<String, Double> completionPrice = Collections.emptyMap();

    /** 全部已注册 ChatModel Bean（key=bean 名），Spring 自动收集 */
    private final Map<String, ChatModel> models;

    private final AiMetricsCollector metrics;

    /** token 计量（成本报表数据源） */
    private final com.zhuri.coding.content.service.ai.AiTokenMeter tokenMeter;

    @Autowired
    public AiModelRouter(Map<String, ChatModel> models, AiMetricsCollector metrics,
                         com.zhuri.coding.content.service.ai.AiTokenMeter tokenMeter) {
        this.models = models;
        this.metrics = metrics;
        this.tokenMeter = tokenMeter;
        log.info("[AiModelRouter] 注册模型 {} 个: {}", models.size(), models.keySet());
    }

    /**
     * 按功能解析应使用的模型。
     * 单模型阶段返回默认模型；多模型阶段按 feature→model 映射决策。
     */
    public ChatModel resolve(String feature) {
        String key = featureMapping.getOrDefault(feature, defaultModelKey);
        ChatModel model = models.get(key);
        if (model == null && !models.isEmpty()) {
            // 配置的 key 未注册 → 兜底首个可用模型（fail-open，不阻断 AI 主链路）
            model = models.values().iterator().next();
            log.warn("[AiModelRouter] 模型 key={} 未注册，兜底首个可用模型, feature={}", key, feature);
        }
        metrics.incr("ai_route_feature_" + feature);
        log.debug("[AiModelRouter] feature={} → model={}", feature, model == null ? "none" : model.getClass().getSimpleName());
        return model;
    }

    /** 路由配置与可用模型快照（观测端点用） */
    public Map<String, Object> configSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default", defaultModelKey);
        out.put("features", featureMapping);
        List<String> available = new ArrayList<>();
        for (Map.Entry<String, ChatModel> e : models.entrySet()) {
            available.add(e.getKey() + "(" + e.getValue().getClass().getSimpleName() + ")");
        }
        out.put("available", available);
        out.put("pricing", pricingSnapshot());
        return out;
    }

    /** 定价快照（元/千 token） */
    public Map<String, Object> pricingSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(promptPrice.keySet());
        keys.addAll(completionPrice.keySet());
        for (String k : keys) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("promptPer1k", promptPrice.getOrDefault(k, 0.0));
            p.put("completionPer1k", completionPrice.getOrDefault(k, 0.0));
            out.put(k, p);
        }
        return out;
    }

    /**
     * 成本报表：把 token 计量折算成金额，按 feature / model 两级呈现。
     *
     * <p>这是"成本 + 质量"双维度决策的数据基础：
     * <ul>
     *   <li><b>质量维度</b>：{@link #resolve(String)} 按 feature 选模型（高价值走强模型）；</li>
     *   <li><b>成本维度</b>：本报表给出每个 feature 的实际花费与单位成本，
     *       据此判断"这个功能用强模型值不值 / 换便宜模型能省多少"。</li>
     * </ul>
     *
     * <p>未配置定价的模型金额按 0 计并在 {@code unpricedModels} 中列出——
     * 宁可在报表里显式暴露"没配价"，也不让 0 被误读成"免费"。
     *
     * @param days 统计天数（1~30）
     */
    public Map<String, Object> costReport(int days) {
        int span = Math.max(1, Math.min(days, 30));
        Map<String, Map<String, Map<String, Object>>> raw = tokenMeter.summaryByFeatureModel(span);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", span);
        out.put("currency", "CNY");
        out.put("pricing", pricingSnapshot());

        java.util.Set<String> unpriced = new java.util.TreeSet<>();
        double totalCost = 0.0;
        long totalTokens = 0L;
        Map<String, Object> byFeature = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Map<String, Object>>> fe : raw.entrySet()) {
            String feature = fe.getKey();
            double featureCost = 0.0;
            long featureTokens = 0L;
            Map<String, Object> modelDetail = new LinkedHashMap<>();

            for (Map.Entry<String, Map<String, Object>> me : fe.getValue().entrySet()) {
                String model = me.getKey();
                long prompt = asLong(me.getValue().get("prompt"));
                long completion = asLong(me.getValue().get("completion"));
                Double pp = promptPrice.get(model);
                Double cp = completionPrice.get(model);
                if (pp == null && cp == null) {
                    unpriced.add(model);
                }
                // 单价单位：元/千 token
                double cost = prompt / 1000.0 * (pp == null ? 0.0 : pp)
                            + completion / 1000.0 * (cp == null ? 0.0 : cp);
                featureCost += cost;
                featureTokens += prompt + completion;

                Map<String, Object> d = new LinkedHashMap<>();
                d.put("promptTokens", prompt);
                d.put("completionTokens", completion);
                d.put("cost", round4(cost));
                d.put("priced", pp != null || cp != null);
                modelDetail.put(model, d);
            }

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("tokens", featureTokens);
            f.put("cost", round4(featureCost));
            // 单位成本便于横向比较不同功能的"贵不贵"（元/千 token）
            f.put("costPer1kTokens", featureTokens == 0 ? 0.0 : round4(featureCost / featureTokens * 1000));
            f.put("models", modelDetail);
            byFeature.put(feature, f);

            totalCost += featureCost;
            totalTokens += featureTokens;
        }

        out.put("totalTokens", totalTokens);
        out.put("totalCost", round4(totalCost));
        out.put("costPer1kTokens", totalTokens == 0 ? 0.0 : round4(totalCost / totalTokens * 1000));
        out.put("byFeature", byFeature);
        out.put("unpricedModels", new ArrayList<>(unpriced));
        return out;
    }

    private static long asLong(Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
