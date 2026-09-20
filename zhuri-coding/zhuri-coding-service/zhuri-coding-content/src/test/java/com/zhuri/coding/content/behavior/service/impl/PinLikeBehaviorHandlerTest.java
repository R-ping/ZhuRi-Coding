package com.zhuri.coding.content.behavior.service.impl;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PinLikeBehaviorHandler 单元测试（沸点点赞行为记录）
 *
 * 普通 @Component + @Autowired 字段注入，@ExtendWith(MockitoExtension.class) + @InjectMocks。
 * 覆盖 execute / rollback / getType 的分支，含幂等重复分支与撤销分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("沸点点赞行为处理器单元测试")
class PinLikeBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private PinLikeBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造一个参数完整的“点赞沸点”上下文 */
    private BehaviorContext pinLikeContext() {
        return new BehaviorContext(BehaviorType.LIKE_PIN, 1001)
            .withTarget(2, 2002L)
            .withTargetUser(3003);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回点赞沸点类型")
    void testGetType() {
        assertEquals(BehaviorType.LIKE_PIN, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID或目标ID为空返回失败")
    void testExecuteParamIncomplete() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_PIN, null)
            .withTarget(2, 2002L);
        assertFalse(handler.execute(ctx).isSuccess());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 同用户同沸点已点赞幂等返回重复")
    void testExecuteDuplicate() {
        when(behaviorRecordMapper.selectOne(any())).thenReturn(new UserBehaviorRecord());
        BehaviorResult r = handler.execute(pinLikeContext());
        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 正常点赞沸点返回成功且写入行为日志")
    void testExecuteSuccess() {
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.execute(pinLikeContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("liked"));

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.LIKE_PIN.getCode(), rec.getBehaviorType());
        assertEquals(2, rec.getTargetType());
        assertEquals(2002L, rec.getTargetId());
        assertEquals(3003, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
        assertNotNull(rec.getCreatedTime());
        assertNotNull(rec.getUpdatedTime());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 存在有效行为记录则撤销状态")
    void testRollbackWithRecord() {
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        BehaviorResult r = handler.rollback(pinLikeContext());

        assertTrue(r.isSuccess());

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).updateById(recCaptor.capture());
        assertEquals(0, recCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("rollback - 无有效行为记录则不更新")
    void testRollbackWithoutRecord() {
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.rollback(pinLikeContext());

        assertTrue(r.isSuccess());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}