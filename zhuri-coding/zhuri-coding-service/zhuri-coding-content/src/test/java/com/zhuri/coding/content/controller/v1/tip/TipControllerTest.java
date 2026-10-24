package com.zhuri.coding.content.controller.v1.tip;

import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.tip.TipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TipController 异步通知安全校验测试。
 *
 * <p>验签之外还必须校验 app_id / seller_id，防止其他支付宝应用或商户的回调被误当作本应用订单。</p>
 */
class TipControllerTest {

    @Mock
    private TipService tipService;

    @Mock
    private AlipayService alipayService;

    @InjectMocks
    private TipController tipController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private MockHttpServletRequest notifyRequest(String appId, String sellerId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("app_id", appId);
        request.setParameter("seller_id", sellerId);
        request.setParameter("trade_no", "T1");
        request.setParameter("out_trade_no", "TIP1");
        request.setParameter("total_amount", "5.00");
        request.setParameter("trade_status", "TRADE_SUCCESS");
        return request;
    }

    @Test
    @DisplayName("验签失败：直接返回 fail，不进入业务处理")
    void rejectsInvalidSignature() {
        when(alipayService.verifySign(any())).thenReturn(false);

        assertEquals("fail", tipController.payNotify(notifyRequest("app", "seller")));
        verify(tipService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("app_id 不匹配：返回 fail")
    void rejectsAppIdMismatch() {
        when(alipayService.verifySign(any())).thenReturn(true);
        when(alipayService.getAppId()).thenReturn("expected-app");

        assertEquals("fail", tipController.payNotify(notifyRequest("other-app", "seller")));
        verify(tipService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("配置了 seller_id 但不匹配：返回 fail")
    void rejectsSellerIdMismatch() {
        when(alipayService.verifySign(any())).thenReturn(true);
        when(alipayService.getAppId()).thenReturn("app");
        when(alipayService.getSellerId()).thenReturn("expected-seller");

        assertEquals("fail", tipController.payNotify(notifyRequest("app", "other-seller")));
        verify(tipService, never()).handleNotify(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("身份校验通过：进入打赏入账并返回 success")
    void acceptsValidNotify() {
        when(alipayService.verifySign(any())).thenReturn(true);
        when(alipayService.getAppId()).thenReturn("app");
        when(alipayService.getSellerId()).thenReturn("seller");
        when(tipService.handleNotify("T1", "TIP1", "5.00", "TRADE_SUCCESS")).thenReturn(true);

        assertEquals("success", tipController.payNotify(notifyRequest("app", "seller")));
        verify(tipService).handleNotify("T1", "TIP1", "5.00", "TRADE_SUCCESS");
    }
}
