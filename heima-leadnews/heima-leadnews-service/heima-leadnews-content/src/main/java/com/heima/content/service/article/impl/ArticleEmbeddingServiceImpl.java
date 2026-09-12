package com.heima.content.service.article.impl;

import com.heima.content.service.article.ArticleEmbeddingService;
import com.heima.content.utils.PgVectorUtil;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ArticleEmbeddingServiceImpl implements ArticleEmbeddingService {

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 分块检索开关（父子分块 small-to-big）：关闭即回退文章级向量，可一键回滚 */
    @Value("${ai.retrieval.chunk-enabled:true}")
    private boolean chunkEnabled;

    /** 单篇最大分块数（控 embedding 调用成本） */
    @Value("${ai.retrieval.chunk-max-per-article:30}")
    private int chunkMaxPerArticle;

    /** 单篇最大分块数对外暴露（供写入侧裁剪） */
    public int getChunkMaxPerArticle() {
        return chunkMaxPerArticle;
    }

    public boolean isChunkEnabled() {
        return chunkEnabled;
    }

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

    // ==================== 父子分块检索（small-to-big） ====================

    /**
     * 写入文章分块向量（子块）。先删旧块保证幂等；批量 embedding 失败自动降级逐条。
     * 分块文本长度可控（≤ 数百字），因此不再截断。
     */
    public void saveChunks(Long articleId, List<String> chunks) {
        if (articleId == null || chunks == null || chunks.isEmpty() || pgVectorJdbcTemplate == null) {
            return;
        }
        try {
            List<String> valid = new ArrayList<>();
            for (String c : chunks) {
                if (c != null && !c.isBlank()) {
                    valid.add(c);
                }
            }
            if (valid.isEmpty()) {
                return;
            }
            List<float[]> embeddings = embedAll(valid);
            if (embeddings == null || embeddings.size() != valid.size()) {
                log.warn("Chunk embeddings unavailable, skip chunk save for articleId={}", articleId);
                return;
            }
            pgVectorJdbcTemplate.update("DELETE FROM ap_article_chunk WHERE article_id = ?", articleId);
            for (int i = 0; i < valid.size(); i++) {
                pgVectorJdbcTemplate.update(
                    "INSERT INTO ap_article_chunk (article_id, chunk_index, content, embedding, created_time) "
                        + "VALUES (?, ?, ?, CAST(? AS vector), now())",
                    articleId, i, valid.get(i), PgVectorUtil.toLiteral(embeddings.get(i)));
            }
            log.info("Saved {} chunks for articleId={}", valid.size(), articleId);
        } catch (Exception e) {
            log.error("Failed to save chunks for articleId={}: {}", articleId, e.getMessage());
        }
    }

    /** 是否已有分块（回填任务据此判断是否需要补块） */
    public boolean hasChunks(Long articleId) {
        if (articleId == null || pgVectorJdbcTemplate == null) {
            return false;
        }
        try {
            Integer cnt = pgVectorJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ap_article_chunk WHERE article_id = ?", Integer.class, articleId);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            log.debug("Check chunks failed for articleId={}: {}", articleId, e.getMessage());
            return false;
        }
    }

    /**
     * 子块级召回：每篇文章取其最相似子块的相似度，按相似度降序返回 [articleId, similarity]。
     * 返回结构刻意与 {@link #findSimilarArticles} 保持一致，调用方（RAG/评测）无需区分。
     */
    public List<Object[]> findSimilarChunks(double[] embedding, int limit, double threshold) {
        List<Object[]> results = new ArrayList<>();
        if (embedding == null || embedding.length == 0 || pgVectorJdbcTemplate == null) {
            return results;
        }
        try {
            String vec = PgVectorUtil.toLiteral(embedding);
            return pgVectorJdbcTemplate.query(
                "SELECT article_id, MAX(1 - (embedding <=> CAST(? AS vector))) AS similarity "
                    + "FROM ap_article_chunk "
                    + "GROUP BY article_id "
                    + "HAVING MAX(1 - (embedding <=> CAST(? AS vector))) >= ? "
                    + "ORDER BY similarity DESC LIMIT ?",
                (ResultSet rs) -> {
                    while (rs.next()) {
                        results.add(new Object[]{rs.getLong("article_id"), rs.getDouble("similarity")});
                    }
                    return results;
                },
                vec, vec, threshold, limit);
        } catch (Exception e) {
            log.error("Failed to find similar chunks: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 统一召回入口（RAG 与离线评测共用同一口径，否则用评测集跑 recall@k 没有意义）：
     * 分块优先（小颗粒命中、大颗粒供上下文），无分块数据/异常时回退文章级向量。
     */
    public List<Object[]> recallArticles(double[] embedding, int limit) {
        if (chunkEnabled && embedding != null && embedding.length > 0) {
            try {
                List<Object[]> chunkHits = findSimilarChunks(embedding, limit, 0d);
                if (chunkHits != null && !chunkHits.isEmpty()) {
                    return chunkHits;
                }
            } catch (Exception e) {
                log.warn("Chunk recall failed, fallback to article-level: {}", e.getMessage());
            }
        }
        return findSimilarArticles(embedding, limit, 0d);
    }

    /** 批量 embedding（模型支持 list 入参则一次调用；失败/不支持则逐条降级） */
    private List<float[]> embedAll(List<String> texts) {
        try {
            List<float[]> out = embeddingModel.embed(texts);
            if (out != null && out.size() == texts.size()) {
                return out;
            }
        } catch (Exception e) {
            log.warn("Batch embedding failed, fallback to single calls: {}", e.getMessage());
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            float[] e = embeddingModel.embed(t.length() > 2000 ? t.substring(0, 2000) : t);
            if (e == null || e.length == 0) {
                return null;
            }
            out.add(e);
        }
        return out;
    }
}