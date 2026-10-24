package com.zhuri.coding.content.service.tip;

import com.zhuri.coding.model.article.pojos.ApArticleTipOrder;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

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

    /**
     * 对账补偿：查询创建超过支付超时时间、仍为待支付的打赏订单。
     *
     * @param limit         单批上限
     * @param createdBefore 只取该时间之前创建的订单（通常为 now - payTimeout）
     */
    List<ApArticleTipOrder> listExpiredPendingOrders(int limit, Date createdBefore);

    /**
     * 关闭本地打赏订单（PENDING → CANCELLED，条件更新幂等）。
     *
     * @return true=本次确实关闭；false=订单已非待支付态
     */
    boolean markClosed(String orderNo);
}
