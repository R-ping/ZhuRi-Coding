package com.heima.content.service.pay;

import java.util.Map;

public interface AlipayService {

    /** 生成支付页面HTML（通知地址与回跳地址由各业务场景基于 base-url / web-base-url 拼接后传入） */
    String generatePayPage(String orderNo, String subject, String amount, String notifyUrl, String returnUrl);

    /** 处理支付异步通知 */
    boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status);

    /** 验证签名 */
    boolean verifySign(Map<String, String> params);
}