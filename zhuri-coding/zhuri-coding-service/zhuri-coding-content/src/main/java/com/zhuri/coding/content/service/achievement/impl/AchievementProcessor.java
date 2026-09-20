package com.zhuri.coding.content.service.achievement.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.apis.notification.INotificationClient;
import com.zhuri.coding.content.behavior.service.BehaviorPostProcessor;
import com.zhuri.coding.content.mapper.achievement.ApAchievementMapper;
import com.zhuri.coding.content.mapper.achievement.ApUserAchievementMapper;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.interaction.ApBehaviorLikesMapper;
import com.zhuri.coding.content.mapper.pins.ApPinsMapper;
import com.zhuri.coding.model.achievement.pojos.ApAchievement;
import com.zhuri.coding.model.achievement.pojos.ApUserAchievement;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.pojos.ApBehaviorLikes;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import com.zhuri.coding.model.pins.pojos.ApPins;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 成就解锁行为后置处理器（事件驱动）
 * <p>
 * 在行为事件总线执行成功后触发，统计相关维度当前值并检查勋章是否解锁：
 * <ul>
 *   <li>PUBLISH_ARTICLE → 作者 publish_article / publish_content 进度</li>
 *   <li>PUBLISH_PIN → 作者 publish_content 进度</li>
 *   <li>FOLLOW_USER → 被关注者 followers 进度</li>
 *   <li>LIKE_ARTICLE → 被赞作者 likes 进度</li>
 * </ul>
 * 解锁后写入 {@code ap_user_achievement}（幂等，UK(user_id, achievement_code)）并通过
 * INotificationClient 发送成就解锁站内信。checkin_streak 无事件源（签到在 reward 服务），
 * 由成就查询接口实时兜底。
 * 失败不影响主行为流程（catch 后仅记日志）。
 */
@Slf4j
@Component
public class AchievementProcessor implements BehaviorPostProcessor {

    private static final String LINK_GROWTH_PAGE = "/user/growth";

    @Autowired
    private ApAchievementMapper achievementMapper;

    @Autowired
    private ApUserAchievementMapper userAchievementMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired(required = false)
    private INotificationClient notificationClient;

    @Override
    public void postProcess(BehaviorContext context, BehaviorResult result) {
        if (context == null || context.getBehaviorType() == null) {
            return;
        }
        try {
            switch (context.getBehaviorType()) {
                case PUBLISH_ARTICLE:
                    checkPublish(context.getUserId());
                    break;
                case PUBLISH_PIN:
                    checkPublish(context.getUserId());
                    break;
                case FOLLOW_USER:
                    checkFollowers(context.getTargetUserId());
                    break;
                case LIKE_ARTICLE:
                    checkLikes(context.getTargetUserId());
                    break;
                default:
                    // 其他行为不涉及勋章进度
                    break;
            }
        } catch (Exception e) {
            log.error("成就检查处理异常, type={}, userId={}", context.getBehaviorType().getCode(),
                context.getUserId(), e);
        }
    }

    @Override
    public int getOrder() {
        // 在等级积分(1)、文章热度(2)、成就(3)、通知(4)之后执行，解锁通知紧随其后的顺序
        return 3;
    }

    /** 发布文章/沸点：更新 publish_article / publish_content 勋章进度 */
    private void checkPublish(Integer userId) {
        if (userId == null) {
            return;
        }
        Long uid = userId.longValue();
        long articleCount = apArticleMapper.selectCount(new LambdaQueryWrapper<ApArticle>()
            .eq(ApArticle::getAuthorId, uid)
            .eq(ApArticle::getIsDeleted, false));
        long pinsCount = apPinsMapper.selectCount(new LambdaQueryWrapper<ApPins>()
            .eq(ApPins::getAuthorId, uid)
            .eq(ApPins::getIsDeleted, false));

        // publish_article 勋章（文章数）
        List<ApAchievement> articleDefs = loadDefs("publish_article");
        if (!articleDefs.isEmpty()) {
            checkAndUnlock(uid, articleDefs, articleCount);
        }
        // publish_content 勋章（文章+沸点）
        List<ApAchievement> contentDefs = loadDefs("publish_content");
        if (!contentDefs.isEmpty()) {
            checkAndUnlock(uid, contentDefs, articleCount + pinsCount);
        }
    }

    /** 被关注：更新被关注者 followers 勋章进度 */
    private void checkFollowers(Integer targetUserId) {
        if (targetUserId == null) {
            return;
        }
        Long uid = targetUserId.longValue();
        List<ApAchievement> defs = loadDefs("followers");
        if (defs.isEmpty()) {
            return;
        }
        long followers = apFollowMapper.selectCount(new LambdaQueryWrapper<ApFollow>()
            .eq(ApFollow::getFollowUserId, uid));
        checkAndUnlock(uid, defs, followers);
    }

    /** 被点赞：更新被赞作者 likes 勋章进度 */
    private void checkLikes(Integer targetUserId) {
        if (targetUserId == null) {
            return;
        }
        Long uid = targetUserId.longValue();
        List<ApAchievement> defs = loadDefs("likes");
        if (defs.isEmpty()) {
            return;
        }
        long articleLikes = calcArticleLikes(uid);
        checkAndUnlock(uid, defs, articleLikes);
    }

    /** 读取启用中的指定触发类型勋章定义 */
    private List<ApAchievement> loadDefs(String triggerType) {
        return achievementMapper.selectList(new LambdaQueryWrapper<ApAchievement>()
            .eq(ApAchievement::getIsActive, true)
            .eq(ApAchievement::getTriggerType, triggerType));
    }

    /**
     * 幂等检查并落库：未解锁且达标 → 标记解锁并发送通知；仅进度变化 → 只更新进度（只增不减）
     */
    private void checkAndUnlock(Long userId, List<ApAchievement> defs, long progress) {
        for (ApAchievement def : defs) {
            int threshold = def.getThreshold() != null ? def.getThreshold() : 0;
            boolean unlocked = progress >= threshold;
            try {
                ApUserAchievement record = userAchievementMapper.selectByUserAndCode(userId, def.getCode());
                if (record == null) {
                    ApUserAchievement fresh = new ApUserAchievement();
                    fresh.setUserId(userId);
                    fresh.setAchievementCode(def.getCode());
                    fresh.setProgress(progress);
                    fresh.setThreshold(threshold);
                    fresh.setUnlocked(unlocked);
                    if (unlocked) {
                        fresh.setUnlockedAt(new Date());
                    }
                    try {
                        userAchievementMapper.insert(fresh);
                    } catch (DuplicateKeyException e) {
                        // 并发下另一请求已插入，幂等忽略
                        log.info("成就记录并发插入冲突，忽略, userId={}, code={}", userId, def.getCode());
                        continue;
                    }
                    if (unlocked) {
                        sendUnlockNotification(userId, def);
                    }
                } else {
                    boolean newlyUnlocked = !Boolean.TRUE.equals(record.getUnlocked()) && unlocked;
                    long oldProgress = record.getProgress() != null ? record.getProgress() : 0L;
                    record.setProgress(Math.max(oldProgress, progress));
                    record.setThreshold(threshold);
                    if (newlyUnlocked) {
                        record.setUnlocked(true);
                        record.setUnlockedAt(new Date());
                    }
                    userAchievementMapper.updateById(record);
                    if (newlyUnlocked) {
                        sendUnlockNotification(userId, def);
                    }
                }
            } catch (Exception e) {
                log.error("成就解锁落库失败, userId={}, code={}", userId, def.getCode(), e);
            }
        }
    }

    /** 发送成就解锁站内信（失败不影响解锁） */
    private void sendUnlockNotification(Long userId, ApAchievement def) {
        if (notificationClient == null) {
            log.info("INotificationClient not available, skip achievement notification, userId={}, code={}",
                userId, def.getCode());
            return;
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("title", "🎉 解锁成就「" + def.getName() + "」");
            params.put("content", def.getDescription() != null ? def.getDescription() : "继续加油！");
            params.put("link", LINK_GROWTH_PAGE);
            ResponseResult result = notificationClient.sendActivityNotification(params);
            log.info("成就解锁通知已发送, userId={}, code={}, result={}", userId, def.getCode(), result);
        } catch (Exception e) {
            log.warn("成就解锁通知发送失败, userId={}, code={}", userId, def.getCode(), e);
        }
    }

    /** 计算用户所有文章（未删除）累计获赞数 */
    private long calcArticleLikes(Long userId) {
        List<ApArticle> articles = apArticleMapper.selectList(new LambdaQueryWrapper<ApArticle>()
            .eq(ApArticle::getAuthorId, userId)
            .eq(ApArticle::getIsDeleted, false));
        if (articles == null || articles.isEmpty()) {
            return 0L;
        }
        List<Long> articleIds = articles.stream()
            .map(ApArticle::getId)
            .collect(Collectors.toList());
        return apBehaviorLikesMapper.selectCount(new LambdaQueryWrapper<ApBehaviorLikes>()
            .in(ApBehaviorLikes::getEntryId, articleIds)
            .eq(ApBehaviorLikes::getType, 0)
            .eq(ApBehaviorLikes::getOperation, 0));
    }
}
