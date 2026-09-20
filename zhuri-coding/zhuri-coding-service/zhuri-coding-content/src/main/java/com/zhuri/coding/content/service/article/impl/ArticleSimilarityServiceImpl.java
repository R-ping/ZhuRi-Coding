package com.zhuri.coding.content.service.article.impl;

import com.zhuri.coding.content.service.article.ArticleSimilarityService;
import com.zhuri.coding.content.utils.MarkdownUtils;
import com.zhuri.coding.content.utils.TextChunker;
import com.zhuri.coding.model.article.pojos.ApArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ArticleSimilarityServiceImpl implements ArticleSimilarityService {

    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int MAX_RESULTS = 5;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Override
    public Map<String, Object> checkSimilarity(ApArticle article, String content) {
        Map<String, Object> result = new HashMap<>();
        result.put("isSimilar", false);
        result.put("maxSimilarity", 0.0);
        result.put("similarArticleId", null);

        try {
            // 1. 生成当前文章的向量嵌入
            double[] embedding = embeddingService.generateEmbedding(content);
            if (embedding == null) {
                log.warn("Failed to generate embedding for articleId={}, skipping similarity check", article.getId());
                return result;
            }

            // 2. 查找相似文章
            List<Object[]> similarArticles = embeddingService.findSimilarArticles(
                    embedding, MAX_RESULTS, SIMILARITY_THRESHOLD);

            if (similarArticles != null && !similarArticles.isEmpty()) {
                // 排除自身
                Object[] mostSimilar = similarArticles.stream()
                        .filter(arr -> !arr[0].equals(article.getId()))
                        .findFirst()
                        .orElse(null);

                if (mostSimilar != null) {
                    double maxSimilarity = (Double) mostSimilar[1];
                    Long similarArticleId = (Long) mostSimilar[0];
                    result.put("isSimilar", true);
                    result.put("maxSimilarity", maxSimilarity);
                    result.put("similarArticleId", similarArticleId);
                    log.info("Found similar article: current={}, similar={}, similarity={}",
                            article.getId(), similarArticleId, String.format("%.4f", maxSimilarity));
                }
            }

            // 3. Step4 内容诚信：疑似 AI 水文不入向量库（防污染 RAG 检索）
            if (article.getIsAigc() != null && article.getIsAigc() == 1) {
                log.info("[Aigc] 跳过 AI 水文入向量库, articleId={}", article.getId());
                return result;
            }
            // 保存当前文章的向量嵌入（无论是否相似）
            // P0-1：同时记录来源内容指纹与版本时间 —— 内容后续被编辑时，回填/增量任务据此判定向量过期并重算
            String contentHash = ArticleEmbeddingServiceImpl.contentHash(content);
            embeddingService.saveEmbedding(article.getId(), embedding, contentHash, article.getPublishTime());
            // 父子分块：同步写子块向量，供 RAG 子块级召回（内部 fail-open，不阻断发布）
            embeddingService.saveChunks(article.getId(), TextChunker.split(
                MarkdownUtils.normalizeContent(content),
                TextChunker.DEFAULT_TARGET, TextChunker.DEFAULT_OVERLAP,
                embeddingService.getChunkMaxPerArticle()), contentHash, article.getPublishTime());

        } catch (Exception e) {
            log.error("Error checking similarity for articleId={}: {}", article.getId(), e.getMessage());
        }

        return result;
    }
}