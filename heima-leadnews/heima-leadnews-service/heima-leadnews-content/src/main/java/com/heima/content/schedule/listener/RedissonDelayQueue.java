package com.heima.content.schedule.listener;

import com.heima.content.event.RedissonDelayTaskEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RedissonDelayQueue {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final ExecutorService executorService;
    // 管理多个队列，key=queueName
    private final Map<String, RBlockingQueue<String>> blockingQueues = new ConcurrentHashMap<>();
    private final Map<String, RDelayedQueue<String>> delayedQueues = new ConcurrentHashMap<>();

    public RedissonDelayQueue() {
        executorService = new ThreadPoolExecutor(
            10, 20, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20)
        );
    }

    @PostConstruct
    public void init() {
        // 启动默认队列的消费者
        startConsumer("TASK_FIRST_EXECUTE_DELAY_QUEUE");
        startConsumer("TASK_LAST_EXECUTE_DELAY_QUEUE");
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down RedissonDelayQueue...");
        // 关闭所有延迟队列（不关闭全局 RedissonClient）
        for (Map.Entry<String, RDelayedQueue<String>> entry : delayedQueues.entrySet()) {
            try {
                entry.getValue().destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy delayed queue: {}", entry.getKey(), e);
            }
        }
        delayedQueues.clear();
        blockingQueues.clear();

        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Some tasks did not terminate in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取或创建阻塞队列 + 延迟队列
     */
    private void ensureQueue(String queueName) {
        if (!blockingQueues.containsKey(queueName)) {
            RBlockingQueue<String> blockingQueue = redissonClient.getBlockingQueue(queueName);
            RDelayedQueue<String> delayedQueue = redissonClient.getDelayedQueue(blockingQueue);
            blockingQueues.put(queueName, blockingQueue);
            delayedQueues.put(queueName, delayedQueue);
            log.info("Redisson queue initialized: {}", queueName);
        }
    }

    /**
     * 启动队列消费者线程
     * 消费到任务后发布 RedissonDelayTaskEvent，由监听器处理具体业务逻辑，
     * 从而与 ApArticleService、TaskService 解耦，打破循环依赖。
     */
    private void startConsumer(String queueName) {
        ensureQueue(queueName);
        RBlockingQueue<String> blockingQueue = blockingQueues.get(queueName);

        executorService.submit(() -> {
            Thread.currentThread().setName("redisson-consumer-" + queueName);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String taskJson = blockingQueue.take();
                    log.info("消费延迟任务，queueName={}", queueName);
                    // 发布事件，由监听器异步处理
                    eventPublisher.publishEvent(new RedissonDelayTaskEvent(queueName, taskJson));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("RedissonDelayQueue consumer error, queueName={}", queueName, e);
                }
            }
        });
        log.info("RedissonDelayQueue consumer started for: {}", queueName);
    }

    /**
     * 添加延迟任务到指定队列
     */
    public void addTask(String queueName, String task, long delay) {
        ensureQueue(queueName);
        RDelayedQueue<String> delayedQueue = delayedQueues.get(queueName);
        delayedQueue.offer(task, delay, TimeUnit.MILLISECONDS);
        log.info("Task added to queue={}, delay={}ms", queueName, delay);
    }
}
