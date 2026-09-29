package com.zhuri.coding.content.service.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhuri.coding.content.mapper.outbox.OutboxEventMapper;
import com.zhuri.coding.content.service.outbox.localmsg.LocalMessageReplayRegistry;
import com.zhuri.coding.model.outbox.pojos.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p><b>事件执行从哪来</b>：按 {@code event_type} 到 {@link LocalMessageReplayRegistry}
 * 取 {@code @LocalMessage} 标注方法的反射重放器 —— 新增一种异步副作用 =
 * 在业务方法上打一个注解，本类零改动。
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
    private final LocalMessageReplayRegistry replayRegistry;

    /** 指标：直接注册为 Micrometer Counter，暴露到 /actuator/prometheus 供 Prometheus 抓取与告警 */
    private final Counter dispatchTotal;
    private final Counter doneTotal;
    private final Counter failTotal;
    private final Counter deadTotal;
    /** 重试耗尽后按 DEGRADE / DISCARD 收尾的次数（非成功，必须与 doneTotal 区分统计） */
    private final Counter degradedTotal;
    /** 「不计数重试」的次数（暂时性竞态，不消耗重试配额） */
    private final Counter noCountRetryTotal;
    /** 因超过生命周期上限被强制判死的次数（属人工介入范畴，必须单独观测） */
    private final Counter lifetimeKilledTotal;

    @Autowired
    public OutboxDispatcher(OutboxEventMapper outboxEventMapper,
                            OutboxService outboxService,
                            LocalMessageReplayRegistry replayRegistry,
                            MeterRegistry meterRegistry) {
        this.outboxEventMapper = outboxEventMapper;
        this.outboxService = outboxService;
        this.replayRegistry = replayRegistry;
        // 计数器命名遵循 Prometheus 约定：outbox.dead → outbox_dead_total（可直接写告警表达式）
        this.dispatchTotal = counter(meterRegistry, "outbox.dispatch", "已分发的 Outbox 事件数");
        this.doneTotal = counter(meterRegistry, "outbox.done", "执行成功的 Outbox 事件数");
        this.failTotal = counter(meterRegistry, "outbox.fail", "执行失败（含重试中）的 Outbox 事件数");
        this.deadTotal = counter(meterRegistry, "outbox.dead", "重试超限进死信的事件数（需人工介入）");
        this.degradedTotal = counter(meterRegistry, "outbox.degraded", "重试耗尽后按 DEGRADE / DISCARD 收尾的次数");
        this.noCountRetryTotal = counter(meterRegistry, "outbox.no_count_retry", "不计数重试次数");
        this.lifetimeKilledTotal = counter(meterRegistry, "outbox.lifetime_killed", "超生命周期上限被强制判死的次数");
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    @PostConstruct
    void logRegisteredHandlers() {
        // 注意：此处打印的是「截至本 bean 初始化完成」已注册的事件类型，
        // 晚于本 bean 初始化的 @LocalMessage 方法不含在内 —— 完整清单以启动结束后的日志为准
        log.info("OutboxDispatcher 已注册 {} 个事件类型: {}", replayRegistry.registeredTypes().size(),
                replayRegistry.registeredTypes());
    }

    /** 每 5 秒一轮；initialDelay 避开启动高峰 */
    @Scheduled(fixedDelay = 5000, initialDelay = 10_000)
    public void dispatch() {
        try {
            // 先做生命周期判定：它不依赖本轮的待处理批次（批次为空时也须执行，
            // 否则一批事件全处于 PENDING 等退避时，护栏就不会被触发）
            enforceLifetime();
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

    /**
     * 生命周期护栏：把超过 Handler 声明上限仍未完成的事件强制判死。
     *
     * <p><b>为什么必须有它</b>：Handler 抛 {@link RetryWithoutCountingException} 时，
     * 事件<b>不消耗重试配额</b>，因此永远不会因「重试超限」而收敛 ——
     * 缺少时间维度的兜底，它就是一个永不结束的重试循环。
     *
     * <p><b>为什么按 Handler 遍历、而不是一条全局 SQL</b>：{@link OutboxHandler#maxLifetimeMinutes()}
     * 默认为 0（不限）。若全局执行，会把语义上「必须完成、或等人工介入」的事件（如支付副作用）
     * 一并误杀 —— 护栏反而成了故障源。
     *
     * <p>可见性为包级，便于单测直接覆盖。
     */
    void enforceLifetime() {
        Date now = new Date();
        for (String eventType : replayRegistry.registeredTypes()) {
            OutboxHandler handler = replayRegistry.forType(eventType);
            if (handler == null) {
                continue;
            }
            int limitMinutes = handler.maxLifetimeMinutes();
            if (limitMinutes <= 0) {
                continue; // 未声明上限：不参与判定（等人工介入）
            }
            Date deadline = new Date(now.getTime() - limitMinutes * 60_000L);
            int killed = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                    .eq(OutboxEvent::getEventType, eventType)
                    .notIn(OutboxEvent::getStatus, OutboxEvent.STATUS_DONE, OutboxEvent.STATUS_DEAD)
                    .lt(OutboxEvent::getCreatedTime, deadline)
                    .set(OutboxEvent::getStatus, OutboxEvent.STATUS_DEAD)
                    .set(OutboxEvent::getLastError, "超过生命周期上限(" + limitMinutes + " 分钟)未完成，强制判死")
                    .set(OutboxEvent::getUpdatedTime, now));
            if (killed > 0) {
                lifetimeKilledTotal.increment(killed);
                log.error("[OUTBOX-LIFETIME] {} 条 {} 事件超过 {} 分钟生命周期上限，已强制置 DEAD，需人工确认",
                        killed, eventType, limitMinutes);
            }
        }
    }

    /** 单条事件分发：CAS 抢占 → 路由执行 → markDone/markFailed，任何异常不影响其余事件 */
    void safeDispatchOne(OutboxEvent event) {
        dispatchTotal.increment();
        // 声明在 try 之外：catch 分支需要按 Handler 声明的失败策略收尾
        OutboxHandler handler = null;
        try {
            if (!casClaim(event)) {
                return; // 被其它实例抢走，跳过
            }
            handler = replayRegistry.forType(event.getEventType());
            if (handler == null) {
                // 配置错误（没注册 handler）：按失败走重试/死信，ERROR 暴露
                log.error("[OUTBOX] 未找到事件处理器: type={}, eventKey={}",
                        event.getEventType(), event.getEventKey());
                failTotal.increment();
                outboxService.markFailed(event, "No handler for eventType=" + event.getEventType());
                return;
            }
            handler.execute(event.getPayload());
            outboxService.markDone(event.getId());
            doneTotal.increment();
            log.info("Outbox 事件执行成功: id={}, eventKey={}, type={}",
                    event.getId(), event.getEventKey(), event.getEventType());
        } catch (Exception e) {
            failTotal.increment();
            if (e instanceof DeadSignal) {
                // handler 内部明确判死（如业务校验永久失败）：直接置 DEAD，不再重试
                deadTotal.increment();
                markDead(event, e.getMessage());
                return;
            }
            String reason = String.valueOf(e.getMessage());
            if (e instanceof RetryWithoutCountingException) {
                // 暂时性竞态（如延迟任务的锚点事务尚未提交）：退回待处理但不消耗重试配额。
                // 必须排在 willExhaust 之前 —— 这类失败压根不参与「是否超限」的计算，
                // 它的收敛由 Handler 声明的生命周期护栏兜底（见 enforceLifetime）。
                noCountRetryTotal.increment();
                outboxService.markRetryWithoutCounting(event, reason);
                return;
            }
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
     */
    private void handleExhausted(OutboxEvent event, OutboxHandler handler, String reason) {
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
                degradedTotal.increment();
                outboxService.markExhaustedDone(event, reason);
            }
            case DISCARD -> {
                degradedTotal.increment();
                outboxService.markExhaustedDone(event, reason);
                log.warn("[OUTBOX] 事件按 DISCARD 策略丢弃: eventKey={}, type={}",
                        event.getEventKey(), event.getEventType());
            }
            default -> {
                deadTotal.increment();
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

    /** 指标快照（运维/自检用；与 /actuator/prometheus 同源） */
    public Map<String, Long> metricsSnapshot() {
        Map<String, Long> m = new ConcurrentHashMap<>();
        m.put("dispatchTotal", (long) this.dispatchTotal.count());
        m.put("doneTotal", (long) this.doneTotal.count());
        m.put("failTotal", (long) this.failTotal.count());
        m.put("deadTotal", (long) this.deadTotal.count());
        m.put("degradedTotal", (long) this.degradedTotal.count());
        m.put("noCountRetryTotal", (long) this.noCountRetryTotal.count());
        m.put("lifetimeKilledTotal", (long) this.lifetimeKilledTotal.count());
        return m;
    }

    /** Handler 抛出此异常表示业务上永久失败，直接进死信不再重试 */
    public static class DeadSignal extends RuntimeException {
        public DeadSignal(String message) {
            super(message);
        }
    }
}
