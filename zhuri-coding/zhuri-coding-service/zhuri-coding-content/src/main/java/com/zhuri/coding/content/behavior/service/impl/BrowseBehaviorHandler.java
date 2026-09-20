package com.zhuri.coding.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhuri.coding.content.behavior.service.BehaviorHandler;
import com.zhuri.coding.content.mapper.interaction.ApBrowseHistoryMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.ApBrowseHistory;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 浏览行为处理器
 * 支持文章、沸点、课程浏览
 */
@Slf4j
@Component
public class BrowseBehaviorHandler implements BehaviorHandler {

    @Autowired
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @Autowired
    private ApBrowseHistoryMapper apBrowseHistoryMapper;

    @Override
    public BehaviorType getType() {
        return BehaviorType.BROWSE_ARTICLE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult execute(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        if (userId == null || targetId == null) {
            return BehaviorResult.failure(context.getBehaviorType(), "参数不完整");
        }

        // 浏览行为：同一用户同一天浏览同一内容，仅更新浏览时间
        String today = new java.sql.Date(System.currentTimeMillis()).toString();
        LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
        query.eq(UserBehaviorRecord::getUserId, userId);
        query.eq(UserBehaviorRecord::getBehaviorType, context.getBehaviorType().getCode());
        query.eq(UserBehaviorRecord::getTargetId, targetId);
        query.eq(UserBehaviorRecord::getStatus, 1);
        query.apply("DATE(created_time) = {0}", today);
        UserBehaviorRecord existing = behaviorRecordMapper.selectOne(query);

        if (existing != null) {
            // 已存在今日浏览记录，仅更新时间
            existing.setUpdatedTime(new Date());
            behaviorRecordMapper.updateById(existing);
            return BehaviorResult.duplicate(context.getBehaviorType())
                .withData("browsed", true)
                .withData("recordId", existing.getId());
        }

        // 新增浏览记录
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

        // 文章浏览历史（ap_browse_history，永久去重：同一用户对同一文章仅保留一条，供"已读去重/浏览历史"使用）
        if (context.getTargetType() != null && context.getTargetType() == 1) {
            try {
                LambdaQueryWrapper<ApBrowseHistory> historyQuery = new LambdaQueryWrapper<>();
                historyQuery.eq(ApBrowseHistory::getUserId, userId.longValue());
                historyQuery.eq(ApBrowseHistory::getArticleId, targetId);
                ApBrowseHistory history = apBrowseHistoryMapper.selectOne(historyQuery);
                if (history == null) {
                    ApBrowseHistory browseHistory = new ApBrowseHistory();
                    browseHistory.setUserId(userId.longValue());
                    browseHistory.setArticleId(targetId);
                    browseHistory.setReadCount(1);
                    browseHistory.setBrowseTime(new Date());
                    apBrowseHistoryMapper.insert(browseHistory);
                } else {
                    history.setBrowseTime(new Date());
                    history.setReadCount(history.getReadCount() == null ? 1 : history.getReadCount() + 1);
                    apBrowseHistoryMapper.updateById(history);
                }
            } catch (Exception e) {
                log.warn("记录文章浏览历史失败, userId={}, articleId={}", userId, targetId, e);
            }
        }

        log.info("用户{}浏览了{} {}", userId,
            getTargetTypeName(context.getTargetType()), targetId);

        return BehaviorResult.success(context.getBehaviorType(), "浏览记录成功")
            .withNewRecord(true)
            .withData("browsed", true)
            .withData("recordId", record.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BehaviorResult rollback(BehaviorContext context) {
        Integer userId = context.getUserId();
        Long targetId = context.getTargetId();

        // 浏览记录不做撤销，直接更新状态
        LambdaQueryWrapper<UserBehaviorRecord> query = new LambdaQueryWrapper<>();
        query.eq(UserBehaviorRecord::getUserId, userId);
        query.eq(UserBehaviorRecord::getBehaviorType, context.getBehaviorType().getCode());
        query.eq(UserBehaviorRecord::getTargetId, targetId);
        query.eq(UserBehaviorRecord::getStatus, 1);
        UserBehaviorRecord record = behaviorRecordMapper.selectOne(query);
        if (record != null) {
            record.setStatus(0);
            record.setUpdatedTime(new Date());
            behaviorRecordMapper.updateById(record);
        }

        return BehaviorResult.success(context.getBehaviorType(), "浏览记录已清除");
    }

    private String getTargetTypeName(Integer targetType) {
        switch (targetType) {
            case 1: return "文章";
            case 2: return "沸点";
            case 3: return "用户";
            case 4: return "课程";
            case 5: return "专栏";
            default: return "未知";
        }
    }
}