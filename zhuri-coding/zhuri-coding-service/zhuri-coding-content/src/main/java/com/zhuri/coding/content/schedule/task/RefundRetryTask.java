package com.zhuri.coding.content.schedule.task;

import com.zhuri.coding.content.service.order.OrderService;
import com.zhuri.coding.content.service.pay.AlipayService;
import com.zhuri.coding.model.course.pojos.ApCourseOrder;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款失败重试定时任务（退款兜底闭环的兜底层）。
 * <p>
 * 触发条件：首次自动退款失败时通过 {@link OrderService#markRefundPending} 将订单
 * 标记为 {@code refund_pending=1}（状态仍为 CANCELLED）。本任务周期扫描这些订单，
 * 重新调用支付宝退款；成功则 {@code markRefunded}（REFUNDED + 清标记），失败则保留标记，
 * 由下一轮继续重试并打印告警，避免“付款但拿不到课程”的资损长期残留。
 * </p>
 */
@Slf4j
@Component
public class RefundRetryTask {

    /** 单轮最大处理条数（防止一次拉取过多阻塞调度线程） */
    private static final int MAX_BATCH = 100;

    /** 退款重试上限（次数），默认 3，可通过 app.order.refund-max-retries 配置；达到上限停止重试并告警 */
    @Value("${app.order.refund-max-retries:3}")
    private int maxRetries;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AlipayService alipayService;

    @Scheduled(fixedDelayString = "${app.order.refund-retry-fixed-delay-ms:300000}")
    public void retryPendingRefunds() {
        try {
            List<ApCourseOrder> pending = orderService.listPendingRefundOrders(MAX_BATCH);
            if (pending.isEmpty()) {
                return;
            }
            log.info("退款重试任务扫描到 {} 笔待重试退款", pending.size());
            for (ApCourseOrder order : pending) {
                retryOne(order);
            }
        } catch (Exception e) {
            log.error("退款重试任务执行异常", e);
        }
    }

    /** 单笔退款重试：成功置已退款；失败计数，达到上限停止并告警（需人工介入） */
    private void retryOne(ApCourseOrder order) {
        String orderNo = order.getOrderNo();
        String amount = order.getPaidAmount() != null ? order.getPaidAmount().toString() : "0";
        String tradeNo = order.getTradeNo();
        try {
            String refundNo = alipayService.refund(orderNo, amount, tradeNo);
            if (refundNo != null) {
                orderService.markRefunded(orderNo, refundNo);
                log.warn("退款重试成功: orderNo={}, amount={}, refundNo={}", orderNo, amount, refundNo);
                return;
            }
            boolean exhausted = orderService.markRefundRetryFailure(orderNo, maxRetries);
            if (exhausted) {
                // ★ 告警：已达重试上限，停止自动重试，需运营/财务人工介入，可通过 listAlertedRefundOrders 查询
                log.error("[退款告警] 订单退款重试达上限({}次)仍失败，已停止自动重试，需人工介入: orderNo={}, amount={}, 支付宝交易号={}",
                        maxRetries, orderNo, amount, tradeNo);
            } else {
                int cur = order.getRefundRetryCount() == null ? 0 : order.getRefundRetryCount();
                log.warn("退款重试失败(第{}次)，下轮重试: orderNo={}, amount={}", (cur + 1), orderNo, amount);
            }
        } catch (Exception e) {
            // 单笔异常不影响其余订单，保留标记由下轮重试
            log.error("退款重试异常: orderNo={}", orderNo, e);
        }
    }
}