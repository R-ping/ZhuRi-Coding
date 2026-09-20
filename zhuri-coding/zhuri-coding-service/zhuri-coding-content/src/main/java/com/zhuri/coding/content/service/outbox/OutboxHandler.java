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
}
