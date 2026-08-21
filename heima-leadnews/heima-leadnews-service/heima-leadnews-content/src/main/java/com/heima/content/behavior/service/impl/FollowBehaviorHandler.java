package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.behavior.service.BehaviorHandler;
import com.heima.content.mapper.follow.ApFollowMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.follow.pojos.ApFollow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 关注/取消关注行为处理器
 */
@Slf4j
@Component
public class FollowBehaviorHandler implements BehaviorHandler {

    @Autowired
    private ApFollowMapper apFollowMapper;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.FOLLOW_USER;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Integer targetUserId = context.getTargetUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetUserId == null) {
            return BehaviorResult.failure(BehaviorType.FOLLOW_USER, "参数不完整");
        }

        // 自关注检查
        if (userId.equals(targetUserId)) {
            return BehaviorResult.failure(BehaviorType.FOLLOW_USER, "不能关注自己");
        }

        // 幂等检查：是否已关注
        LambdaQueryWrapper<ApFollow> query = new LambdaQueryWrapper<>();
        query.eq(ApFollow::getUserId, userId);
        query.eq(ApFollow::getFollowUserId, targetUserId);
        ApFollow existing = apFollowMapper.selectOne(query);

        if (existing != null) {
            // 已关注，返回重复
            return BehaviorResult.duplicate(BehaviorType.FOLLOW_USER)
                .withData("followed", true)
                .withData("followId", existing.getId());
        }

        // 执行关注；唯一索引 uk_follow_user_target 兜底并发下的先查后插竞态
        ApFollow follow = new ApFollow();
        follow.setUserId(userId);
        follow.setFollowUserId(targetUserId);
        follow.setCreatedTime(new Date());
        try {
            apFollowMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            // 并发下另一请求已插入关注记录，幂等视为已关注
            log.info("并发关注冲突，视为已关注, userId={}, targetUserId={}", userId, targetUserId);
            return BehaviorResult.duplicate(BehaviorType.FOLLOW_USER)
                .withData("followed", true);
        }

        // 记录行为日志
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setUserId(userId);
        record.setBehaviorType(BehaviorType.FOLLOW_USER.getCode());
        record.setTargetType(3); // 用户
        record.setTargetId(targetId != null ? targetId : targetUserId.longValue());
        record.setTargetUserId(targetUserId);
        record.setStatus(1);
        record.setCreatedTime(new Date());
        record.setUpdatedTime(new Date());
        behaviorRecordMapper.insert(record);

        log.info("用户{}关注了用户{}", userId, targetUserId);

        return BehaviorResult.success(BehaviorType.FOLLOW_USER, "关注成功")
            .withNewRecord(true)
            .withData("followed", true)
            .withData("followId", follow.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        Integer userId = context.getUserId();
        Integer targetUserId = context.getTargetUserId();

        if (userId == null || targetUserId == null) {
            return BehaviorResult.failure(BehaviorType.UNFOLLOW_USER, "参数不完整");
        }

        // 检查是否存在关注关系
        LambdaQueryWrapper<ApFollow> query = new LambdaQueryWrapper<>();
        query.eq(ApFollow::getUserId, userId);
        query.eq(ApFollow::getFollowUserId, targetUserId);
        ApFollow existing = apFollowMapper.selectOne(query);

        if (existing == null) {
            return BehaviorResult.failure(BehaviorType.UNFOLLOW_USER, "未关注该用户");
        }

        // 删除关注记录
        apFollowMapper.deleteById(existing.getId());

        // 更新行为记录状态为已撤销
        LambdaQueryWrapper<UserBehaviorRecord> recordQuery = new LambdaQueryWrapper<>();
        recordQuery.eq(UserBehaviorRecord::getUserId, userId);
        recordQuery.eq(UserBehaviorRecord::getBehaviorType, BehaviorType.FOLLOW_USER.getCode());
        recordQuery.eq(UserBehaviorRecord::getTargetUserId, targetUserId);
        recordQuery.eq(UserBehaviorRecord::getStatus, 1);
        UserBehaviorRecord record = behaviorRecordMapper.selectOne(recordQuery);
        if (record != null) {
            record.setStatus(0);
            record.setUpdatedTime(new Date());
            behaviorRecordMapper.updateById(record);
        }

        log.info("用户{}取消关注了用户{}", userId, targetUserId);

        return BehaviorResult.success(BehaviorType.UNFOLLOW_USER, "取消关注成功")
            .withData("followed", false);
    }
}