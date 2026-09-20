package com.zhuri.coding.content.service.outbox;

import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.content.service.outbox.impl.OutboxServiceImpl;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxDispatcher 单元测试（分发状态机）。
 *
 * <p>重点验证：<br>
 * 1. CAS 抢占失败（其它实例已抢）→ 跳过不执行；<br>
 * 2. 正常路径：路由 handler → execute → markDone；<br>
 * 3. handler 抛异常 → markFailed（走重试）；<br>
 * 4. handler 抛 DeadSignal → 直接置 DEAD；<br>
 * 5. 无 handler（配置错误）→ markFailed 带原因；<br>
 * 6. 重复 eventType 注册 → 构造期快速失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxDispatcher 分发状态机")
class OutboxDispatcherTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;
    @Mock
    private OutboxService outboxService;
    @Mock
    private OutboxHandler handler;

    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 运行时解析 lambda 需要 OutboxEvent 的 TableInfo 缓存
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.zhuri.coding.model.outbox.pojos.OutboxEvent.class);
        org.mockito.Mockito.lenient().when(handler.eventType()).thenReturn("PAY_REWARD");
        dispatcher = new OutboxDispatcher(outboxEventMapper, outboxService, List.of(handler));
    }

    private OutboxEvent event(int status) {
        OutboxEvent e = new OutboxEvent();
        e.setId(1L);
        e.setEventKey("PAY_REWARD:O1");
        e.setEventType("PAY_REWARD");
        e.setPayload("{\"orderNo\":\"O1\",\"userId\":7,\"courseId\":10}");
        e.setStatus(status);
        e.setRetryCount(0);
        e.setMaxRetries(5);
        return e;
    }

    @Test
    @DisplayName("CAS 抢占失败（被其它实例抢走）→ 不执行 handler")
    void testCasLossSkipsExecution() throws Exception {
        OutboxEvent e = event(OutboxEvent.STATUS_PENDING);
        when(outboxEventMapper.update(any(), any())).thenReturn(0); // CAS 未命中

        dispatcher.safeDispatchOne(e);

        verify(handler, never()).execute(any());
        verify(outboxService, never()).markDone(any());
    }

    @Test
    @DisplayName("正常路径：CAS 抢占 → handler 执行 → markDone")
    void testHappyPath() throws Exception {
        OutboxEvent e = event(OutboxEvent.STATUS_PENDING);
        when(outboxEventMapper.update(any(), any())).thenReturn(1); // CAS 命中

        dispatcher.safeDispatchOne(e);

        verify(handler).execute(e.getPayload());
        verify(outboxService).markDone(1L);
        assertEquals(1L, dispatcher.metricsSnapshot().get("doneTotal"));
    }

    @Test
    @DisplayName("handler 抛异常 → markFailed（进入重试/死信流转）")
    void testHandlerFailureGoesToRetry() throws Exception {
        OutboxEvent e = event(OutboxEvent.STATUS_PENDING);
        when(outboxEventMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("feign down"))
                .when(handler).execute(any());

        dispatcher.safeDispatchOne(e);

        verify(outboxService).markFailed(e, "feign down");
        verify(outboxService, never()).markDone(any());
        assertEquals(1L, dispatcher.metricsSnapshot().get("failTotal"));
    }

    @Test
    @DisplayName("handler 抛 DeadSignal → 直接置 DEAD 不再重试")
    void testDeadSignalGoesStraightToDead() throws Exception {
        OutboxEvent e = event(OutboxEvent.STATUS_PENDING);
        when(outboxEventMapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new OutboxDispatcher.DeadSignal("payload 坏了"))
                .when(handler).execute(any());

        dispatcher.safeDispatchOne(e);

        verify(outboxService, never()).markDone(any());
        verify(outboxService, never()).markFailed(any(), any());
        // update 共 2 次：1=CAS 抢占 PROCESSING，2=markDead 置 DEAD（不经 markFailed 的重试计数）
        verify(outboxEventMapper, org.mockito.Mockito.times(2)).update(any(), any());
        assertEquals(1L, dispatcher.metricsSnapshot().get("deadTotal"));
    }

    @Test
    @DisplayName("无 handler（eventType 未注册）→ markFailed 带原因，走重试/死信")
    void testUnknownEventTypeFails() {
        OutboxEvent e = event(OutboxEvent.STATUS_PENDING);
        e.setEventType("UNKNOWN_TYPE");
        when(outboxEventMapper.update(any(), any())).thenReturn(1);

        dispatcher.safeDispatchOne(e);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outboxService).markFailed(any(OutboxEvent.class), reason.capture());
        assertTrue(reason.getValue().contains("No handler"));
    }

    @Test
    @DisplayName("重复 eventType 注册 → 构造期快速失败（防配置错误静默路由错乱）")
    void testDuplicateEventTypeRejected() {
        OutboxHandler another = new OutboxHandler() {
            @Override public String eventType() { return "PAY_REWARD"; }
            @Override public void execute(String payload) { }
        };

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new OutboxDispatcher(outboxEventMapper, outboxService, List.of(handler, another)));
    }

    @Test
    @DisplayName("dispatch 空批次不抛异常（@Scheduled 心跳安全）")
    void testDispatchEmptyBatchSafe() throws Exception {
        when(outboxEventMapper.selectList(any())).thenReturn(List.of());

        dispatcher.dispatch(); // 不应抛异常

        verify(handler, never()).execute(any());
    }
}
