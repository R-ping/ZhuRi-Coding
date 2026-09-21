package com.zhuri.coding.content.service.ai.agent.workflow;

/**
 * 单阶段执行结果载体。
 *
 * <p>任一阶段（无论专家直调或 LLM 调用）都产出结构化 {@code StageResult}，交由
 * {@link PrecheckWorkflow} 汇总：状态区分「成功/跳过/失败」——失败的非阻断阶段仅
 * SKIPPED，不影响整体；阻断性阶段（安全/终审）失败则触发整体降级。
 *
 * @param stage   所属阶段类型
 * @param status  执行状态
 * @param payload 阶段产物（各专家返回的 JSON 字符串，或已解析的对象）
 * @param latencyMs 单阶段耗时（毫秒）
 */
public record StageResult(StageType stage, Status status, Object payload, long latencyMs) {

    /** 阶段执行状态 */
    public enum Status {
        /** 成功产出 payload */
        OK,
        /** 跳过：非阻断阶段失败，不影响整体继续 */
        SKIPPED,
        /** 失败：阻断性阶段失败，触发整体降级 */
        FAILED
    }

    public static StageResult ok(StageType stage, Object payload, long latencyMs) {
        return new StageResult(stage, Status.OK, payload, latencyMs);
    }

    public static StageResult skipped(StageType stage, long latencyMs) {
        return new StageResult(stage, Status.SKIPPED, null, latencyMs);
    }

    public static StageResult failed(StageType stage, long latencyMs) {
        return new StageResult(stage, Status.FAILED, null, latencyMs);
    }
}