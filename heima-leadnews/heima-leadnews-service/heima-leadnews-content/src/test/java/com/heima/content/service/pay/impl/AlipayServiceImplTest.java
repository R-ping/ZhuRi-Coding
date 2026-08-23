package com.heima.content.service.pay.impl;

import com.heima.content.service.order.OrderService;
import com.heima.model.course.pojos.ApCourseOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlipayServiceImpl 单元测试（支付宝支付页生成与支付回调校验）
 *
 * @Service 大量逻辑基于 @Value 注入的凭据与真实支付宝 SDK，本测试通过反射注入字段，
 * 聚焦可确定性验证的控制流：
 * 1. 有凭据/无凭据/生成异常时支付页的生成分支（回退本地模拟页）；
 * 2. handleNotify 支付状态过滤、金额一致性校验（防篡改）、订单存在性、成功流转；
 * 3. verifySign 公钥缺失 fail-closed；
 * 4. verifyAmount 金额缺失 / 非法数字 / 不一致等拒绝分支。
 */
class AlipayServiceImplTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private AlipayServiceImpl alipayService;

    private final String orderNo = "20270101120000123456";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 注入 notifier 等各分支默认字段；具体用例内按需改写
        ReflectionTestUtils.setField(alipayService, "appId", "");
        ReflectionTestUtils.setField(alipayService, "gatewayUrl", "http://localhost:1");
        ReflectionTestUtils.setField(alipayService, "privateKey", "");
        ReflectionTestUtils.setField(alipayService, "alipayPublicKey", "PUB_KEY");
    }

    // ---------- generatePayPage ----------
    @Test
    @DisplayName("无凭据时回退到本地模拟支付页")
    void generateMockPayPageWhenNoCredential() {
        String html = alipayService.generatePayPage(orderNo, "课程", "100.00", "http://localhost:51601/n", "http://localhost:9901/r");
        assertTrue(html.contains("支付宝沙箱支付"));
        assertTrue(html.contains(orderNo));
        assertTrue(html.contains("100.00"));
    }

    @Test
    @DisplayName("有凭据但生成支付宝表单失败时回退模拟页")
    void generateMockPayPageWhenAlipayDown() {
        ReflectionTestUtils.setField(alipayService, "appId", "app-123");
        ReflectionTestUtils.setField(alipayService, "privateKey", "fake-private-key");
        String html = alipayService.generatePayPage(orderNo, "课程", "100.00", "http://localhost:51601/n", "http://localhost:9901/r");
        // 假凭据无法完成真实 RSA2 签名/网关交互，SDK 抛异常后应兜底返回模拟页
        assertTrue(html.contains("支付宝沙箱支付"));
    }

    // ---------- handleNotify ----------
    @Test
    @DisplayName("回调状态非成功被拒绝")
    void handleNotifyNonSuccess() {
        assertFalse(alipayService.handleNotify("TN", orderNo, "100", "TRADE_CLOSED"));
        verify(orderService, never()).handlePaySuccess(anyString(), anyString());
    }

    @Test
    @DisplayName("回调金额缺失被拒绝")
    void handleNotifyMissingAmount() {
        assertFalse(alipayService.handleNotify("TN", orderNo, "", "TRADE_SUCCESS"));
        verify(orderService, never()).handlePaySuccess(anyString(), anyString());
    }

    @Test
    @DisplayName("回调订单不存在被拒绝")
    void handleNotifyOrderNotExist() {
        when(orderService.getByOrderNo(orderNo)).thenReturn(null);
        assertFalse(alipayService.handleNotify("TN", orderNo, "100", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("回调金额非法被拒绝")
    void handleNotifyInvalidAmount() {
        ApCourseOrder o = new ApCourseOrder();
        o.setPaidAmount(new BigDecimal("100"));
        when(orderService.getByOrderNo(orderNo)).thenReturn(o);
        assertFalse(alipayService.handleNotify("TN", orderNo, "abc", "TRADE_SUCCESS"));
    }

    @Test
    @DisplayName("回调金额与订单不一致被拒绝（防篡改）")
    void handleNotifyAmountMismatch() {
        ApCourseOrder o = new ApCourseOrder();
        o.setPaidAmount(new BigDecimal("100"));
        when(orderService.getByOrderNo(orderNo)).thenReturn(o);
        assertFalse(alipayService.handleNotify("TN", orderNo, "200", "TRADE_SUCCESS"));
        verify(orderService, never()).handlePaySuccess(anyString(), anyString());
    }

    @Test
    @DisplayName("回调校验通过后流转支付成功")
    void handleNotifySuccess() {
        ApCourseOrder o = new ApCourseOrder();
        o.setPaidAmount(new BigDecimal("100"));
        when(orderService.getByOrderNo(orderNo)).thenReturn(o);
        assertTrue(alipayService.handleNotify("TN123", orderNo, "100.00", "TRADE_SUCCESS"));
        verify(orderService).handlePaySuccess(orderNo, "TN123");
    }

    // ---------- verifySign ----------
    @Test
    @DisplayName("公钥未配置时 fail-closed 拒绝验签")
    void verifySignNoPublicKey() {
        ReflectionTestUtils.setField(alipayService, "alipayPublicKey", "");
        Map<String, String> params = Map.of("out_trade_no", orderNo, "trade_status", "TRADE_SUCCESS");
        assertFalse(alipayService.verifySign(params));
    }

    @Test
    @DisplayName("公钥配置但签名不合法时拒绝")
    void verifySignInvalid() {
        ReflectionTestUtils.setField(alipayService, "alipayPublicKey", "INVALID_PUBLIC_KEY");
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", orderNo);
        params.put("sign", "bad-sign");
        params.put("sign_type", "RSA2");
        // 假公钥无法通过 RSA2 验签，最终应返回 false（无论校验抛错或返回 false）
        assertFalse(alipayService.verifySign(params));
    }
}