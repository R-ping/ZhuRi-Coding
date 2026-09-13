package com.heima.content.service.ai.memory.impl;

import com.heima.content.service.ai.memory.UserMemoryService;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 语义长期记忆实现（PGVector 余弦检索，按用户隔离）。
 *
 * <p>建表依赖 resources/db/migrations/ai_memory_setup.sql；为防止「未迁移即启动」，
 * 首次使用（remember/recall）时执行幂等 CREATE TABLE IF NOT EXISTS 兜底，重复执行为 no-op。
 * 向量读写沿用 ap_article_embedding 的 JDBC 模式（float8[] ↔ vector 互转）。
 */
@Slf4j
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    /** 幂等建表 DDL（与 ai_memory_setup.sql 保持一致） */
    private static final String CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS ap_user_memory (" +
        " id BIGSERIAL PRIMARY KEY," +
        " user_id BIGINT NOT NULL," +
        " content TEXT NOT NULL," +
        " embedding vector(1024)," +
        " created_time TIMESTAMP NOT NULL DEFAULT NOW())";

    private static final String CREATE_IDX_USER_SQL =
        "CREATE INDEX IF NOT EXISTS idx_user_memory_user ON ap_user_memory (user_id, created_time DESC)";

    private static final String CREATE_IDX_VECTOR_SQL =
        "CREATE INDEX IF NOT EXISTS idx_user_memory_embedding ON ap_user_memory " +
        "USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50)";

    private volatile boolean ensured = false;

    @Autowired(required = false)
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Override
    public void remember(Integer userId, String content, double[] embedding) {
        if (userId == null || content == null || content.isBlank() || embedding == null || embedding.length == 0) {
            return;
        }
        JdbcTemplate jdbc = pgVectorJdbcTemplate;
        if (jdbc == null) {
            log.debug("[UserMemory] PgVector 未配置，跳过语义记忆写入 userId={}", userId);
            return;
        }
        try {
            ensureTable(jdbc);
            // 同内容覆盖（幂等），再插入新记录
            jdbc.update("DELETE FROM ap_user_memory WHERE user_id = ? AND content = ?", userId, content);
            jdbc.update((Connection conn) -> {
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO ap_user_memory (user_id, content, embedding, created_time) VALUES (?, ?, ?, ?)");
                ps.setLong(1, userId);
                ps.setString(2, content);
                Array vectorArray = conn.createArrayOf("float8",
                    Arrays.stream(embedding).boxed().toArray(Double[]::new));
                ps.setArray(3, vectorArray);
                ps.setTimestamp(4, new java.sql.Timestamp(System.currentTimeMillis()));
                return ps;
            });
            capPerUser(jdbc, userId);
            log.debug("[UserMemory] 已沉淀语义记忆 userId={}, content={}", userId, truncate(content, 40));
        } catch (Exception e) {
            log.warn("[UserMemory] 记忆写入失败 userId={}", userId, e);
        }
    }

    @Override
    public List<String> recall(Integer userId, double[] queryEmbedding, int topK, double threshold) {
        if (userId == null || queryEmbedding == null || queryEmbedding.length == 0) {
            return Collections.emptyList();
        }
        JdbcTemplate jdbc = pgVectorJdbcTemplate;
        if (jdbc == null) {
            return Collections.emptyList();
        }
        try {
            ensureTable(jdbc);
            int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 5);
            double th = threshold <= 0 ? DEFAULT_THRESHOLD : threshold;
            return jdbc.query((Connection conn) -> {
                    PreparedStatement ps = conn.prepareStatement(
                        "SELECT content FROM ap_user_memory " +
                        "WHERE user_id = ? AND 1 - (embedding <=> ?::vector) >= ? " +
                        "ORDER BY 1 - (embedding <=> ?::vector) DESC LIMIT ?");
                    ps.setLong(1, userId);
                    Array vectorArray = conn.createArrayOf("float8",
                        Arrays.stream(queryEmbedding).boxed().toArray(Double[]::new));
                    ps.setArray(2, vectorArray);
                    ps.setDouble(3, th);
                    ps.setArray(4, vectorArray);
                    ps.setInt(5, k);
                    return ps;
                },
                (ResultSet rs) -> {
                    List<String> hits = new ArrayList<>();
                    while (rs.next()) {
                        hits.add(rs.getString("content"));
                    }
                    return hits;
                });
        } catch (Exception e) {
            log.warn("[UserMemory] 语义召回失败 userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /** 每用户容量上限：只保留最近 MAX_PER_USER 条（按写入时间倒序），防止表无限膨胀 */
    private void capPerUser(JdbcTemplate jdbc, Integer userId) {
        jdbc.update("DELETE FROM ap_user_memory WHERE user_id = ? AND id NOT IN (" +
            "  SELECT id FROM (SELECT id FROM ap_user_memory WHERE user_id = ? " +
            "    ORDER BY created_time DESC, id DESC LIMIT ?) t)", userId, userId, MAX_PER_USER);
    }

    /** 幂等建表 + 索引（重复执行 no-op；失败仅告警不影响主链路） */
    private void ensureTable(JdbcTemplate jdbc) {
        if (ensured) {
            return;
        }
        try {
            jdbc.execute(CREATE_TABLE_SQL);
            jdbc.execute(CREATE_IDX_USER_SQL);
            jdbc.execute(CREATE_IDX_VECTOR_SQL);
            ensured = true;
            log.info("[UserMemory] ap_user_memory 表已就绪");
        } catch (Exception e) {
            log.warn("[UserMemory] 建表失败，后续写入/召回会自动跳过", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}