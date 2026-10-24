package com.zhuri.coding.content.schedule.task;

import com.zhuri.coding.content.service.ai.AiTopupService;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.pay.TradeQueryResult;
import com.zhuri.coding.content.service.tip.TipService;
import com.zhuri.coding.model.ai.pojos.AiTopupOrder;
import com.zhuri.coding.model.article.pojos.ApArticleTipOrder;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PaymentReconcileTask 单元测试。
 *
 * <p>对账的核心安全语义：查询不可用时必须跳过（不得推断支付结果）；
 * 已支付必须补入账；未支付必须先关支付宝交易再关本地单。</p>
 */
class PaymentReconcileTaskTest {

    private static final String ORDER_NO = "O1";

    @Mock
    private OrderService orderService;

    @Mock
    private AlipayService alipayService;

    @Mock
    private AiTopupService aiTopupService;

    @Mock
    private TipService tipService;

    @InjectMocks
    private PaymentReconcileTask task;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(task, "batchSize", 50);
        ReflectionTestUtils.setField(task, "cancelledLookbackMinutes", 120L);
        ReflectionTestUtils.setField(task, "payTimeoutMs", 300000L);
        ReflectionTestUtils.setField(task, "orderTimeoutMs", 1800000L);
        when(alipayService.isConfigured()).thenReturn(true);
    }

    private ApCourseOrder courseOrder(String orderNo, ApCourseOrder.Status status) {
        ApCourseOrder order = new ApCourseOrder();
        order.setOrderNo(orderNo);
        order.setStatus(status.getCode());
        order.setPaidAmount(new BigDecimal("100.00"));
        return order;
    }

    private AiTopupOrder aiOrder(String orderNo) {
        AiTopupOrder order = new AiTopupOrder();
        order.setOrderNo(orderNo);
        order.setUserId(7);
        order.setAmountFen(990);
        order.setTokenAdded(10000L);
        order.setStatus(AiTopupOrder.STATUS_PENDING);
        order.setCreateTime(new Date());
        return order;
    }

    private ApArticleTipOrder tipOrder(String orderNo) {
        ApArticleTipOrder order = new ApArticleTipOrder();
        order.setOrderNo(orderNo);
        order.setUserId(7);
        order.setArticleId(500L);
        order.setAuthorId(9);
        order.setAmount(new BigDecimal("5.00"));
        order.setStatus(ApArticleTipOrder.Status.PENDING.getCode());
        order.setCreatedTime(new Date());
        return order;
    }

    private TradeQueryResult paid(String amount) {
        return TradeQueryResult.of("TRADE_SUCCESS", "T1", amount);
    }

    @Test
    @DisplayName("超时未关单但支付宝已支付：补触发 handleNotify 入账")
    void pendingPaidOrderTriggersHandleNotify() {
        when(orderService.listReconcilePendingOrders(anyInt(), any()))
                .thenReturn(List.of(courseOrder(ORDER_NO, ApCourseOrder.Status.PROCESSING)));
        when(alipayService.queryTrade(ORDER_NO)).thenReturn(paid("100.00"));

        task.reconcile();

        verify(alipayService).handleNotify("T1", ORDER_NO, "100.00", "TRADE_SUCCESS");
        verify(orderService, never()).closeExpiredOrder(anyString());
    }

    @Test
    @DisplayName("超时未关单且支付宝仍待支付：先关支付宝交易，再关本地单")
    void pendingWaitingPayClosesTradeThenLocal() {
        when(orderService.listReconcilePendingOrders(anyInt(), any()))
                .thenReturn(List.of(courseOrder(ORDER_NO, ApCourseOrder.Status.PROCESSING)));
        when(alipayService.queryTrade(ORDER_NO))
                .thenReturn(TradeQueryResult.of("WAIT_BUYER_PAY", null, null));
        when(alipayService.closeTrade(ORDER_NO)).thenReturn(true);
        when(orderService.closeExpiredOrder(ORDER_NO)).thenReturn(false);
        when(orderService.closePayChannel(ORDER_NO)).thenReturn(true);

        task.reconcile();

        verify(alipayService).closeTrade(ORDER_NO);
        verify(orderService).closePayChannel(ORDER_NO);
        verify(alipayService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("超时未关单且支付宝已关闭/不存在：直接关本地单")
    void pendingClosedTradeClosesLocal() {
        when(orderService.listReconcilePendingOrders(anyInt(), any()))
                .thenReturn(List.of(courseOrder(ORDER_NO, ApCourseOrder.Status.PENDING)));
        when(alipayService.queryTrade(ORDER_NO))
                .thenReturn(TradeQueryResult.of("TRADE_CLOSED", null, null));
        when(orderService.closeExpiredOrder(ORDER_NO)).thenReturn(true);

        task.reconcile();

        verify(orderService).closeExpiredOrder(ORDER_NO);
        verify(alipayService, never()).closeTrade(anyString());
        verify(alipayService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("查询不可用：跳过该订单，不得推断为未支付")
    void queryUnavailableSkipsOrder() {
        when(orderService.listReconcilePendingOrders(anyInt(), any()))
                .thenReturn(List.of(courseOrder(ORDER_NO, ApCourseOrder.Status.PENDING)));
        when(alipayService.queryTrade(ORDER_NO)).thenReturn(TradeQueryResult.unknown());

        task.reconcile();

        verify(orderService, never()).closeExpiredOrder(anyString());
        verify(orderService, never()).closePayChannel(anyString());
        verify(alipayService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("本地已关单但支付宝已支付：补触发 handleNotify（内部走退款兜底）")
    void cancelledPaidOrderTriggersRefundPath() {
        String cancelledNo = "O2";
        when(orderService.listCancelledForReconcile(anyInt(), any()))
                .thenReturn(List.of(courseOrder(cancelledNo, ApCourseOrder.Status.CANCELLED)));
        when(alipayService.queryTrade(cancelledNo)).thenReturn(paid("100.00"));

        task.reconcile();

        verify(alipayService).handleNotify("T1", cancelledNo, "100.00", "TRADE_SUCCESS");
    }

    @Test
    @DisplayName("AI 额度包超时但支付宝已支付：补入账")
    void aiTopupPaidOrderGrantsTokens() {
        String aiNo = "ai1001";
        when(aiTopupService.listExpiredPendingOrders(anyInt(), any())).thenReturn(List.of(aiOrder(aiNo)));
        when(alipayService.queryTrade(aiNo)).thenReturn(paid("9.90"));

        task.reconcile();

        verify(aiTopupService).handleNotify(aiNo, "9.90", "T1", "TRADE_SUCCESS");
    }

    @Test
    @DisplayName("AI 额度包超时仍待支付：关闭支付宝交易并置 CLOSED")
    void aiTopupWaitingPayClosed() {
        String aiNo = "ai1001";
        when(aiTopupService.listExpiredPendingOrders(anyInt(), any())).thenReturn(List.of(aiOrder(aiNo)));
        when(alipayService.queryTrade(aiNo))
                .thenReturn(TradeQueryResult.of("WAIT_BUYER_PAY", null, null));
        when(alipayService.closeTrade(aiNo)).thenReturn(true);

        task.reconcile();

        verify(aiTopupService).markClosed(aiNo);
        verify(aiTopupService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("打赏订单超时但支付宝已支付：补入账")
    void tipOrderPaidTriggersNotify() {
        String tipNo = "T1001";
        when(tipService.listExpiredPendingOrders(anyInt(), any())).thenReturn(List.of(tipOrder(tipNo)));
        when(alipayService.queryTrade(tipNo)).thenReturn(paid("5.00"));

        task.reconcile();

        verify(tipService).handleNotify("T1", tipNo, "5.00", "TRADE_SUCCESS");
    }

    @Test
    @DisplayName("打赏订单超时仍待支付：关闭支付宝交易并置 CANCELLED")
    void tipOrderWaitingPayClosed() {
        String tipNo = "T1001";
        when(tipService.listExpiredPendingOrders(anyInt(), any())).thenReturn(List.of(tipOrder(tipNo)));
        when(alipayService.queryTrade(tipNo))
                .thenReturn(TradeQueryResult.of("WAIT_BUYER_PAY", null, null));
        when(alipayService.closeTrade(tipNo)).thenReturn(true);

        task.reconcile();

        verify(tipService).markClosed(tipNo);
        verify(tipService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("未配置支付宝凭据：整轮跳过")
    void notConfiguredSkips() {
        when(alipayService.isConfigured()).thenReturn(false);

        task.reconcile();

        verifyNoInteractions(orderService, aiTopupService, tipService);
    }

    @Test
    @DisplayName("单条链路异常不影响其他链路：课程查询失败后 AI 对账仍执行")
    void failureInOneReconcilerDoesNotStopOthers() {
        String aiNo = "ai1001";
        when(orderService.listReconcilePendingOrders(anyInt(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(aiTopupService.listExpiredPendingOrders(anyInt(), any())).thenReturn(List.of(aiOrder(aiNo)));
        when(alipayService.queryTrade(aiNo)).thenReturn(paid("9.90"));

        task.reconcile();

        verify(aiTopupService).handleNotify(aiNo, "9.90", "T1", "TRADE_SUCCESS");
    }
}
