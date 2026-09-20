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
}
