package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.model.ai.pojos.AiTopupOrder;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.util.Date;
import java.util.List;

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

    /**
     * 支付异步通知处理（PayController 按前缀分发调用；幂等）。
     *
     * @param tradeStatus 支付宝交易状态，必须为 TRADE_SUCCESS / TRADE_FINISHED 才允许入账；
     *                    WAIT_BUYER_PAY、TRADE_CLOSED 等非成功状态一律拒绝，防止未付款就发放额度
     */
    boolean handleNotify(String orderNo, String totalAmount, String tradeNo, String tradeStatus);

    /**
     * 对账补偿：查询创建超过支付超时时间、仍为待支付的额度包订单。
     *
     * @param limit         单批上限
     * @param createdBefore 只取该时间之前创建的订单（通常为 now - payTimeout）
     */
    List<AiTopupOrder> listExpiredPendingOrders(int limit, Date createdBefore);

    /**
     * 关闭本地额度包订单（PENDING → CLOSED，条件更新幂等）。
     *
     * @return true=本次确实关闭；false=订单已非待支付态
     */
    boolean markClosed(String orderNo);
}
