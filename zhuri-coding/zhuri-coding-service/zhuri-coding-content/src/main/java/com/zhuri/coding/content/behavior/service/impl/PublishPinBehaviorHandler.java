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
 * 发布沸点行为处理器
 * 在沸点审核通过后调用，记录行为并触发后置处理（等级积分、通知等）
 */
@Slf4j
@Component
public class PublishPinBehaviorHandler implements BehaviorHandler {

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.PUBLISH_PIN;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 幂等检查：同一天同一用户发布同一沸点
        LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
        query.eq(UserBehaviorRecord::getUserId, userId);
        query.eq(UserBehaviorRecord::getBehaviorType, BehaviorType.PUBLISH_PIN.getCode());
        query.eq(UserBehaviorRecord::getTargetId, targetId);
        query.eq(UserBehaviorRecord::getStatus, 1);
        UserBehaviorRecord existing = behaviorRecordMapper.selectOne(query);

        if (existing != null) {
            return BehaviorResult.duplicate(context.getBehaviorType());
        }

        // 记录行为日志
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setUserId(userId);
        record.setBehaviorType(BehaviorType.PUBLISH_PIN.getCode());
        record.setTargetType(2); // 沸点
        record.setTargetId(targetId);
        record.setTargetUserId(context.getTargetUserId());
        record.setStatus(1);
        record.setCreatedTime(new Date());
        record.setUpdatedTime(new Date());
        behaviorRecordMapper.insert(record);

        log.info("用户{}发布了沸点, pinsId={}", userId, targetId);

        return BehaviorResult.success(context.getBehaviorType(), "发布沸点成功")
            .withNewRecord(true)
            .withData("pinsId", targetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        // 沸点发布不支持撤销（可删除，但通过其他接口处理）
        // 这里仅更新行为记录状态
        Long targetId = context.getTargetId();
        if (targetId != null) {
            LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
            query.eq(UserBehaviorRecord::getBehaviorType, BehaviorType.PUBLISH_PIN.getCode());
            query.eq(UserBehaviorRecord::getTargetId, targetId);
            query.eq(UserBehaviorRecord::getUserId, context.getUserId());
            query.eq(UserBehaviorRecord::getStatus, 1);
            UserBehaviorRecord record = behaviorRecordMapper.selectOne(query);
            if (record != null) {
                record.setStatus(0);
                record.setUpdatedTime(new Date());
                behaviorRecordMapper.updateById(record);
            }
        }
        return BehaviorResult.success(context.getBehaviorType(), "沸点已删除");
    }
}