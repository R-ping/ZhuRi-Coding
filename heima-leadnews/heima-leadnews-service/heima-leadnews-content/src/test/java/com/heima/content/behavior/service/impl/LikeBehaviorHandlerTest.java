package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.interaction.ApBehaviorLikesMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.ApBehaviorLikes;
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
 * LikeBehaviorHandler 单元测试（点赞/取消点赞：文章、沸点）
 *
 * 普通 @Component + @Autowired 字段注入，采用 @ExtendWith(MockitoExtension.class) +
 * @InjectMocks 注入两个 Mapper Mock。
 * 覆盖 execute / rollback / getType 的全部分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("点赞/取消点赞行为处理器单元测试")
class LikeBehaviorHandlerTest {

    @Mock
    private ApBehaviorLikesMapper apBehaviorLikesMapper;
    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private LikeBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造一个参数完整的“点赞文章”上下文 */
    private BehaviorContext likeContext() {
        return new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1001)
            .withTarget(1, 2002L)
            .withTargetUser(3003);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回点赞文章类型")
    void testGetType() {
        assertEquals(BehaviorType.LIKE_ARTICLE, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID为空返回失败")
    void testExecuteUserIdNull() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, null)
            .withTarget(1, 2002L);
        BehaviorResult r = handler.execute(ctx);
        assertFalse(r.isSuccess());
        // 未触发任何插入
        verify(apBehaviorLikesMapper, never()).insert(any(ApBehaviorLikes.class));
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 目标ID为空返回失败")
    void testExecuteTargetIdNull() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, 1001)
            .withTarget(1, null);
        BehaviorResult r = handler.execute(ctx);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("execute - 已点赞幂等返回重复")
    void testExecuteDuplicate() {
        ApBehaviorLikes existing = new ApBehaviorLikes();
        existing.setId(9L);
        when(apBehaviorLikesMapper.selectOne(any())).thenReturn(existing);
        BehaviorResult r = handler.execute(likeContext());
        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("liked"));
        // 已存在则不再插入
        verify(apBehaviorLikesMapper, never()).insert(any(ApBehaviorLikes.class));
    }

    @Test
    @DisplayName("execute - 正常点赞返回成功且写入正确字段")
    void testExecuteSuccess() {
        when(apBehaviorLikesMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.execute(likeContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("liked"));

        // 断言点赞记录写入字段
        ArgumentCaptor<ApBehaviorLikes> likeCaptor = ArgumentCaptor.forClass(ApBehaviorLikes.class);
        verify(apBehaviorLikesMapper).insert(likeCaptor.capture());
        ApBehaviorLikes like = likeCaptor.getValue();
        assertEquals(2002L, like.getEntryId());
        assertEquals(1001, like.getUserId());
        assertEquals(1, like.getType());
        assertEquals(0, like.getOperation());
        assertNotNull(like.getCreatedTime());

        // 断言行为日志写入字段
        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.LIKE_ARTICLE.getCode(), rec.getBehaviorType());
        assertEquals(1, rec.getTargetType());
        assertEquals(2002L, rec.getTargetId());
        assertEquals(3003, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
        assertNotNull(rec.getCreatedTime());
        assertNotNull(rec.getUpdatedTime());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 用户ID为空返回失败")
    void testRollbackUserIdNull() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.LIKE_ARTICLE, null)
            .withTarget(1, 2002L);
        assertFalse(handler.rollback(ctx).isSuccess());
    }

    @Test
    @DisplayName("rollback - 未找到点赞记录返回失败")
    void testRollbackNotFound() {
        when(apBehaviorLikesMapper.selectOne(any())).thenReturn(null);
        BehaviorResult r = handler.rollback(likeContext());
        assertFalse(r.isSuccess());
        verify(apBehaviorLikesMapper, never()).updateById(any(ApBehaviorLikes.class));
    }

    @Test
    @DisplayName("rollback - 取消点赞成功且同步撤销行为记录")
    void testRollbackSuccessWithRecord() {
        ApBehaviorLikes existing = new ApBehaviorLikes();
        existing.setId(9L);
        existing.setOperation(0);
        when(apBehaviorLikesMapper.selectOne(any())).thenReturn(existing);

        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        BehaviorResult r = handler.rollback(likeContext());

        assertTrue(r.isSuccess());
        assertFalse((Boolean) r.getDataValue("liked"));
        // 点赞记录置为取消（operation=1）
        assertEquals(1, existing.getOperation());
        verify(apBehaviorLikesMapper).updateById(existing);

        // 行为记录状态置 0
        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).updateById(recCaptor.capture());
        assertEquals(0, recCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("rollback - 取消点赞成功但无行为记录不更新")
    void testRollbackSuccessWithoutRecord() {
        ApBehaviorLikes existing = new ApBehaviorLikes();
        existing.setOperation(0);
        when(apBehaviorLikesMapper.selectOne(any())).thenReturn(existing);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.rollback(likeContext());

        assertTrue(r.isSuccess());
        assertEquals(1, existing.getOperation());
        verify(apBehaviorLikesMapper).updateById(existing);
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}