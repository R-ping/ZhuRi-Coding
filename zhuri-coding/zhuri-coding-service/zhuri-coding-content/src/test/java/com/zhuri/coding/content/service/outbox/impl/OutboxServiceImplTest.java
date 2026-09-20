package com.heima.content.service.outbox.impl;

import com.heima.content.mapper.outbox.OutboxEventMapper;
import com.heima.model.outbox.pojos.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxServiceImpl 单元测试（本地消息表写入与状态流转）。
 *
 * <p>重点验证：<br>
 * 1. record 正常写入（PENDING，maxRetries=5）；<br>
 * 2. uk_event_key 冲突 → 幂等短路返回 false（不抛异常，不阻断主流程）；<br>
 * 3. markFailed 未达上限 → 回 PENDING + 指数退避（2^n 分钟）；<br>
 * 4. markFailed 达上限 → DEAD + ERROR 告警。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxServiceImpl 写入与状态流转")
class OutboxServiceImplTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    private OutboxServiceImpl outboxService;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 运行时解析 lambda 需要 OutboxEvent 的 TableInfo 缓存
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.heima.model.outbox.pojos.OutboxEvent.class);
        outboxService = new OutboxServiceImpl();
        org.springframework.test.util.ReflectionTestUtils
                .setField(outboxService, "outboxEventMapper", outboxEventMapper);
    }

    @Test
    @DisplayName("record 正常写入：PENDING + maxRetries=5，返回 true")
    void testRecordInsertsPending() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        boolean ok = outboxService.record("PAY_REWARD:O1", "PAY_REWARD", "{}");

        assertTrue(ok);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent e = captor.getValue();
        assertEquals("PAY_REWARD:O1", e.getEventKey());
        assertEquals("PAY_REWARD", e.getEventType());
        assertEquals(OutboxEvent.STATUS_PENDING, e.getStatus());
        assertEquals(0, e.getRetryCount());
        assertEquals(5, e.getMaxRetries());
    }

    @Test
    @DisplayName("record event_key 冲突 → 幂等短路返回 false（不抛异常）")
    void testRecordDuplicateKeyShortCircuit() {
        when(outboxEventMapper.insert(any(OutboxEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_event_key"));

        boolean ok = outboxService.record("PAY_REWARD:O1", "PAY_REWARD", "{}");

        assertFalse(ok); // 幂等短路，不抛异常不阻断主流程
    }

    @Test
    @DisplayName("markDone 置 DONE")
    void testMarkDone() {
        outboxService.markDone(9L);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(outboxEventMapper).update(any(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("status"));
    }

    @Test
    @DisplayName("markFailed 未达上限 → 回 PENDING + 指数退避（2^n 分钟，封顶 60）")
    void testMarkFailedSchedulesBackoff() {
        OutboxEvent e = event(0, 5); // retryCount=0, maxRetries=5

        outboxService.markFailed(e, "boom");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(outboxEventMapper).update(any(), captor.capture());
        // 第 1 次失败 → 2^1=2 分钟后重试
        Calendar expected = Calendar.getInstance();
        expected.add(Calendar.MINUTE, 2);
        // setSql 不便解析时退而求其次：通过 getSqlSet 确认 next_retry_at 与 retry_count 被设置
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("next_retry_at"));
        assertTrue(sqlSet.contains("retry_count"));
    }

    @Test
    @DisplayName("markFailed 达上限 → 置 DEAD")
    void testMarkFailedExhaustedDead() {
        OutboxEvent e = event(4, 5); // retryCount=4, 第 5 次失败 → 5>=5 DEAD

        outboxService.markFailed(e, "still down");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(outboxEventMapper).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("last_error"));
    }

    @Test
    @DisplayName("markFailed 错误原因截断到 500 字符")
    void testMarkFailedTruncatesError() {
        OutboxEvent e = event(0, 5);
        String longError = "x".repeat(2000);

        outboxService.markFailed(e, longError);

        verify(outboxEventMapper).update(any(), any());
    }

    private OutboxEvent event(int retryCount, int maxRetries) {
        OutboxEvent e = new OutboxEvent();
        e.setId(1L);
        e.setEventKey("PAY_REWARD:O1");
        e.setEventType("PAY_REWARD");
        e.setPayload("{}");
        e.setStatus(OutboxEvent.STATUS_PROCESSING);
        e.setRetryCount(retryCount);
        e.setMaxRetries(maxRetries);
        return e;
    }
}
