package com.zhuri.coding.content.service.pay.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.pay.TradeQueryResult;
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

    /** 支付宝卖家 ID（可选）：配置后异步回调会校验 seller_id，防止跨商户回调混淆 */
    @Value("${alipay.seller-id:}")
    private String sellerId;

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

    @Override
    public String getSellerId() {
        return sellerId;
    }

    @Override
    public boolean isConfigured() {
        return isCredentialReady();
    }

    /** 创建支付宝客户端（抽出方法便于单测注入 mock 客户端） */
    protected AlipayClient buildClient() {
        return new DefaultAlipayClient(
                gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, "RSA2");
    }

    /**
     * 生成支付宝「电脑网站支付」签名表单（HTML，含自动提交脚本）。
     * 页面加载后表单自动 POST 到支付宝网关，用户即可在支付宝收银台完成支付。
     */
    private String buildAlipayPagePayForm(String orderNo, String subject, String amount,
                                          String notifyUrl, String returnUrl) throws Exception {
        AlipayClient alipayClient = buildClient();
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
        // 官方规范：TRADE_SUCCESS / TRADE_FINISHED 均表示支付成功（后者常见于通知延迟/补发场景）
        if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
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
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            // out_request_no 用订单号作幂等键，支付宝保证同一请求号仅能退一次款，防止重复退款
            String bizContent = "{"
                    + "\"out_trade_no\":\"" + orderNo + "\","
                    + "\"trade_no\":\"" + (tradeNo == null ? "" : tradeNo) + "\","
                    + "\"refund_amount\":\"" + amount + "\","
                    + "\"out_request_no\":\"" + orderNo + "\""
                    + "}";
            request.setBizContent(bizContent);
            AlipayTradeRefundResponse response = buildClient().execute(request);
            if (response.isSuccess()) {
                // 官方规范：fund_change=Y 才代表资金确实发生变动（退款成功）；
                // 仅 isSuccess() 只能说明退款请求被受理。
                if ("Y".equals(response.getFundChange())) {
                    log.info("支付宝退款成功: orderNo={}, amount={}, fundChange={}", orderNo, amount, response.getFundChange());
                    return orderNo;
                }
                // fund_change=N / 缺失：可能是重复退款（资金早已退回），以退款查询结果为准
                log.warn("支付宝退款受理但资金变动不明确, orderNo={}, fundChange={}, 尝试退款查询确认",
                        orderNo, response.getFundChange());
                if (queryRefundSuccess(orderNo)) {
                    log.info("退款查询确认成功: orderNo={}, amount={}", orderNo, amount);
                    return orderNo;
                }
                log.warn("退款查询未确认成功，保留待重试: orderNo={}, amount={}", orderNo, amount);
                return null;
            }
            log.error("支付宝退款失败: orderNo={}, code={}, subMsg={}", orderNo, response.getCode(), response.getSubMsg());
            return null;
        } catch (Exception e) {
            log.error("支付宝退款异常: orderNo={}", orderNo, e);
            return null;
        }
    }

    @Override
    public boolean queryRefundSuccess(String orderNo) {
        if (!isCredentialReady()) {
            return false;
        }
        try {
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            // out_request_no 与退款时保持一致的幂等键
            request.setBizContent("{"
                    + "\"out_trade_no\":\"" + orderNo + "\","
                    + "\"out_request_no\":\"" + orderNo + "\""
                    + "}");
            AlipayTradeFastpayRefundQueryResponse response = buildClient().execute(request);
            boolean refunded = response.isSuccess() && "REFUND_SUCCESS".equals(response.getRefundStatus());
            if (!refunded) {
                log.warn("退款查询未确认成功: orderNo={}, code={}, subCode={}, refundStatus={}",
                        orderNo, response.getCode(), response.getSubCode(), response.getRefundStatus());
            }
            return refunded;
        } catch (Exception e) {
            log.error("退款查询异常: orderNo={}", orderNo, e);
            return false;
        }
    }

    @Override
    public TradeQueryResult queryTrade(String orderNo) {
        if (!isCredentialReady()) {
            return TradeQueryResult.unknown();
        }
        try {
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            request.setBizContent("{\"out_trade_no\":\"" + orderNo + "\"}");
            AlipayTradeQueryResponse response = buildClient().execute(request);
            if (response.isSuccess()) {
                log.info("支付宝交易查询: orderNo={}, tradeStatus={}, tradeNo={}",
                        orderNo, response.getTradeStatus(), response.getTradeNo());
                return TradeQueryResult.of(response.getTradeStatus(), response.getTradeNo(), response.getTotalAmount());
            }
            if ("40004".equals(response.getCode()) && "ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                log.info("支付宝交易不存在: orderNo={}", orderNo);
                return TradeQueryResult.notExist();
            }
            // 其他错误码（网络/权限/参数等）不能推断支付结果，交由下一轮重试
            log.warn("支付宝交易查询失败: orderNo={}, code={}, subCode={}, subMsg={}",
                    orderNo, response.getCode(), response.getSubCode(), response.getSubMsg());
            return TradeQueryResult.unknown();
        } catch (Exception e) {
            log.error("支付宝交易查询异常: orderNo={}", orderNo, e);
            return TradeQueryResult.unknown();
        }
    }

    @Override
    public boolean closeTrade(String orderNo) {
        if (!isCredentialReady()) {
            return false;
        }
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            request.setBizContent("{\"out_trade_no\":\"" + orderNo + "\"}");
            AlipayTradeCloseResponse response = buildClient().execute(request);
            if (response.isSuccess()) {
                log.info("支付宝交易已关闭: orderNo={}", orderNo);
                return true;
            }
            // 交易不存在/已关闭：支付宝侧已无待支付交易，对本地关单而言等同成功
            if ("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) {
                log.info("支付宝交易不存在，无需关闭: orderNo={}", orderNo);
                return true;
            }
            log.warn("支付宝交易关闭失败: orderNo={}, code={}, subCode={}, subMsg={}",
                    orderNo, response.getCode(), response.getSubCode(), response.getSubMsg());
            return false;
        } catch (Exception e) {
            log.error("支付宝交易关闭异常: orderNo={}", orderNo, e);
            return false;
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
