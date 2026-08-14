package com.heima.content.service.achievement.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.common.dtos.ResponseResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AchievementServiceImplTest {

    @Mock
    private ApAchievementMapper achievementMapper;
    @Mock
    private ApArticleMapper apArticleMapper;
    @Mock
    private ApPinsMapper apPinsMapper;
    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private LevelService levelService;
    @Mock
    private IRewardClient rewardClient;

    @InjectMocks
    private AchievementServiceImpl achievementService;

    private ApAchievement def(String code, String triggerType, int threshold) {
        ApAchievement d = new ApAchievement();
        d.setCode(code);
        d.setName(code);
        d.setCategory(2);
        d.setIcon("x");
        d.setDescription("desc");
        d.setTriggerType(triggerType);
        d.setThreshold(threshold);
        return d;
    }

    private Map<String, Object> defaultLevelInfo() {
        Map<String, Object> m = new HashMap<>();
        m.put("dailyLevel", 2);
        m.put("dailyTitle", "见习掘友");
        m.put("powerLevel", 1);
        m.put("powerTitle", "新秀");
        return m;
    }

    private void stubBase() {
        when(apArticleMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(apPinsMapper.selectCount(any())).thenReturn(0L);
        when(apFollowMapper.selectCount(any())).thenReturn(0L);
        when(levelService.getUserLevelInfo(anyLong())).thenReturn(defaultLevelInfo());
        Map<String, Object> streak = new HashMap<>();
        streak.put("continuousDays", 0);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenReturn(ResponseResult.okResult(streak));
    }

    @Test
    @DisplayName("初来乍到：发布1篇文章即解锁，进度=1")
    void firstContentUnlockedWhenOneArticle() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("first_content", "publish_content", 1));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(1L);
        stubBase();

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertEquals(1, vo.getList().size());
        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(1L, vo.getList().get(0).getProgress());
        assertEquals(1, vo.getUnlockedCount());
        assertEquals(2, vo.getLevels().size());
    }

    @Test
    @DisplayName("笔耕不辍：文章数不足阈值不解锁，进度保留")
    void publishTenNotUnlockedWhenFiveArticles() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("publish_10", "publish_article", 10));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(5L);
        stubBase();

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertEquals(1, vo.getList().size());
        assertFalse(vo.getList().get(0).getUnlocked());
        assertEquals(5L, vo.getList().get(0).getProgress());
        assertEquals(0, vo.getUnlockedCount());
    }

    @Test
    @DisplayName("连续签到30天：reward 返回连续35天时解锁")
    void checkinStreakUnlocked() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("checkin_streak_30", "checkin_streak", 30));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(apArticleMapper.selectCount(any())).thenReturn(0L);
        stubBase();
        Map<String, Object> streak = new HashMap<>();
        streak.put("continuousDays", 35);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenReturn(ResponseResult.okResult(streak));

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(35L, vo.getList().get(0).getProgress());
    }
}
