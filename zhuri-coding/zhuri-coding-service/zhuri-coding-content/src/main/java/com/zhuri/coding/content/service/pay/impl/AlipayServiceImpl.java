package com.zhuri.coding.content.service.pay.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import java.math.BigDecimal;
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

    /** 支付通道超时（毫秒），与 app.order.pay-timeout-ms 保持一致；据此设置支付宝 timeout_express */
    @Value("${app.order.pay-timeout-ms:300000}")
    private long payTimeoutMs;

    @Autowired
    private OrderService orderService;

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

    @Override
    public String getAppId() {
        return appId;
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
        // timeout_express：与「支付通道超时」一致，超时后支付宝拒绝收款，避免关单后仍被支付造成资损
        long timeoutMinutes = Math.max(1, payTimeoutMs / 60000);
        String bizContent = "{"
                + "\"out_trade_no\":\"" + orderNo + "\","
                + "\"product_code\":\"FAST_INSTANT_TRADE_PAY\","
                + "\"total_amount\":\"" + amount + "\","
                + "\"subject\":\"" + subject + "\","
                + "\"timeout_express\":\"" + timeoutMinutes + "m\""
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

        // 金额一致性校验：仅以服务端订单金额为准，防止回调 total_amount 被篡改
        if (!verifyAmount(orderNo, totalAmount)) {
            return false;
        }

        // 尝试将支付应用到订单（PENDING/PROCESSING→PAID 并放权）。若返回 false：
        //  场景 1：订单已不在可支付态（多半已在超时关单的并发窗口内被置为 CANCELLED），
        //          但钱已真实入账 → 触发退款兜底，避免"用户付款却拿不到课程"。
        //  场景 2（P0-5 新增）：订单已 PAID 但 handlePaySuccess 内部因券核销失败返回 false，
        //          订单被置为 refund_pending=1 → 同样走退款兜底。
        boolean applied = orderService.handlePaySuccess(orderNo, tradeNo);
        if (!applied) {
            ApCourseOrder order = orderService.getByOrderNo(orderNo);
            if (order == null) {
                log.warn("支付回调后查不到订单: orderNo={}", orderNo);
                return true;
            }
            Integer orderStatus = order.getStatus();
            boolean needRefund = false;
            if (orderStatus != null && orderStatus.intValue() == ApCourseOrder.Status.CANCELLED.getCode()) {
                needRefund = true;
            } else if (orderStatus != null && orderStatus.intValue() == ApCourseOrder.Status.PAID.getCode()
                    && order.getRefundPending() != null && order.getRefundPending() == 1) {
                // P0-5：已 PAID + 券核销失败场景（refund_pending_reason ∈ {discount_code_exhausted, coupon_consume_failed}）
                needRefund = true;
                log.warn("订单已 PAID 但券核销失败，触发自动退款兜底: orderNo={}, reason={}",
                        orderNo, order.getRefundPendingReason());
            }
            if (needRefund) {
                String refundAmount = order.getPaidAmount() != null ? order.getPaidAmount().toString() : "0";
                log.warn("支付成功但订单需退款兜底: orderNo={}, amount={}, status={}, refundPending={}",
                        orderNo, refundAmount, orderStatus, order.getRefundPending());
                String refundNo = refund(orderNo, refundAmount, tradeNo);
                if (refundNo != null) {
                    orderService.markRefunded(orderNo, refundNo);
                } else {
                    // 首次退款失败：标记待重试，由定时任务（RefundRetryTask）兜底补偿
                    log.error("订单退款兜底失败，标记待重试: orderNo={}", orderNo);
                    if (orderStatus != null && orderStatus.intValue() == ApCourseOrder.Status.CANCELLED.getCode()) {
                        orderService.markRefundPending(orderNo);
                    }
                    // 若 status=PAID 且 refund_pending=1 时退款失败：保持原 refund_pending=1 状态，
                    // RefundRetryTask 会重试（不会重复覆盖 reason）
                }
            }
        }
        return true;
    }

    @Override
    public String refund(String orderNo, String refundAmount, String tradeNo) {
        String amount = refundAmount == null ? "0" : refundAmount;
        // 未配置凭据时模拟退款成功（本地/沙箱联调），真实环境走支付宝退款接口
        if (!isCredentialReady()) {
            String mockRefundNo = "MOCK_" + orderNo;
            log.info("未配置支付宝凭据，模拟退款成功: orderNo={}, amount={}, refundNo={}", orderNo, amount, mockRefundNo);
            return mockRefundNo;
        }
        try {
            AlipayClient client = new DefaultAlipayClient(
                    gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2");
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            // out_request_no 用订单号作幂等键，支付宝保证同一请求号仅能退一次款，防止重复退款
            String bizContent = "{"
                    + "\"out_trade_no\":\"" + orderNo + "\","
                    + "\"trade_no\":\"" + (tradeNo == null ? "" : tradeNo) + "\","
                    + "\"refund_amount\":\"" + amount + "\","
                    + "\"out_request_no\":\"" + orderNo + "\""
                    + "}";
            request.setBizContent(bizContent);
            AlipayTradeRefundResponse response = client.execute(request);
            if (response.isSuccess()) {
                // 以支付宝返回的交易号作为退款关联流水号（无则用订单号兜底）
                String refundNo = response.getTradeNo();
                if (refundNo == null || refundNo.isEmpty()) {
                    refundNo = orderNo;
                }
                log.info("支付宝退款成功: orderNo={}, amount={}, refundNo={}", orderNo, amount, refundNo);
                return refundNo;
            }
            log.error("支付宝退款失败: orderNo={}, code={}, subMsg={}", orderNo, response.getCode(), response.getSubMsg());
            return null;
        } catch (Exception e) {
            log.error("支付宝退款异常: orderNo={}", orderNo, e);
            return null;
        }
    }

    /**
     * 校验支付回调金额与订单实付金额是否一致（仅信任服务端订单数据）。
     * 不一致或金额非法时拒绝回调，避免被篡改的 total_amount 完成入账。
     *
     * @param orderNo     订单号
     * @param totalAmount 回调金额（字符串）
     * @return 一致返回 true，否则 false
     */
    private boolean verifyAmount(String orderNo, String totalAmount) {
        if (totalAmount == null || totalAmount.isEmpty()) {
            log.error("支付回调缺少金额字段, orderNo={}", orderNo);
            return false;
        }
        ApCourseOrder order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            log.error("支付回调订单不存在, orderNo={}", orderNo);
            return false;
        }
        BigDecimal notifyAmount;
        try {
            notifyAmount = new BigDecimal(totalAmount);
        } catch (NumberFormatException e) {
            log.error("支付回调金额非法, orderNo={}, totalAmount={}", orderNo, totalAmount);
            return false;
        }
        BigDecimal orderAmount = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        if (notifyAmount.compareTo(orderAmount) != 0) {
            log.error("支付回调金额不一致, orderNo={}, 回调金额={}, 订单金额={}",
                    orderNo, totalAmount, order.getPaidAmount());
            return false;
        }
        return true;
    }

    @Override
    public boolean verifySign(Map<String, String> params) {
        // 未配置支付宝公钥时拒绝回调（fail-closed），防止公钥缺失时伪造回调通过
        if (alipayPublicKey == null || alipayPublicKey.isEmpty()) {
            log.error("支付宝公钥 alipay.alipay-public-key 未配置，拒绝支付回调");
            return false;
        }
        try {
            // RSA2 验签：params 为支付宝异步通知的全部参数（去除 sign / sign_type）
            boolean verified = AlipaySignature.rsaCheckV1(params, alipayPublicKey, "UTF-8", "RSA2");
            if (!verified) {
                log.error("支付宝回调验签失败");
            }
            return verified;
        } catch (AlipayApiException e) {
            log.error("支付宝回调验签异常", e);
            return false;
        }
    }
}
