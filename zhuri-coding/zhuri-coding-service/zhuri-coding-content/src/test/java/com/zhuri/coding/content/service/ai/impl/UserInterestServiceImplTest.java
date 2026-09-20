package com.heima.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.common.redis.CacheService;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApCollection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 兴趣画像三层兜底单测（P2-3a 冷启动）：
 * L1 收藏画像 → L2 即时兴趣（Redis 24h）→ L3 全站热门兜底；learnFromQuery 沉淀与 fail-open。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("用户兴趣画像（冷启动三层兜底）测试")
class UserInterestServiceImplTest {

    private static final Integer UID = 42;
    private static final String INSTANT_KEY = "ai:interest:instant:42";

    @Mock
    private ApCollectionMapper collectionMapper;

    @Mock
    private ApArticleMapper apArticleMapper;

    @Mock
    private CacheService cacheService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private UserInterestServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(cacheService.getstringRedisTemplate()).thenReturn(stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private ApArticle article(Long id, List<String> tags) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTags(tags);
        return a;
    }

    @Test
    @DisplayName("L1 收藏画像生效：按标签频次取 TopN，不触达 Redis 与热门兜底")
    void collectionProfileShouldWin() {
        ApCollection c1 = new ApCollection();
        c1.setArticleId(1L);
        when(collectionMapper.selectList(any())).thenReturn(List.of(c1));
        when(apArticleMapper.selectBatchIds(anyList())).thenReturn(List.of(
            article(1L, List.of("Java", "并发")),
            article(2L, List.of("Java"))));

        List<String> tags = service.buildInterestTags(UID);

        assertEquals(List.of("Java", "并发"), tags);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("L1 空 → L2 即时兴趣：读 Redis 并截取 MAX_TAGS")
    void instantInterestShouldFallback() {
        when(collectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(valueOps.get(INSTANT_KEY))
            .thenReturn("[\"Java\",\"并发\",\"JVM\",\"MySQL\",\"Redis\",\"ES\"]");

        List<String> tags = service.buildInterestTags(UID);

        assertEquals(5, tags.size());
        assertEquals("Java", tags.get(0));
    }

    @Test
    @DisplayName("L1/L2 均 空 → L3 全站热门兜底")
    void hotArticlesShouldBeLastFallback() {
        when(collectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(valueOps.get(INSTANT_KEY)).thenReturn(null);
        when(apArticleMapper.selectList(any())).thenReturn(List.of(
            article(9L, List.of("前端")),
            article(10L, List.of("前端", "工程化"))));

        List<String> tags = service.buildInterestTags(UID);

        assertEquals(List.of("前端", "工程化"), tags);
    }

    @Test
    @DisplayName("三层全空返回空列表（调用方跳过注入）；userId=null 短路不查库")
    void emptyWhenAllLayersMiss() {
        when(collectionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(valueOps.get(INSTANT_KEY)).thenReturn(null);
        when(apArticleMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertTrue(service.buildInterestTags(UID).isEmpty());
        // 清掉 UID 调用记录后验证 null 走短路：不触达任何数据源
        org.mockito.Mockito.clearInvocations(collectionMapper, apArticleMapper, valueOps);
        assertTrue(service.buildInterestTags(null).isEmpty());
        verify(collectionMapper, never()).selectList(any());
        verify(apArticleMapper, never()).selectList(any());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("收藏查询异常 fail-open 降级下一层，不抛异常")
    void failOpenWhenCollectionQueryFails() {
        when(collectionMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        when(valueOps.get(INSTANT_KEY)).thenReturn("[\"Java\"]");

        List<String> tags = service.buildInterestTags(UID);

        assertEquals(List.of("Java"), tags);
    }

    @Test
    @DisplayName("learnFromQuery：召回文章标签合并既有兴趣写回，24h TTL")
    void learnFromQueryShouldMergeAndPersist() throws Exception {
        when(apArticleMapper.selectBatchIds(anyList())).thenReturn(List.of(
            article(1L, List.of("Java", "并发"))));
        when(valueOps.get(INSTANT_KEY)).thenReturn("[\"Java\",\"MySQL\"]");

        service.learnFromQuery(UID, List.of(1L, 2L));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(INSTANT_KEY), jsonCaptor.capture(), any(Duration.class));
        List<String> merged = objectMapper.readValue(jsonCaptor.getValue(),
            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        // 新标签在前，旧的 MySQL 保留
        assertEquals(List.of("Java", "并发", "MySQL"), merged);
    }

    @Test
    @DisplayName("learnFromQuery：articleIds 为空直接跳过；异常 fail-open 不抛")
    void learnFromQueryShouldSkipAndFailOpen() {
        service.learnFromQuery(UID, null);
        service.learnFromQuery(UID, new ArrayList<>());
        verify(apArticleMapper, never()).selectBatchIds(anyList());

        when(apArticleMapper.selectBatchIds(anyList())).thenThrow(new RuntimeException("db down"));
        service.learnFromQuery(UID, List.of(1L));
        verify(valueOps, never()).set(eq(INSTANT_KEY), anyString(), any(Duration.class));
    }
}
