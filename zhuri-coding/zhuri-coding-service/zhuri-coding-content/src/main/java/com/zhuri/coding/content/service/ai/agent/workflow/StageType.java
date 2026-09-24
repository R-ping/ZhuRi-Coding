package com.zhuri.coding.content.service.ai.agent.workflow;

/**
 * 发布预检显式工作流的阶段类型（Stages）。
 *
 * <p>把原本交由主编 Agent 临场调度的步骤显式化为代码顺序：安全/质量/SEO/查重四个
 * 独立专家并行产出 → 汇总草稿 → 终审 Critic 校验修正 → 结构化为最终 VO。
 */
public enum StageType {

    /** 安全审查：判定文章是否违规（阻断性——违规即需告警） */
    SAFETY,

    /** 质量评审：原创性/逻辑/表达综合评分 + 改进建议 */
    QUALITY,

    /** SEO/运营：提炼标签与摘要 */
    SEO,

    /** 查重：检索社区最相似已发布文章 */
    DUPLICATE,

    /** 终审 Critic：汇总各阶段产物，检查一致性与完整性并修正（阻断性） */
    CRITIC,

    /** 结构化：把各阶段产物组装为最终 {@code AiPrecheckVo} */
    FORMAT
}