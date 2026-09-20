package com.heima.content.service.pay.impl;

import com.heima.content.service.order.OrderService;
import com.heima.model.course.pojos.ApCourseOrder;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlipayServiceImpl 退款兜底逻辑单元测试。
 * <p>未配置支付宝凭据（@Value 为 null）时 {@code isCredentialReady()} 返回 false，
 * 退款走本地模拟成功分支，便于纯 Mock 验证「支付成功但订单已关闭 → 自动退款」的编排逻辑；
 * 另用 Spy 覆写 refund 返回 null 验证「退款失败 → 标记待重试」分支。</p>
 */
class AlipayServiceImplTest {

    @Mock
    private OrderService orderService;

    @Spy
    @InjectMocks
    private AlipayServiceImpl alipayService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private ApCourseOrder closedOrder() {
        ApCourseOrder o = new ApCourseOrder();
        o.setOrderNo("O1");
        o.setStatus(ApCourseOrder.Status.CANCELLED.getCode());
        o.setPaidAmount(new BigDecimal("100.00"));
        return o;
    }

    @Test
    @DisplayName("支付成功但订单已关闭：自动退款并置为已退款")
    void handleNotifyRefundsClosedOrder() {
        when(orderService.handlePaySuccess("O1", "T1")).thenReturn(false);
        when(orderService.getByOrderNo("O1")).thenReturn(closedOrder());

        assertTrue(alipayService.handleNotify("T1", "O1", "100.00", "TRADE_SUCCESS"));
        // 未配置凭据 → 模拟退款流水号 MOCK_O1，标记订单已退款
        verify(orderService).markRefunded("O1", "MOCK_O1");
    }

    @Test
    @DisplayName("支付成功但订单已关闭且首次退款失败：标记待重试退款")
    void handleNotifyMarksRefundPendingOnFailure() throws Exception {
        when(orderService.handlePaySuccess("O1", "T1")).thenReturn(false);
        when(orderService.getByOrderNo("O1")).thenReturn(closedOrder());
        doReturn(null).when(alipayService).refund("O1", "100.00", "T1"); // 模拟退款失败

        assertTrue(alipayService.handleNotify("T1", "O1", "100.00", "TRADE_SUCCESS"));
        verify(orderService).markRefundPending("O1");
        verify(orderService, never()).markRefunded(anyString(), anyString());
    }

    @Test
    @DisplayName("支付已真正处理完成（如重复通知/已 PAID）不触发退款")
    void handleNotifyNoRefundWhenApplied() {
        when(orderService.getByOrderNo("O1")).thenReturn(closedOrder());
        when(orderService.handlePaySuccess("O1", "T1")).thenReturn(true);

        assertTrue(alipayService.handleNotify("T1", "O1", "100.00", "TRADE_SUCCESS"));
        verify(orderService, never()).markRefunded(anyString(), anyString());
        verify(orderService, never()).markRefundPending(anyString());
    }

    @Test
    @DisplayName("订单金额不符：拒绝回调，不进入退款")
    void handleNotifyNoRefundWhenAmountMismatch() {
        when(orderService.getByOrderNo("O1")).thenReturn(closedOrder());
        assertFalse(alipayService.handleNotify("T1", "O1", "1.00", "TRADE_SUCCESS"));
        verify(orderService, never()).markRefunded(anyString(), anyString());
        verify(orderService, never()).markRefundPending(anyString());
    }

    @Test
    @DisplayName("非成功状态回调直接拒绝")
    void handleNotifyRejectsNonSuccess() {
        assertFalse(alipayService.handleNotify("T1", "O1", "100.00", "WAIT_BUYER_PAY"));
    }

    @Test
    @DisplayName("未配置凭据时退款返回本地模拟流水号（供本地/沙箱联调）")
    void refundMockSuccess() {
        assertEquals("MOCK_O1", alipayService.refund("O1", "100.00", "T1"));
    }
}