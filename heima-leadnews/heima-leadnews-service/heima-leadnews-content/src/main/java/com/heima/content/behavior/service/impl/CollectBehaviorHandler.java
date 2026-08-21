package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.behavior.service.BehaviorHandler;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
import com.heima.model.behavior.pojos.ApCollection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 收藏/取消收藏行为处理器
 */
@Slf4j
@Component
public class CollectBehaviorHandler implements BehaviorHandler {

    @Autowired
    private ApCollectionMapper apCollectionMapper;

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.COLLECT_ARTICLE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId(); // 文章/沸点/专栏ID

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 幂等检查：是否已收藏
        LambdaQueryWrapper<ApCollection> query = new LambdaQueryWrapper<>();
        query.eq(ApCollection::getUserId, userId);
        query.eq(ApCollection::getArticleId, targetId);
        // 如果收藏表支持软删除，还需检查 is_deleted=false
        ApCollection existing = apCollectionMapper.selectOne(query);

        if (existing != null) {
            return BehaviorResult.duplicate(context.getBehaviorType())
                .withData("collected", true);
        }

        // 执行收藏；唯一索引 uk_collection_user_article 兜底并发下的先查后插竞态
        ApCollection collection = new ApCollection();
        collection.setUserId(userId);
        collection.setArticleId(targetId);
        collection.setCreatedTime(new Date());
        try {
            apCollectionMapper.insert(collection);
        } catch (DuplicateKeyException e) {
            // 并发下另一请求已插入收藏记录，幂等视为已收藏
            log.info("并发收藏冲突，视为已收藏, userId={}, targetId={}", userId, targetId);
            return BehaviorResult.duplicate(context.getBehaviorType())
                .withData("collected", true);
        }

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

        log.info("用户{}收藏了{} {}", userId,
            context.getTargetType() == 1 ? "文章" : "专栏", targetId);

        return BehaviorResult.success(context.getBehaviorType(), "收藏成功")
            .withNewRecord(true)
            .withData("collected", true)
            .withData("collectionId", collection.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 查找收藏记录
        LambdaQueryWrapper<ApCollection> query = new LambdaQueryWrapper<>();
        query.eq(ApCollection::getUserId, userId);
        query.eq(ApCollection::getArticleId, targetId);
        ApCollection existing = apCollectionMapper.selectOne(query);

        if (existing == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "未收藏");
        }

        // 删除收藏记录
        apCollectionMapper.deleteById(existing.getId());

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

        log.info("用户{}取消收藏{} {}", userId,
            context.getTargetType() == 1 ? "文章" : "专栏", targetId);

        return BehaviorResult.success(context.getBehaviorType(), "取消收藏成功")
            .withData("collected", false);
    }
}