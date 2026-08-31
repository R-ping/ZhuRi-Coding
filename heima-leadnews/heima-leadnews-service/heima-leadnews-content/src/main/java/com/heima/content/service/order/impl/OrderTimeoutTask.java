package com.heima.content.service.order.impl;

import com.heima.content.service.order.OrderService;
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

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderService orderService;

    /** 订单超时时间（毫秒），默认 30 分钟，可通过 app.order.timeout-ms 配置 */
    @Value("${app.order.timeout-ms:1800000}")
    private long orderTimeoutMs;

    private RBlockingQueue<String> blockingQueue;
    private RDelayedQueue<String> delayedQueue;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "order-timeout-consumer");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        blockingQueue = redissonClient.getBlockingQueue(ORDER_TIMEOUT_DELAY_QUEUE);
        delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
        executor.submit(this::consume);
        log.info("订单超时关单消费者已启动, timeoutMs={}", orderTimeoutMs);
    }

    /** 阻塞消费：取出订单号并执行幂等关单 */
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

    /** 下单后排程超时关单 */
    public void scheduleClose(String orderNo) {
        if (orderNo == null || orderNo.isEmpty()) {
            return;
        }
        delayedQueue.offer(orderNo, orderTimeoutMs, TimeUnit.MILLISECONDS);
        log.info("订单超时关单已排程: orderNo={}, timeoutMs={}", orderNo, orderTimeoutMs);
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
    }
}
