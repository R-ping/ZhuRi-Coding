package com.heima.content.behavior.service.impl;

import com.heima.content.mapper.article.ApArticleMapper;
import com.heima.content.mapper.interaction.ApCollectionMapper;
import com.heima.content.mapper.user.UserBehaviorRecordMapper;
import com.heima.model.behavior.BehaviorContext;
import com.heima.model.behavior.BehaviorResult;
import com.heima.model.behavior.BehaviorType;
import com.heima.model.behavior.pojos.ApCollection;
import com.heima.model.behavior.pojos.UserBehaviorRecord;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CollectBehaviorHandler 单元测试（收藏/取消收藏：文章、专栏）
 *
 * 普通 @Component + @Autowired 字段注入，@ExtendWith(MockitoExtension.class) + @InjectMocks。
 * 覆盖 execute / rollback / getType 全部分支，含 DuplicateKeyException 捕获分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("收藏/取消收藏行为处理器单元测试")
class CollectBehaviorHandlerTest {

    @Mock
    private ApCollectionMapper apCollectionMapper;
    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;
    @Mock
    private ApArticleMapper apArticleMapper;

    @InjectMocks
    private CollectBehaviorHandler handler;

    // ---------- 辅助 ----------

    /** 构造一个参数完整的“收藏文章”上下文 */
    private BehaviorContext collectContext() {
        return new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 1001)
            .withTarget(1, 2002L)
            .withTargetUser(3003);
    }

    // ==================== getType ====================

    @Test
    @DisplayName("getType - 返回收藏文章类型")
    void testGetType() {
        assertEquals(BehaviorType.COLLECT_ARTICLE, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - 用户ID或目标ID为空返回失败")
    void testExecuteParamIncomplete() {
        BehaviorContext ctx1 = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, null)
            .withTarget(1, 2002L);
        assertFalse(handler.execute(ctx1).isSuccess());

        BehaviorContext ctx2 = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 1001)
            .withTarget(1, null);
        assertFalse(handler.execute(ctx2).isSuccess());

        verify(apCollectionMapper, never()).insert(any(ApCollection.class));
    }

    @Test
    @DisplayName("execute - 已收藏幂等返回重复")
    void testExecuteDuplicate() {
        when(apCollectionMapper.selectOne(any())).thenReturn(new ApCollection());
        BehaviorResult r = handler.execute(collectContext());
        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("collected"));
        verify(apCollectionMapper, never()).insert(any(ApCollection.class));
    }

    @Test
    @DisplayName("execute - 正常收藏返回成功且写入正确字段")
    void testExecuteSuccess() {
        when(apCollectionMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.execute(collectContext());

        assertTrue(r.isSuccess());
        assertTrue(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("collected"));

        ArgumentCaptor<ApCollection> captor = ArgumentCaptor.forClass(ApCollection.class);
        verify(apCollectionMapper).insert(captor.capture());
        ApCollection collection = captor.getValue();
        assertEquals(1001, collection.getUserId());
        assertEquals(2002L, collection.getArticleId());
        assertNotNull(collection.getCreatedTime());

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(recCaptor.capture());
        UserBehaviorRecord rec = recCaptor.getValue();
        assertEquals(1001, rec.getUserId());
        assertEquals(BehaviorType.COLLECT_ARTICLE.getCode(), rec.getBehaviorType());
        assertEquals(1, rec.getTargetType());
        assertEquals(2002L, rec.getTargetId());
        assertEquals(3003, rec.getTargetUserId());
        assertEquals(1, rec.getStatus());
    }

    @Test
    @DisplayName("execute - 并发唯一索引冲突捕获 DuplicateKeyException 视为已收藏")
    void testExecuteDuplicateKeyException() {
        when(apCollectionMapper.selectOne(any())).thenReturn(null);
        when(apCollectionMapper.insert(any(ApCollection.class)))
            .thenThrow(new DuplicateKeyException("uk_collection_user_article"));

        BehaviorResult r = handler.execute(collectContext());

        assertTrue(r.isSuccess());
        assertFalse(r.isNewRecord());
        assertTrue((Boolean) r.getDataValue("collected"));
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 目标ID为空返回失败")
    void testRollbackParamIncomplete() {
        BehaviorContext ctx = new BehaviorContext(BehaviorType.COLLECT_ARTICLE, 1001)
            .withTarget(1, null);
        assertFalse(handler.rollback(ctx).isSuccess());
    }

    @Test
    @DisplayName("rollback - 未收藏返回失败")
    void testRollbackNotFound() {
        when(apCollectionMapper.selectOne(any())).thenReturn(null);
        BehaviorResult r = handler.rollback(collectContext());
        assertFalse(r.isSuccess());
        verify(apCollectionMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("rollback - 取消收藏成功删除记录并撤销行为日志")
    void testRollbackSuccessWithRecord() {
        ApCollection existing = new ApCollection();
        existing.setId(9L);
        when(apCollectionMapper.selectOne(any())).thenReturn(existing);

        UserBehaviorRecord record = new UserBehaviorRecord();
        record.setStatus(1);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(record);

        BehaviorResult r = handler.rollback(collectContext());

        assertTrue(r.isSuccess());
        assertFalse((Boolean) r.getDataValue("collected"));
        verify(apCollectionMapper).deleteById(9L);

        ArgumentCaptor<UserBehaviorRecord> recCaptor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).updateById(recCaptor.capture());
        assertEquals(0, recCaptor.getValue().getStatus());

        // 文章热度分同步更新（collection -1）
        verify(apArticleMapper).updateInteractionAndScore(2002L, "collection", -1);
    }

    @Test
    @DisplayName("rollback - 取消收藏成功但无行为记录不更新")
    void testRollbackSuccessWithoutRecord() {
        ApCollection existing = new ApCollection();
        existing.setId(9L);
        when(apCollectionMapper.selectOne(any())).thenReturn(existing);
        when(behaviorRecordMapper.selectOne(any())).thenReturn(null);

        BehaviorResult r = handler.rollback(collectContext());

        assertTrue(r.isSuccess());
        verify(apCollectionMapper).deleteById(9L);
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
    }
}