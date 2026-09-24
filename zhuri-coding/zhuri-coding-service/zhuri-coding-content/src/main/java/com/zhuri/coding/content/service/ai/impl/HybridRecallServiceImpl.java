package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.apis.search.ISearchClient;
import com.zhuri.coding.content.service.ai.AiCircuitBreaker;
import com.zhuri.coding.content.service.ai.AiMetricsCollector;
import com.zhuri.coding.content.service.ai.HybridRecallService;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.content.utils.RrfFusion;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.search.dtos.Bm25RecallDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 混合召回实现：向量路（父子分块优先）+ BM25 路（search 服务 ES）→ RRF 融合。
 *
 * <p>两路互相兜底：术语/代码类查询 BM25 强，口语化改写查询向量强；
 * 任一路不可用只降级不报错（BM25 经 Feign，fallback 返回空列表）。
 */
@Slf4j
@Service
public class HybridRecallServiceImpl implements HybridRecallService {

    /** 混合召回开关（关闭即退化为纯向量，可一键回滚） */
    @Value("${ai.retrieval.hybrid-enabled:true}")
    private boolean hybridEnabled;

    /** RRF 平滑常数 */
    @Value("${ai.retrieval.rrf-k:60}")
    private int rrfK;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    /** search 服务客户端（可能未注册/不可用，故 required=false） */
    @Autowired(required = false)
    private ISearchClient searchClient;

    /** 熔断器（search 目标；未注入时跳过熔断，纯靠 try/catch 降级） */
    @Autowired(required = false)
    private AiCircuitBreaker circuitBreaker;

    /** 指标收集（熔断拒绝计数；未注入时跳过） */
    @Autowired(required = false)
    private AiMetricsCollector metrics;

    @Override
    public Recall recall(String query, double[] queryEmbedding, int limit) {
        List<Long> vectorIds = new ArrayList<>();
        Map<Long, Double> vectorSims = new LinkedHashMap<>();
        try {
            List<Object[]> hits = embeddingService.recallArticles(queryEmbedding, limit);
            if (hits != null) {
                for (Object[] hit : hits) {
                    if (hit == null || !(hit[0] instanceof Number)) {
                        continue;
                    }
                    Long id = ((Number) hit[0]).longValue();
                    double sim = hit.length > 1 && hit[1] instanceof Number
                        ? ((Number) hit[1]).doubleValue() : 0d;
                    if (vectorSims.putIfAbsent(id, sim) == null) {
                        vectorIds.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[HybridRecall] 向量召回异常，本次尝试仅用关键词路: {}", e.getMessage());
        }

        List<Long> keywordIds = hybridEnabled ? bm25Recall(query, limit) : Collections.emptyList();

        if (keywordIds.isEmpty()) {
            return new Recall(vectorIds, vectorSims);
        }
        if (vectorIds.isEmpty()) {
            log.info("[HybridRecall] 向量路无结果，本次仅用 BM25: bm25={}", keywordIds.size());
            return new Recall(keywordIds, vectorSims);
        }
        List<Long> fused = RrfFusion.fuse(vectorIds, keywordIds, rrfK, limit);
        log.info("[HybridRecall] RRF 融合: vector={}, bm25={}, fused={}, k={}",
            vectorIds.size(), keywordIds.size(), fused.size(), rrfK);
        return new Recall(fused, vectorSims);
    }

    /** BM25 路：经 Feign 调 search 服务的内部召回端点；熔断打开或异常一律降级为空列表 */
    private List<Long> bm25Recall(String query, int limit) {
        if (searchClient == null || query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        // 熔断前置判定：search 已被判定故障时不再发请求。
        // 仅靠下方 try/catch 只能做到"异常后降级"，故障期间每个请求仍要等满 Feign 超时才走到 catch，
        // 会把请求线程占住（与 embedding 侧用熔断解决的“不再逐个请求等满超时”是同一类问题）。
        if (circuitBreaker != null && !circuitBreaker.allow(AiCircuitBreaker.TARGET_SEARCH)) {
            if (metrics != null) {
                metrics.incr("ai_circuit_rejected_search");
            }
            log.warn("[HybridRecall] search 服务熔断打开中，本次退化为纯向量召回");
            return Collections.emptyList();
        }
        try {
            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery(query);
            dto.setTopK(limit);
            ResponseResult result = searchClient.bm25Recall(dto);
            // 能拿到响应即说明依赖可用 → 清零失败计数（半开试探成功后也由此恢复正常）
            if (circuitBreaker != null) {
                circuitBreaker.onSuccess(AiCircuitBreaker.TARGET_SEARCH);
            }
            if (result == null || !(result.getData() instanceof List)) {
                return Collections.emptyList();
            }
            List<Long> ids = new ArrayList<>();
            for (Object item : (List<?>) result.getData()) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Long id = parseId(((Map<?, ?>) item).get("id"));
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            // 仅"依赖不可用"类异常（Feign 超时 / 连接失败等）计入熔断，参数类错误不会走到这里
            if (circuitBreaker != null) {
                circuitBreaker.onFailure(AiCircuitBreaker.TARGET_SEARCH);
            }
            log.warn("[HybridRecall] BM25 召回异常，本次退化为纯向量: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Long parseId(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
