package com.zhuri.coding.content.service.ai.agent.workers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 终审专家 Worker（Evaluator-Optimizer 工作流 · 评审-优化角色）
 *
 * <p>职责：主编把各专家产出汇总的预检草稿交给本专家做一次终审（Critique）：
 * 检查字段完整性、一致性、明显错误，输出修正后的完整 JSON。
 *
 * <p>输入为预检草稿 JSON，输出仍为同结构的完整 JSON，字段与 AiPrecheckVo 对齐：
 * {"is_violation":false,"violation_type":"","violation_reason":"","quality_score":0,"is_tech":true,
 *  "suggestions":[...],"tags":[...],"summary":"..."}
 */
@Component
public class CriticExpertWorker extends ExpertWorkerBase {

    public CriticExpertWorker(@Qualifier("aiExpertChatClient") ChatClient chatClient) {
        super(chatClient);
    }

    private static final String SYSTEM_PROMPT =
        "你是内容社区《逐日 Coding》主编指派的总编辑审核专家（终审）。\n\n" +
        "你会收到一份由其他专家共同产出的文章预检草稿 JSON，请以总编视角复查，仅输出一个 JSON 对象（不要多余文字/markdown）：\n" +
        "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\",\"quality_score\":0,\"is_tech\":true," +
        "\"suggestions\":[\"建议1\",\"建议2\"],\"tags\":[\"标签1\",\"标签2\",\"标签3\"],\"summary\":\"不超过120字摘要\"}\n\n" +
        "复查要点：\n" +
        "1. 违规判断与理由是否自洽（如 is_violation=false 但 violation_type 非空 → 修正）；\n" +
        "2. quality_score 是否明显不合理（与全文质量不符 → 校准到合理区间）；\n" +
        "3. tags 是否空泛/与主题无关，summary 是否准确覆盖全文核心；\n" +
        "4. suggestions 是否具体可执行；字段必须齐全，禁止返回 null 或缺失字段。\n" +
        "若无明显问题，按原样返回完整 JSON；有问题则输出修正后的完整 JSON。";

    @Tool(name = "expert_critic",
          description = "终审专家：复查并修正确认预检草稿的一致性与完整性，输出同结构的完整 JSON")
    public String review(@ToolParam(description = "待终审的预检草稿 JSON") String reviewDraft) {
        String user = "请终审以下预检草稿，输出修正后的完整 JSON：\n" + reviewDraft;
        return askExpert(prompt("expert_critic", SYSTEM_PROMPT).content, user);
    }
}