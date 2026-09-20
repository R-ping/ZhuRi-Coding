package com.zhuri.coding.content.service.payment;

import java.math.BigDecimal;

/**
 * 支付成功联动服务
 *
 * <p>支付服务（打赏/课程购买）在收到三方异步回调确认支付成功后，通过本服务联动：
 * 1. 给付款用户增加逐日等级经验（等级体系服务）
 * 2. 给付款用户发送"系统通知"站内信（站内信服务，跨服务通过 Feign 调用）
 *
 * <p>所有联动失败均不影响支付主流程（内部兜底异常）。
 */
public interface PaymentRewardService {

    /**
     * 课程购买支付成功联动
     *
     * @param userId     付款用户ID
     * @param courseId   购买的课程ID
     * @param paidAmount 实付金额
     * @param orderNo    支付订单号
     */
    void onCoursePurchaseSuccess(Long userId, Long courseId, BigDecimal paidAmount, String orderNo);

    /**
     * 文章打赏支付成功联动
     *
     * @param userId    打赏用户ID
     * @param articleId 被打赏的文章ID
     * @param amount    打赏金额
     * @param orderNo   支付订单号
     */
    void onArticleRewardSuccess(Long userId, Long articleId, BigDecimal amount, String orderNo);
}
