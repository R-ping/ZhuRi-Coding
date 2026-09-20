package com.zhuri.coding.content.service.outbox.handler;

import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.payment.PaymentRewardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PayRewardOutboxHandler 单元测试。
 *
 * <p>重点验证：正常 payload → 调 PaymentRewardService；payload 坏 / 缺字段 → DeadSignal
 * （判死信直接进 DEAD，不进入无意义的重试循环）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PayRewardOutboxHandler 支付联动事件")
class PayRewardOutboxHandlerTest {

    @Mock
    private PaymentRewardService paymentRewardService;

    private PayRewardOutboxHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PayRewardOutboxHandler();
        ReflectionTestUtils.setField(handler, "paymentRewardService", paymentRewardService);
    }

    @Test
    @DisplayName("eventType 路由键 = PAY_REWARD")
    void testEventType() {
        org.junit.jupiter.api.Assertions.assertEquals("PAY_REWARD", handler.eventType());
    }

    @Test
    @DisplayName("正常 payload → 反序列化并调用 PaymentRewardService")
    void testExecuteHappyPath() throws Exception {
        String payload = "{\"userId\":7,\"courseId\":10,\"paidAmount\":99.50,\"orderNo\":\"NO123\"}";

        handler.execute(payload);

        verify(paymentRewardService).onCoursePurchaseSuccess(7L, 10L, new BigDecimal("99.50"), "NO123");
    }

    @Test
    @DisplayName("payload JSON 损坏 → DeadSignal（直接死信，不重试）")
    void testCorruptedPayloadDead() {
        assertThrows(OutboxDispatcher.DeadSignal.class, () -> handler.execute("{not json"));
        verify(paymentRewardService, never()).onCoursePurchaseSuccess(any(), any(), any(), any());
    }

    @Test
    @DisplayName("payload 缺关键字段 → DeadSignal")
    void testMissingFieldsDead() {
        assertThrows(OutboxDispatcher.DeadSignal.class,
                () -> handler.execute("{\"userId\":null,\"courseId\":10,\"orderNo\":\"NO1\"}"));
        verify(paymentRewardService, never()).onCoursePurchaseSuccess(any(), any(), any(), any());
    }
}
