package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
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

/**
 * PinCommentBehaviorHandler 单元测试（沸点评论行为记录）
 *
 * 普通 @Component + @Autowired 字段注入，@ExtendWith(MockitoExtension.class) + @InjectMocks。
 * 覆盖 execute / rollback / getType 的分支；rollback 仅返回成功，不涉及 Mapper 交互。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("沸点评论行为处理器单元测试")
class PinCommentBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private PinCommentBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造携带 commentId 的“评论沸点”上下文 */
    private BehaviorContext pinCommentContext() {
        return new BehaviorContext(BehaviorType.COMMENT_PIN, 1001)
            .withTarget(2, 2002L)
            .withTargetUser(3003)
            .withExtra("commentId", 666L);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回评论沸点类型")
    void testGetType() {
        assertEquals(BehaviorType.COMMENT_PIN, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID或目标ID为空返回失败")
    void testExecuteParamIncomplete() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.COMMENT_PIN, null)
            .withTarget(2, 2002L);
        assertFalse(handler.execute(ctx).isSuccess());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 正常评论沸点返回成功且写入行为日志")
    void testExecuteSuccess() {
        BehaviorResult r = handler.execute(pinCommentContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        assertEquals(666L, (Long) r.getDataValue("commentId"));

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.COMMENT_PIN.getCode(), rec.getBehaviorType());
        assertEquals(2, rec.getTargetType());
        assertEquals(2002L, rec.getTargetId());
        assertEquals(3003, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
        assertNotNull(rec.getCreatedTime());
        assertNotNull(rec.getUpdatedTime());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 直接返回成功且不触发任何 Mapper 调用")
    void testRollback() {
        BehaviorResult r = handler.rollback(pinCommentContext());
        assertTrue(r.isSuccess());
        verify(behaviorRecordMapper, never()).selectOne(any());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}