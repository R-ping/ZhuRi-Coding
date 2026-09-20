package com.zhuri.coding.content.service.achievement.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.content.mapper.achievement.ApAchievementMapper;
import com.zhuri.coding.content.mapper.achievement.ApUserAchievementMapper;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.achievement.pojos.ApAchievement;
import com.zhuri.coding.model.achievement.pojos.ApUserAchievement;
import com.zhuri.coding.model.achievement.vos.AchievementDataVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AchievementServiceImpl 单元测试（事件驱动后：查询只读解锁记录表 + 定义表，
 * checkin_streak 由查询实时兜底）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("成就查询（事件驱动落库后读表）测试")
class AchievementServiceImplTest {

    @Mock
    private ApAchievementMapper achievementMapper;
    @Mock
    private ApUserAchievementMapper userAchievementMapper;
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

    private ApUserAchievement record(String code, long progress, boolean unlocked) {
        ApUserAchievement r = new ApUserAchievement();
        r.setUserId(1001L);
        r.setAchievementCode(code);
        r.setProgress(progress);
        r.setThreshold(1);
        r.setUnlocked(unlocked);
        r.setUnlockedAt(unlocked ? new Date() : null);
        return r;
    }

    private Map<String, Object> defaultLevelInfo() {
        Map<String, Object> m = new HashMap<>();
        m.put("dailyLevel", 2);
        m.put("dailyTitle", "见习掘友");
        m.put("powerLevel", 1);
        m.put("powerTitle", "新秀");
        return m;
    }

    @Test
    @DisplayName("查询只读解锁记录表：已解锁勋章标记 unlocked")
    void unlockedFromRecordTable() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("first_content", "publish_content", 1));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        List<ApUserAchievement> records = new ArrayList<>();
        records.add(record("first_content", 5L, true));
        when(userAchievementMapper.selectList(any())).thenReturn(records);
        when(levelService.getUserLevelInfo(anyLong())).thenReturn(defaultLevelInfo());

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertEquals(1, vo.getList().size());
        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(5L, vo.getList().get(0).getProgress());
        assertEquals(1, vo.getUnlockedCount());
        assertEquals(2, vo.getLevels().size());
    }

    @Test
    @DisplayName("无解锁记录：勋章未解锁，进度为 0（不再实时统计）")
    void noRecordMeansNotUnlocked() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("publish_10", "publish_article", 10));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(userAchievementMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(levelService.getUserLevelInfo(anyLong())).thenReturn(defaultLevelInfo());

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertFalse(vo.getList().get(0).getUnlocked());
        assertEquals(0L, vo.getList().get(0).getProgress());
        assertEquals(0, vo.getUnlockedCount());
    }

    @Test
    @DisplayName("checkin_streak 无事件源：查询时实时 Feign 兜底解锁")
    void checkinStreakFallback() {
        List<ApAchievement> defs = new ArrayList<>();
        defs.add(def("checkin_streak_30", "checkin_streak", 30));
        when(achievementMapper.selectList(any())).thenReturn(defs);
        when(userAchievementMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(levelService.getUserLevelInfo(anyLong())).thenReturn(defaultLevelInfo());
        Map<String, Object> streak = new HashMap<>();
        streak.put("continuousDays", 35);
        when(rewardClient.getContinuousCheckinDays(anyLong())).thenReturn(ResponseResult.okResult(streak));

        AchievementDataVO vo = achievementService.getUserAchievements(1001L);

        assertTrue(vo.getList().get(0).getUnlocked());
        assertEquals(35L, vo.getList().get(0).getProgress());
        assertEquals(1, vo.getUnlockedCount());
    }
}
