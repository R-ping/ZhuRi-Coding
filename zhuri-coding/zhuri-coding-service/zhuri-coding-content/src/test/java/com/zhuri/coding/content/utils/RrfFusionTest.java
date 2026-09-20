package com.zhuri.coding.content.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RRF 融合单测：覆盖 双路交叉 / 单路独占 / 名次优先 / 去重 / 空输入 / limit 截断 / 稳定性。
 */
class RrfFusionTest {

    @Test
    @DisplayName("同时出现在两路的文档优先；名次和相同时按 id 稳定排序")
    void docsInBothRoutesRankFirst() {
        List<Long> vector = Arrays.asList(1L, 2L, 3L);
        List<Long> keyword = Arrays.asList(3L, 2L, 1L);

        List<Long> fused = RrfFusion.fuse(vector, keyword, RrfFusion.DEFAULT_K, 10);
        Map<Long, Double> scores = RrfFusion.fuseWithScores(vector, keyword, RrfFusion.DEFAULT_K);

        // 三篇都出现在两路 → 全部在候选内；1 与 3 名次和相同（rank1+rank3 / rank3+rank1）同分，按 id 升序
        assertEquals(3, fused.size());
        assertEquals(Arrays.asList(1L, 3L, 2L), fused);
        // 注意：rank1+rank3 = 1/61+1/63 略高于 rank2+rank2 = 2/62，这是 RRF 的固有特性
        assertTrue(scores.get(1L) > scores.get(2L));
        assertEquals(scores.get(1L), scores.get(3L), 1e-12);
    }

    @Test
    @DisplayName("只被单路召回的文档仍进入候选（融合的价值）")
    void singleRouteHitsAreKept() {
        List<Long> vector = Arrays.asList(1L, 2L);
        List<Long> keyword = Arrays.asList(3L, 4L);

        List<Long> fused = RrfFusion.fuse(vector, keyword, RrfFusion.DEFAULT_K, 10);

        assertEquals(4, fused.size(), "两路各自独有的文档都应保留");
        assertTrue(fused.containsAll(Arrays.asList(1L, 2L, 3L, 4L)));
    }

    @Test
    @DisplayName("同一文档出现在两路会累加分数，优于单路同名次文档")
    void scoresAccumulateAcrossRoutes() {
        // 9 在两路都是第 1 名；1 只在向量路第 1 名
        List<Long> vector = Arrays.asList(9L, 1L);
        List<Long> keyword = Arrays.asList(9L, 2L);

        Map<Long, Double> scores = RrfFusion.fuseWithScores(vector, keyword, 60);

        double nine = scores.get(9L);
        double one = scores.get(1L);
        assertTrue(nine > one, "两路命中应比单路命中得分高");
        assertEquals(9L, scores.keySet().iterator().next(), "得分最高者应排在首位");
    }

    @Test
    @DisplayName("重复 id 不产生重复候选")
    void deduplicatesIds() {
        List<Long> vector = Arrays.asList(5L, 5L, 6L);
        List<Long> keyword = Collections.singletonList(5L);

        List<Long> fused = RrfFusion.fuse(vector, keyword, 60, 10);

        assertEquals(2, fused.size());
        assertEquals(1L, fused.stream().filter(id -> id == 5L).count());
    }

    @Test
    @DisplayName("空输入不抛异常：单路为空退化为另一路，全空返回空列表")
    void handlesEmptyInput() {
        List<Long> vector = Arrays.asList(1L, 2L);

        assertEquals(Arrays.asList(1L, 2L), RrfFusion.fuse(vector, null, 60, 10));
        assertEquals(Arrays.asList(1L, 2L), RrfFusion.fuse(vector, Collections.emptyList(), 60, 10));
        assertTrue(RrfFusion.fuse(null, null, 60, 10).isEmpty());
        assertTrue(RrfFusion.fuse(Collections.emptyList(), Collections.emptyList(), 60, 10).isEmpty());
    }

    @Test
    @DisplayName("limit 截断生效，且 k<=0 时回退默认常数")
    void limitAndDefaultK() {
        List<Long> vector = Arrays.asList(1L, 2L, 3L, 4L);
        List<Long> keyword = Collections.emptyList();

        List<Long> fused = RrfFusion.fuse(vector, keyword, 0, 2);

        assertEquals(2, fused.size());
        // k<=0 回退默认 60：首名分为 1/61
        Map<Long, Double> scores = RrfFusion.fuseWithScores(vector, keyword, 0);
        assertEquals(1.0 / 61.0, scores.get(1L), 1e-12);
    }

    @Test
    @DisplayName("分数相同时按 id 升序，结果稳定可复现")
    void tieBreakIsStable() {
        List<Long> vector = Arrays.asList(7L, 8L);
        List<Long> keyword = Collections.emptyList();

        List<Long> first = RrfFusion.fuse(vector, keyword, 60, 10);
        List<Long> second = RrfFusion.fuse(vector, keyword, 60, 10);

        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }
}
