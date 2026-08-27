package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleScoreProcessor 单元测试（文章热度分后置处理器）
 *
 * 逻辑要点（M5 修复后）：
 * - postProcess：targetType 非 1/null 或 targetId 缺失直接返回；行为类型未映射热度字段直接返回；
 * - 命中热度字段的行为通过单条原子 SQL（updateInteractionAndScore）递增计数并同步重算热度分，
 *   规避并发"读-改-写"丢计数；
 * - getOrder 返回 2。
 */
@ExtendWith(MockitoExtension.class)
class ArticleScoreProcessorTest {

    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private ArticleScoreProcessor processor;

    // ---------- 辅助 ----------

    private BehaviorContext context(BehaviorType type, Integer targetType, Long targetId) {
        return new BehaviorContext(type, 1).withTarget(targetType, targetId);
    }

    private BehaviorResult result(BehaviorType type) {
        return BehaviorResult.success(type);
    }

    // ==================== getOrder ====================

    @Test
    @DisplayName("getOrder - 返回固定整数 2")
    void getOrderShouldReturnTwo() {
        assertEquals(2, processor.getOrder());
    }

    // ==================== postProcess 提前返回分支 ====================

    @Test
    @DisplayName("postProcess - targetType 为 null 直接返回，不触发任何 SQL")
    void postProcessNullTargetType() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1).withTarget(null, 100L);
        processor.postProcess(ctx, result(BehaviorType.LIKE_ARTICLE));
        verify(apArticleMapper, never()).updateInteractionAndScore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("postProcess - targetType 非 1（沸点=2）直接返回")
    void postProcessNonArticleTargetType() {
        processor.postProcess(context(BehaviorType.LIKE_PIN, 2, 100L), result(BehaviorType.LIKE_PIN));
        verify(apArticleMapper, never()).updateInteractionAndScore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("postProcess - targetId 缺失直接返回")
    void postProcessNullTargetId() {
        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, null), result(BehaviorType.LIKE_ARTICLE));
        verify(apArticleMapper, never()).updateInteractionAndScore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("postProcess - 行为类型未映射热度字段（如发布沸点）直接返回")
    void postProcessUnmappedBehavior() {
        processor.postProcess(context(BehaviorType.PUBLISH_PIN, 2, 100L), result(BehaviorType.PUBLISH_PIN));
        verify(apArticleMapper, never()).updateInteractionAndScore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================== postProcess 正常分支：原子递增 + 重算热度分 ====================

    @Test
    @DisplayName("postProcess - 点赞文章原子递增 likes")
    void postProcessLikeArticle() {
        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE));
        verify(apArticleMapper).updateInteractionAndScore(100L, "likes", 1);
    }

    @Test
    @DisplayName("postProcess - 收藏文章原子递增 collection")
    void postProcessCollectArticle() {
        processor.postProcess(context(BehaviorType.COLLECT_ARTICLE, 1, 100L), result(BehaviorType.COLLECT_ARTICLE));
        verify(apArticleMapper).updateInteractionAndScore(100L, "collection", 1);
    }

    @Test
    @DisplayName("postProcess - 评论文章原子递增 comment")
    void postProcessCommentArticle() {
        processor.postProcess(context(BehaviorType.COMMENT_ARTICLE, 1, 100L), result(BehaviorType.COMMENT_ARTICLE));
        verify(apArticleMapper).updateInteractionAndScore(100L, "comment", 1);
    }

    @Test
    @DisplayName("postProcess - 浏览文章原子递增 views")
    void postProcessBrowseArticle() {
        processor.postProcess(context(BehaviorType.BROWSE_ARTICLE, 1, 100L), result(BehaviorType.BROWSE_ARTICLE));
        verify(apArticleMapper).updateInteractionAndScore(100L, "views", 1);
    }

    @Test
    @DisplayName("postProcess - 捕获内部异常不影响主流程")
    void postProcessCatchesException() {
        org.mockito.Mockito.doThrow(new RuntimeException("db error"))
                .when(apArticleMapper).updateInteractionAndScore(100L, "likes", 1);

        assertDoesNotThrow(() ->
                processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE)));
    }
}