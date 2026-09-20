package com.zhuri.coding.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * PublishPinBehaviorHandler 单元测试（发布沸点行为处理器）
 *
 * 逻辑要点：
 * - execute：参数缺失 → failure；幂等命中 → duplicate；否则新增沸点行为记录 success
 * - rollback：targetId 为 null 时直接成功；存在记录 → 置 status=0；不存在 → 直接成功
 * - getType 返回 PUBLISH_PIN
 */
@ExtendWith(MockitoExtension.class)
class PublishPinBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private PublishPinBehaviorHandler handler;

    // ---------- 辅助 ----------

    private BehaviorContext context(Integer userId, Long targetId) {
        // 发布沸点，目标类型固定为沸点(2)
        return new BehaviorContext(BehaviorType.PUBLISH_PIN, userId)
                .withTarget(2, targetId)
                .withTargetUser(100);
    }

    private UserBehaviorRecord existingRecord(Long id, Integer status) {
        UserBehaviorRecord r = new UserBehaviorRecord();
        r.setId(id);
        r.setStatus(status);
        return r;
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回 PUBLISH_PIN")
    void getTypeShouldReturnPublishPin() {
        assertEquals(BehaviorType.PUBLISH_PIN, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - userId 为 null 返回参数缺失失败")
    void executeWithNullUserId() {
        BehaviorResult result = handler.execute(new BehaviorContext(BehaviorType.PUBLISH_PIN, null).withTarget(2, 100L));
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getMessage());
    }

    @Test
    @DisplayName("execute - targetId 为 null 返回参数缺失失败")
    void executeWithNullTargetId() {
        BehaviorResult result = handler.execute(new BehaviorContext(BehaviorType.PUBLISH_PIN, 1).withTarget(2, null));
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getMessage());
    }

    @Test
    @DisplayName("execute - 幂等命中返回 duplicate 且不新增记录")
    void executeWhenDuplicated() {
        lenient().when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(existingRecord(9L, 1));

        BehaviorResult result = handler.execute(context(1, 100L));

        assertTrue(result.isSuccess());
        assertFalse(result.isNewRecord());
        assertEquals(BehaviorType.PUBLISH_PIN, result.getBehaviorType());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 首次发布则记录行为并成功返回 pinsId")
    void executeWhenNewRecord() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BehaviorResult result = handler.execute(context(1, 100L));

        assertTrue(result.isSuccess());
        assertTrue(result.isNewRecord());
        assertEquals("发布沸点成功", result.getMessage());
        assertEquals(100L, (Long) result.getDataValue("pinsId"));

        ArgumentCaptor<UserBehaviorRecord> captor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(captor.capture());
        UserBehaviorRecord saved = captor.getValue();
        assertEquals(Integer.valueOf(1), saved.getUserId());
        assertEquals(BehaviorType.PUBLISH_PIN.getCode(), saved.getBehaviorType());
        assertEquals(Integer.valueOf(2), saved.getTargetType());   // 沸点
        assertEquals(Long.valueOf(100L), saved.getTargetId());
        assertEquals(Integer.valueOf(100), saved.getTargetUserId());
        assertEquals(Integer.valueOf(1), saved.getStatus());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - targetId 为 null 直接成功，不查询不更新")
    void rollbackWhenNullTargetId() {
        BehaviorResult result = handler.rollback(new BehaviorContext(BehaviorType.PUBLISH_PIN, 1));
        assertTrue(result.isSuccess());
        assertEquals("沸点已删除", result.getMessage());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("rollback - 存在记录则置 status=0 并更新时间")
    void rollbackWhenRecordExists() {
        UserBehaviorRecord existing = existingRecord(5L, 1);
        lenient().when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        BehaviorResult result = handler.rollback(context(1, 100L));

        assertTrue(result.isSuccess());
        assertEquals("沸点已删除", result.getMessage());
        assertEquals(Integer.valueOf(0), existing.getStatus());
        assertNotNull(existing.getUpdatedTime());
        verify(behaviorRecordMapper).updateById(existing);
    }

    @Test
    @DisplayName("rollback - 记录不存在则直接成功不更新")
    void rollbackWhenNoRecord() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BehaviorResult result = handler.rollback(context(1, 100L));

        assertTrue(result.isSuccess());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}