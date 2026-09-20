package com.heima.content.service.search.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.content.service.search.SemanticSearchService;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.search.dtos.SemanticSearchDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语义搜索实现（向量化增强）
 *
 * <p>query 经 Embedding 向量化 → pgvector(ap_article_embedding, 1024 维) 余弦召回 → 过滤
 * 已发布且非 AIGC 水文 → 组装与 ES 搜索结果字段对齐的 map 列表。复用 RAG 的向量基建与
 * 内容诚信治理过滤（is_aigc=1 不入语义召回）。
 */
@Slf4j
@Service
public class SemanticSearchServiceImpl implements SemanticSearchService {

    private static final int TOP_K_DEFAULT = 8;
    private static final int TOP_K_MAX = 20;
    /** 向量宽召回倍数（代码层过滤后取 topK，补偿 embedding 偶发误命中） */
    private static final int RECALL_FACTOR = 3;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public ResponseResult semanticSearch(SemanticSearchDto dto) {
        if (dto == null || StringUtils.isBlank(dto.getSearchWords())) {
            return ResponseResult.errorResult(AppHttpCodeEnum.PARAM_INVALID, "搜索词不能为空");
        }
        String query = dto.getSearchWords().trim();
        int topK = dto.getTopK() == null ? TOP_K_DEFAULT
            : Math.max(1, Math.min(dto.getTopK(), TOP_K_MAX));

        double[] queryEmb = embeddingService.generateEmbedding(query);
        if (queryEmb == null || queryEmb.length == 0) {
            log.warn("[SemanticSearch] query 向量化失败, words={}", query);
            return ResponseResult.errorResult(AppHttpCodeEnum.SERVER_ERROR, "语义检索暂不可用");
        }
        List<Object[]> hits = embeddingService.findSimilarArticles(queryEmb, topK * RECALL_FACTOR, 0d);
        if (hits == null || hits.isEmpty()) {
            return ResponseResult.okResult(new ArrayList<>());
        }
        Map<Long, Double> simMap = new LinkedHashMap<>();
        for (Object[] hit : hits) {
            Long articleId = hit[0] instanceof Number ? ((Number) hit[0]).longValue() : null;
            double similarity = hit.length > 1 && hit[1] != null ? ((Number) hit[1]).doubleValue() : 0d;
            if (articleId != null) {
                simMap.put(articleId, similarity);
            }
        }
        // 过滤：仅已发布、非 AIGC 水文；按相似度降序取 topK
        List<ApArticle> articles = apArticleMapper.selectBatchIds(simMap.keySet()).stream()
            .filter(a -> a.getStatus() != null && a.getStatus() == Status.PUBLISHED.getCode())
            .filter(a -> a.getIsAigc() == null || a.getIsAigc() != 1)
            .sorted((a1, a2) -> Double.compare(
                simMap.getOrDefault(a2.getId(), 0d), simMap.getOrDefault(a1.getId(), 0d)))
            .limit(topK)
            .collect(Collectors.toList());

        List<Map<String, Object>> list = new ArrayList<>();
        for (ApArticle a : articles) {
            list.add(toSearchMap(a));
        }
        log.info("[SemanticSearch] words={}, 召回 {} 条", query, list.size());
        return ResponseResult.okResult(list);
    }

    /** 组装与 ES 搜索结果 map 对齐的字段（id/publishTime 保持 JS 安全字符串/毫秒） */
    private Map<String, Object> toSearchMap(ApArticle a) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", String.valueOf(a.getId()));
        map.put("title", a.getTitle() != null ? a.getTitle() : "");
        map.put("h_title", a.getTitle() != null ? a.getTitle() : "");
        map.put("publishTime", a.getPublishTime());
        map.put("layout", a.getLayout() != null ? a.getLayout() : 0);
        map.put("authorId", a.getAuthorId() != null ? String.valueOf(a.getAuthorId()) : "");
        map.put("authorName", a.getAuthorName() != null ? a.getAuthorName() : "");
        map.put("staticUrl", "");
        map.put("content", "");
        List<String> images = new ArrayList<>();
        if (a.getCoverImage() != null && !a.getCoverImage().isBlank()) {
            images.add(a.getCoverImage());
        }
        map.put("images", images);
        map.put("semantic", Boolean.TRUE); // 语义补充召回标记（前端可选展示）
        return map;
    }
}
