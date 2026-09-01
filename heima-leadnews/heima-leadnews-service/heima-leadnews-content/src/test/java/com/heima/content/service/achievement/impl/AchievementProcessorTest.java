package com.heima.content.service.achievement.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.apis.notification.INotificationClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.achievement.ApUserAchievementMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.pojos.ApUserAchievement;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.common.dtos.ResponseResult;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AchievementProcessor 单元测试（事件驱动成就解锁）
 *
 * 覆盖发布文章/沸点、被关注、被点赞四类事件：达标解锁落库 + 发通知；未达标仅记录进度；
 * 已解锁不再重复通知。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("成就解锁事件处理器测试")
class AchievementProcessorTest {

    @Mock
    private ApAchievementMapper achievementMapper;
    @Mock
    private ApUserAchievementMapper userAchievementMapper;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private INotificationClient notificationClient;

    @InjectMocks
    private AchievementProcessor processor;

    private ApAchievement def(String code, String triggerType, int threshold) {
        ApAchievement d = new ApAchievement();
        d.setCode(code);
        d.setName("勋章" + code);
        d.setTriggerType(triggerType);
        d.setThreshold(threshold);
        d.setIsActive(true);
        d.setDescription("解锁条件描述");
        return d;
    }

    @BeforeEach
    void setUp() {
        // 需要发通知的用例各自显式 stub（避免 UnnecessaryStubbing）
    }

    @Test
    @DisplayName("发布文章达标 → 解锁落库 + 发送通知")
    void publishArticleUnlocks() {
        when(notificationClient.sendActivityNotification(anyMap()))
            .thenReturn(ResponseResult.okResult());
        // checkPublish 会先查 publish_article 再查 publish_content（两次 selectList），
        // 第二次返回空列表，避免同一定义被重复检查
        when(achievementMapper.selectList(any()))
            .thenReturn(Collections.singletonList(def("publish_10", "publish_article", 10)))
            .thenReturn(Collections.emptyList());
        when(apArticleMapper.selectCount(any())).thenReturn(10L);
        when(apPinsMapper.selectCount(any())).thenReturn(0L);
        when(userAchievementMapper.selectByUserAndCode(1001L, "publish_10")).thenReturn(null);

        BehaviorContext ctx = new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, 1001)
            .withTarget(1, 500L);
        processor.postProcess(ctx, BehaviorResult.success(BehaviorType.PUBLISH_ARTICLE, "ok"));

        ArgumentCaptor<ApUserAchievement> captor = ArgumentCaptor.forClass(ApUserAchievement.class);
        verify(userAchievementMapper).insert(captor.capture());
        assertTrue(captor.getValue().getUnlocked());
        assertEquals(10L, captor.getValue().getProgress());
        // 解锁通知发送
        verify(notificationClient).sendActivityNotification(anyMap());
    }

    @Test
    @DisplayName("发布文章未达标 → 仅记录进度不通知")
    void publishArticleNotUnlocked() {
        when(achievementMapper.selectList(any()))
            .thenReturn(Collections.singletonList(def("publish_10", "publish_article", 10)))
            .thenReturn(Collections.emptyList());
        when(apArticleMapper.selectCount(any())).thenReturn(5L);
        when(apPinsMapper.selectCount(any())).thenReturn(0L);
        when(userAchievementMapper.selectByUserAndCode(1001L, "publish_10")).thenReturn(null);

        processor.postProcess(
            new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, 1001).withTarget(1, 500L),
            BehaviorResult.success(BehaviorType.PUBLISH_ARTICLE, "ok"));

        ArgumentCaptor<ApUserAchievement> captor = ArgumentCaptor.forClass(ApUserAchievement.class);
        verify(userAchievementMapper).insert(captor.capture());
        assertEquals(5L, captor.getValue().getProgress());
        verify(notificationClient, never()).sendActivityNotification(anyMap());
    }

    @Test
    @DisplayName("已解锁记录 → 更新进度但不重复发通知")
    void alreadyUnlockedNoDuplicateNotification() {
        when(achievementMapper.selectList(any()))
            .thenReturn(Collections.singletonList(def("publish_10", "publish_article", 10)))
            .thenReturn(Collections.emptyList());
        when(apArticleMapper.selectCount(any())).thenReturn(11L);
        when(apPinsMapper.selectCount(any())).thenReturn(0L);
        ApUserAchievement existing = new ApUserAchievement();
        existing.setUserId(1001L);
        existing.setAchievementCode("publish_10");
        existing.setProgress(10L);
        existing.setThreshold(10);
        existing.setUnlocked(true);
        when(userAchievementMapper.selectByUserAndCode(1001L, "publish_10")).thenReturn(existing);

        processor.postProcess(
            new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, 1001).withTarget(1, 500L),
            BehaviorResult.success(BehaviorType.PUBLISH_ARTICLE, "ok"));

        verify(userAchievementMapper).updateById(existing);
        assertEquals(11L, existing.getProgress());
        verify(notificationClient, never()).sendActivityNotification(anyMap());
    }

    @Test
    @DisplayName("被关注事件 → 更新被关注者 followers 勋章")
    void followUnlocksTargetFollowers() {
        when(notificationClient.sendActivityNotification(anyMap()))
            .thenReturn(ResponseResult.okResult());
        when(achievementMapper.selectList(any())).thenReturn(
            Collections.singletonList(def("followers_100", "followers", 100)));
        when(apFollowMapper.selectCount(any())).thenReturn(100L);
        when(userAchievementMapper.selectByUserAndCode(2002L, "followers_100")).thenReturn(null);

        BehaviorContext ctx = new BehaviorContext(BehaviorType.FOLLOW_USER, 1)
            .withTarget(3, 2002L)
            .withTargetUser(2002);
        processor.postProcess(ctx, BehaviorResult.success(BehaviorType.FOLLOW_USER, "ok"));

        ArgumentCaptor<ApUserAchievement> captor = ArgumentCaptor.forClass(ApUserAchievement.class);
        verify(userAchievementMapper).insert(captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertTrue(captor.getValue().getUnlocked());
        verify(notificationClient).sendActivityNotification(anyMap());
    }

    @Test
    @DisplayName("被点赞事件 → 更新被赞作者 likes 勋章")
    void likeUnlocksTargetAuthorLikes() {
        when(notificationClient.sendActivityNotification(anyMap()))
            .thenReturn(ResponseResult.okResult());
        when(achievementMapper.selectList(any())).thenReturn(
            Collections.singletonList(def("likes_100", "likes", 100)));
        // 作者有 1 篇文章，获 100 赞
        com.heima.model.article.pojos.ApArticle article = new com.heima.model.article.pojos.ApArticle();
        article.setId(300L);
        when(apArticleMapper.selectList(any())).thenReturn(Arrays.asList(article));
        when(apBehaviorLikesMapper.selectCount(any())).thenReturn(100L);
        when(userAchievementMapper.selectByUserAndCode(2002L, "likes_100")).thenReturn(null);

        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1)
            .withTarget(1, 300L)
            .withTargetUser(2002);
        processor.postProcess(ctx, BehaviorResult.success(BehaviorType.LIKE_ARTICLE, "ok"));

        ArgumentCaptor<ApUserAchievement> captor = ArgumentCaptor.forClass(ApUserAchievement.class);
        verify(userAchievementMapper).insert(captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertEquals(100L, captor.getValue().getProgress());
        assertTrue(captor.getValue().getUnlocked());
        assertNotNull(captor.getValue().getUnlockedAt());
    }

    @Test
    @DisplayName("无关行为 → 不处理")
    void unrelatedBehaviorIgnored() {
        processor.postProcess(
            new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 1).withTarget(1, 300L),
            BehaviorResult.success(BehaviorType.COLLECT_ARTICLE, "ok"));
        verify(userAchievementMapper, never()).insert(any(ApUserAchievement.class));
        verify(userAchievementMapper, never()).updateById(any(ApUserAchievement.class));
    }
}
