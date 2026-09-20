package com.zhuri.coding.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhuri.coding.content.mapper.interaction.ApBrowseHistoryMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.ApBrowseHistory;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BrowseBehaviorHandler 单元测试（浏览行为处理器：文章/沸点/课程浏览）
 *
 * 逻辑要点：
 * - execute：参数缺失 → failure；今日已浏览 → duplicate 且仅更新时间；未浏览 → 新增记录 success+withNewRecord(true)
 * - rollback：存在记录 → 置 status=0 并更新时间；不存在 → 不更新仍返回 success
 * - getType 返回 BROWSE_ARTICLE
 */
@ExtendWith(MockitoExtension.class)
class BrowseBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;
    @Mock
    private ApBrowseHistoryMapper apBrowseHistoryMapper;

    @InjectMocks
    private BrowseBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造一个完整的浏览行为上下文（文章 targetType=1） */
    private BehaviorContext context(Integer userId, Integer targetType, Long targetId) {
        return new BehaviorContext(BehaviorType.BROWSE_ARTICLE, userId)
                .withTarget(targetType, targetId)
                .withTargetUser(100);
    }

    private UserBehaviorRecord existingRecord(Long id, Integer status) {
        UserBehaviorRecord r = new UserBehaviorRecord();
        r.setId(id);
        r.setStatus(status);
        r.setUpdatedTime(new Date());
        return r;
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回 BROWSE_ARTICLE")
    void getTypeShouldReturnBrowseArticle() {
        assertEquals(BehaviorType.BROWSE_ARTICLE, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - userId 为 null 返回参数缺失失败")
    void executeWithNullUserId() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.BROWSE_ARTICLE, null).withTarget(1, 100L);
        BehaviorResult result = handler.execute(ctx);
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getMessage());
    }

    @Test
    @DisplayName("execute - targetId 为 null 返回参数缺失失败")
    void executeWithNullTargetId() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.BROWSE_ARTICLE, 1).withTarget(1, null);
        BehaviorResult result = handler.execute(ctx);
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getMessage());
    }

    @Test
    @DisplayName("execute - 今日已浏览则返回 duplicate 并更新浏览时间")
    void executeWhenExistsToday() {
        UserBehaviorRecord existing = existingRecord(9L, 1);
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        BehaviorResult result = handler.execute(context(1, 1, 100L));

        assertTrue(result.isSuccess());
        assertFalse(result.isNewRecord());          // 重复浏览不视为新增
        assertEquals("已操作过，无需重复处理", result.getMessage());
        assertEquals(true, result.getDataValue("browsed"));
        assertEquals(9L, (Long) result.getDataValue("recordId"));
        // 仅更新已有记录时间，不再 insert
        assertNotNull(existing.getUpdatedTime());
        verify(behaviorRecordMapper).updateById(existing);
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 首次浏览则新增记录并返回成功")
    void executeWhenNoExistingRecord() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 文章浏览历史：无记录 → 新增（永久去重）
        when(apBrowseHistoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BehaviorResult result = handler.execute(context(1, 1, 100L));

        assertTrue(result.isSuccess());
        assertTrue(result.isNewRecord());
        assertEquals("浏览记录成功", result.getMessage());
        assertEquals(true, result.getDataValue("browsed"));

        ArgumentCaptor<UserBehaviorRecord> captor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(captor.capture());
        UserBehaviorRecord saved = captor.getValue();
        assertEquals(Integer.valueOf(1), saved.getUserId());
        assertEquals(BehaviorType.BROWSE_ARTICLE.getCode(), saved.getBehaviorType());
        assertEquals(Integer.valueOf(1), saved.getTargetType());
        assertEquals(Long.valueOf(100L), saved.getTargetId());
        assertEquals(Integer.valueOf(100), saved.getTargetUserId());
        assertEquals(Integer.valueOf(1), saved.getStatus());

        // 浏览历史同步写入
        ArgumentCaptor<ApBrowseHistory> historyCaptor = ArgumentCaptor.forClass(ApBrowseHistory.class);
        verify(apBrowseHistoryMapper).insert(historyCaptor.capture());
        assertEquals(Long.valueOf(1L), historyCaptor.getValue().getUserId());
        assertEquals(Long.valueOf(100L), historyCaptor.getValue().getArticleId());
        assertNotNull(historyCaptor.getValue().getBrowseTime());
    }

    @Test
    @DisplayName("execute - 同一用户 沸点/课程 目标类型不同仍可记录")
    void executeWithDifferentTargetType() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 沸点 targetType=2
        BehaviorResult pinResult = handler.execute(context(2, 2, 200L));
        assertTrue(pinResult.isSuccess());
        // 课程 targetType=4
        BehaviorResult courseResult = handler.execute(context(3, 4, 300L));
        assertTrue(courseResult.isSuccess());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 存在浏览记录则将状态置为 0")
    void rollbackWhenRecordExists() {
        UserBehaviorRecord existing = existingRecord(5L, 1);
        lenient().when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        BehaviorResult result = handler.rollback(context(1, 1, 100L));

        assertTrue(result.isSuccess());
        assertEquals("浏览记录已清除", result.getMessage());
        assertEquals(Integer.valueOf(0), existing.getStatus());
        assertNotNull(existing.getUpdatedTime());
        verify(behaviorRecordMapper).updateById(existing);
    }

    @Test
    @DisplayName("rollback - 记录不存在时直接成功且不更新")
    void rollbackWhenNoRecord() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BehaviorResult result = handler.rollback(context(1, 1, 100L));

        assertTrue(result.isSuccess());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }
}