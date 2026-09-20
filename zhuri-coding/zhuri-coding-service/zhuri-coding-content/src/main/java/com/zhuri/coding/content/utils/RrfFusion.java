package com.zhuri.coding.content.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）。
 *
 * <p>把多路召回的「排名」融合成一个排名，不需要两路分数可比（BM25 的 _score 与向量余弦
 * 量纲完全不同，直接加权是错的）；只用名次，天然鲁棒：
 * <pre>score(d) = Σ_r 1 / (k + rank_r(d))</pre>
 * k 默认 60（原论文取值）：k 越大，靠前名次的优势越平缓 —— 避免单路第一名单方面霸榜。
 *
 * <p>只出现在某一路的文档也能进候选（这正是融合的价值：向量召回打不中的术语查询，
 * BM25 能捞回来，反之亦然）。
 */
public final class RrfFusion {

    /** RRF 平滑常数（业界默认 60） */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合两路召回（顺序即排名，调用方保证按相关度降序传入）。
     *
     * @param first      第一路排名（如向量召回）
     * @param second     第二路排名（如 BM25 召回）
     * @param k          平滑常数（≤0 用默认值）
     * @param limit      返回上限（≤0 表示不限制）
     * @return 融合分降序、已去重的 id 列表；两路皆空返回空列表
     */
    public static List<Long> fuse(List<Long> first, List<Long> second, int k, int limit) {
        Map<Long, Double> scores = fuseWithScores(first, second, k);
        List<Long> ordered = new ArrayList<>(scores.keySet());
        if (limit > 0 && ordered.size() > limit) {
            return new ArrayList<>(ordered.subList(0, limit));
        }
        return ordered;
    }

    /**
     * 融合并返回每个 id 的融合分（降序的有序 Map，便于排障/观测）。
     * 两路同一 id 的分相加；名次从 1 开始计。
     */
    public static Map<Long, Double> fuseWithScores(List<Long> first, List<Long> second, int k) {
        int smooth = k > 0 ? k : DEFAULT_K;
        Map<Long, Double> scores = new HashMap<>();
        accumulate(scores, first, smooth);
        accumulate(scores, second, smooth);
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
        // 融合分降序；分相同时按 id 升序，保证结果稳定可复现
        entries.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : Long.compare(a.getKey(), b.getKey());
        });
        Map<Long, Double> ordered = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : entries) {
            ordered.put(e.getKey(), e.getValue());
        }
        return ordered;
    }

    private static void accumulate(Map<Long, Double> scores, List<Long> ranked, int smooth) {
        if (ranked == null || ranked.isEmpty()) {
            return;
        }
        int rank = 1;
        for (Long id : ranked) {
            if (id == null) {
                continue;
            }
            scores.merge(id, 1.0 / (smooth + rank), Double::sum);
            rank++;
        }
    }
}
