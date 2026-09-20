package com.heima.content.service.ai.memory;

import java.util.List;

/**
 * 语义长期记忆服务（Memory & State 之「长期语义记忆」）。
 *
 * <p>将用户兴趣轨迹（如提问过的问题）向量化后沉淀到 PGVector（ap_user_memory 表），
 * 提问时按余弦相似度召回与当前问题最相近的历史记忆片段，注入提示词做个性化参考。
 * 语义记忆不依赖显式标签，检索由向量语义驱动——这是区别于 {@link com.heima.content.service.ai.UserInterestService}
 * （规则式标签聚合）的核心差异。
 */
public interface UserMemoryService {

    /** 每用户记忆容量上限（超出淘汰最旧） */
    int MAX_PER_USER = 100;

    /** 默认召回条数 */
    int DEFAULT_TOP_K = 2;

    /** 召回相似度阈值（余弦相似度） */
    double DEFAULT_THRESHOLD = 0.35d;

    /**
     * 记入一条用户语义记忆：内容去重（同用户同内容覆盖），向量入库，超容量淘汰最旧。
     *
     * @param embedding 内容向量（由调用方复用检索阶段已生成的向量，避免重复 embedding 调用）
     */
    void remember(Integer userId, String content, double[] embedding);

    /**
     * 语义召回与 query 最相近的用户历史记忆内容列表（按相似度降序）。
     * 无命中 / 向量库未启用 / 异常返回空列表（fail-open）。
     */
    List<String> recall(Integer userId, double[] queryEmbedding, int topK, double threshold);
}