package com.heima.content.service.tip;

import com.heima.model.common.dtos.ResponseResult;
import java.math.BigDecimal;

/**
 * 文章打赏服务
 */
public interface TipService {

    /**
     * 创建打赏订单
     * @param articleId 文章ID
     * @param amount    打赏金额
     * @param message   打赏留言
     * @param userId    打赏人用户ID
     * @return { orderNo, payUrl }
     */
    ResponseResult createOrder(Long articleId, BigDecimal amount, String message, Long userId);

    /**
     * 生成打赏支付页面 HTML
     */
    String getPayPage(String orderNo);

    /**
     * 处理打赏支付异步通知
     */
    boolean handleNotify(String tradeNo, String orderNo, String totalAmount, String status);

    /**
     * 获取文章打赏汇总信息（人数、总金额）
     */
    ResponseResult getTipSummary(Long articleId);

    /**
     * 获取文章打赏名单（公开感谢名单）
     */
    ResponseResult getTipList(Long articleId, Integer page, Integer size);

    /**
     * 作者打赏收益汇总（创作中心结算）
     */
    ResponseResult getMyRevenue(Long authorId);
}
