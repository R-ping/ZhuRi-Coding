package com.zhuri.coding.content.service.outbox;

/**
 * 「本次失败不值得消耗重试配额」的信号。
 *
 * <p><b>存在理由</b>：绝大多数失败都应该计次（重试有限，防止无限循环）。但有一类失败是
 * <b>暂时性竞态</b>，计次反而不合适 —— 典型场景见 {@code ArticlePublishHandler}：
 * 延迟任务的锚点事务尚未提交时，「置文章为已发布」的条件更新会返回 0 行，
 * 此时文章仍处于待审态、过一会儿自然就能成功。把它计入重试配额，
 * 等于让一个毫秒级的时序问题去挤占真正的故障重试预算。
 *
 * <p>Handler 抛出本异常后，{@link OutboxDispatcher} 的处理是：
 * <b>只把事件退回 {@code PENDING} 并按固定间隔排程，不递增 {@code retry_count}</b>。
 *
 * <p><b>⚠️ 与生命周期护栏配套使用</b>：抛出本异常意味着"这类失败不计数"，
 * 若不配时间上限，"不计数重试"就会退化成永不收敛的无限重试。
 * 因此使用本异常的 Handler <b>必须同时声明</b>
 * {@link OutboxHandler#maxLifetimeMinutes()}（返回正数），由 Dispatcher 兜底判死。
 */
public class RetryWithoutCountingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetryWithoutCountingException(String message) {
        super(message);
    }
}
