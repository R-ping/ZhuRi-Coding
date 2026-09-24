package com.zhuri.coding.content.service.ai.agent.workflow;

/**
 * 显式工作流中的单个阶段执行器。
 *
 * <p>把预检拆解为按序（或并行）执行的阶段；每阶段接收共享的 {@link StageContext}，
 * 从中读取前序阶段产物、写入本阶段产物。实现方负责内部异常兜底：
 * 非阻断阶段捕获异常自降级为 {@link StageResult.Status#SKIPPED}，阻断性阶段（安全/终审）
 * 捕获异常返回 {@link StageResult.Status#FAILED} 交由编排器整体降级。
 */
public interface Stage {

    /** 该阶段对应的类型（决定产物归档 key） */
    StageType type();

    /**
     * 执行本阶段。
     *
     * @param ctx 共享阶段上下文（可读写，内部携带 title/content/各阶段产物）
     * @return 本阶段结果（已写入中的产物由实现方承担，此处为可累积的汇总信息）
     */
    StageResult execute(StageContext ctx);
}