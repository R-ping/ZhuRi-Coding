package com.zhuri.coding.content.service.outbox;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxDispatcher 失败终态策略（{@link FailPolicy}）单测 —— 覆盖 {@code safeDispatchOne} 全流程。
 *
 * <p>覆盖六条分支：三种策略各自的收尾方式、未耗尽仍走常规重试、DeadSignal 优先于策略、
 * 以及 onExhausted 回调抛异常时不退化成无限重试。
 *
 * <p><b>关于 MybatisPlus lambda 缓存</b>：CAS 抢占与退避更新都用 {@code LambdaUpdateWrapper}，
 * 它需要实体类的 lambda 列缓存。集成测试在共享 JVM 中先加载 Spring 时会自动注册，
 * 但单测须自足 —— 故在 {@code setUp} 中显式 {@code TableInfoHelper.initTableInfo} 预热，
 * 保证 CI 无库也能稳定运行（写法与 {@code PinsReviewServiceTest} 一致）。
 */
class OutboxDispatcherFailPolicyTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private OutboxService outboxService;

    /** 可配置策略与抛错行为的测试 Handler */
    static class TestHandler implements OutboxHandler {

        private final FailPolicy policy;
        boolean exhaustedCalled;
        boolean payloadPresent;
        String lastError;
        RuntimeException executeThrow = new IllegalStateException("boom");
        RuntimeException exhaustedThrow;

        TestHandler(FailPolicy policy) {
            this.policy = policy;
        }

        @Override
        public String eventType() {
            return "TEST_EVENT";
        }

        @Override
        public FailPolicy failPolicy() {
            return policy;
        }

        @Override
        public void execute(String payload) {
            throw executeThrow;
        }

        @Override
        public void onExhausted(String payload, String lastError) {
            exhaustedCalled = true;
            payloadPresent = payload != null;
            this.lastError = lastError;
            if (exhaustedThrow != null) {
                throw exhaustedThrow;
            }
        }
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 预热 lambda 列缓存：否则 LambdaUpdateWrapper 会抛 can not find lambda cache for this entity
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OutboxEvent.class);
    }

    private OutboxEvent pendingEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventKey("TEST:1");
        event.setEventType("TEST_EVENT");
        event.setPayload("{}");
        event.setStatus(OutboxEvent.STATUS_PENDING);
        event.setRetryCount(4);
        event.setMaxRetries(5);
        return event;
    }

    /** 构造 dispatcher；CAS 抢占成功（update 返回 1）才继续执行 */
    private OutboxDispatcher dispatcherWith(OutboxHandler handler, boolean casAcquired) {
        when(outboxEventMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenReturn(casAcquired ? 1 : 0);
        return new OutboxDispatcher(outboxEventMapper, outboxService, List.of(handler));
    }

    @Test
    @DisplayName("DEGRADE：超限时先回调 onExhausted 执行业务降级，再降级置 DONE")
    void degradePolicyCallsOnExhaustedThenDone() {
        TestHandler handler = new TestHandler(FailPolicy.DEGRADE);
        when(outboxService.willExhaust(any())).thenReturn(true);

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertTrue(handler.exhaustedCalled, "应回调 onExhausted 执行业务降级");
        assertTrue(handler.payloadPresent, "回调应带上事件载荷");
        assertEquals("boom", handler.lastError, "回调应带上最后一次失败原因");
        verify(outboxService).markExhaustedDone(any(), eq("boom"));
        verify(outboxService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("DISCARD：超限时直接降级收尾，不回调 onExhausted")
    void discardPolicySkipsCallback() {
        TestHandler handler = new TestHandler(FailPolicy.DISCARD);
        when(outboxService.willExhaust(any())).thenReturn(true);

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertFalse(handler.exhaustedCalled, "DISCARD 不需要业务降级动作");
        assertNull(handler.lastError);
        verify(outboxService).markExhaustedDone(any(), anyString());
        verify(outboxService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("DEAD（默认策略）：超限时仍走 markFailed 置死信")
    void deadPolicyUsesMarkFailed() {
        TestHandler handler = new TestHandler(FailPolicy.DEAD);
        when(outboxService.willExhaust(any())).thenReturn(true);

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertFalse(handler.exhaustedCalled);
        verify(outboxService).markFailed(any(), anyString());
        verify(outboxService, never()).markExhaustedDone(any(), anyString());
    }

    @Test
    @DisplayName("未耗尽：走常规重试，不触发终态策略")
    void notExhaustedFallsBackToRetry() {
        TestHandler handler = new TestHandler(FailPolicy.DEGRADE);
        when(outboxService.willExhaust(any())).thenReturn(false);

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertFalse(handler.exhaustedCalled, "未耗尽不应触发降级");
        assertNull(handler.lastError);
        verify(outboxService).markFailed(any(), anyString());
        verify(outboxService, never()).markExhaustedDone(any(), anyString());
    }

    @Test
    @DisplayName("DeadSignal 优先于策略：handler 主动判死时不再走降级")
    void deadSignalTakesPrecedenceOverPolicy() {
        TestHandler handler = new TestHandler(FailPolicy.DEGRADE);
        handler.executeThrow = new OutboxDispatcher.DeadSignal("业务上永久失败");

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertFalse(handler.exhaustedCalled, "主动判死不应再触发降级回调");
        verify(outboxService, never()).markExhaustedDone(any(), anyString());
        verify(outboxService, never()).markFailed(any(), anyString());
    }

    @Test
    @DisplayName("CAS 未抢占成功：直接跳过，不执行 handler 也不写终态")
    void casNotAcquiredSkipsEverything() {
        TestHandler handler = new TestHandler(FailPolicy.DEGRADE);

        dispatcherWith(handler, false).safeDispatchOne(pendingEvent());

        assertFalse(handler.exhaustedCalled);
        verify(outboxService, never()).markFailed(any(), anyString());
        verify(outboxService, never()).markExhaustedDone(any(), anyString());
    }

    @Test
    @DisplayName("onExhausted 自身抛异常：仍按 DEGRADE 收尾，不退化成无限重试")
    void onExhaustedThrowingStillDegrades() {
        TestHandler handler = new TestHandler(FailPolicy.DEGRADE);
        handler.exhaustedThrow = new IllegalStateException("callback bug");
        when(outboxService.willExhaust(any())).thenReturn(true);

        dispatcherWith(handler, true).safeDispatchOne(pendingEvent());

        assertTrue(handler.exhaustedCalled);
        verify(outboxService).markExhaustedDone(any(), anyString());
        verify(outboxService, never()).markFailed(any(), anyString());
    }
}
