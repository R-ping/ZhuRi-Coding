package com.zhuri.coding.content.service.outbox;

import com.zhuri.coding.model.outbox.pojos.OutboxEvent;

/**
 * 本地消息表（Transactional Outbox）写入与状态流转服务。
 *
 * <p><b>使用方式</b>：在业务主事务内调用 {@link #record(String, String, String)}，
 * 事件与业务变更原子提交；{@link OutboxDispatcher} 异步把事件执行到 DONE。
 * 任何「失败不影响主流程但绝不能丢」的副作用（发通知、加经验、联动打点）都适合走这里；
 * <b>资金类操作（券核销、扣款）不要走 Outbox</b> —— 那类要同步失败即补偿（见铁律 4）。
 */
public interface OutboxService {

    /**
     * 写入一条待分发事件（必须运行在业务事务内）。
     * <p>幂等：event_key 唯一键冲突时静默忽略（返回 false），不抛异常、不阻断主流程 ——
     * 典型场景：支付宝重复回调触发 handlePaySuccess 幂等短路前的重复写入。
     *
     * @param eventKey  业务幂等键，如 "PAY_REWARD:20260912..."
     * @param eventType 事件类型（OutboxHandler 路由键）
     * @param payload   JSON 载荷
     * @return true=新写入；false=event_key 已存在（幂等短路）
     */
    boolean record(String eventKey, String eventType, String payload);

    /** 标记事件执行成功（DONE） */
    void markDone(Long eventId);

    /**
     * 标记事件执行失败：retry_count+1；未达上限则回 PENDING 并按指数退避设置
     * next_retry_at（2^n 分钟，封顶 60 分钟）；达到 max_retries 置 DEAD 并 ERROR 告警。
     *
     * @param event     被执行的事件（含当前 retryCount/maxRetries）
     * @param errorReason 失败原因（截断 500 字符入库）
     */
    void markFailed(OutboxEvent event, String errorReason);

    /**
     * 判断"本次再失败一次"是否就会耗尽重试次数。
     *
     * <p>存在的意义：让「是否耗尽」的判定只定义一处（本实现），Dispatacher 据此决定
     * 走常规重试还是走终态策略（{@link FailPolicy}），避免两处各写一遍导致口径漂移。
     *
     * @param event 被执行的事件（含当前 retryCount / maxRetries）
     * @return true = 本次失败即耗尽，应由 Dispatcher 按 Handler 的策略收尾
     */
    boolean willExhaust(OutboxEvent event);

    /**
     * 终态为"降级放行 / 丢弃"时，把事件置为 DONE 并**保留最后一次失败原因**。
     *
     * <p>与 {@link #markDone(Long)} 的区别：本方法会把失败原因写入 {@code last_error}，
     * 而 markDone 是"真的成功了"。二者混用会造成可观测性盲区 ——
     * 「有多少事件是降级收尾的」将无法统计。
     *
     * @param event       被执行的事件
     * @param errorReason 最后一次失败原因（截断 500 字符入库）
     */
    void markExhaustedDone(OutboxEvent event, String errorReason);

    /**
     * 「不计数重试」：把事件退回 {@code PENDING} 并按<b>固定间隔</b>排程，
     * 但不递增 {@code retry_count}（本次失败不消耗重试配额）。
     *
     * <p>用途：Handler 抛出 {@link RetryWithoutCountingException} 时调用 ——
     * 典型是"暂时性竞态，过一会儿自然会成功"的场景。
     *
     * <p><b>为什么用固定间隔而非指数退避</b>：既然认定它是"很快会好的暂时性竞态"，
     * 就不该按退避的节奏让它越等越久。真正的收敛由 Handler 声明的
     * {@link OutboxHandler#maxLifetimeMinutes()} 兜底，而不是靠重试次数。
     *
     * @param event       被执行的事件
     * @param errorReason 失败原因（截断 500 字符入库）
     */
    void markRetryWithoutCounting(OutboxEvent event, String errorReason);
}
