package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.AiMetricsCollector;
import com.heima.content.service.ai.AiSemanticCacheService;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.content.utils.PgVectorUtil;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 语义缓存实现（pgvector + 精确扫描，不引新基建）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>按用户隔离</b>：答案 prompt 注入了长期记忆与兴趣画像，跨用户复用会泄露画像；</li>
 *   <li><b>双失效</b>：TTL（默认 6h）兜底 + 命中时校验引用文章仍「已发布且非 AIGC」；</li>
 *   <li><b>不缓存上下文依赖问题</b>：含「它/上面/刚才」等指代或过短问题，语义相似 ≠ 意图相同；</li>
 *   <li><b>代价</b>：命中省 3 次 chat 调用，代价是 1 次 embedding 调用；未命中多 1 次 embedding，可接受。</li>
 * </ul>
 * 所有异常 fail-open 返回未命中，绝不阻断问答。
 */
@Slf4j
@Service
public class AiSemanticCacheServiceImpl implements AiSemanticCacheService {

    /** 命中阈值（问题向量余弦） */
    @Value("${ai.semantic-cache.threshold:0.95}")
    private double threshold;

    /** 缓存有效期（小时） */
    @Value("${ai.semantic-cache.ttl-hours:6}")
    private int ttlHours;

    /** 单用户最多保留条数（超出淘汰最旧） */
    @Value("${ai.semantic-cache.max-per-user:50}")
    private int maxPerUser;

    /** 总开关（排障可一键关闭） */
    @Value("${ai.semantic-cache.enabled:true}")
    private boolean enabled;

    /** 上下文依赖问题（指代/追问）：语义相似但意图不同，禁用缓存 */
    private static final Pattern CONTEXT_DEPENDENT =
        Pattern.compile("它|他|她|这个|那个|这篇|那篇|上面|刚才|继续|还有吗|详细说说|再讲|啥意思");

    /** 过短问题不做缓存（信息量不足，相似度高但意图可能完全不同） */
    private static final int MIN_CACHEABLE_LEN = 8;

    /** 过短答案不缓存（多为「知识库中暂未找到」类兜底） */
    private static final int MIN_ANSWER_LEN = 20;

    @Autowired
    private ArticleEmbeddingServiceImpl embeddingService;

    @Autowired(required = false)
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private AiMetricsCollector metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiAnswerVo lookup(String question, Integer userId) {
        if (!enabled || userId == null || pgVectorJdbcTemplate == null || notCacheable(question)) {
            return null;
        }
        try {
            double[] qEmb = embeddingService.generateEmbedding(question);
            if (qEmb == null || qEmb.length == 0) {
                return null;
            }
            String vecLiteral = PgVectorUtil.toLiteral(qEmb);
            Timestamp cutoff = new Timestamp(System.currentTimeMillis() - ttlHours * 3600_000L);
            Object[] row = pgVectorJdbcTemplate.query(
                "SELECT id, answer, sources_json, 1 - (question_vec <=> CAST(? AS vector)) AS similarity "
                    + "FROM ap_ai_semantic_cache "
                    + "WHERE user_id = ? AND created_time > ? "
                    + "AND 1 - (question_vec <=> CAST(? AS vector)) >= ? "
                    + "ORDER BY similarity DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Object[]{rs.getLong("id"), rs.getString("answer"),
                        rs.getString("sources_json"), rs.getDouble("similarity")};
                },
                vecLiteral, userId, cutoff, vecLiteral, threshold);
            if (row == null) {
                return null;
            }
            long id = (Long) row[0];
            String answer = (String) row[1];
            List<AiSourceVo> sources = parseSources((String) row[2]);
            if (answer == null || answer.isBlank() || sources.isEmpty()) {
                evict(id, "答案或来源为空");
                return null;
            }
            // 来源存活校验：任一文章下架/删除/被判 AIGC → 整条缓存不可信，删除后走正常链路
            if (!sourcesAlive(sources)) {
                evict(id, "引用文章已失效");
                return null;
            }
            touch(id);
            AiAnswerVo vo = new AiAnswerVo();
            vo.setAnswer(answer);
            vo.setSources(sources);
            metrics.incr("ai_semcache_hit");
            log.info("[AiSemCache] 命中, userId={}, sim={}, id={}, q={}",
                userId, String.format("%.4f", (Double) row[3]), id, brief(question));
            return vo;
        } catch (Exception e) {
            log.warn("[AiSemCache] 查询异常，回退正常链路, userId={}", userId, e);
            return null;
        }
    }

    @Override
    public void store(String question, Integer userId, String answer, List<AiSourceVo> sources) {
        if (!enabled || userId == null || pgVectorJdbcTemplate == null || notCacheable(question)) {
            return;
        }
        if (answer == null || answer.trim().length() < MIN_ANSWER_LEN
                || sources == null || sources.isEmpty()) {
            return;
        }
        try {
            // 与 lookup 保持同一向量口径：都用用户原始问题（而非 rewrite 后的检索查询）
            double[] qEmb = embeddingService.generateEmbedding(question);
            if (qEmb == null || qEmb.length == 0) {
                return;
            }
            String sourcesJson = objectMapper.writeValueAsString(sources);
            pgVectorJdbcTemplate.update(
                "INSERT INTO ap_ai_semantic_cache "
                    + "(user_id, question, question_vec, answer, sources_json, created_time) "
                    + "VALUES (?, ?, CAST(? AS vector), ?, ?, now())",
                userId, brief(question), PgVectorUtil.toLiteral(qEmb), answer.trim(), sourcesJson);
            // 单用户条数上限：超出则淘汰最旧（缓存是收益项，不做事务/重试）
            pgVectorJdbcTemplate.update(
                "DELETE FROM ap_ai_semantic_cache WHERE user_id = ? AND id NOT IN "
                    + "(SELECT id FROM ap_ai_semantic_cache WHERE user_id = ? "
                    + "ORDER BY created_time DESC LIMIT ?)",
                userId, userId, maxPerUser);
            metrics.incr("ai_semcache_store");
        } catch (Exception e) {
            log.warn("[AiSemCache] 落缓存失败（忽略）, userId={}", userId, e);
        }
    }

    /** 命中计数与时间（可观测性，失败不影响命中结果） */
    private void touch(long id) {
        try {
            pgVectorJdbcTemplate.update(
                "UPDATE ap_ai_semantic_cache SET hit_count = hit_count + 1, last_hit_time = now() WHERE id = ?", id);
        } catch (Exception e) {
            log.debug("[AiSemCache] 命中计数更新失败, id={}", id);
        }
    }

    private void evict(long id, String reason) {
        try {
            pgVectorJdbcTemplate.update("DELETE FROM ap_ai_semantic_cache WHERE id = ?", id);
            metrics.incr("ai_semcache_evict");
            log.info("[AiSemCache] 缓存失效已删除, id={}, reason={}", id, reason);
        } catch (Exception e) {
            log.debug("[AiSemCache] 删除失效缓存失败, id={}", id);
        }
    }

    /** 引用文章是否全部仍可对外展示（已发布 + 未删除 + 非 AIGC） */
    private boolean sourcesAlive(List<AiSourceVo> sources) {
        List<Long> ids = sources.stream()
            .map(AiSourceVo::getArticleId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return false;
        }
        List<ApArticle> alive = apArticleMapper.selectBatchIds(ids);
        if (alive == null || alive.size() < ids.size()) {
            return false;
        }
        for (ApArticle a : alive) {
            if (a.getStatus() == null || a.getStatus() != Status.PUBLISHED.getCode()
                    || a.isDeletedArticle()
                    || (a.getIsAigc() != null && a.getIsAigc() == 1)) {
                return false;
            }
        }
        return true;
    }

    private List<AiSourceVo> parseSources(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<AiSourceVo> list = objectMapper.readValue(json, new TypeReference<List<AiSourceVo>>() {
            });
            return list == null ? Collections.emptyList() : new ArrayList<>(list);
        } catch (Exception e) {
            log.warn("[AiSemCache] 来源 JSON 解析失败，视为未命中");
            return Collections.emptyList();
        }
    }

    /** 上下文依赖/过短问题不缓存 */
    private static boolean notCacheable(String question) {
        if (question == null) {
            return true;
        }
        String q = question.trim();
        return q.length() < MIN_CACHEABLE_LEN || CONTEXT_DEPENDENT.matcher(q).find();
    }

    /** 问题落库截断（保留原文语义前缀，控制单行体积） */
    private static String brief(String q) {
        if (q == null) {
            return "";
        }
        String t = q.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
