package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.behavior.service.BehaviorHandler;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 发布文章行为处理器
 * 在文章审核通过后调用，记录行为并触发后置处理（等级积分等）
 *
 * 注意：文章发布后不走通知（文章审核通过后已有系统通知），
 * 等级积分由 LevelScoreProcessor 统一处理
 */
@Slf4j
@Component
public class PublishArticleBehaviorHandler implements BehaviorHandler {

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.PUBLISH_ARTICLE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 幂等检查
        LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
        query.eq(UserBehaviorRecord::getUserId, userId);
        query.eq(UserBehaviorRecord::getBehaviorType, BehaviorType.PUBLISH_ARTICLE.getCode());
        query.eq(UserBehaviorRecord::getTargetId, targetId);
        query.eq(UserBehaviorRecord::getStatus, 1);
        UserBehaviorRecord existing = behaviorRecordMapper.selectOne(query);

        if (existing != null) {
            return BehaviorResult.duplicate(context.getBehaviorType());
        }

        // 记录行为日志
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setUserId(userId);
        record.setBehaviorType(BehaviorType.PUBLISH_ARTICLE.getCode());
        record.setTargetType(1); // 文章
        record.setTargetId(targetId);
        record.setTargetUserId(context.getTargetUserId());
        record.setStatus(1);
        record.setCreatedTime(new Date());
        record.setUpdatedTime(new Date());
        behaviorRecordMapper.insert(record);

        log.info("用户{}发布了文章, articleId={}", userId, targetId);

        return BehaviorResult.success(context.getBehaviorType(), "发布文章成功")
            .withNewRecord(true)
            .withData("articleId", targetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        // 文章发布不支持撤销
        return BehaviorResult.success(context.getBehaviorType(), "不支持撤销");
    }
}