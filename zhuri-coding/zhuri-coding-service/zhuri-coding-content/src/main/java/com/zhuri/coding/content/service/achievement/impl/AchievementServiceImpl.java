package com.heima.content.service.achievement.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.achievement.ApUserAchievementMapper;
import com.heima.content.service.achievement.AchievementService;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.pojos.ApUserAchievement;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.achievement.vos.AchievementItemVO;
import com.heima.model.achievement.vos.AchievementLevelVO;
import com.heima.model.common.dtos.ResponseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成就勋章判定服务实现
 * <p>
 * 解锁状态改为事件驱动：行为发生时由 {@link AchievementProcessor} 检查并写入
 * ap_user_achievement 解锁记录，本查询接口只读记录表 + 定义表（O(定义数)），
 * 不再每次请求实时统计各维度。
 * checkin_streak 勋章无事件源（签到在 reward 服务），查询时实时 Feign 兜底。
 */
@Slf4j
@Service
public class AchievementServiceImpl implements AchievementService {

    /** 无事件源的触发类型：连续签到（reward 服务），查询时实时兜底 */
    private static final String TRIGGER_CHECKIN_STREAK = "checkin_streak";

    @Autowired
    private ApAchievementMapper achievementMapper;

    @Autowired
    private ApUserAchievementMapper userAchievementMapper;

    @Autowired
    private LevelService levelService;

    @Autowired
    private IRewardClient rewardClient;

    @Override
    public AchievementDataVO getUserAchievements(Long userId) {
        // 1. 勋章定义（静态配置，低频变化）
        List<ApAchievement> definitions = achievementMapper.selectList(
                new LambdaQueryWrapper<ApAchievement>()
                        .eq(ApAchievement::getIsActive, true)
                        .orderByAsc(ApAchievement::getSortOrder));

        // 2. 用户解锁记录（事件驱动已落库，一次查询全量）
        List<ApUserAchievement> records = userAchievementMapper.selectList(
                new LambdaQueryWrapper<ApUserAchievement>().eq(ApUserAchievement::getUserId, userId));
        Map<String, ApUserAchievement> recordMap = records.stream()
                .collect(Collectors.toMap(ApUserAchievement::getAchievementCode,
                        Function.identity(), (a, b) -> a));

        List<AchievementItemVO> list = new ArrayList<>();
        long unlockedCount = 0;
        for (ApAchievement def : definitions) {
            ApUserAchievement rec = recordMap.get(def.getCode());
            boolean unlocked = rec != null && Boolean.TRUE.equals(rec.getUnlocked());
            long progress = rec != null && rec.getProgress() != null ? rec.getProgress() : 0L;

            // checkin_streak 无事件源：查询时实时兜底（仅一次 Feign，且只在存在该类型勋章时）
            if (TRIGGER_CHECKIN_STREAK.equals(def.getTriggerType())) {
                int streak = getCheckinStreak(userId);
                int threshold = def.getThreshold() != null ? def.getThreshold() : 0;
                progress = Math.max(progress, streak);
                unlocked = unlocked || progress >= threshold;
            }

            if (unlocked) {
                unlockedCount++;
            }
            list.add(toItem(def, progress, unlocked));
        }

        // 3. 等级徽章（复用等级服务，动态展示当前等级）
        List<AchievementLevelVO> levels = buildLevels(userId);

        // 4. 组装返回
        AchievementDataVO data = new AchievementDataVO();
        data.setUnlockedCount((int) unlockedCount);
        data.setTotalCount(list.size() + levels.size());
        data.setList(list);
        data.setLevels(levels);
        return data;
    }

    /**
     * 获取连续签到天数（远程调用 reward 服务，失败时降级为 0）
     */
    private int getCheckinStreak(Long userId) {
        try {
            ResponseResult res = rewardClient.getContinuousCheckinDays(userId);
            if (res != null && res.getCode() == 200 && res.getData() instanceof Map) {
                Object val = ((Map<?, ?>) res.getData()).get("continuousDays");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Exception e) {
            log.warn("获取连续签到天数失败，userId={}, error={}", userId, e.getMessage());
        }
        return 0;
    }

    /**
     * 将定义记录转为返回 VO
     */
    private AchievementItemVO toItem(ApAchievement def, long progress, boolean unlocked) {
        AchievementItemVO vo = new AchievementItemVO();
        vo.setCode(def.getCode());
        vo.setName(def.getName());
        vo.setCategory(def.getCategory());
        vo.setIcon(def.getIcon());
        vo.setDescription(def.getDescription());
        vo.setUnlocked(unlocked);
        vo.setProgress(progress);
        vo.setThreshold(def.getThreshold());
        return vo;
    }

    /**
     * 动态构造两枚等级徽章（逐友/逐力值），复用 getUserLevelInfo
     */
    private List<AchievementLevelVO> buildLevels(Long userId) {
        List<AchievementLevelVO> levels = new ArrayList<>();
        try {
            Map<String, Object> levelInfo = levelService.getUserLevelInfo(userId);
            levels.add(new AchievementLevelVO() {{
                setType("daily");
                setName("逐友等级");
                setLevel(toInt(levelInfo.get("dailyLevel"), 1));
                setLevelTitle(str(levelInfo.get("dailyTitle")));
            }});
            levels.add(new AchievementLevelVO() {{
                setType("power");
                setName("逐力值等级");
                setLevel(toInt(levelInfo.get("powerLevel"), 1));
                setLevelTitle(str(levelInfo.get("powerTitle")));
            }});
        } catch (Exception e) {
            log.warn("获取用户等级信息失败，userId={}, error={}", userId, e.getMessage());
            levels.add(new AchievementLevelVO() {{
                setType("daily");
                setName("逐友等级");
                setLevel(1);
                setLevelTitle("");
            }});
            levels.add(new AchievementLevelVO() {{
                setType("power");
                setName("逐力值等级");
                setLevel(1);
                setLevelTitle("");
            }});
        }
        return levels;
    }

    private int toInt(Object val, int defaultValue) {
        return val instanceof Number ? ((Number) val).intValue() : defaultValue;
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }
}
