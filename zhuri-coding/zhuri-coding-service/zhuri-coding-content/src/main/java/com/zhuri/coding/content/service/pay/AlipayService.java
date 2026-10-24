package com.zhuri.coding.content.service.pay;

import java.util.Map;

public interface AlipayService {

    /** 生成支付页面HTML（通知地址与回跳地址由各业务场景基于 base-url / web-base-url 拼接后传入） */
    String generatePayPage(String orderNo, String subject, String amount, String notifyUrl, String returnUrl);

    /** 处理支付异步通知 */
    boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status);

    /** 验证签名 */
    boolean verifySign(Map<String, String> params);

    /** 获取支付宝应用 ID（用于异步回调中校验 app_id 一致性，防止跨应用回调混淆） */
    String getAppId();

    /** 获取支付宝卖家 ID（用于异步回调校验 seller_id；未配置时返回空串，跳过该校验） */
    String getSellerId();

    /** 支付宝凭据是否齐全（未配置时支付/查询/退款均不可用，对账任务应直接跳过） */
    boolean isConfigured();

    /**
     * 查询支付宝交易真实状态（用于异步通知丢失或结果不确定时的兜底确认）。
     *
     * @param orderNo 商户订单号（out_trade_no）
     * @return 查询结果；查询失败时 {@link TradeQueryResult#available()} 为 false，调用方必须跳过而非推断
     */
    TradeQueryResult queryTrade(String orderNo);

    /**
     * 关闭支付宝侧未付款交易（幂等）。
     * <p>本地超时关单后调用，防止"本地已取消、支付宝侧仍可付款"的窗口。
     * 交易不存在或已关闭时同样视为成功。</p>
     *
     * @param orderNo 商户订单号（out_trade_no）
     * @return true=支付宝侧已无待支付交易；false=关闭失败或交易已支付（需交由查询/通知链路处理）
     */
    boolean closeTrade(String orderNo);

    /**
     * 退款（退款兜底）：当支付成功但订单已被关闭（无法放权）时，将款项原路退回。
     * <p>以 {@code out_request_no=orderNo} 保证支付宝侧幂等，防止并发/重复通知重复退款。</p>
     *
     * @param orderNo       订单号（同时作为支付宝退款幂等键）
     * @param refundAmount  退款金额（订单实付金额）
     * @param tradeNo       原支付交易号
     * @return 退款请求号（out_request_no）表示资金确实退回；失败或结果不明确返回 null，由重试任务继续确认
     */
    String refund(String orderNo, String refundAmount, String tradeNo);

    /**
     * 退款结果查询（用于 {@code fund_change=N} 或退款响应不明确时二次确认）。
     *
     * @param orderNo 订单号（同时作为退款请求号 out_request_no）
     * @return true=支付宝确认退款成功（refund_status=REFUND_SUCCESS）
     */
    boolean queryRefundSuccess(String orderNo);
}