package com.heima.content.behavior.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PublishArticleBehaviorHandler 单元测试（发布文章行为处理器）
 *
 * 逻辑要点：
 * - execute：参数缺失 → failure；幂等命中（已是 PUBLISH_ARTICLE 且 targetId 存在）→ duplicate；否则 insert 行为记录 success
 * - rollback：文章发布不支持撤销，恒返回 success（"不支持撤销"）
 * - getType 返回 PUBLISH_ARTICLE
 */
@ExtendWith(MockitoExtension.class)
class PublishArticleBehaviorHandlerTest {

    @Mock
    private UserBehaviorRecordMapper behaviorRecordMapper;

    @InjectMocks
    private PublishArticleBehaviorHandler handler;

    // ---------- 辅助 ----------

    private BehaviorContext context(Integer userId, Long targetId) {
        // 发布文章，目标类型固定为文章(1)
        return new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, userId)
                .withTarget(1, targetId)
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
    @DisplayName("getType - 返回 PUBLISH_ARTICLE")
    void getTypeShouldReturnPublishArticle() {
        assertEquals(BehaviorType.PUBLISH_ARTICLE, handler.getType());
    }

    // ==================== execute ====================

    @Test
    @DisplayName("execute - userId 为 null 返回参数缺失失败")
    void executeWithNullUserId() {
        BehaviorResult result = handler.execute(new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, null).withTarget(1, 100L));
        assertFalse(result.isSuccess());
        assertEquals("参数不完整", result.getMessage());
    }

    @Test
    @DisplayName("execute - targetId 为 null 返回参数缺失失败")
    void executeWithNullTargetId() {
        BehaviorResult result = handler.execute(new BehaviorContext(BehaviorType.PUBLISH_ARTICLE, 1).withTarget(1, null));
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
        assertEquals(BehaviorType.PUBLISH_ARTICLE, result.getBehaviorType());
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }

    @Test
    @DisplayName("execute - 首次发布则记录行为并成功返回 articleId")
    void executeWhenNewRecord() {
        when(behaviorRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BehaviorResult result = handler.execute(context(1, 100L));

        assertTrue(result.isSuccess());
        assertTrue(result.isNewRecord());
        assertEquals("发布文章成功", result.getMessage());
        assertEquals(100L, (Long) result.getDataValue("articleId"));

        ArgumentCaptor<UserBehaviorRecord> captor = ArgumentCaptor.forClass(UserBehaviorRecord.class);
        verify(behaviorRecordMapper).insert(captor.capture());
        UserBehaviorRecord saved = captor.getValue();
        assertEquals(Integer.valueOf(1), saved.getUserId());
        assertEquals(BehaviorType.PUBLISH_ARTICLE.getCode(), saved.getBehaviorType());
        assertEquals(Integer.valueOf(1), saved.getTargetType());   // 文章
        assertEquals(Long.valueOf(100L), saved.getTargetId());
        assertEquals(Integer.valueOf(100), saved.getTargetUserId());
        assertEquals(Integer.valueOf(1), saved.getStatus());
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback - 发布文章不支持撤销，恒返回成功")
    void rollbackAlwaysSuccess() {
        BehaviorResult result = handler.rollback(context(1, 100L));
        assertTrue(result.isSuccess());
        assertEquals("不支持撤销", result.getMessage());
        // 不触发任何数据库写入
        verify(behaviorRecordMapper, never()).updateById(any(UserBehaviorRecord.class));
        verify(behaviorRecordMapper, never()).insert(any(UserBehaviorRecord.class));
    }
}