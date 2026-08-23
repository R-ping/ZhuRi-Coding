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
import static org.mockito.Mockito.when;

/**
 * CommentBehaviorHandler 单元测试（评论行为记录）
 *
 * 普通 @Component + @Autowired 字段注入，@ExtendWith(MockitoExtension.class) + @InjectMocks。
 * 覆盖 execute / rollback / getType 全部分支，含 extra.commentId 为空/非空的撤销分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评论行为处理器单元测试")
class CommentBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private CommentBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造携带 commentId 的“评论文章”上下文 */
    private BehaviorContext commentContext() {
        return new BehaviorContext(BehaviorType.COMMENT_ARTICLE, 1001)
            .withTarget(1, 2002L)
            .withTargetUser(3003)
            .withExtra("commentId", 555L);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回评论文章类型")
    void testGetType() {
        assertEquals(BehaviorType.COMMENT_ARTICLE, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID或目标ID为空返回失败")
    void testExecuteParamIncomplete() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.COMMENT_ARTICLE, null)
            .withTarget(1, 2002L);
        assertFalse(handler.execute(ctx).isSuccess());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 正常评论返回成功且写入行为日志")
    void testExecuteSuccess() {
        BehaviorResult r = handler.execute(commentContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        // 返回值透传 extra.commentId
        assertEquals(555L, (Long) r.getDataValue("commentId"));

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.COMMENT_ARTICLE.getCode(), rec.getBehaviorType());
        assertEquals(1, rec.getTargetType());
        assertEquals(2002L, rec.getTargetId());
        assertEquals(3003, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
        assertNotNull(rec.getCreatedTime());
        assertNotNull(rec.getUpdatedTime());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 无 commentId 直接返回成功且不查询行为记录")
    void testRollbackNoCommentId() {
        // 未携带 commentId
        BehaviorContext ctx = new BehaviorContext(BehaviorType.COMMENT_ARTICLE, 1001)
            .withTarget(1, 2002L);
        BehaviorResult r = handler.rollback(ctx);
        assertTrue(r.isSuccess());
        verify(behaviorRecordMapper, never()).selectOne(any());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("rollback - 有 commentId 且存在有效行为记录则撤销状态")
    void testRollbackWithRecord() {
        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        BehaviorResult r = handler.rollback(commentContext());

        assertTrue(r.isSuccess());

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).updateById(recCaptor.capture());
        assertEquals(0, recCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("rollback - 有 commentId 但无行为记录不更新")
    void testRollbackWithoutRecord() {
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.rollback(commentContext());

        assertTrue(r.isSuccess());
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}