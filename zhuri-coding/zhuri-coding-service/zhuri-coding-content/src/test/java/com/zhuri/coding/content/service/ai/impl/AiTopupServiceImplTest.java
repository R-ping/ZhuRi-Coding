package com.zhuri.coding.content.service.ai.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhuri.coding.content.mapper.ai.AiTopupOrderMapper;
import com.zhuri.coding.content.service.ai.AiWalletService;
import com.zhuri.coding.model.ai.pojos.AiTopupOrder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiTopupServiceImpl 支付回调单元测试。
 *
 * <p>重点覆盖资金安全红线：只有 TRADE_SUCCESS / TRADE_FINISHED 才允许入账，
 * WAIT_BUYER_PAY、TRADE_CLOSED 等非成功状态绝不能发放 token（否则未付款也能"免费充值"）。</p>
 */
class AiTopupServiceImplTest {

    private static final String ORDER_NO = "ai1001";
    private static final int USER_ID = 7;
    private static final long TOKENS = 10000L;
    private static final String AMOUNT = "9.90";

    @Mock
    private AiTopupOrderMapper topupOrderMapper;

    @Mock
    private AiWalletService walletService;

    @InjectMocks
    private AiTopupServiceImpl aiTopupService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // LambdaUpdateWrapper 解析列名依赖 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTopupOrder.class);
    }

    private AiTopupOrder pendingOrder() {
        AiTopupOrder order = new AiTopupOrder();
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setAmountFen(990);
        order.setTokenAdded(TOKENS);
        order.setStatus(AiTopupOrder.STATUS_PENDING);
        return order;
    }

    @Test
    @DisplayName("TRADE_SUCCESS：置 PAID 并发放 token")
    void tradeSuccessGrantsTokens() {
        AiTopupOrder order = pendingOrder();
        when(topupOrderMapper.selectOne(any())).thenReturn(order);

        assertTrue(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "TRADE_SUCCESS"));
        assertEquals(AiTopupOrder.STATUS_PAID, order.getStatus());
        verify(topupOrderMapper).updateById(order);
        verify(walletService).grantTokens(USER_ID, TOKENS);
    }

    @Test
    @DisplayName("TRADE_FINISHED：按官方规范同样视为支付成功并发放 token")
    void tradeFinishedGrantsTokens() {
        AiTopupOrder order = pendingOrder();
        when(topupOrderMapper.selectOne(any())).thenReturn(order);

        assertTrue(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "TRADE_FINISHED"));
        assertEquals(AiTopupOrder.STATUS_PAID, order.getStatus());
        verify(walletService).grantTokens(USER_ID, TOKENS);
    }

    @Test
    @DisplayName("TRADE_CLOSED（未付款超时关单）：拒绝入账，不发放 token")
    void tradeClosedRejected() {
        when(topupOrderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertFalse(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "TRADE_CLOSED"));
        verify(topupOrderMapper, never()).updateById(any(AiTopupOrder.class));
        verify(walletService, never()).grantTokens(anyInt(), anyLong());
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY（等待付款）：拒绝入账，不发放 token")
    void waitBuyerPayRejected() {
        when(topupOrderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertFalse(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "WAIT_BUYER_PAY"));
        verify(topupOrderMapper, never()).updateById(any(AiTopupOrder.class));
        verify(walletService, never()).grantTokens(anyInt(), anyLong());
    }

    @Test
    @DisplayName("已 PAID 订单重复通知：幂等成功且不重复发放")
    void paidOrderIdempotent() {
        AiTopupOrder order = pendingOrder();
        order.setStatus(AiTopupOrder.STATUS_PAID);
        when(topupOrderMapper.selectOne(any())).thenReturn(order);

        assertTrue(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "TRADE_SUCCESS"));
        verify(topupOrderMapper, never()).updateById(any(AiTopupOrder.class));
        verify(walletService, never()).grantTokens(anyInt(), anyLong());
    }

    @Test
    @DisplayName("回调金额与订单金额不一致：拒绝入账")
    void amountMismatchRejected() {
        when(topupOrderMapper.selectOne(any())).thenReturn(pendingOrder());

        assertFalse(aiTopupService.handleNotify(ORDER_NO, "0.01", "T1", "TRADE_SUCCESS"));
        verify(walletService, never()).grantTokens(anyInt(), anyLong());
    }

    @Test
    @DisplayName("订单不存在：拒绝入账")
    void orderNotFoundRejected() {
        when(topupOrderMapper.selectOne(any())).thenReturn(null);

        assertFalse(aiTopupService.handleNotify(ORDER_NO, AMOUNT, "T1", "TRADE_SUCCESS"));
        verify(walletService, never()).grantTokens(anyInt(), anyLong());
    }

    @Test
    @DisplayName("markClosed 条件更新 PENDING→CLOSED，命中返回 true")
    void markClosedIdempotent() {
        when(topupOrderMapper.update(any(), any())).thenReturn(1);
        assertTrue(aiTopupService.markClosed(ORDER_NO));

        when(topupOrderMapper.update(any(), any())).thenReturn(0);
        assertFalse(aiTopupService.markClosed(ORDER_NO));
    }

    @Test
    @DisplayName("listExpiredPendingOrders 返回待支付超时订单")
    void listExpiredPendingOrders() {
        when(topupOrderMapper.selectList(any())).thenReturn(List.of(pendingOrder()));

        assertEquals(1, aiTopupService.listExpiredPendingOrders(10, new Date()).size());
    }
}
