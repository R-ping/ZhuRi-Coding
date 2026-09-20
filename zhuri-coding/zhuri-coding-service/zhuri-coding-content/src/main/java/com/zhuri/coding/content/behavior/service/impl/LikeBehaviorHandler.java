package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.behavior.service.BehaviorHandler;
import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 点赞/取消点赞行为处理器
 * 支持文章点赞和沸点点赞
 */
@Slf4j
@Component
public class LikeBehaviorHandler implements BehaviorHandler {

    @Autowired
    private ApBehaviorLikesMapper apBehaviorLikesMapper;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Autowired
    private ApArticleMapper apArticleMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.LIKE_ARTICLE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId(); // 文章/沸点ID

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 幂等检查：是否已点赞
        LambdaQueryWrapper<ApBehaviorLikes> query = new LambdaQueryWrapper<>();
        query.eq(ApBehaviorLikes::getUserId, userId);
        query.eq(ApBehaviorLikes::getEntryId, targetId);
        query.eq(ApBehaviorLikes::getOperation, 0); // 点赞状态
        ApBehaviorLikes existing = apBehaviorLikesMapper.selectOne(query);

        if (existing != null) {
            return BehaviorResult.duplicate(context.getBehaviorType())
                .withData("liked", true);
        }

        // 执行点赞
        ApBehaviorLikes like = new ApBehaviorLikes();
        like.setEntryId(targetId);
        like.setUserId(userId);
        like.setType(context.getTargetType()); // 1-文章, 2-沸点
        like.setOperation(0); // 点赞
        like.setCreatedTime(new Date());
        apBehaviorLikesMapper.insert(like);

        // 记录行为日志
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setUserId(userId);
        record.setBehaviorType(context.getBehaviorType().getCode());
        record.setTargetType(context.getTargetType());
        record.setTargetId(targetId);
        record.setTargetUserId(context.getTargetUserId());
        record.setStatus(1);
        record.setCreatedTime(new Date());
        record.setUpdatedTime(new Date());
        behaviorRecordMapper.insert(record);

        log.info("用户{}点赞了{} {}", userId,
            context.getTargetType() == 1 ? "文章" : "沸点", targetId);

        return BehaviorResult.success(context.getBehaviorType(), "点赞成功")
            .withNewRecord(true)
            .withData("liked", true)
            .withData("likeId", like.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 查找点赞记录
        LambdaQueryWrapper<ApBehaviorLikes> query = new LambdaQueryWrapper<>();
        query.eq(ApBehaviorLikes::getUserId, userId);
        query.eq(ApBehaviorLikes::getEntryId, targetId);
        query.eq(ApBehaviorLikes::getOperation, 0);
        ApBehaviorLikes existing = apBehaviorLikesMapper.selectOne(query);

        if (existing == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "未点赞");
        }

        // 更新为取消点赞
        existing.setOperation(1);
        apBehaviorLikesMapper.updateById(existing);

        // 更新行为记录状态为已撤销
        LambdaQueryWrapper<UserBehaviorRecord> recordQuery = new LambdaQueryWrapper<>();
        recordQuery.eq(UserBehaviorRecord::getUserId, userId);
        recordQuery.eq(UserBehaviorRecord::getBehaviorType, context.getBehaviorType().getCode());
        recordQuery.eq(UserBehaviorRecord::getTargetId, targetId);
        recordQuery.eq(UserBehaviorRecord::getStatus, 1);
        UserBehaviorRecord record = behaviorRecordMapper.selectOne(recordQuery);
        if (record != null) {
            record.setStatus(0);
            record.setUpdatedTime(new Date());
            behaviorRecordMapper.updateById(record);
        }

        // 取消点赞：文章计数减 1 并重算热度分（rollback 不触发后置处理器链，故在此原子执行；
        // 沸点点赞数由沸点表自身计数，此处仅处理文章）
        if (context.getTargetType() != null && context.getTargetType() == 1) {
            try {
                apArticleMapper.updateInteractionAndScore(targetId, "likes", -1);
            } catch (Exception e) {
                log.error("取消点赞更新文章热度分失败: articleId={}", targetId, e);
            }
        }

        log.info("用户{}取消点赞{} {}", userId,
            context.getTargetType() == 1 ? "文章" : "沸点", targetId);

        return BehaviorResult.success(context.getBehaviorType(), "取消点赞成功")
            .withData("liked", false);
    }
}