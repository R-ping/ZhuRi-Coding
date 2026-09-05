package com.heima.content.behavior.service.impl;

import com.heima.content.service.level.LevelService;
import com.heima.content.service.level.impl.LevelActionService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * LevelScoreProcessor 单元测试（等级积分后置处理器）
 *
 * 逻辑要点：
 * - 操作用户获得逐日积分：mapToLevelAction 命中则 recordActionWithLimit，未命中（actionType=null）跳过
 * - 目标用户获得逐力值与被动进度：mapToPassiveAction / mapToPowerChange 是否命中影响调用
 * - 各 try/catch 分支：recordActionWithLimit / recordPassiveAction / calculatePower 抛异常均被捕获
 * - getOrder 返回 1
 */
@ExtendWith(MockitoExtension.class)
class LevelScoreProcessorTest {

    @Mock
    private LevelService levelService;

    @Mock
    private LevelActionService levelActionService;

    @InjectMocks
    private LevelScoreProcessor processor;

    // ---------- 辅助 ----------

    private BehaviorContext context(BehaviorType type, Integer userId, Integer targetUserId, Integer targetType, Long targetId) {
        return new BehaviorContext(type, userId)
                .withTarget(targetType, targetId)
                .withTargetUser(targetUserId);
    }

    private BehaviorResult result(BehaviorType type) {
        return BehaviorResult.success(type);
    }

    // ==================== getOrder ====================

    @Test
    @DisplayName("getOrder - 返回固定整数 1")
    void getOrderShouldReturnOne() {
        assertEquals(1, processor.getOrder());
    }

    // ==================== userId 缺失 ====================

    @Test
    @DisplayName("postProcess - userId 为 null 直接返回，不发生任何积分动作")
    void postProcessNullUserId() {
        processor.postProcess(context(BehaviorType.LIKE_ARTICLE, null, 100, 1, 100L),
                result(BehaviorType.LIKE_ARTICLE));
        verify(levelService, never()).recordActionWithLimit(anyLong(), anyString(), anyString());
        verify(levelActionService, never()).recordPassiveAction(anyLong(), anyString());
    }

    // ==================== 主动积分分支 ====================

    @Test
    @DisplayName("postProcess - 行为类型未映射到等级行为则跳过主动积分")
    void postProcessUnmappedLevelAction() {
        // UNLIKE_ARTICLE 未映射，且无 targetUserId → 不调用 levelService / levelActionService
        processor.postProcess(context(BehaviorType.UNLIKE_ARTICLE, 1, null, 1, 100L),
                result(BehaviorType.UNLIKE_ARTICLE));
        verify(levelService, never()).recordActionWithLimit(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("postProcess - 主动行为且无目标用户，仅记录逐日积分")
    void postProcessActiveOnly() {
        // BROWSE_COURSE → 归一化为 actionType=browse_article（浏览课程并入浏览文章任务），targetUserId=null
        processor.postProcess(context(BehaviorType.BROWSE_COURSE, 5, null, 4, 300L),
                result(BehaviorType.BROWSE_COURSE));

        verify(levelService).recordActionWithLimit(eq(5L), eq("browse_article"), eq("课程ID:300"));
        // 无目标用户 → 不触发被动进度与逐力值
        verify(levelActionService, never()).recordPassiveAction(anyLong(), anyString());
        verify(levelService, never()).calculatePower(anyLong(), anyLong(), anyString(), eq(1));
    }

    @Test
    @DisplayName("postProcess - 评论文章：主动积分 + 目标用户逐力值（无被动进度）")
    void postProcessCommentArticle() {
        Long targetId = 100L;
        // COMMENT_ARTICLE → action=comment_article；passive=null；power=get_comment
        processor.postProcess(context(BehaviorType.COMMENT_ARTICLE, 1, 100, 1, targetId),
                result(BehaviorType.COMMENT_ARTICLE));

        verify(levelService).recordActionWithLimit(eq(1L), eq("comment_article"), eq("文章ID:100"));
        // 无被动行为（mapToPassiveAction=null）
        verify(levelActionService, never()).recordPassiveAction(anyLong(), anyString());
        // 目标用户获得逐力值
        verify(levelService).calculatePower(eq(100L), eq(targetId), eq("get_comment"), eq(1));
    }

    // ==================== 目标用户 / 被动进度分支 ====================

    @Test
    @DisplayName("postProcess - 关注用户：被动进度 be_followed，无逐力值")
    void postProcessFollowUser() {
        Long targetId = 9L;
        processor.postProcess(context(BehaviorType.FOLLOW_USER, 1, 100, 3, targetId),
                result(BehaviorType.FOLLOW_USER));

        verify(levelService).recordActionWithLimit(eq(1L), eq("follow_user"), eq("用户ID:9"));
        // 被动进度：be_followed
        verify(levelActionService).recordPassiveAction(eq(100L), eq("be_followed"));
        // FOLLOW_USER 无逐力值映射
        verify(levelService, never()).calculatePower(anyLong(), anyLong(), anyString(), eq(1));
    }

    @Test
    @DisplayName("postProcess - 点赞沸点：被动进度 pin_liked 与逐力值 get_like 同触发")
    void postProcessLikePin() {
        Long targetId = 200L;
        processor.postProcess(context(BehaviorType.LIKE_PIN, 2, 50, 2, targetId),
                result(BehaviorType.LIKE_PIN));

        verify(levelService).recordActionWithLimit(eq(2L), eq("like_pin"), eq("沸点ID:200"));
        verify(levelActionService).recordPassiveAction(eq(50L), eq("pin_liked"));
        verify(levelService).calculatePower(eq(50L), eq(targetId), eq("get_like"), eq(1));
    }

    // ==================== 异常捕获分支 ====================

    @Test
    @DisplayName("postProcess - 主动积分 recordActionWithLimit 抛异常被捕获")
    void postProcessCatchesRecordActionException() {
        doThrow(new RuntimeException("积分失败")).when(levelService)
                .recordActionWithLimit(eq(1L), eq("like_article"), anyString());

        assertDoesNotThrow(() -> processor.postProcess(
                context(BehaviorType.LIKE_ARTICLE, 1, null, 1, 100L),
                result(BehaviorType.LIKE_ARTICLE)));
    }

    @Test
    @DisplayName("postProcess - 被动进度 recordPassiveAction 抛异常被捕获，逐力值仍正常执行")
    void postProcessCatchesPassiveActionException() {
        doThrow(new RuntimeException("被动记录失败")).when(levelActionService)
                .recordPassiveAction(eq(100L), eq("article_liked"));

        Long targetId = 100L;
        // LIKE_ARTICLE → 被动 article_liked；主动 like_article；逐力值 get_like
        assertDoesNotThrow(() -> processor.postProcess(
                context(BehaviorType.LIKE_ARTICLE, 1, 100, 1, targetId),
                result(BehaviorType.LIKE_ARTICLE)));
        // 被动失败不影响逐力值
        verify(levelService).calculatePower(eq(100L), eq(targetId), eq("get_like"), eq(1));
    }

    @Test
    @DisplayName("postProcess - 逐力值 calculatePower 抛异常被捕获")
    void postProcessCatchesCalculatePowerException() {
        Long targetId = 100L;
        doThrow(new RuntimeException("逐力值失败")).when(levelService)
                .calculatePower(eq(100L), eq(targetId), eq("get_read"), eq(1));

        // BROWSE_ARTICLE → 主动 browse_article；逐力值 get_read
        assertDoesNotThrow(() -> processor.postProcess(
                context(BehaviorType.BROWSE_ARTICLE, 1, 100, 1, targetId),
                result(BehaviorType.BROWSE_ARTICLE)));
    }
}