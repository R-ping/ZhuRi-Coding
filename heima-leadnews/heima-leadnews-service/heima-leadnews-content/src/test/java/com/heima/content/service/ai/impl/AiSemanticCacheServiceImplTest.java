package com.heima.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.service.ai.AiMetricsCollector;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.article.dtos.AiAnswerVo;
import com.heima.model.article.dtos.AiSourceVo;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticle.Status;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 语义缓存（pgvector + 精确扫描）单测。
 *
 * <p>覆盖：开关/入参短路、不可缓存问题（过短/指代）、命中链路（向量查询 → 来源存活校验 →
 * touch → 指标）、命中但来源失效/答案或来源为空 → evict、落缓存（插入 + 单用户容量裁剪 + 指标）。
 * JdbcTemplate 全程 mock，不依赖真实 PG 环境。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("语义缓存（AiSemanticCacheService）")
class AiSemanticCacheServiceImplTest {

    private static final double[] VEC = {0.1, 0.2, 0.3, 0.4};

    private static final String DECENT_QUESTION = "Redis 分布式锁有哪些实现方式";

    @Mock
    private ArticleEmbeddingServiceImpl embeddingService;

    @Mock
    private JdbcTemplate pgVectorJdbcTemplate;

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private AiMetricsCollector metrics;

    @InjectMocks
    private AiSemanticCacheServiceImpl service;

    @BeforeEach
    void enableCache() {
        // @Value 字段在单测中无默认注入，显式配置
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "threshold", 0.5d);
        ReflectionTestUtils.setField(service, "ttlHours", 6);
        ReflectionTestUtils.setField(service, "maxPerUser", 50);
    }

    // ==================== 入参/开关短路 ====================

    @Test
    @DisplayName("总开关关闭时直接未命中")
    void disabledReturnsNull() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertNull(service.lookup(DECENT_QUESTION, 7));
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("userId 为空直接未命中")
    void nullUserIdReturnsNull() {
        assertNull(service.lookup(DECENT_QUESTION, null));
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("过短问题不做缓存（信息量不足）")
    void shortQuestionNotCacheable() {
        assertNull(service.lookup("嗯", 7));
        verify(embeddingService, never()).generateEmbedding(anyString());
        // store 同样短路：过短问题不落缓存
        service.store("嗯", 7, "这是一段足够长的答案，字数不少于二十个字，用于验证过滤逻辑。",
            List.of(source(1L)));
        verify(pgVectorJdbcTemplate, never()).update(startsWith("INSERT INTO ap_ai_semantic_cache"),
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("含指代的问题不做缓存（语义相似≠意图相同）")
    void contextDependentQuestionNotCacheable() {
        assertNull(service.lookup("它是什么意思", 7));
        assertNull(service.lookup("上面的方案详细说说", 7));
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    // ==================== lookup：命中链路 ====================

    private void stubLookupQuery(ResultSet rs) {
        when(pgVectorJdbcTemplate.query(anyString(), any(ResultSetExtractor.class),
            any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ResultSetExtractor<Object> rse = inv.getArgument(1);
            return rse.extractData(rs);
        });
    }

    @Test
    @DisplayName("命中：向量查询 + 来源存活校验通过 → 返回答案并 touch、计指标")
    void lookupHit() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("answer")).thenReturn("这是缓存的答案内容，足够长。");
        when(rs.getString("sources_json"))
            .thenReturn("[{\"articleId\":1,\"title\":\"分布式锁实战\",\"author\":\"张三\",\"likes\":10,\"similarity\":0.96}]");
        when(rs.getDouble("similarity")).thenReturn(0.96);
        stubLookupQuery(rs);
        when(embeddingService.generateEmbedding(DECENT_QUESTION)).thenReturn(VEC);
        when(apArticleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(published(1L)));

        AiAnswerVo vo = service.lookup(DECENT_QUESTION, 7);

        assertNotNull(vo);
        assertEquals("这是缓存的答案内容，足够长。", vo.getAnswer());
        assertEquals(1L, vo.getSources().get(0).getArticleId());
        // 命中计数 touch + 指标
        verify(pgVectorJdbcTemplate).update(startsWith("UPDATE ap_ai_semantic_cache SET hit_count"), eq(1L));
        verify(metrics).incr("ai_semcache_hit");
    }

    @Test
    @DisplayName("无向量命中时返回未命中")
    void lookupMiss() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        stubLookupQuery(rs);
        when(embeddingService.generateEmbedding(DECENT_QUESTION)).thenReturn(VEC);

        assertNull(service.lookup(DECENT_QUESTION, 7));
    }

    @Test
    @DisplayName("答案或来源为空时 evict 并视为未命中")
    void lookupEmptyAnswerEvicts() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("answer")).thenReturn("  ");
        when(rs.getString("sources_json")).thenReturn("[]");
        when(rs.getDouble("similarity")).thenReturn(0.96);
        stubLookupQuery(rs);
        when(embeddingService.generateEmbedding(DECENT_QUESTION)).thenReturn(VEC);

        assertNull(service.lookup(DECENT_QUESTION, 7));

        verify(pgVectorJdbcTemplate).update(startsWith("DELETE FROM ap_ai_semantic_cache WHERE id"), eq(1L));
        verify(metrics).incr("ai_semcache_evict");
        verify(metrics, never()).incr("ai_semcache_hit");
    }

    @Test
    @DisplayName("命中但来源文章已判 AIGC → 整条缓存不可信，evict 后走正常链路")
    void lookupSourceNotAliveEvicts() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getLong("id")).thenReturn(1L);
        when(rs.getString("answer")).thenReturn("这是缓存的答案内容，足够长。");
        when(rs.getString("sources_json"))
            .thenReturn("[{\"articleId\":1,\"title\":\"t\",\"author\":\"a\",\"likes\":1,\"similarity\":0.9}]");
        when(rs.getDouble("similarity")).thenReturn(0.9);
        stubLookupQuery(rs);
        when(embeddingService.generateEmbedding(DECENT_QUESTION)).thenReturn(VEC);
        ApArticle aigc = published(1L);
        aigc.setIsAigc(1);
        when(apArticleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(aigc));

        assertNull(service.lookup(DECENT_QUESTION, 7));

        verify(pgVectorJdbcTemplate).update(startsWith("DELETE FROM ap_ai_semantic_cache WHERE id"), eq(1L));
    }

    // ==================== store：落缓存 ====================

    @Test
    @DisplayName("落缓存：嵌入问题向量 + 插入 + 单用户容量裁剪 + 指标")
    void storeInsertsAndCaps() {
        when(embeddingService.generateEmbedding(DECENT_QUESTION)).thenReturn(VEC);
        List<AiSourceVo> sources = List.of(source(1L), source(2L));

        service.store(DECENT_QUESTION, 7, "这是一段足够长的问答答案，字数满足最小长度要求，用于验证缓存写入。", sources);

        verify(pgVectorJdbcTemplate).update(startsWith("INSERT INTO ap_ai_semantic_cache"),
            eq(7), eq(DECENT_QUESTION), any(), any(), any());
        // 单用户条数上限裁剪（maxPerUser=50）
        verify(pgVectorJdbcTemplate).update(
            startsWith("DELETE FROM ap_ai_semantic_cache WHERE user_id = ? AND id NOT IN"), eq(7), eq(7), eq(50));
        verify(metrics).incr("ai_semcache_store");
    }

    @Test
    @DisplayName("答案过短或来源为空不落缓存")
    void storeShortAnswerSkipped() {
        // 答案过短（< MIN_ANSWER_LEN=20），不触发 embedding
        service.store(DECENT_QUESTION, 7, "短答案", List.of(source(1L)));
        verify(embeddingService, never()).generateEmbedding(anyString());

        // 来源为空也不落缓存
        service.store(DECENT_QUESTION, 7, "这是一段足够长的问答答案，字数满足最小长度要求，用于验证缓存写入。",
            List.of());
        verify(pgVectorJdbcTemplate, never()).update(startsWith("INSERT INTO ap_ai_semantic_cache"),
            any(), any(), any(), any(), any());
    }

    // ==================== 辅助 ====================

    private AiSourceVo source(Long id) {
        AiSourceVo s = new AiSourceVo();
        s.setArticleId(id);
        return s;
    }

    private ApArticle published(Long id) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setStatus(Status.PUBLISHED.getCode());
        a.setIsAigc(0);
        return a;
    }
}