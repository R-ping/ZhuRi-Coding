package com.zhuri.coding.content.service.order.impl;

import com.zhuri.coding.content.service.order.OrderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单延迟队列消费者。
 * <p>
 * 下单时把订单号投入 Redis 延迟队列（{@link #scheduleClose}），
 * 到达超时时间后由消费者线程取出，调用 {@link OrderService#closeExpiredOrder} 幂等关单。
 * 复用 Redisson RBlockingQueue + RDelayedQueue，与文章延迟发布共用同一套基础设施。
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    /** 订单超时关单延迟队列名 */
    public static final String ORDER_TIMEOUT_DELAY_QUEUE = "ORDER_TIMEOUT_DELAY_QUEUE";

    /** 支付通道超时关单延迟队列名 */
    public static final String ORDER_PAY_TIMEOUT_DELAY_QUEUE = "ORDER_PAY_TIMEOUT_DELAY_QUEUE";

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    @Lazy
    private OrderService orderService;

    /** 订单超时时间（毫秒），默认 30 分钟，可通过 app.order.timeout-ms 配置 */
    @Value("${app.order.timeout-ms:1800000}")
    private long orderTimeoutMs;

    /** 支付通道超时时间（毫秒，进入支付页后未支付即关单），默认 5 分钟，可通过 app.order.pay-timeout-ms 配置 */
    @Value("${app.order.pay-timeout-ms:300000}")
    private long payTimeoutMs;

    private RBlockingQueue<String> blockingQueue;
    private RDelayedQueue<String> delayedQueue;
    private RBlockingQueue<String> payBlockingQueue;
    private RDelayedQueue<String> payDelayedQueue;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "order-timeout-consumer");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        try {
            blockingQueue = redissonClient.getBlockingQueue(ORDER_TIMEOUT_DELAY_QUEUE);
            delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
            payBlockingQueue = redissonClient.getBlockingQueue(ORDER_PAY_TIMEOUT_DELAY_QUEUE);
            payDelayedQueue = redissonClient.getDelayedQueue(payBlockingQueue);
            if (blockingQueue == null || delayedQueue == null
                    || payBlockingQueue == null || payDelayedQueue == null) {
                throw new IllegalStateException("Redisson 延迟队列初始化返回空对象");
            }
            executor.submit(this::consume);
            executor.submit(this::consumePay);
            log.info("订单超时关单消费者已启动, orderTimeoutMs={}, payTimeoutMs={}", orderTimeoutMs, payTimeoutMs);
        } catch (Exception e) {
            // Redis 不可用时降级：不再启动消费者，关单可交由兜底定时扫描补偿，避免拖垮应用上下文
            log.error("订单超时关单消费者启动失败，Redis 可能不可用，降级跳过", e);
        }
    }

    /** 阻塞消费：取出订单号并执行幂等关单（待支付超时） */
    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String orderNo = blockingQueue.take();
                log.info("消费超时关单任务, orderNo={}", orderNo);
                orderService.closeExpiredOrder(orderNo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 单条失败不阻塞后续消费；关单幂等，重试可由兜底定时扫描承担
                log.error("订单超时关单处理异常", e);
            }
        }
    }

    /** 阻塞消费：取出订单号并关闭支付通道（支付页超时未支付） */
    private void consumePay() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String orderNo = payBlockingQueue.take();
                log.info("消费支付通道超时关单任务, orderNo={}", orderNo);
                orderService.closePayChannel(orderNo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("支付通道超时关单处理异常", e);
            }
        }
    }

    /** 下单后排程超时关单 */
    public void scheduleClose(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        delayedQueue.offer(orderNo, orderTimeoutMs, TimeUnit.MILLISECONDS);
        log.info("订单超时关单已排程: orderNo={}, timeoutMs={}", orderNo, orderTimeoutMs);
    }

    /** 去支付抢占成功后，排程支付通道超时关单 */
    public void scheduleClosePayChannel(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        payDelayedQueue.offer(orderNo, payTimeoutMs, TimeUnit.MILLISECONDS);
        log.info("支付通道超时关单已排程: orderNo={}, timeoutMs={}", orderNo, payTimeoutMs);
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("订单超时消费者未在时限内停止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (delayedQueue != null) {
            delayedQueue.destroy();
        }
        if (payDelayedQueue != null) {
            payDelayedQueue.destroy();
        }
    }
}
