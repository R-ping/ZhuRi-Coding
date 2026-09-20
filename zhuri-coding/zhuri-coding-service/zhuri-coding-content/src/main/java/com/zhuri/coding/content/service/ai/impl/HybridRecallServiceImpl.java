package com.zhuri.coding.content.service.ai.impl;

import com.zhuri.coding.apis.search.ISearchClient;
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

    /** BM25 路：经 Feign 调 search 服务的内部召回端点；异常一律降级为空列表 */
    private List<Long> bm25Recall(String query, int limit) {
        if (searchClient == null || query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        try {
            Bm25RecallDto dto = new Bm25RecallDto();
            dto.setQuery(query);
            dto.setTopK(limit);
            ResponseResult result = searchClient.bm25Recall(dto);
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
