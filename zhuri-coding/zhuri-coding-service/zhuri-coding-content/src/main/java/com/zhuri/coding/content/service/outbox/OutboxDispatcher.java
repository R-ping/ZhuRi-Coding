package com.zhuri.coding.content.service.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * Outbox 事件分发器：定时把 PENDING 事件执行到 DONE。
 *
 * <p><b>多实例安全</b>：不依赖分布式锁 —— 每条事件执行前用单条 CAS
 * （{@code UPDATE ... SET status=PROCESSING WHERE id=? AND status=PENDING}）抢占，
 * 多实例同时扫到同一事件时只有 CAS 命中的那台执行，其余跳过。
 * 执行中崩溃的 PROCESSING 事件由 {@link #PROCESSING_STUCK_MINUTES} 超时回收重新分发。
 *
 * <p><b>Handler 路由</b>：构造期收集所有 {@link OutboxHandler} Bean 按 eventType 建索引；
 * 新增一种异步副作用 = 新增一个 Handler 实现，零改本类。
 */
@Component
@Slf4j
public class OutboxDispatcher {

    /** 每轮分发的最大事件数 */
    static final int BATCH_SIZE = 20;

    /** PROCESSING 卡死回收阈值（分钟）：超过视为执行实例已崩溃 */
    static final int PROCESSING_STUCK_MINUTES = 5;

    private final OutboxEventMapper outboxEventMapper;
    private final OutboxService outboxService;
    private final Map<String, OutboxHandler> handlerIndex;

    /** 轻量内存指标（与 AiMetricsCollector 同款；生产可平滑迁 Micrometer） */
    private final AtomicLong dispatchTotal = new AtomicLong();
    private final AtomicLong doneTotal = new AtomicLong();
    private final AtomicLong failTotal = new AtomicLong();
    private final AtomicLong deadTotal = new AtomicLong();
    /** 重试耗尽后按 DEGRADE / DISCARD 收尾的次数（非成功，必须与 doneTotal 区分统计） */
    private final AtomicLong degradedTotal = new AtomicLong();

    @Autowired
    public OutboxDispatcher(OutboxEventMapper outboxEventMapper,
                            OutboxService outboxService,
                            List<OutboxHandler> handlers) {
        this.outboxEventMapper = outboxEventMapper;
        this.outboxService = outboxService;
        this.handlerIndex = handlers == null ? Map.of() : handlers.stream()
                .collect(Collectors.toMap(OutboxHandler::eventType, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Duplicate OutboxHandler eventType: "
                            + a.eventType());
                }));
    }

    @PostConstruct
    void logRegisteredHandlers() {
        log.info("OutboxDispatcher 已注册 {} 个处理器: {}", handlerIndex.size(), handlerIndex.keySet());
    }

    /** 每 5 秒一轮；initialDelay 避开启动高峰 */
    @Scheduled(fixedDelay = 5000, initialDelay = 10_000)
    public void dispatch() {
        try {
            List<OutboxEvent> batch = listDispatchable(BATCH_SIZE);
            if (CollectionUtils.isEmpty(batch)) {
                return;
            }
            for (OutboxEvent event : batch) {
                safeDispatchOne(event);
            }
        } catch (Exception e) {
            // 调度器自身异常不能终止 @Scheduled 心跳
            log.error("Outbox dispatch 轮次异常", e);
        }
    }

    /** 单条事件分发：CAS 抢占 → 路由执行 → markDone/markFailed，任何异常不影响其余事件 */
    void safeDispatchOne(OutboxEvent event) {
        dispatchTotal.incrementAndGet();
        // 声明在 try 之外：catch 分支需要按 Handler 声明的失败策略收尾
        OutboxHandler handler = null;
        try {
            if (!casClaim(event)) {
                return; // 被其它实例抢走，跳过
            }
            handler = handlerIndex.get(event.getEventType());
            if (handler == null) {
                // 配置错误（没注册 handler）：按失败走重试/死信，ERROR 暴露
                log.error("[OUTBOX] 未找到事件处理器: type={}, eventKey={}",
                        event.getEventType(), event.getEventKey());
                failTotal.incrementAndGet();
                outboxService.markFailed(event, "No handler for eventType=" + event.getEventType());
                return;
            }
            handler.execute(event.getPayload());
            outboxService.markDone(event.getId());
            doneTotal.incrementAndGet();
            log.info("Outbox 事件执行成功: id={}, eventKey={}, type={}",
                    event.getId(), event.getEventKey(), event.getEventType());
        } catch (Exception e) {
            failTotal.incrementAndGet();
            if (e instanceof DeadSignal) {
                // handler 内部明确判死（如业务校验永久失败）：直接置 DEAD，不再重试
                deadTotal.incrementAndGet();
                markDead(event, e.getMessage());
                return;
            }
            String reason = String.valueOf(e.getMessage());
            // 本次失败即耗尽重试 → 按 Handler 声明的终态策略收尾，不再排程重试
            // （handler 为 null 只可能是未注册 handler 的配置错误，此时没有策略可查，走常规重试/死信）
            if (handler != null && outboxService.willExhaust(event)) {
                handleExhausted(event, handler, reason);
                return;
            }
            outboxService.markFailed(event, reason);
        }
    }

    /**
     * 重试耗尽的终态处理：按 {@link OutboxHandler#failPolicy()} 决定收尾方式。
     *
     * <p>三种策略的差别只在于「失败方向的错哪边更不可接受」，但实现上都保证同一件事：
     * <b>事件一定离开 PENDING</b>，不会永远卡在重试循环里。
     *
     * <p>可见性为包级（而非 private）是为了让单测直接覆盖策略分派：{@link #safeDispatchOne}
     * 全流程依赖 CAS 抢占，而 CAS 用到的 {@code LambdaUpdateWrapper} 需要 MyBatis-Plus 的
     * lambda 缓存（由 Spring/MyBatis 上下文初始化），纯单测环境不可用。
     */
    void handleExhausted(OutboxEvent event, OutboxHandler handler, String reason) {
        switch (handler.failPolicy()) {
            case DEGRADE -> {
                // 降级放行：先执行业务降级动作，再置 DONE。
                // onExhausted 自身异常不改变终态判定 —— 此时已明确不再重试，
                // 若因回调异常抛出去，会被误当作"普通失败"重新排程，退化成无限重试。
                try {
                    handler.onExhausted(event.getPayload(), reason);
                } catch (Exception ex) {
                    log.error("[OUTBOX] onExhausted 回调异常，仍按降级收尾: eventKey={}, type={}",
                            event.getEventKey(), event.getEventType(), ex);
                }
                degradedTotal.incrementAndGet();
                outboxService.markExhaustedDone(event, reason);
            }
            case DISCARD -> {
                degradedTotal.incrementAndGet();
                outboxService.markExhaustedDone(event, reason);
                log.warn("[OUTBOX] 事件按 DISCARD 策略丢弃: eventKey={}, type={}",
                        event.getEventKey(), event.getEventType());
            }
            default -> {
                deadTotal.incrementAndGet();
                // 复用 markFailed：内部同样判定为耗尽 → 置 DEAD + ERROR 告警
                outboxService.markFailed(event, reason);
            }
        }
    }

    /**
     * CAS 抢占：PENDING → PROCESSING；PROCESSING 超时回收行 → 保持 PROCESSING 刷新时间。
     * 只有抢占成功的实例执行事件，多实例天然去重。
     */
    private boolean casClaim(OutboxEvent event) {
        LambdaUpdateWrapper<OutboxEvent> claim = new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .eq(OutboxEvent::getStatus, event.getStatus())
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_PROCESSING)
                .set(OutboxEvent::getUpdatedTime, new Date());
        return outboxEventMapper.update(null, claim) == 1;
    }

    private void markDead(OutboxEvent event, String reason) {
        outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_DEAD)
                .set(OutboxEvent::getLastError, reason)
                .set(OutboxEvent::getUpdatedTime, new Date()));
        log.error("[OUTBOX-DEAD] 事件被判定死信: id={}, eventKey={}, type={}, reason={}",
                event.getId(), event.getEventKey(), event.getEventType(), reason);
    }

    /** 可分发事件：到期 PENDING + 卡死 PROCESSING */
    private List<OutboxEvent> listDispatchable(int limit) {
        Date now = new Date();
        Date stuckBefore = new Date(now.getTime() - PROCESSING_STUCK_MINUTES * 60_000L);
        LambdaQueryWrapper<OutboxEvent> query = new LambdaQueryWrapper<OutboxEvent>()
                .and(w -> w
                        // 到期待重试的 PENDING（首次无 next_retry_at 视为立即到期）
                        .and(p -> p.eq(OutboxEvent::getStatus, OutboxEvent.STATUS_PENDING)
                                .and(n -> n.isNull(OutboxEvent::getNextRetryAt)
                                        .or().le(OutboxEvent::getNextRetryAt, now)))
                        // 执行中崩溃的 PROCESSING（超时回收）
                        .or(pr -> pr.eq(OutboxEvent::getStatus, OutboxEvent.STATUS_PROCESSING)
                                .lt(OutboxEvent::getUpdatedTime, stuckBefore)))
                .orderByAsc(OutboxEvent::getId)
                .last("LIMIT " + limit);
        return outboxEventMapper.selectList(query);
    }

    /** 指标快照（运维/自检用） */
    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        m.put("dispatchTotal", dispatchTotal.get());
        m.put("doneTotal", doneTotal.get());
        m.put("failTotal", failTotal.get());
        m.put("deadTotal", deadTotal.get());
        m.put("degradedTotal", degradedTotal.get());
        return m;
    }

    /** Handler 抛出此异常表示业务上永久失败，直接进死信不再重试 */
    public static class DeadSignal extends RuntimeException {
        public DeadSignal(String message) {
            super(message);
        }
    }
}
