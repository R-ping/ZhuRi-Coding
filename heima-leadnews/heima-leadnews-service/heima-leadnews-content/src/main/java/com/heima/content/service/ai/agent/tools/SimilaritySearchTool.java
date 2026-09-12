package com.heima.content.service.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.agent.AgentTool;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具：相似文章检索（pgvector 余弦 TopK）。模型可据此判断内容是否与他人文章重复。
 */
@Slf4j
@Component
public class SimilaritySearchTool implements AgentTool {

    private static final double ALERT_THRESHOLD = 0.70;

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
            String sample = content.length() > 2000 ? content.substring(0, 2000) : content;
            double[] emb = embeddingService.generateEmbedding(sample);
            if (emb == null) {
                return "{\"articles\":[]}";
            }
            List<Object[]> hits = embeddingService.findSimilarArticles(emb, 5, 0);
            if (hits == null || hits.isEmpty()) {
                return "{\"articles\":[]}";
            }
            List<Long> ids = new ArrayList<>();
            for (Object[] h : hits) {
                ids.add((Long) h[0]);
            }
            StringBuilder sb = new StringBuilder("{\"articles\":[");
            boolean first = true;
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
                if (sim < ALERT_THRESHOLD) {
                    continue;
                }
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("{\"articleId\":").append(a.getId())
                    .append(",\"title\":\"").append(escape(a.getTitle())).append("\"")
                    .append(",\"similarity\":").append(Math.round(sim * 10000) / 10000.0).append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[SimilaritySearchTool] 执行失败", e);
            return "{\"error\":\"检索服务暂不可用\"}";
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
