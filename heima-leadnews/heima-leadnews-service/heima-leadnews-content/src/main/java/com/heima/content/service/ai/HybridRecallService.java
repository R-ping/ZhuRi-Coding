package com.heima.content.service.ai;

import java.util.List;
import java.util.Map;

/**
 * 混合召回（向量 + BM25 → RRF 融合），RAG 与离线评测共用的统一召回入口。
 *
 * <p>为什么需要：纯向量召回对「术语/代码/精确名词」类查询偏弱（Redis 分布式锁、@Transactional、
 * 类名 API 名等），而这正是 BM25 的强项；反过来口语化改写问句 BM25 又常常全空。
 * 两路分数（余弦 vs BM25 _score）量纲不可比，因此用 RRF 只融合**排名**。
 *
 * <p>降级策略：BM25 路失败/未开启 → 纯向量；向量路失败 → 纯 BM25；两路都失败 → 空（调用方按无命中处理）。
 * 任何异常都不抛出，不阻断问答主链路。
 */
public interface HybridRecallService {

    /**
     * 混合召回。
     *
     * @param query          检索用查询（RAG 场景为 Query Rewrite 后的文本）
     * @param queryEmbedding 查询向量（调用方已算好，避免重复调用 embedding）
     * @param limit          候选上限
     * @return 融合结果（含顺序与向量相似度），永不返回 null
     */
    Recall recall(String query, double[] queryEmbedding, int limit);

    /** 召回结果：RRF 融合后的候选顺序 + 向量路相似度（仅向量命中的文章带值） */
    final class Recall {

        private final List<Long> ids;

        private final Map<Long, Double> vectorSims;

        public Recall(List<Long> ids, Map<Long, Double> vectorSims) {
            this.ids = ids;
            this.vectorSims = vectorSims;
        }

        /** RRF 融合顺序（已去重） */
        public List<Long> getIds() {
            return ids;
        }

        /** articleId -> 向量余弦相似度（BM25 独有命中不在其中） */
        public Map<Long, Double> getVectorSims() {
            return vectorSims;
        }

        public boolean isEmpty() {
            return ids == null || ids.isEmpty();
        }
    }
}
