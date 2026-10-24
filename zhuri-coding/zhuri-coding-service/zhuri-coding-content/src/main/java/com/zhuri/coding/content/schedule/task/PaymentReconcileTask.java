package com.zhuri.coding.content.schedule.task;

import com.zhuri.coding.content.service.ai.AiTopupService;
import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.content.service.pay.TradeQueryResult;
import com.zhuri.coding.content.service.tip.TipService;
import com.zhuri.coding.model.ai.pojos.AiTopupOrder;
import com.zhuri.coding.model.article.pojos.ApArticleTipOrder;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付对账补偿任务（异步通知丢失 / 延迟队列丢消息的兜底层）。
 *
 * <p>以 {@code alipay.trade.query} 的真实交易状态为准，分四条链路校准本地订单：</p>
 * <ol>
 *   <li>课程订单：PENDING/PROCESSING 超过支付超时仍未被关单 → 查询后补入账或关单；</li>
 *   <li>课程订单：最近被本地关闭、但支付宝侧可能已付款 → 补触发入账/退款兜底；</li>
 *   <li>AI 额度包：PENDING 超过支付超时 → 补入账或关闭交易并置 CLOSED；</li>
 *   <li>文章打赏：PENDING 超过支付超时 → 补入账或关闭交易并置 CANCELLED。</li>
 * </ol>
 *
 * <p><b>安全约束</b>：查询失败（{@link TradeQueryResult#available()} 为 false）时只记日志跳过，
 * 绝不把"查不到"推断成"未支付"；所有动作均为幂等条件更新。</p>
 */
@Slf4j
@Component
public class PaymentReconcileTask {

    /** 单批处理上限（防止一次拉取过多阻塞调度线程） */
    @Value("${app.pay.reconcile.batch-size:50}")
    private int batchSize;

    /** 本地已关单订单的回溯窗口（分钟）：只对最近关闭的订单查"是否其实已付款" */
    @Value("${app.pay.reconcile.cancelled-lookback-minutes:120}")
    private long cancelledLookbackMinutes;

    /** 支付通道超时（毫秒），与 app.order.pay-timeout-ms 保持一致；用于 AI 额度包订单 */
    @Value("${app.order.pay-timeout-ms:300000}")
    private long payTimeoutMs;

    /** 订单超时（毫秒），与 app.order.timeout-ms 保持一致；课程 PENDING 订单需按该阈值扫描，避免误关 */
    @Value("${app.order.timeout-ms:1800000}")
    private long orderTimeoutMs;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AlipayService alipayService;

    @Autowired
    private AiTopupService aiTopupService;

    @Autowired
    private TipService tipService;

    @Scheduled(fixedDelayString = "${app.pay.reconcile.fixed-delay-ms:600000}")
    public void reconcile() {
        // 未配置支付宝凭据（本地联调）时无法查询真实状态，直接跳过
        if (!alipayService.isConfigured()) {
            log.debug("未配置支付宝凭据，跳过支付对账");
            return;
        }
        try {
            reconcilePendingCourseOrders();
        } catch (Exception e) {
            log.error("支付对账（待支付课程订单）执行异常", e);
        }
        try {
            reconcileCancelledCourseOrders();
        } catch (Exception e) {
            log.error("支付对账（已取消课程订单）执行异常", e);
        }
        try {
            reconcileAiTopupOrders();
        } catch (Exception e) {
            log.error("支付对账（AI 额度包订单）执行异常", e);
        }
        try {
            reconcileTipOrders();
        } catch (Exception e) {
            log.error("支付对账（文章打赏订单）执行异常", e);
        }
    }

    /** 本地仍待支付/处理中却已超时的课程订单：查询真实状态后补入账或关单 */
    private void reconcilePendingCourseOrders() {
        // 课程订单窗口取“下单超时”（默认 30 分钟）：PENDING 订单尚未打开收银台，
        // 若按 5 分钟的支付通道超时扫描会把正常待支付订单误关。
        Date updatedBefore = new Date(System.currentTimeMillis() - orderTimeoutMs);
        List<ApCourseOrder> orders = orderService.listReconcilePendingOrders(batchSize, updatedBefore);
        if (orders.isEmpty()) {
            return;
        }
        log.info("支付对账：扫描到 {} 笔超时未关单的课程订单", orders.size());
        for (ApCourseOrder order : orders) {
            TradeQueryResult result = alipayService.queryTrade(order.getOrderNo());
            if (!result.available()) {
                continue;
            }
            if (result.paid()) {
                // 复用通知处理：金额一致性校验 + 幂等入账 + 已关单退款兜底都在 handleNotify 内
                boolean applied = alipayService.handleNotify(result.tradeNo(), order.getOrderNo(),
                        result.totalAmount(), result.tradeStatus());
                if (!applied) {
                    log.error("支付对账入账失败（金额不一致或订单异常），需人工核查: orderNo={}, tradeNo={}, tradeStatus={}",
                            order.getOrderNo(), result.tradeNo(), result.tradeStatus());
                }
                continue;
            }
            if (result.waitingPay()) {
                // 先关闭支付宝侧交易，再关本地单，避免本地关单后仍可支付
                if (alipayService.closeTrade(order.getOrderNo())) {
                    closeLocalOrder(order.getOrderNo());
                }
                continue;
            }
            // TRADE_CLOSED / 交易不存在：支付宝侧已无待支付交易，直接关本地单
            closeLocalOrder(order.getOrderNo());
        }
    }

    /** 最近被本地关闭、但支付宝侧可能已付款的课程订单：补入账（已关单则退款兜底） */
    private void reconcileCancelledCourseOrders() {
        Date updatedAfter = new Date(System.currentTimeMillis() - cancelledLookbackMinutes * 60_000L);
        List<ApCourseOrder> orders = orderService.listCancelledForReconcile(batchSize, updatedAfter);
        if (orders.isEmpty()) {
            return;
        }
        log.info("支付对账：扫描到 {} 笔已关闭待确认的课程订单", orders.size());
        for (ApCourseOrder order : orders) {
            TradeQueryResult result = alipayService.queryTrade(order.getOrderNo());
            if (!result.available()) {
                continue;
            }
            if (result.paid()) {
                // 本地已 CANCELLED → handlePaySuccess 失败 → 自动退款兜底
                boolean applied = alipayService.handleNotify(result.tradeNo(), order.getOrderNo(),
                        result.totalAmount(), result.tradeStatus());
                if (!applied) {
                    log.error("已关闭订单对账退款失败，需人工核查: orderNo={}, tradeNo={}, tradeStatus={}",
                            order.getOrderNo(), result.tradeNo(), result.tradeStatus());
                }
            } else if (result.waitingPay()) {
                // 本地已关单但支付宝侧仍待支付：关闭交易，避免用户之后完成付款
                alipayService.closeTrade(order.getOrderNo());
            }
        }
    }

    /** AI 额度包：PENDING 超过支付超时未支付 → 关闭支付宝交易并置 CLOSED；已付款则补入账 */
    private void reconcileAiTopupOrders() {
        Date createdBefore = new Date(System.currentTimeMillis() - payTimeoutMs);
        List<AiTopupOrder> orders = aiTopupService.listExpiredPendingOrders(batchSize, createdBefore);
        if (orders.isEmpty()) {
            return;
        }
        log.info("支付对账：扫描到 {} 笔超时未支付的 AI 额度包订单", orders.size());
        for (AiTopupOrder order : orders) {
            TradeQueryResult result = alipayService.queryTrade(order.getOrderNo());
            if (!result.available()) {
                continue;
            }
            if (result.paid()) {
                boolean applied = aiTopupService.handleNotify(order.getOrderNo(), result.totalAmount(),
                        result.tradeNo(), result.tradeStatus());
                if (!applied) {
                    log.error("AI 额度包对账入账失败，需人工核查: orderNo={}, tradeNo={}, tradeStatus={}",
                            order.getOrderNo(), result.tradeNo(), result.tradeStatus());
                }
                continue;
            }
            if (result.waitingPay() && !alipayService.closeTrade(order.getOrderNo())) {
                // 关闭失败则保持 PENDING，下一轮继续尝试
                continue;
            }
            aiTopupService.markClosed(order.getOrderNo());
        }
    }

    /** 文章打赏：PENDING 超过支付超时未支付 → 关闭支付宝交易并置 CANCELLED；已付款则补入账 */
    private void reconcileTipOrders() {
        Date createdBefore = new Date(System.currentTimeMillis() - payTimeoutMs);
        List<ApArticleTipOrder> orders = tipService.listExpiredPendingOrders(batchSize, createdBefore);
        if (orders.isEmpty()) {
            return;
        }
        log.info("支付对账：扫描到 {} 笔超时未支付的打赏订单", orders.size());
        for (ApArticleTipOrder order : orders) {
            TradeQueryResult result = alipayService.queryTrade(order.getOrderNo());
            if (!result.available()) {
                continue;
            }
            if (result.paid()) {
                boolean applied = tipService.handleNotify(result.tradeNo(), order.getOrderNo(),
                        result.totalAmount(), result.tradeStatus());
                if (!applied) {
                    log.error("打赏订单对账入账失败，需人工核查: orderNo={}, tradeNo={}, tradeStatus={}",
                            order.getOrderNo(), result.tradeNo(), result.tradeStatus());
                }
                continue;
            }
            if (result.waitingPay() && !alipayService.closeTrade(order.getOrderNo())) {
                // 关闭失败则保持 PENDING，下一轮继续尝试
                continue;
            }
            tipService.markClosed(order.getOrderNo());
        }
    }

    /** 关闭本地课程订单：PENDING 与 PROCESSING 各自条件更新，命中其一即可 */
    private void closeLocalOrder(String orderNo) {
        boolean closed = orderService.closeExpiredOrder(orderNo);
        if (!closed) {
            closed = orderService.closePayChannel(orderNo);
        }
        if (closed) {
            log.info("支付对账已关闭本地订单: orderNo={}", orderNo);
        }
    }
}
