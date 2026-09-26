package com.zhuri.coding.content.service.outbox;

/**
 * Outbox 事件处理器 SPI。
 *
 * <p>实现类注册为 Spring Bean 后，{@link OutboxDispatcher} 自动按
 * {@link #eventType()} 路由 —— 新增一种异步副作用 = 新增一个 Handler，零改分发器
 * （与 BehaviorEventBus 的处理器注册模式同款）。
 *
 * <p><b>幂等要求</b>：Dispatcher 保证事件从 PENDING 抢占后只执行一次，
 * 但「执行成功 → markDone 前进程崩溃」会重新分发 —— Handler 需容忍重复执行
 * （写库类操作请以 eventKey/业务键做幂等；Feign 通知类重复发送可接受）。
 */
public interface OutboxHandler {

    /** 事件类型路由键（与 OutboxService.record 的 eventType 对应） */
    String eventType();

    /**
     * 执行事件。抛出任何异常都视为本次失败，由 Dispatcher 记入重试/死信。
     *
     * @param payload OutboxService.record 时写入的 JSON 载荷
     */
    void execute(String payload) throws Exception;

    /**
     * 重试耗尽后的终态策略，默认 {@link FailPolicy#DEAD}（保守：不静默丢弃）。
     *
     * <p>只在"本次失败即将耗尽重试次数"时被读取，实现应为**无副作用的常量返回**。
     * 需要"降级放行"的业务（如内容审核）返回 {@link FailPolicy#DEGRADE}。
     */
    default FailPolicy failPolicy() {
        return FailPolicy.DEAD;
    }

    /**
     * 重试耗尽且 {@link #failPolicy()} 为 {@link FailPolicy#DEGRADE} 时回调，用于业务降级。
     *
     * <p>约定：本方法应尽最大努力完成"保持原状"的降级动作（例如让内容保持可见），
     * 且**必须容忍重复执行**——调用后事件会被置为 DONE，但进程若在置位前崩溃，事件仍会重新分发。
     *
     * @param payload   事件载荷
     * @param lastError 最后一次失败原因（可能为 null）
     */
    default void onExhausted(String payload, String lastError) {
        // 默认无降级动作：配合默认策略 DEAD 使用，正常情况下不会走到这里
    }
}
