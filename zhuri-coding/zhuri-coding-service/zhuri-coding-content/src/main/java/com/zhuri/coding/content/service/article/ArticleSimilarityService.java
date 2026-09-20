package com.heima.content.service.article;

import com.heima.model.article.pojos.ApArticle;

import java.util.Map;

public interface ArticleSimilarityService {

    /**
     * 检查文章相似度，返回相似度最高的文章信息
     * @param article 待审核文章
     * @param content 文章内容
     * @return Map包含: isSimilar(boolean), maxSimilarity(double), similarArticleId(Long)
     */
    Map<String, Object> checkSimilarity(ApArticle article, String content);
}