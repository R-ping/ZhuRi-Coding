package com.heima.content.service.article.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.pins.ApPinsMapper;
import com.heima.content.service.achievement.AchievementService;
import com.heima.content.service.article.ArticleStatisticsService;
import com.heima.content.service.level.LevelService;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import com.heima.model.behavior.pojos.ApCollection;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.follow.pojos.ApFollow;
import com.heima.model.pins.pojos.ApPins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ArticleStatisticsServiceImpl implements ArticleStatisticsService {

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Autowired
    private ApPinsMapper apPinsMapper;

    @Autowired
    private LevelService levelService;

    @Autowired
    private AchievementService achievementService;

    @Override
    public ResponseResult getUserStatistics(Long userId) {
        Map<String, Object> result = new HashMap<>();

        // 1. followCount（我关注的用户数）
        long followCount = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getUserId, userId));
        result.put("followCount", followCount);

        // 2. followerCount（关注我的用户数）
        long followerCount = apFollowMapper.selectCount(
                new LambdaQueryWrapper<ApFollow>().eq(ApFollow::getFollowUserId, userId));
        result.put("followerCount", followerCount);

        // 3. likeCount（文章+沸点合计被点赞数）
        long likeCount = calculateLikeCount(userId);
        result.put("likeCount", likeCount);

        // 4. collectCount（收藏的文章数）
        long collectCount = apCollectionMapper.selectCount(
                new LambdaQueryWrapper<ApCollection>().eq(ApCollection::getUserId, userId));
        result.put("collectCount", collectCount);

        // 5. readCount（文章被阅读总数）
        long readCount = calculateReadCount(userId);
        result.put("readCount", readCount);

        // 6. collectionCount（收藏集数量，与收藏文章数相同，因为当前设计为每篇文章一个收藏）
        result.put("collectionCount", collectCount);

        // 7. tagCount（关注的标签数，暂未实现标签关注功能）
        result.put("tagCount", 0);

        // 8. badgeCount（已解锁成就勋章数）
        try {
            result.put("badgeCount", achievementService.getUserAchievements(userId).getUnlockedCount());
        } catch (Exception e) {
            log.warn("获取用户成就勋章数失败，userId={}, error={}", userId, e.getMessage());
            result.put("badgeCount", 0);
        }

        // 9. levelInfo
        Map<String, Object> levelInfo = levelService.getUserLevelInfo(userId);
        result.put("levelInfo", levelInfo);

        // 10. diamondCount（钻石数，与后续转盘抽奖业务关联）
        result.put("diamondCount", 0);

        return ResponseResult.okResult(result);
    }

    private long calculateReadCount(Long userId) {
        LambdaQueryWrapper<ApArticle> query = new LambdaQueryWrapper<>();
        query.eq(ApArticle::getAuthorId, userId);
        query.eq(ApArticle::getIsDeleted, false);
        List<ApArticle> articles = apArticleMapper.selectList(query);
        return articles.stream().mapToLong(article -> {
            Integer views = article.getViews();
            return views != null ? views.longValue() : 0L;
        }).sum();
    }

    private long calculateLikeCount(Long userId) {
        long totalLikes = 0;

        // 文章点赞数
        List<ApArticle> articles = apArticleMapper.selectList(
                new LambdaQueryWrapper<ApArticle>()
                        .eq(ApArticle::getAuthorId, userId)
                        .eq(ApArticle::getIsDeleted, false));
        if (!articles.isEmpty()) {
            List<Long> articleIds = articles.stream()
                    .map(ApArticle::getId)
                    .collect(Collectors.toList());
            totalLikes += apBehaviorLikesMapper.selectCount(
                    new LambdaQueryWrapper<ApBehaviorLikes>()
                            .in(ApBehaviorLikes::getEntryId, articleIds)
                            .eq(ApBehaviorLikes::getType, 0)
                            .eq(ApBehaviorLikes::getOperation, 0));
        }

        // 沸点点赞数
        List<ApPins> pins = apPinsMapper.selectList(
                new LambdaQueryWrapper<ApPins>()
                        .eq(ApPins::getAuthorId, userId)
                        .eq(ApPins::getIsDeleted, false));
        if (!pins.isEmpty()) {
            List<Long> pinsIds = pins.stream()
                    .map(ApPins::getId)
                    .collect(Collectors.toList());
            totalLikes += apBehaviorLikesMapper.selectCount(
                    new LambdaQueryWrapper<ApBehaviorLikes>()
                            .in(ApBehaviorLikes::getEntryId, pinsIds)
                            .eq(ApBehaviorLikes::getType, 1)
                            .eq(ApBehaviorLikes::getOperation, 0));
        }

        return totalLikes;
    }
}