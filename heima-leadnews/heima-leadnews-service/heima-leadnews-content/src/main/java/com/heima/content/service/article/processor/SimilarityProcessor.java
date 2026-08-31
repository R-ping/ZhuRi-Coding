package com.heima.content.service.article.processor;

import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.service.article.ArticleSimilarityService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RAG相似度检测处理器
 * 检测文章内容是否与已有文章高度相似，并更新推荐状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimilarityProcessor implements ArticleAuditProcessor {

    private final ArticleSimilarityService articleSimilarityService;
    private final ApArticleConfigMapper apArticleConfigMapper;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        if (content == null || content.isEmpty()) {
            log.info("文章内容为空，跳过RAG相似度检测, articleId={}", article.getId());
            return true;
        }

        // RAG相似度检验
        boolean isHighSimilarity = false;
        Map<String, Object> similarityResult;
        try {
            similarityResult = articleSimilarityService.checkSimilarity(article, content);
        } catch (Exception e) {
            // 供审核责任链按阶段重试：重复高相似文章会漏审并错误放行，故视为需重试的异常而非静默忽略
            log.error("RAG相似度检验异常, articleId={}", article.getId(), e);
            throw new AuditRetryableException("RAG相似度检验失败, articleId=" + article.getId(), e);
        }
        if (similarityResult != null && Boolean.TRUE.equals(similarityResult.get("isSimilar"))) {
            isHighSimilarity = true;
            log.info("检测到高相似度文章, articleId={}, similarity={}",
                article.getId(), similarityResult.get("maxSimilarity"));
        }

        context.setHighSimilarity(isHighSimilarity);

        // 更新文章推荐状态（高相似度标记为不推荐）
        // 使用幂等 upsert：依赖 uk_article_id 唯一索引兜底并发首次插入，避免重复行
        try {
            ApArticleConfig config = new ApArticleConfig(article.getId());
            config.setIsRecommend(!isHighSimilarity);
            apArticleConfigMapper.insertOrUpdateRecommend(config);
            log.info("更新文章推荐状态, articleId={}, isRecommend={}", article.getId(), !isHighSimilarity);
        } catch (Exception e) {
            log.error("更新文章配置失败, articleId={}", article.getId(), e);
            throw new AuditRetryableException("文章推荐状态更新失败, articleId=" + article.getId(), e);
        }

        return true;
    }
}