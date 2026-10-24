package com.zhuri.coding.content.service.pay.impl;

import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.TradeQueryResult;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
    @DisplayName("TRADE_FINISHED 也视为支付成功（官方规范）")
    void handleNotifyAcceptsTradeFinished() {
        when(orderService.getByOrderNo("O1")).thenReturn(closedOrder());
        when(orderService.handlePaySuccess("O1", "T1")).thenReturn(true);

        assertTrue(alipayService.handleNotify("T1", "O1", "100.00", "TRADE_FINISHED"));
    }

    @Test
    @DisplayName("未配置凭据时退款返回本地模拟流水号（供本地/沙箱联调）")
    void refundMockSuccess() {
        assertEquals("MOCK_O1", alipayService.refund("O1", "100.00", "T1"));
    }

    // ---------- 退款成功判定：fund_change / 退款查询 ----------

    @Test
    @DisplayName("退款 fund_change=Y：确认资金退回并返回退款请求号")
    void refundSuccessWhenFundChangeY() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeRefundResponse response = new AlipayTradeRefundResponse();
        response.setCode("10000");
        response.setFundChange("Y");
        response.setTradeNo("T1");
        doReturn(response).when(client).execute(any(AlipayTradeRefundRequest.class));

        assertEquals("O1", alipayService.refund("O1", "100.00", "T1"));
    }

    @Test
    @DisplayName("退款 fund_change=N 但退款查询 REFUND_SUCCESS：视为成功（重复退款场景）")
    void refundSuccessWhenFundChangeNButQuerySuccess() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        refundResponse.setCode("10000");
        refundResponse.setFundChange("N");
        doReturn(refundResponse).when(client).execute(any(AlipayTradeRefundRequest.class));

        AlipayTradeFastpayRefundQueryResponse queryResponse = new AlipayTradeFastpayRefundQueryResponse();
        queryResponse.setCode("10000");
        queryResponse.setRefundStatus("REFUND_SUCCESS");
        doReturn(queryResponse).when(client).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        assertEquals("O1", alipayService.refund("O1", "100.00", "T1"));
    }

    @Test
    @DisplayName("退款 fund_change=N 且退款查询未成功：返回 null，保留待重试")
    void refundPendingWhenFundChangeNAndQueryNotSuccess() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeRefundResponse refundResponse = new AlipayTradeRefundResponse();
        refundResponse.setCode("10000");
        refundResponse.setFundChange("N");
        doReturn(refundResponse).when(client).execute(any(AlipayTradeRefundRequest.class));

        AlipayTradeFastpayRefundQueryResponse queryResponse = new AlipayTradeFastpayRefundQueryResponse();
        queryResponse.setCode("10000");
        queryResponse.setRefundStatus("REFUND_PROCESSING");
        doReturn(queryResponse).when(client).execute(any(AlipayTradeFastpayRefundQueryRequest.class));

        assertNull(alipayService.refund("O1", "100.00", "T1"));
    }

    @Test
    @DisplayName("退款接口返回失败码：返回 null，不误判成功")
    void refundFailureWhenApiError() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeRefundResponse response = new AlipayTradeRefundResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.TRADE_NOT_EXIST");
        doReturn(response).when(client).execute(any(AlipayTradeRefundRequest.class));

        assertNull(alipayService.refund("O1", "100.00", "T1"));
    }

    // ---------- 交易查询 / 关闭 ----------

    @Test
    @DisplayName("交易查询 TRADE_SUCCESS：paid=true 并返回交易号与金额")
    void queryTradeReturnsPaidResult() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("10000");
        response.setTradeStatus("TRADE_SUCCESS");
        response.setTradeNo("T1");
        response.setTotalAmount("100.00");
        doReturn(response).when(client).execute(any(AlipayTradeQueryRequest.class));

        TradeQueryResult result = alipayService.queryTrade("O1");

        assertTrue(result.available());
        assertTrue(result.paid());
        assertEquals("T1", result.tradeNo());
        assertEquals("100.00", result.totalAmount());
    }

    @Test
    @DisplayName("交易查询 TRADE_NOT_EXIST：available=true、paid=false")
    void queryTradeNotExist() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.TRADE_NOT_EXIST");
        doReturn(response).when(client).execute(any(AlipayTradeQueryRequest.class));

        TradeQueryResult result = alipayService.queryTrade("O1");

        assertTrue(result.available());
        assertFalse(result.paid());
        assertEquals(TradeQueryResult.STATUS_NOT_EXIST, result.tradeStatus());
    }

    @Test
    @DisplayName("交易查询遇到未知错误码：available=false，调用方必须跳过")
    void queryTradeUnknownOnUnexpectedError() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse();
        response.setCode("20000");
        response.setSubCode("isp.unknow-error");
        doReturn(response).when(client).execute(any(AlipayTradeQueryRequest.class));

        assertFalse(alipayService.queryTrade("O1").available());
    }

    @Test
    @DisplayName("关闭交易成功 / 交易不存在 均返回 true")
    void closeTradeSuccessAndNotExist() throws Exception {
        AlipayClient client = enableRealClient();

        AlipayTradeCloseResponse success = new AlipayTradeCloseResponse();
        success.setCode("10000");
        doReturn(success).when(client).execute(any(AlipayTradeCloseRequest.class));
        assertTrue(alipayService.closeTrade("O1"));

        AlipayTradeCloseResponse notExist = new AlipayTradeCloseResponse();
        notExist.setCode("40004");
        notExist.setSubCode("ACQ.TRADE_NOT_EXIST");
        doReturn(notExist).when(client).execute(any(AlipayTradeCloseRequest.class));
        assertTrue(alipayService.closeTrade("O2"));
    }

    @Test
    @DisplayName("关闭交易失败（如交易已支付）：返回 false，交由查询/通知链路处理")
    void closeTradeFailsOnError() throws Exception {
        AlipayClient client = enableRealClient();
        AlipayTradeCloseResponse response = new AlipayTradeCloseResponse();
        response.setCode("40004");
        response.setSubCode("ACQ.TRADE_STATUS_ERROR");
        doReturn(response).when(client).execute(any(AlipayTradeCloseRequest.class));

        assertFalse(alipayService.closeTrade("O1"));
    }

    /** 配置真实凭据并让 buildClient 返回 mock 客户端，便于验证 SDK 响应处理 */
    private AlipayClient enableRealClient() {
        ReflectionTestUtils.setField(alipayService, "appId", "test-app");
        ReflectionTestUtils.setField(alipayService, "privateKey", "test-private-key");
        ReflectionTestUtils.setField(alipayService, "alipayPublicKey", "test-public-key");
        ReflectionTestUtils.setField(alipayService, "gatewayUrl", "https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        AlipayClient client = mock(AlipayClient.class);
        doReturn(client).when(alipayService).buildClient();
        return client;
    }
}