package com.heima.content.service.pay;

import java.util.Map;

public interface AlipayService {

    /** 生成支付页面HTML（课程支付，使用默认通知/回跳地址） */
    String generatePayPage(String orderNo, String subject, String amount);

    /** 生成支付页面HTML（自定义通知地址与回跳地址，用于打赏等场景） */
    String generatePayPage(String orderNo, String subject, String amount, String notifyUrl, String returnUrl);

    /** 处理支付异步通知 */
    boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status);

    /** 验证签名 */
    boolean verifySign(Map<String, String> params);
}