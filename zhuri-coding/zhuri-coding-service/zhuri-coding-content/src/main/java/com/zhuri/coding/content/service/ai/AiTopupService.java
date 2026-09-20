package com.heima.content.service.ai;

import com.heima.model.common.dtos.ResponseResult;

/**
 * AI 额度包充值服务（订阅-配额打通）
 *
 * <p>流程：create 生成订单 → page 出支付宝收银台 HTML（复用现有 AlipayService）
 * → 支付异步通知按 out_trade_no 前缀 "ai" 分发到本服务 → 金额校验通过后入账钱包。
 */
public interface AiTopupService {

    /** 创建额度包订单 */
    ResponseResult createTopup(Integer userId, String packageCode);

    /** 组装支付宝收银台 HTML（订单归属/待支付校验） */
    ResponseResult buildPayPage(String orderNo, Integer userId);

    /** 查询充值订单状态（本人） */
    ResponseResult getStatus(String orderNo, Integer userId);

    /** 支付异步通知处理（AlipayServiceImpl 按前缀分发调用；幂等） */
    boolean handleNotify(String orderNo, String totalAmount, String tradeNo);
}
