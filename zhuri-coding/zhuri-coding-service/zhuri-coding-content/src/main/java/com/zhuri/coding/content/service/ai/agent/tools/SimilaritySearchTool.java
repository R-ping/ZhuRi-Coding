package com.zhuri.coding.content.service.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.service.ai.agent.AgentTool;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.article.pojos.ApArticle.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具：相似文章检索（pgvector 余弦 TopK）。模型可据此判断内容是否与他人文章重复。
 *
 * <p>核心检索逻辑统一收敛在 {@link #searchSimilar(String)}：工具层（{@link #execute}）
 * 与服务层兜底（Precheck 相似预警）共用同一实现，避免双处重复导致口径漂移。
 */
@Slf4j
@Component
public class SimilaritySearchTool implements AgentTool {

    private static final double ALERT_THRESHOLD = 0.70;
    private static final int TOP_K = 5;
    private static final int SAMPLE_CHARS = 2000;

    /** 相似文章命中：文章 + 相似度（已过滤为已发布） */
    public record SimilarArticle(ApArticle article, double similarity) {
    }

    private final ArticleEmbeddingServiceImpl embeddingService;
    private final ApArticleMapper apArticleMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SimilaritySearchTool(ArticleEmbeddingServiceImpl embeddingService, ApArticleMapper apArticleMapper) {
        this.embeddingService = embeddingService;
        this.apArticleMapper = apArticleMapper;
    }

    @Override
    public String name() {
        return "search_similar_article";
    }

    @Override
    public String description() {
        return "根据正文检索社区中语义最相近的已发布文章。参数: {\"content\":\"正文文本\"}。"
            + "返回最相似文章列表（含 articleId/title/similarity），similarity 接近 1 表示高度重复。";
    }

    @Override
    public String execute(String args) {
        try {
            JsonNode node = objectMapper.readTree(args);
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                return "{\"error\":\"content 不能为空\"}";
            }
            StringBuilder sb = new StringBuilder("{\"articles\":[");
            boolean first = true;
            for (SimilarArticle hit : searchSimilar(content)) {
                if (hit.similarity() < ALERT_THRESHOLD) {
                    continue;
                }
                if (!first) {
                    sb.append(",");
                }
                first = false;
                ApArticle a = hit.article();
                sb.append("{\"articleId\":").append(a.getId())
                    .append(",\"title\":\"").append(escape(a.getTitle())).append("\"")
                    .append(",\"similarity\":").append(Math.round(hit.similarity() * 10000) / 10000.0).append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[SimilaritySearchTool] 执行失败", e);
            return "{\"error\":\"检索服务暂不可用\"}";
        }
    }

    /**
     * 核心相似检索：正文向量化 -> 余弦 TopK(TopK=5) -> 仅保留已发布文章，按相似度降序。
     *
     * <p>供 {@link #execute}（工具输出 JSON）与发布预检兜底（相似预警）共用；
     * 向量化失败或无命中时返回空列表。
     *
     * @param content 待检测正文（内部截断至 2000 字符，避免超长输入拖慢 embedding）
     */
    public List<SimilarArticle> searchSimilar(String content) {
        try {
            if (content == null || content.isBlank()) {
                return List.of();
            }
            String sample = content.length() > SAMPLE_CHARS ? content.substring(0, SAMPLE_CHARS) : content;
            double[] emb = embeddingService.generateEmbedding(sample);
            if (emb == null || emb.length == 0) {
                return List.of();
            }
            List<Object[]> hits = embeddingService.findSimilarArticles(emb, TOP_K, 0);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (Object[] h : hits) {
                ids.add((Long) h[0]);
            }
            List<SimilarArticle> result = new ArrayList<>();
            for (ApArticle a : apArticleMapper.selectBatchIds(ids)) {
                if (a.getStatus() == null || a.getStatus() != Status.PUBLISHED.getCode()) {
                    continue;
                }
                double sim = 0;
                for (Object[] h : hits) {
                    if (a.getId().equals(h[0]) && h.length > 1 && h[1] != null) {
                        sim = (Double) h[1];
                    }
                }
                result.add(new SimilarArticle(a, sim));
            }
            result.sort(Comparator.comparing(SimilarArticle::similarity).reversed());
            return result;
        } catch (Exception e) {
            log.warn("[SimilaritySearchTool] 相似检索失败", e);
            return List.of();
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}