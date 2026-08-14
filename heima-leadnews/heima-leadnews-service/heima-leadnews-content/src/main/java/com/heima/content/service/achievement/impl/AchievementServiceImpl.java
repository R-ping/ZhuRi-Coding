package com.heima.content.service.achievement.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.apis.reward.IRewardClient;
import com.heima.content.mapper.achievement.ApAchievementMapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.achievement.AchievementService;
import com.heima.content.service.level.LevelService;
import com.heima.model.achievement.pojos.ApAchievement;
import com.heima.model.achievement.vos.AchievementDataVO;
import com.heima.model.achievement.vos.AchievementItemVO;
import com.heima.model.achievement.vos.AchievementLevelVO;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 成就勋章判定服务实现
 * 统计口径与个人主页一致：获赞用 ap_behavior_likes、粉丝用 ap_user_follow、签到用 reward 服务
 */
@Slf4j
@Service
public class AchievementServiceImpl implements AchievementService {

    @Autowired
    private ApAchievementMapper achievementMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private LevelService levelService;

    @Autowired
    private IRewardClient rewardClient;

    @Override
    public AchievementDataVO getUserAchievements(Long userId) {
        // 1. 采集各维度统计值（批量聚合，避免 N+1）
        long publishedArticles = apArticleMapper.selectCount(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getIsDeleted, false));
        long publishedPins = apPinsMapper.selectCount(
                new LambdaQueryWrapper<ApPins>()
                        .eq(ApPins::getAuthorId, userId)
                        .eq(ApPins::getIsDeleted, false));
        long articleLikes = calcArticleLikes(userId);
        long followers = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, userId));
        int checkinStreak = getCheckinStreak(userId);

        // 2. 读取勋章定义并按序组装解锁状态
        List<ApAchievement> definitions = achievementMapper.selectList(
                new LambdaQueryWrapper<ApAchievement>()
                        .eq(ApAchievement::getIsActive, true)
                        .orderByAsc(ApAchievement::getSortOrder));
        List<AchievementItemVO> list = new ArrayList<>();
        long unlockedCount = 0;
        for (ApAchievement def : definitions) {
            long progress = resolveProgress(def.getTriggerType(),
                    publishedArticles, publishedPins, articleLikes, followers, checkinStreak);
            boolean unlocked = progress >= def.getThreshold();
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
     * 计算用户所有文章（未删除）累计获赞数
     * 一次性查出文章集合再按 entryId 批量统计，避免 N+1
     */
    private long calcArticleLikes(Long userId) {
        List<ApArticle> articles = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getIsDeleted, false));
        if (articles == null || articles.isEmpty()) {
            return 0L;
        }
        List<Long> articleIds = articles.stream()
                .map(ApArticle::getId)
                .collect(Collectors.toList());
        return apBehaviorLikesMapper.selectCount(
                new LambdaQueryWrapper<ApBehaviorLikes>()
                        .in(ApBehaviorLikes::getEntryId, articleIds)
                        .eq(ApBehaviorLikes::getType, 0)
                        .eq(ApBehaviorLikes::getOperation, 0));
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
     * 按触发类型解析当前进度值
     */
    private long resolveProgress(String triggerType, long publishedArticles, long publishedPins,
                                 long articleLikes, long followers, int checkinStreak) {
        if ("publish_article".equals(triggerType)) {
            return publishedArticles;
        }
        if ("publish_content".equals(triggerType)) {
            return publishedArticles + publishedPins;
        }
        if ("checkin_streak".equals(triggerType)) {
            return checkinStreak;
        }
        if ("likes".equals(triggerType)) {
            return articleLikes;
        }
        if ("followers".equals(triggerType)) {
            return followers;
        }
        return 0L;
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
