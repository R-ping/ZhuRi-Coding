package com.heima.content.service.article;

import com.heima.model.article.pojos.ApArticleEmbedding;

import java.util.List;

public interface ArticleEmbeddingService {

    /**
     * 保存文章向量嵌入
     * @param articleId 文章ID
     * @param embedding 向量数据
     */
    void saveEmbedding(Long articleId, double[] embedding);

    /**
     * 获取文章向量嵌入
     * @param articleId 文章ID
     * @return 向量嵌入
     */
    ApArticleEmbedding getEmbedding(Long articleId);

    /**
     * 查找相似文章
     * @param embedding 查询向量
     * @param limit 返回数量上限
     * @param threshold 相似度阈值
     * @return 相似文章ID列表及相似度
     */
    List<Object[]> findSimilarArticles(double[] embedding, int limit, double threshold);
}