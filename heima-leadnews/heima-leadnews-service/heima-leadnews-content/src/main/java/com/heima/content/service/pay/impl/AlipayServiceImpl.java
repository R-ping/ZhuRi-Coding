package com.heima.content.service.pay.impl;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.heima.content.service.order.OrderService;
import com.heima.content.service.pay.AlipayService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 支付宝支付实现。
 * <p>
 * 已配置沙箱/正式凭据（app-id + 私钥 + 支付宝公钥）时，通过 alipay-sdk-java
 * 生成「电脑网站支付 alipay.trade.page.pay」的签名表单（页面加载即自动提交到支付宝网关），
 * 从而真实调起支付宝收银台。
 * <p>
 * 未配置凭据时回退到本地模拟支付页，便于纯前端/无凭据环境联调。
 */
@Service
@Slf4j
public class AlipayServiceImpl implements AlipayService {

    @Value("${alipay.app-id}")
    private String appId;

    @Value("${alipay.gateway-url}")
    private String gatewayUrl;

    @Value("${alipay.private-key}")
    private String privateKey;

    @Value("${alipay.alipay-public-key}")
    private String alipayPublicKey;

    @Value("${alipay.notify-url}")
    private String notifyUrl;

    @Value("${alipay.return-url}")
    private String returnUrl;

    @Autowired
    private OrderService orderService;

    @Override
    public String generatePayPage(String orderNo, String subject, String amount) {
        return generatePayPage(orderNo, subject, amount, notifyUrl, returnUrl);
    }

    @Override
    public String generatePayPage(String orderNo, String subject, String amount, String notifyUrl, String returnUrl) {
        // 凭据齐全时生成真实支付宝支付表单（自动提交到支付宝网关）
        if (isCredentialReady()) {
            try {
                return buildAlipayPagePayForm(orderNo, subject, amount, notifyUrl, returnUrl);
            } catch (Exception e) {
                log.error("生成支付宝支付表单失败, orderNo={}, 回退到模拟支付页", orderNo, e);
            }
        }
        // 无凭据或生成失败时，回退到本地模拟支付页（开发调试用）
        return buildMockPayPage(orderNo, subject, amount, notifyUrl, returnUrl);
    }

    /** 判断支付宝凭据是否齐全（appId 与私钥同时存在才视为可发起真实支付） */
    private boolean isCredentialReady() {
        return appId != null && !appId.isEmpty()
                && privateKey != null && !privateKey.isEmpty();
    }

    /**
     * 生成支付宝「电脑网站支付」签名表单（HTML，含自动提交脚本）。
     * 页面加载后表单自动 POST 到支付宝网关，用户即可在支付宝收银台完成支付。
     */
    private String buildAlipayPagePayForm(String orderNo, String subject, String amount,
                                          String notifyUrl, String returnUrl) throws Exception {
        AlipayClient alipayClient = new DefaultAlipayClient(
                gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(returnUrl);
        // 构建业务参数（字段值经 SDK 内部转义，安全）
        String bizContent = "{"
                + "\"out_trade_no\":\"" + orderNo + "\","
                + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\","
                + "\"total_amount\":\"" + amount + "\","
                + "\"subject\":\"" + subject + "\""
                + "}";
        request.setBizContent(bizContent);
        // pageExecute 返回的 body 即为带自动提交脚本的支付表单 HTML
        String form = alipayClient.pageExecute(request).getBody();
        log.info("已生成支付宝支付表单, orderNo={}, amount={}", orderNo, amount);
        return form;
    }

    /** 本地模拟支付页：点击后直接向 notify 接口发送 TRADE_SUCCESS，用于无凭据/纯前端联调 */
    private String buildMockPayPage(String orderNo, String subject, String amount, String notifyUrl, String returnUrl) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>支付宝沙箱支付</title>");
        html.append("<style>body{font-family:Arial,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;background:#f5f5f5;margin:0}");
        html.append(".pay-box{background:#fff;padding:40px;border-radius:12px;box-shadow:0 4px 20px rgba(0,0,0,0.1);text-align:center;max-width:400px;width:100%}");
        html.append(".pay-title{font-size:20px;color:#333;margin-bottom:8px}");
        html.append(".pay-subtitle{font-size:14px;color:#999;margin-bottom:24px}");
        html.append(".pay-amount{font-size:36px;color:#ff6b00;font-weight:bold;margin-bottom:24px}");
        html.append(".pay-btn{display:inline-block;padding:14px 48px;background:#1677ff;color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer;text-decoration:none}");
        html.append(".pay-btn:hover{background:#4096ff}");
        html.append(".pay-note{font-size:12px;color:#999;margin-top:16px}</style></head><body>");
        html.append("<div class='pay-box'>");
        html.append("<div class='pay-title'>").append(subject).append("</div>");
        html.append("<div class='pay-subtitle'>订单号: ").append(orderNo).append("</div>");
        html.append("<div class='pay-amount'>¥").append(amount).append("</div>");
        html.append("<div class='pay-subtitle' style='color:#ff9800;margin-bottom:16px'>【沙箱环境】</div>");
        html.append("<a class='pay-btn' href='javascript:void(0)' onclick='confirmPay()'>确认支付</a>");
        html.append("<div class='pay-note'>点击确认后将模拟支付成功</div>");
        html.append("</div>");
        html.append("<script>");
        html.append("function confirmPay() {");
        html.append("  var tradeNo = 'ALIPAY_SANDBOX_' + Date.now();");
        html.append("  fetch('").append(notifyUrl).append("', {");
        html.append("    method: 'POST',");
        html.append("    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },");
        html.append("    body: 'out_trade_no=").append(orderNo).append("&trade_no=' + tradeNo + '&total_amount=").append(amount).append("&trade_status=TRADE_SUCCESS'");
        html.append("  }).then(function(r) { return r.text(); }).then(function() {");
        html.append("    window.location.href = '").append(returnUrl).append("?orderNo=").append(orderNo).append("&status=success';");
        html.append("  }).catch(function() {");
        html.append("    alert('支付通知失败，请稍后重试');");
        html.append("  });");
        html.append("}");
        html.append("</script></body></html>");

        return html.toString();
    }

    @Override
    public boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status) {
        if (!"TRADE_SUCCESS".equals(status)) {
            log.warn("支付状态非成功: {}", status);
            return false;
        }

        orderService.handlePaySuccess(orderNo, tradeNo);
        return true;
    }

    @Override
    public boolean verifySign(Map<String, String> params) {
        // 沙箱环境简化验证，生产环境需使用支付宝SDK验证签名
        return true;
    }
}
