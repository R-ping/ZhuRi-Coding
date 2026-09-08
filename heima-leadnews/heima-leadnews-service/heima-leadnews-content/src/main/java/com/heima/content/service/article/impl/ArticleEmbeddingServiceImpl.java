package com.heima.content.service.article.impl;

import com.heima.content.service.article.ArticleEmbeddingService;
import org.springframework.ai.embedding.EmbeddingModel;
import com.heima.model.article.pojos.ApArticleEmbedding;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ArticleEmbeddingServiceImpl implements ArticleEmbeddingService {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Override
    public void saveEmbedding(Long articleId, double[] embedding) {
        if (embedding == null || embedding.length == 0) {
            log.warn("Empty embedding for articleId={}, skipping save", articleId);
            return;
        }
        if (pgVectorJdbcTemplate == null) {
            log.debug("PgVector not configured, skipping embedding save for articleId={}", articleId);
            return;
        }

        try {
            // 先删除旧记录
            pgVectorJdbcTemplate.update("DELETE FROM ap_article_embedding WHERE article_id = ?", articleId);

            // 使用JDBC直接操作pgvector数组
            pgVectorJdbcTemplate.update((Connection conn) -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ap_article_embedding (article_id, embedding, created_time) VALUES (?, ?, ?)"
                );
                ps.setLong(1, articleId);

                // 将double[]转换为pgvector类型
                Array vectorArray = conn.createArrayOf("float8",
                        java.util.Arrays.stream(embedding).boxed().toArray(Double[]::new));
                ps.setArray(2, vectorArray);
                ps.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));
                return ps;
            });

            log.info("Saved embedding for articleId={}, dimension={}", articleId, embedding.length);
        } catch (Exception e) {
            log.error("Failed to save embedding for articleId={}: {}", articleId, e.getMessage());
        }
    }

    @Override
    public ApArticleEmbedding getEmbedding(Long articleId) {
        if (pgVectorJdbcTemplate == null) {
            return null;
        }
        try {
            return pgVectorJdbcTemplate.query(
                    "SELECT id, article_id, embedding::text, created_time FROM ap_article_embedding WHERE article_id = ?",
                    (ResultSet rs) -> {
                        if (rs.next()) {
                            ApArticleEmbedding emb = new ApArticleEmbedding();
                            emb.setId(rs.getLong("id"));
                            emb.setArticleId(rs.getLong("article_id"));
                            // Parse pgvector text representation to double[]
                            String vectorStr = rs.getString("embedding");
                            if (vectorStr != null) {
                                vectorStr = vectorStr.replaceAll("[\\[\\]\\s]", "");
                                if (!vectorStr.isEmpty()) {
                                    String[] parts = vectorStr.split(",");
                                    double[] vec = new double[parts.length];
                                    for (int i = 0; i < parts.length; i++) {
                                        vec[i] = Double.parseDouble(parts[i]);
                                    }
                                    emb.setEmbedding(vec);
                                }
                            }
                            emb.setCreatedTime(rs.getTimestamp("created_time"));
                            return emb;
                        }
                        return null;
                    }
            );
        } catch (Exception e) {
            log.error("Failed to get embedding for articleId={}: {}", articleId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Object[]> findSimilarArticles(double[] embedding, int limit, double threshold) {
        List<Object[]> results = new ArrayList<>();
        if (pgVectorJdbcTemplate == null) {
            return results;
        }
        try {
            // 使用余弦距离（<=>）进行相似度检索，余弦距离 = 1 - 余弦相似度
            return pgVectorJdbcTemplate.query(
                    (Connection conn) -> {
                        PreparedStatement ps = conn.prepareStatement(
                                "SELECT article_id, 1 - (embedding <=> ?::vector) AS similarity " +
                                "FROM ap_article_embedding " +
                                "WHERE 1 - (embedding <=> ?::vector) >= ? " +
                                "ORDER BY similarity DESC LIMIT ?"
                        );
                        Array vectorArray = conn.createArrayOf("float8",
                                java.util.Arrays.stream(embedding).boxed().toArray(Double[]::new));
                        ps.setArray(1, vectorArray);
                        ps.setArray(2, vectorArray);
                        ps.setDouble(3, threshold);
                        ps.setInt(4, limit);
                        return ps;
                    },
                    (ResultSet rs) -> {
                        while (rs.next()) {
                            results.add(new Object[]{
                                    rs.getLong("article_id"),
                                    rs.getDouble("similarity")
                            });
                        }
                        return results;
                    }
            );
        } catch (Exception e) {
            log.error("Failed to find similar articles: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 生成文章向量嵌入
     */
    public double[] generateEmbedding(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        // 截断过长内容（embedding模型有token限制）
        String truncated = content.length() > 2000 ? content.substring(0, 2000) : content;
        float[] emb = embeddingModel.embed(truncated);
        if (emb == null || emb.length == 0) {
            return null;
        }
        double[] vector = new double[emb.length];
        for (int i = 0; i < emb.length; i++) {
            vector[i] = emb[i];
        }
        return vector;
    }
}