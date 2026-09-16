package com.heima.content.service.ai.skill;

/**
 * AI 可复用技能（Skill）模块（P2：把「安全审查 / 质量评审 / 格式化输出」沉淀为跨会话、跨流程可复用与组合的原子能力）。
 *
 * <p>设计动机：专家 Worker 是「面向主编 Agent 的工具」，Skill 是「面向任意调用方（Agent 工具、后台审核、
 * 兜底链路）的原子能力」——把逻辑从 Worker 中沉淀出来，Worker 退化为薄门面，非 Agent 流程也能直接复用。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #id()} 全局唯一，注册表按此检索；</li>
 *   <li>{@link #execute(SkillContext)} 实现必须 <b>fail-open</b>：内部失败不得抛给调用方
 *       （返回 null / 空串由调用方决定降级策略），与「AI 旁路不得阻断主链路」的全局口径一致；</li>
 *   <li>Skill 可组合：一个 Skill 的 execute 内部也可委托其它 Skill（例如安全 Skill 内部复用 JsonOutputSkill）。</li>
 * </ul>
 */
public interface AiSkill {

    /** 全局唯一技能 id（如 article_safety / article_quality / json_output） */
    String id();

    /** 人类可读名称 */
    String name();

    /** 一句话能力描述（注册表展示 / 日志归因用） */
    String description();

    /** 执行技能；失败返回 null/默认值，不抛异常（fail-open） */
    Object execute(SkillContext ctx);

    /**
     * 技能执行上下文：透明传参的只读 record。
     *
     * @param title          素材标题（安全/质量评审用）
     * @param content        素材正文（安全/质量评审用）
     * @param machineResult  预置的机器检测结果（安全评审可选；null 则由 Skill 自行检测）
     * @param promptKey      Prompt 注册表 key（如 expert_safety；可为 null 表示不走注册表）
     * @param fallbackPrompt 代码兜底 prompt（注册表无行/异常时使用）
     * @param rawText        待格式化/解析的原始文本（json_output 用）
     */
    record SkillContext(String title, String content, String machineResult,
                        String promptKey, String fallbackPrompt, String rawText) {

        public static SkillContext of(String title, String content, String promptKey, String fallbackPrompt) {
            return new SkillContext(title, content, null, promptKey, fallbackPrompt, null);
        }
    }
}