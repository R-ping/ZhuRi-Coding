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
 * 评论行为处理器
 * 在评论已保存到 ap_comment 表后，调用此处理器记录行为并触发后置处理
 */
@Slf4j
@Component
public class CommentBehaviorHandler implements BehaviorHandler {

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.COMMENT_ARTICLE;
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
        // 但频率限制由调用方控制

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

        log.info("用户{}评论了{} {}", userId,
            context.getTargetType() == 1 ? "文章" : "沸点", targetId);

        return BehaviorResult.success(context.getBehaviorType(), "评论成功")
            .withNewRecord(true)
            .withData("commentId", context.getExtraLong("commentId"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        // 评论的撤销通常是删除评论，由评论服务自身处理
        // 这里仅更新行为记录状态
        Long commentId = context.getExtraLong("commentId");
        if (commentId != null) {
            LambdaQueryWrapper<UserBehaviorRecord> query =
                new LambdaQueryWrapper<>();
            query.eq(UserBehaviorRecord::getBehaviorType, context.getBehaviorType().getCode());
            query.eq(UserBehaviorRecord::getTargetId, context.getTargetId());
            query.eq(UserBehaviorRecord::getUserId, context.getUserId());
            query.eq(UserBehaviorRecord::getStatus, 1);
            UserBehaviorRecord record = behaviorRecordMapper.selectOne(query);
            if (record != null) {
                record.setStatus(0);
                record.setUpdatedTime(new Date());
                behaviorRecordMapper.updateById(record);
            }
        }

        return BehaviorResult.success(context.getBehaviorType(), "评论已删除");
    }
}