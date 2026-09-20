package com.heima.content.behavior.service.impl;

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
 * 沸点评论行为处理器
 * 在 PinsInteractionService 中已保存评论记录后，调用此处理器记录行为并触发后置处理（通知、等级分等）
 */
@Slf4j
@Component
public class PinCommentBehaviorHandler implements BehaviorHandler {

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.COMMENT_PIN;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 评论行为不检查幂等（用户可以多次评论同一内容），总是新记录
        // 记录行为日志
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setUserId(userId);
        record.setBehaviorType(BehaviorType.COMMENT_PIN.getCode());
        record.setTargetType(2); // 沸点
        record.setTargetId(targetId);
        record.setTargetUserId(context.getTargetUserId());
        record.setStatus(1);
        record.setCreatedTime(new Date());
        record.setUpdatedTime(new Date());
        behaviorRecordMapper.insert(record);

        log.info("用户{}评论了沸点 {}", userId, targetId);

        return BehaviorResult.success(context.getBehaviorType(), "评论沸点成功")
            .withNewRecord(true)
            .withData("commentId", context.getExtraLong("commentId"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        // 评论的撤销由评论服务自身处理
        return BehaviorResult.success(context.getBehaviorType(), "评论已删除");
    }
}