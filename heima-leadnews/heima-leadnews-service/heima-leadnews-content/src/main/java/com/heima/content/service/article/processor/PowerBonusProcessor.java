package com.heima.content.service.article.processor;

import com.heima.common.constants.ArticleConstants;
import com.heima.content.mapper.article.ApArticleConfigMapper;
import com.heima.content.service.level.LevelService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 逐力值加成处理器
 * 根据AI质量评分计算逐力值加成，并在达到自动推荐等级时更新推荐状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PowerBonusProcessor implements ArticleAuditProcessor {

    private final LevelService levelService;
    private final ApArticleConfigMapper apArticleConfigMapper;
    private final QualityNotificationProcessor qualityNotificationProcessor;

    @Override
    public boolean process(ApArticle article, String content, AuditProcessorContext context) {
        Map<String, Object> aiResult = context.getAiAnalysisResult();
        if (aiResult == null || !Boolean.TRUE.equals(aiResult.get("success"))) {
            log.info("AI分析结果为空或失败，跳过逐力值计算, articleId={}", article.getId());
            return true;
        }

        Integer qualityScore = (Integer) aiResult.get("qualityScore");
        if (qualityScore == null) {
            log.info("AI质量评分为空，跳过逐力值计算, articleId={}", article.getId());
            return true;
        }

        // 计算逐力值加成
        int powerBonus = 0;
        if (qualityScore >= ArticleConstants.QUALITY_SCORE_EXCELLENT) {
            powerBonus = ArticleConstants.POWER_BONUS_EXCELLENT;
        } else if (qualityScore >= ArticleConstants.QUALITY_SCORE_PASS) {
            powerBonus = ArticleConstants.POWER_BONUS_PASS;
        }

        if (powerBonus <= 0 || article.getAuthorId() == null) {
            log.info("无需逐力值加成, articleId={}, qualityScore={}, powerBonus={}", article.getId(), qualityScore, powerBonus);
            return true;
        }

        try {
            // 计算逐力值加成
            Map<String, Object> powerResult = levelService.calculatePowerWithLimit(
                article.getAuthorId(), article.getId(), "publish_article", powerBonus);

            if (powerResult != null && Boolean.TRUE.equals(powerResult.get("success"))) {
                log.info("AI质量评分逐力值加成, articleId={}, authorId={}, qualityScore={}, powerBonus={}",
                    article.getId(), article.getAuthorId(), qualityScore, powerBonus);

                // 质量优秀，发送首页推荐通知
                if (qualityScore >= ArticleConstants.QUALITY_SCORE_EXCELLENT) {
                    qualityNotificationProcessor.sendQualityNotification(article, qualityScore);
                }

                // 检查是否达到自动推荐等级
                Integer newLevel = (Integer) powerResult.get("newLevel");
                if (newLevel != null && newLevel >= ArticleConstants.POWER_LEVEL_AUTO_RECOMMEND) {
                    updateArticleRecommend(article.getId(), true);
                    log.info("作者逐力值达到{}级，文章自动推荐到首页, articleId={}, authorId={}, level={}",
                        ArticleConstants.POWER_LEVEL_AUTO_RECOMMEND,
                        article.getId(), article.getAuthorId(), newLevel);
                }
            }
        } catch (Exception e) {
            log.error("逐力值计算异常, articleId={}", article.getId(), e);
        }

        return true;
    }

    /**
     * 更新文章推荐状态（幂等 upsert，依赖 uk_article_id 唯一索引兜底并发首次插入）
     */
    private void updateArticleRecommend(Long articleId, boolean isRecommend) {
        try {
            ApArticleConfig config = new ApArticleConfig(articleId);
            config.setIsRecommend(isRecommend);
            apArticleConfigMapper.insertOrUpdateRecommend(config);
            log.info("更新文章推荐状态, articleId={}, isRecommend={}", articleId, isRecommend);
        } catch (Exception e) {
            log.error("更新文章配置失败, articleId={}", articleId, e);
        }
    }
}