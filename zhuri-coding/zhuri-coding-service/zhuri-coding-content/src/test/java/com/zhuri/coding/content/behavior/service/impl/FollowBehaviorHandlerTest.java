package com.zhuri.coding.content.behavior.service.impl;

import com.zhuri.coding.content.mapper.follow.ApFollowMapper;
import com.zhuri.coding.content.mapper.user.UserBehaviorRecordMapper;
import com.zhuri.coding.model.behavior.BehaviorContext;
import com.zhuri.coding.model.behavior.BehaviorResult;
import com.zhuri.coding.model.behavior.BehaviorType;
import com.zhuri.coding.model.behavior.pojos.UserBehaviorRecord;
import com.zhuri.coding.model.follow.pojos.ApFollow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FollowBehaviorHandler 单元测试（关注/取消关注用户）
 *
 * 普通 @Component + @Autowired 字段注入，@ExtendWith(MockitoExtension.class) + @InjectMocks。
 * 覆盖 execute / rollback / getType 全部分支，含目标ID为空兜底与 DuplicateKeyException 捕获分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("关注/取消关注行为处理器单元测试")
class FollowBehaviorHandlerTest {

    @Mock
    private ApFollowMapper apFollowMapper;
    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private FollowBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造一个参数完整的“关注用户”上下文 */
    private BehaviorContext followContext() {
        return new BehaviorContext(BehaviorType.FOLLOW_USER, 1001)
            .withTarget(3, 7007L)
            .withTargetUser(2002);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回关注用户类型")
    void testGetType() {
        assertEquals(BehaviorType.FOLLOW_USER, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID或关注对象ID为空返回失败")
    void testExecuteParamIncomplete() {
        // 操作用户为空
        BehaviorContext ctx1 = new BehaviorContext(BehaviorType.FOLLOW_USER, null)
            .withTargetUser(2002);
        assertFalse(handler.execute(ctx1).isSuccess());

        // 关注对象为空
        BehaviorContext ctx2 = new BehaviorContext(BehaviorType.FOLLOW_USER, 1001)
            .withTargetUser(null);
        assertFalse(handler.execute(ctx2).isSuccess());

        verify(apFollowMapper, never()).insert(any(ApFollow.class));
    }

    @Test
    @DisplayName("execute - 不能关注自己返回失败")
    void testExecuteSelfFollow() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.FOLLOW_USER, 1001)
            .withTargetUser(1001);
        BehaviorResult r = handler.execute(ctx);
        assertFalse(r.isSuccess());
        assertEquals("不能关注自己", r.getMessage());
        verify(apFollowMapper, never()).insert(any(ApFollow.class));
    }

    @Test
    @DisplayName("execute - 已关注幂等返回重复")
    void testExecuteDuplicate() {
        ApFollow existing = new ApFollow();
        existing.setId(5L);
        when(apFollowMapper.selectOne(any())).thenReturn(existing);

        BehaviorResult r = handler.execute(followContext());

        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("followed"));
        assertEquals(5L, (Long) r.getDataValue("followId"));
        verify(apFollowMapper, never()).insert(any(ApFollow.class));
    }

    @Test
    @DisplayName("execute - 正常关注返回成功且写入正确字段")
    void testExecuteSuccess() {
        when(apFollowMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.execute(followContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("followed"));

        ArgumentCaptor<ApFollow> followCaptor = ArgumentCaptor.forClass(ApFollow.class);
        verify(apFollowMapper).insert(followCaptor.capture());
        ApFollow follow = followCaptor.getValue();
        assertEquals(1001, follow.getUserId());
        assertEquals(2002, follow.getFollowUserId());

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.FOLLOW_USER.getCode(), rec.getBehaviorType());
        assertEquals(3, rec.getTargetType());
        assertEquals(2002, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
    }

    @Test
    @DisplayName("execute - 正常关注但目标ID为空时行为记录用被关注用户ID兜底")
    void testExecuteSuccessNullTargetId() {
        when(apFollowMapper.selectOne(any())).thenReturn(null);
        BehaviorContext ctx = new BehaviorContext(BehaviorType.FOLLOW_USER, 1001)
            .withTargetUser(2002);
        BehaviorResult r = handler.execute(ctx);
        assertTrue(r.isSuccess());

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        // targetId 为 null → 使用 targetUserId.longValue() 兜底
        assertEquals(2002L, recCaptor.getValue().getTargetId());
    }

    @Test
    @DisplayName("execute - 并发唯一索引冲突捕获 DuplicateKeyException 视为已关注")
    void testExecuteDuplicateKeyException() {
        when(apFollowMapper.selectOne(any())).thenReturn(null);
        when(apFollowMapper.insert(any(ApFollow.class)))
            .thenThrow(new DuplicateKeyException("uk_follow_user_target"));

        BehaviorResult r = handler.execute(followContext());

        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("followed"));
        // 冲突时不写入行为日志
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 用户ID或关注对象ID为空返回失败")
    void testRollbackParamIncomplete() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.UNFOLLOW_USER, null)
            .withTargetUser(2002);
        assertFalse(handler.rollback(ctx).isSuccess());
    }

    @Test
    @DisplayName("rollback - 未关注该用户返回失败")
    void testRollbackNotFound() {
        when(apFollowMapper.selectOne(any())).thenReturn(null);
        BehaviorResult r = handler.rollback(followContext());
        assertFalse(r.isSuccess());
        assertEquals("未关注该用户", r.getMessage());
        verify(apFollowMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("rollback - 取消关注成功且删除记录并撤销行为日志")
    void testRollbackSuccessWithRecord() {
        ApFollow existing = new ApFollow();
        existing.setId(9L);
        when(apFollowMapper.selectOne(any())).thenReturn(existing);

        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        BehaviorResult r = handler.rollback(followContext());

        assertTrue(r.isSuccess());
        assertFalse((Boolean) r.getDataValue("followed"));
        verify(apFollowMapper).deleteById(9L);

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).updateById(recCaptor.capture());
        assertEquals(0, recCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("rollback - 取消关注成功但无行为记录不更新")
    void testRollbackSuccessWithoutRecord() {
        ApFollow existing = new ApFollow();
        existing.setId(9L);
        when(apFollowMapper.selectOne(any())).thenReturn(existing);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.rollback(followContext());

        assertTrue(r.isSuccess());
        verify(apFollowMapper).deleteById(9L);
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}