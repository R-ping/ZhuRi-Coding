package com.heima.content.service.ai.router;

import com.heima.content.service.ai.AiMetricsCollector;
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

    /** 全部已注册 ChatModel Bean（key=bean 名），Spring 自动收集 */
    private final Map<String, ChatModel> models;

    private final AiMetricsCollector metrics;

    @Autowired
    public AiModelRouter(Map<String, ChatModel> models, AiMetricsCollector metrics) {
        this.models = models;
        this.metrics = metrics;
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
        return out;
    }
}
