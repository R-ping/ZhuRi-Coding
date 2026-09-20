package com.heima.content.service.ai.agent.workers;

import com.heima.content.service.ai.skill.AiSkill;
import com.heima.content.service.ai.skill.AiSkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 安全审查专家 Worker（多智能体发布助手 · Worker 之一）
 *
 * <p>职责：基于标题与正文判定内容是否违规。逻辑已沉淀为可复用 Skill（{@code article_safety}，
 * 机械检测 + LLM 终审裁定），本 Worker 退化为薄门面——主编 Agent 的工具外观与 Promise 不变，
 * 非 Agent 流程（后台批量审核）可直接复用同一 Skill。
 *
 * <p>输出 JSON（严格）：{"is_violation":boolean,"violation_type":"","violation_reason":""}
 */
@Component
public class SafetyExpertWorker extends ExpertWorkerBase {

    private final AiSkillRegistry skillRegistry;

    public SafetyExpertWorker(@Qualifier("aiExpertChatClient") ChatClient chatClient,
                              AiSkillRegistry skillRegistry) {
        super(chatClient);
        this.skillRegistry = skillRegistry;
    }

    private static final String SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》的内容安全审查专家。作者提交文章发布前的合规裁定任务。\n\n" +
        "你已收到机器检测结果与文章全文，请以资深审核员视角做最终裁定，仅输出一个 JSON 对象（不要多余文字/markdown）：\n" +
        "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}\n\n" +
        "规则：\n" +
        "1. is_violation=true 仅当内容属于色情低俗/赌博/诈骗/毒品/暴力教唆/政治敏感/违法/辱骂造谣等明确违规；\n" +
        "2. 客观技术讨论（漏洞分析、渗透测试、安全科普、行业新闻、黑产原理科普）一律不算违规；\n" +
        "3. 机器检测结果仅作参考，你的最终裁定可修正它；若机器的 is_violation 与全文明显不符须在 violation_reason 说明。";

    @Tool(name = "expert_safety",
          description = "安全审查专家：基于标题与正文判定文章是否违规，返回 {\"is_violation\":true/false,\"violation_type\":\"\",\"violation_reason\":\"\"}")
    public String review(
            @ToolParam(description = "文章标题") String title,
            @ToolParam(description = "文章正文内容") String content) {
        AiSkill skill = skillRegistry == null ? null : skillRegistry.get("article_safety").orElse(null);
        if (skill != null) {
            Object r = skill.execute(AiSkill.SkillContext.of(title, content, "expert_safety", SYSTEM_PROMPT));
            return r == null ? "" : String.valueOf(r);
        }
        // 兜底（Skill 未装配）：直接 LLM 裁定（不含机械检测，口径略降但流程可用）
        String user = "标题：" + title + "\n\n正文：\n" + content + "\n\n请完成安全裁定，仅输出 JSON。";
        return askExpert(SYSTEM_PROMPT, user);
    }
}