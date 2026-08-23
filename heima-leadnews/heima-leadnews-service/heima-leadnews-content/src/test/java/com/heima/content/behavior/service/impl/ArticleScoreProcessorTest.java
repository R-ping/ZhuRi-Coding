package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleScoreProcessor 单元测试（文章热度分后置处理器）
 *
 * 逻辑要点：
 * - postProcess：targetType 非 1/null 或 targetId 缺失直接返回；行为类型未映射到热度字段直接返回；
 *   try 内 incrementField + recalculateScore，异常被捕获不影响主流程
 * - incrementField：文章不存在(selectById=null)直接返回；各字段累加（null 当 1）
 * - recalculateScore：按权重重算热度分并 updateById
 * - getOrder 返回 2
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

    private ApArticle article(Integer likes, Integer views, Integer comment, Integer collection) {
        ApArticle a = new ApArticle();
        a.setLikes(likes);
        a.setViews(views);
        a.setComment(comment);
        a.setCollection(collection);
        return a;
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
        verify(apArticleMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("postProcess - targetType 非 1（沸点=2）直接返回")
    void postProcessNonArticleTargetType() {
        BehaviorContext ctx = context(BehaviorType.LIKE_PIN, 2, 100L);
        processor.postProcess(ctx, result(BehaviorType.LIKE_PIN));
        verify(apArticleMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("postProcess - targetId 缺失直接返回")
    void postProcessNullTargetId() {
        BehaviorContext ctx = context(BehaviorType.LIKE_ARTICLE, 1, null);
        processor.postProcess(ctx, result(BehaviorType.LIKE_ARTICLE));
        verify(apArticleMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("postProcess - 行为类型未映射热度字段（如发布沸点）直接返回")
    void postProcessUnmappedBehavior() {
        // PUBLISH_PIN / FOLLOW_USER 在 mapToScoreField 未映射，返回 null
        BehaviorContext ctx = context(BehaviorType.PUBLISH_PIN, 2, 100L);
        processor.postProcess(ctx, result(BehaviorType.PUBLISH_PIN));
        verify(apArticleMapper, never()).updateById(any(ApArticle.class));
    }

    // ==================== postProcess 正常分支 ====================

    @Test
    @DisplayName("postProcess - 点赞文章累加 likes 并重算热度分")
    void postProcessLikeArticle() {
        ApArticle existing = article(10, 100, 5, 3);
        when(apArticleMapper.selectById(100L)).thenReturn(existing);
        when(apArticleMapper.updateById(any(ApArticle.class))).thenReturn(1);

        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE));

        // 第一个 updateById 即 incrementField 中累加后的 existing：likes 10 → 11
        ArgumentCaptor<ApArticle> captor = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals(Integer.valueOf(11), captor.getAllValues().get(0).getLikes());
    }

    @Test
    @DisplayName("postProcess - 各行为类型对应字段累加（含 null 当 1 处理）")
    void postProcessEachField() {
        // 收藏：collection 原为 null → 置 1
        when(apArticleMapper.selectById(100L)).thenReturn(article(null, null, null, null));
        when(apArticleMapper.updateById(any(ApArticle.class))).thenReturn(1);
        processor.postProcess(context(BehaviorType.COLLECT_ARTICLE, 1, 100L), result(BehaviorType.COLLECT_ARTICLE));

        ArgumentCaptor<ApArticle> cap = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper, atLeastOnce()).updateById(cap.capture());
        // 第 0 个为 incrementField 更新的原文章对象
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(0).getCollection());
    }

    @Test
    @DisplayName("postProcess - 文章不存在时 incrementField 直接返回，不更新")
    void postProcessArticleNotFound() {
        // 两次 selectById 均返回 null（第一次在 incrementField，第二次在 recalculateScore）
        when(apArticleMapper.selectById(100L)).thenReturn(null);

        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE));

        verify(apArticleMapper, never()).updateById(any(ApArticle.class));
        // selectById 被调用两次（incrementField + recalculateScore）
        verify(apArticleMapper, times(2)).selectById(100L);
    }

    @Test
    @DisplayName("postProcess - 捕获内部异常不影响主流程")
    void postProcessCatchesException() {
        ApArticle existing = article(1, 1, 0, 0);
        when(apArticleMapper.selectById(100L))
                .thenReturn(existing)                  // incrementField 正常
                .thenThrow(new RuntimeException("db error")); // recalculateScore 抛异常
        when(apArticleMapper.updateById(any(ApArticle.class))).thenReturn(1);

        // 异常被捕获，postProcess 不往外抛
        assertDoesNotThrow(() ->
                processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE)));
    }

    // ==================== recalculateScore 权重计算（私有方法经 postProcess 间接覆盖） ====================

    @Test
    @DisplayName("postProcess - 热度分按权重重算 (likes*3 + views + comment*3 + collection*6)")
    void postProcessRecalculaateWeight() {
        // 注意：selectById 两次均返回同一实例，incrementField 先将 likes 10→11，
        // 故重算分数 = 11*3 + 100 + 5*3 + 3*6 = 166
        ApArticle existing = article(10, 100, 5, 3);
        when(apArticleMapper.selectById(100L)).thenReturn(existing);
        when(apArticleMapper.updateById(any(ApArticle.class))).thenReturn(1);

        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, 1, 100L), result(BehaviorType.LIKE_ARTICLE));

        // 最后一次 updateById 传入 recalculateScore 的新对象（仅含 id 与 score）
        ArgumentCaptor<ApArticle> captor = ArgumentCaptor.forClass(ApArticle.class);
        verify(apArticleMapper, atLeast(2)).updateById(captor.capture());
        ApArticle last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(Long.valueOf(100L), last.getId());
        assertEquals(Integer.valueOf(166), last.getScore());
    }
}