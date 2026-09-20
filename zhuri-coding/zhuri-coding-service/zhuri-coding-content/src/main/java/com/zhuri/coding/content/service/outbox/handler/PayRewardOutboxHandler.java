package com.zhuri.coding.content.service.outbox.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhuri.coding.content.service.outbox.OutboxDispatcher;
import com.zhuri.coding.content.service.outbox.OutboxHandler;
import com.zhuri.coding.content.service.payment.PaymentRewardService;
import java.math.BigDecimal;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「课程购买支付成功联动」Outbox 处理器：加逐日等级经验 + 发系统站内信。
 *
 * <p>由 {@code OrderServiceImpl.handlePaySuccess} 在主事务内写事件
 * （eventKey = {@link #EVENT_TYPE} + ":" + orderNo），本 Handler 异步消费 ——
 * 失败由 Outbox 指数退避重试，不再丢事件（P0-5 同源问题的非资金半边）。
 *
 * <p>幂等性：eventKey 含 orderNo 且事件只被 CAS 抢占一次，正常路径天然一次；
 * 「执行成功但 markDone 前崩溃」的极端重放会重复加经验/发通知 —— 可接受（非资金），
 * 且站内信重复两条、经验多加一次的危害远小于丢事件。
 */
@Component
@Slf4j
public class PayRewardOutboxHandler implements OutboxHandler {

    public static final String EVENT_TYPE = "PAY_REWARD";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private PaymentRewardService paymentRewardService;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void execute(String payload) throws Exception {
        PayRewardPayload p;
        try {
            p = MAPPER.readValue(payload, PayRewardPayload.class);
        } catch (Exception e) {
            // 载荷损坏重试也不会成功：判死信
            throw new OutboxDispatcher.DeadSignal("payload 反序列化失败: " + e.getMessage());
        }
        if (p.getUserId() == null || p.getCourseId() == null || p.getOrderNo() == null) {
            throw new OutboxDispatcher.DeadSignal("payload 缺关键字段: " + payload);
        }
        paymentRewardService.onCoursePurchaseSuccess(
                p.getUserId(), p.getCourseId(), p.getPaidAmount(), p.getOrderNo());
        log.info("支付联动事件已执行: orderNo={}, userId={}, courseId={}",
                p.getOrderNo(), p.getUserId(), p.getCourseId());
    }

    /** 事件载荷（JSON 入库 payload 列） */
    @Data
    public static class PayRewardPayload {
        private Long userId;
        private Long courseId;
        private BigDecimal paidAmount;
        private String orderNo;

        public static PayRewardPayload of(Long userId, Long courseId, BigDecimal paidAmount, String orderNo) {
            PayRewardPayload p = new PayRewardPayload();
            p.setUserId(userId);
            p.setCourseId(courseId);
            p.setPaidAmount(paidAmount);
            p.setOrderNo(orderNo);
            return p;
        }
    }
}
