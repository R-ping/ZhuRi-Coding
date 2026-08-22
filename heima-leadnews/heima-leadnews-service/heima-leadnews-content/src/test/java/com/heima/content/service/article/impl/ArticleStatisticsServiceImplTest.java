package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.achievement.AchievementService;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.pins.pojos.ApPins;
import com.heima.model.common.dtos.ResponseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ArticleStatisticsServiceImpl 单元测试（个人主页统计聚合：关注/粉丝/点赞/收藏/阅读/勋章/等级）
 *
 * 普通 @Service，7 个依赖由 @InjectMocks 注入。
 * 覆盖：
 * - getUserStatistics：followCount/followerCount/likeCount/collectCount/readCount/collectionCount/tagCount/badgeCount/levelInfo/diamondCount 全字段；
 * - calculateLikeCount：文章点赞 + 沸点点赞，空文章/空沸点跳过；
 * - calculateReadCount：views 为 null 按 0 计；
 * - badgeCount 异常兜底为 0。
 */
class ArticleStatisticsServiceImplTest {

    @Mock private ApFollowMapper apFollowMapper;
    @Mock private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock private ApCollectionMapper apCollectionMapper;
    @Mock private ApArticleMapper apArticleMapper;
    @Mock private ApPinsMapper apPinsMapper;
    @Mock private LevelService levelService;
    @Mock private AchievementService achievementService;

    @InjectMocks
    private ArticleStatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private ApArticle article(Long id, Integer views) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setViews(views);
        return a;
    }

    private ApPins pin(Long id) {
        ApPins p = new ApPins();
        p.setId(id);
        return p;
    }

    @Test
    @DisplayName("getUserStatistics - 聚合全部统计字段")
    void testStatisticsAllFields() {
        when(apFollowMapper.selectCount(any(Wrapper.class))).thenReturn(3L);
        when(apCollectionMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(apArticleMapper.selectList(any(Wrapper.class)))
                .thenReturn(Arrays.asList(article(1L, 10), article(2L, null), article(3L, 5)));
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(pin(1L), pin(2L)));
        when(apBehaviorLikesMapper.selectCount(any(Wrapper.class)))
                .thenReturn(4L); // 文章+沸点各返回，总计累计
        AchievementDataVO vo = new AchievementDataVO();
        vo.setUnlockedCount(6);
        when(achievementService.getUserAchievements(5L)).thenReturn(vo);
        Map<String, Object> levelInfo = new HashMap<>();
        levelInfo.put("level", "逐日 Lv3");
        when(levelService.getUserLevelInfo(5L)).thenReturn(levelInfo);

        ResponseResult r = service.getUserStatistics(5L);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(3L, data.get("followCount"));
        assertEquals(3L, data.get("followerCount"));
        assertEquals(2L, data.get("collectCount"));
        assertEquals(2L, data.get("collectionCount"));
        assertEquals(15L, data.get("readCount")); // 10+0+5
        assertEquals(8L, data.get("likeCount"));  // 4 (文章) + 4 (沸点)
        assertEquals(6, data.get("badgeCount"));
        assertEquals(0, data.get("tagCount"));
        assertEquals(0, data.get("diamondCount"));
        assertEquals(levelInfo, data.get("levelInfo"));
    }

    @Test
    @DisplayName("getUserStatistics - 空文章/沸点列表，badgeCount 异常兜底 0")
    void testStatisticsEmptyFallback() {
        when(apFollowMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(apCollectionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(apArticleMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(apPinsMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        when(achievementService.getUserAchievements(5L)).thenThrow(new RuntimeException("err"));
        when(levelService.getUserLevelInfo(5L)).thenReturn(new HashMap<>());

        ResponseResult r = service.getUserStatistics(5L);
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(0L, data.get("readCount"));
        assertEquals(0L, data.get("likeCount"));
        assertEquals(0, data.get("badgeCount"));
    }
}