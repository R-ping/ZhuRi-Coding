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

    /**
     * 退款（退款兜底）：当支付成功但订单已被关闭（无法放权）时，将款项原路退回。
     * <p>以 {@code out_request_no=orderNo} 保证支付宝侧幂等，防止并发/重复通知重复退款。</p>
     *
     * @param orderNo       订单号（同时作为支付宝退款幂等键）
     * @param refundAmount  退款金额（订单实付金额）
     * @param tradeNo       原支付交易号
     * @return 退款受理成功返回退款关联流水号（未配置凭据时返回本地模拟流水号）；失败返回 null
     */
    String refund(String orderNo, String refundAmount, String tradeNo);
}