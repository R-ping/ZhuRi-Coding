package com.heima.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.heima.apis.search.ISearchClient;
import com.heima.content.service.ai.HybridRecallService.Recall;
import com.heima.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.search.dtos.Bm25RecallDto;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 混合召回（向量 + BM25 → RRF 融合）单测。
 *
 * <p>覆盖：混合关闭退化为纯向量；向量路为空时仅用 BM25；两路并存时 RRF 名次融合；
 * BM25 服务异常 / 未注入降级；向量路异常降级；两路皆空返回空结果。
 * 全程 mock，不依赖 search 服务与向量库。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("混合召回（HybridRecallService：向量 + BM25 + RRF）")
class HybridRecallServiceImplTest {

    @Mock
    private ArticleEmbeddingServiceImpl embeddingService;

    @Mock
    private ISearchClient searchClient;

    @InjectMocks
    private HybridRecallServiceImpl service;

    private void enableHybrid() {
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
    }

    /** 模拟 search 服务 BM25 命中：id 同时覆盖 Number 与 String 两种反序列化形态 */
    private void stubBm25(List<Map<String, Object>> data) {
        ResponseResult<Object> result = new ResponseResult<>();
        result.setData(data);
        when(searchClient.bm25Recall(any(Bm25RecallDto.class))).thenReturn(result);
    }

    /** 模拟向量路命中：[id, similarity] 行；显式构造 List<Object[]> 规避 varargs 摊平 */
    private static java.util.List<Object[]> hits(Object[]... rows) {
        java.util.List<Object[]> out = new java.util.ArrayList<>(rows.length);
        for (Object[] r : rows) {
            out.add(r);
        }
        return out;
    }

    private static Object[] row(Long id, double sim) {
        return new Object[]{id, sim};
    }

    // ==================== 退化为纯向量 ====================

    @Test
    @DisplayName("混合关闭时退化为纯向量，保留顺序与相似度映射")
    void hybridDisabledUsesVectorOnly() {
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9), row(2L, 0.8)));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(1L, 2L), recall.getIds());
        assertEquals(0.9, recall.getVectorSims().get(1L));
        assertEquals(0.8, recall.getVectorSims().get(2L));
    }

    @Test
    @DisplayName("search 服务未注入时降级为纯向量")
    void missingSearchClientFallsBackToVector() {
        enableHybrid();
        ReflectionTestUtils.setField(service, "searchClient", null);
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9)));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(1L), recall.getIds());
    }

    @Test
    @DisplayName("向量路异常时降级为纯 BM25")
    void vectorFailureFallsBackToBm25() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenThrow(new RuntimeException("pg down"));
        stubBm25(List.of(Map.of("id", 5), Map.of("id", "6")));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(5L, 6L), recall.getIds());
        assertTrue(recall.getVectorSims().isEmpty(), "向量路失败后不应携带相似度");
    }

    // ==================== BM25 导致的降级 ====================

    @Test
    @DisplayName("BM25 服务异常时降级为纯向量")
    void bm25FailureFallsBackToVector() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9)));
        when(searchClient.bm25Recall(any(Bm25RecallDto.class)))
            .thenThrow(new RuntimeException("feign timeout"));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(1L), recall.getIds());
    }

    @Test
    @DisplayName("BM25 返回空数据时按纯向量处理")
    void bm25EmptyFallsBackToVector() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9)));
        stubBm25(Collections.emptyList());

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(1L), recall.getIds());
    }

    // ==================== 两路汇总与 RRF 融合 ====================

    @Test
    @DisplayName("向量空结果时仅用 BM25 命中")
    void vectorEmptyUsesBm25Only() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(Collections.emptyList());
        stubBm25(List.of(Map.of("id", 7), Map.of("id", 8)));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(7L, 8L), recall.getIds());
    }

    @Test
    @DisplayName("两路并存：RRF 名次融合，两路皆有的文档靠前")
    void rrfFusionOrdersByRank() {
        enableHybrid();
        // 向量路排名 [1,2,3]，BM25 路排名 [2,4] → 2 在两路皆出现，RRF 分最高应排第一
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9), row(2L, 0.8), row(3L, 0.7)));
        stubBm25(List.of(Map.of("id", 2), Map.of("id", 4)));

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertEquals(List.of(2L, 1L, 4L, 3L), recall.getIds());
        assertEquals(0.8, recall.getVectorSims().get(2L));
        assertEquals(0.7, recall.getVectorSims().get(3L));
    }

    @Test
    @DisplayName("RRF 受 limit 截断")
    void rrfFusionRespectsLimit() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(hits(row(1L, 0.9), row(2L, 0.8)));
        stubBm25(List.of(Map.of("id", 3), Map.of("id", 4)));

        Recall recall = service.recall("query", new double[]{1.0}, 2);

        assertEquals(2, recall.getIds().size());
        assertEquals(List.of(1L, 3L), recall.getIds(), "融合分降序：1 与 3 均仅一路（1/60），按 id 升序稳定输出");
    }

    @Test
    @DisplayName("两路皆无结果返回空（不抛异常）")
    void bothEmptyReturnsEmpty() {
        enableHybrid();
        when(embeddingService.recallArticles(any(double[].class), anyInt()))
            .thenReturn(Collections.emptyList());
        stubBm25(Collections.emptyList());

        Recall recall = service.recall("query", new double[]{1.0}, 10);

        assertTrue(recall.isEmpty());
        assertTrue(recall.getVectorSims().isEmpty(), "空结果不应携带相似度数据");
    }
}