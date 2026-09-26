package com.zhuri.coding.content.service.audit;

import com.zhuri.coding.model.audit.AuditResult;
import com.zhuri.coding.model.audit.pojos.ApAuditTask;

/**
 * 审核任务处理器 SPI。
 *
 * <p>统一审核任务表 {@code ap_audit_task} 承载了三个业务（文章评论 / 沸点 / 沸点评论），
 * 但它们的**调度机制完全相同**：CAS 抢占 → 执行 → 成功/退避重试/超限降级。
 * 差异只有两处，正是本接口要业务方提供的部分：
 * <ol>
 *   <li><b>怎么审</b>：{@link #audit(ApAuditTask)} —— 有的业务读任务表里的内容快照
 *       （评论类），有的回查业务表取最新内容（沸点）；</li>
 *   <li><b>终态后的业务动作</b>：{@link #onPassed} / {@link #onViolation} / {@link #onDegraded}
 *       —— 例如沸点通过后触发等级积分、评论降级放行后补发通知。</li>
 * </ol>
 *
 * <p>实现类注册为 Spring Bean 后，{@link AuditTaskDispatcher} 构造期按 {@link #bizType()} 建索引并路由，
 * 新增一种审核业务 = 新增一个 Handler + 在任务表里用一个新 bizType，零改调度层。
 *
 * <p><b>回调的容错约定</b>：三个 onXxx 回调由 Dispatcher 在**状态已落库之后**调用，
 * 因此回调抛异常不会改变任务终态（只记 ERROR 日志）；但实现应尽量做到幂等，
 * 因为"回调成功 → 进程崩溃"的场景下任务已置终态、不会重放，反之亦然。
 */
public interface AuditTaskHandler {

    /**
     * 业务类型路由键（与 {@code ApAuditTask.bizType} 取值一致，如 {@code ApAuditTask.BIZ_PINS}）。
     * <p>同一 bizType 只允许注册一个 Handler，重复注册会在启动期直接抛异常，避免静默覆盖。
     */
    String bizType();

    /**
     * 执行一次审核。
     *
     * <p>实现可选择读任务表的内容快照，或回查业务表取最新内容 —— 前者保证审核对象是"提交时的内容"，
     * 后者保证审核对象是"当前内容"，由业务语义决定。
     *
     * @param task 已抢占的任务（状态为 PROCESSING）
     * @return 审核结果；抛出 {@code AuditServiceUnavailableException} 会被视为"审核服务不可用"，
     *         走退避重试而**不误标违规**
     */
    AuditResult audit(ApAuditTask task);

    /** 审核通过后的业务动作（默认无）。 */
    default void onPassed(ApAuditTask task) {
    }

    /** 审核违规后的业务动作（默认无）。 */
    default void onViolation(ApAuditTask task) {
    }

    /**
     * 重试超限、降级放行后的业务动作（默认无）。
     *
     * <p>"降级放行"的含义是**保持内容原状**（评论继续可见、沸点不标违规）——
     * 这是刻意选择：系统故障不该造成不可逆的用户伤害。
     * 若业务需要保证"降级后与正常通过等效"（如评论要补发一条通知），
     * 就在这里补做，使降级路径与通过路径在**用户可见结果**上一致。
     */
    default void onDegraded(ApAuditTask task) {
    }
}
