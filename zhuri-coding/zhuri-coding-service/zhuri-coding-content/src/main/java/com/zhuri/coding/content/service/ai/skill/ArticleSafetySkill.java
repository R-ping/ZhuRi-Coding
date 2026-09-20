package com.heima.content.service.ai.skill;

import com.heima.content.service.ai.AiFeatures;
import com.heima.content.service.ai.AiLlmGateway;
import com.heima.content.service.ai.AiPromptRegistry;
import com.heima.content.service.ai.agent.tools.ContentSafetyTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文章安全审查 Skill（id=article_safety）。
 *
 * <p>能力：机器检测（{@link ContentSafetyTool}，事实优先）+ LLM 安全专家最终裁定（Prompt 注册表
 * {@code expert_safety} 可灰度/回滚，代码常量 Version=0 兜底）。原逻辑沉淀自 {@code SafetyExpertWorker}，
 * 现在任意调用方（Agent 工具 / 后台批量审核）都可复用同一份裁定口径。
 *
 * <p>输出：模型原文（严格 JSON），解析失败时透传原文由调用方兜底。fail-open：
 * LLM 不可用时降级返回机器检测结果 JSON 作为裁定依据（仍可读），不抛异常。
 */
@Slf4j
@Component
public class ArticleSafetySkill implements AiSkill {

    private final ContentSafetyTool contentSafetyTool;
    private final AiLlmGateway llmGateway;
    private final AiPromptRegistry promptRegistry;

    public ArticleSafetySkill(ContentSafetyTool contentSafetyTool,
                              AiLlmGateway llmGateway,
                              AiPromptRegistry promptRegistry) {
        this.contentSafetyTool = contentSafetyTool;
        this.llmGateway = llmGateway;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String id() {
        return "article_safety";
    }

    @Override
    public String name() {
        return "文章安全审查";
    }

    @Override
    public String description() {
        return "机械安全检测 + LLM 终审裁定：判定稿件是否含违规内容（客观技术讨论不算违规）";
    }

    @Override
    public Object execute(SkillContext ctx) {
        String title = ctx == null ? "" : (ctx.title() == null ? "" : ctx.title());
        String content = ctx == null ? "" : (ctx.content() == null ? "" : ctx.content());
        try {
            String machineResult = contentSafetyTool.execute(
                "{\"title\":\"" + safe(title) + "\",\"content\":\"" + safe(content) + "\"}");
            String user = "标题：" + title + "\n\n正文：\n" + content
                + "\n\n机器检测结果：\n" + machineResult
                + "\n\n请基于机器结果与全文做出最终裁定，仅输出 JSON。";
            String sys = prompt(ctx == null ? null : ctx.promptKey(),
                    ctx == null ? null : ctx.fallbackPrompt()).content;
            String raw = llmGateway.generateOrNull(AiFeatures.PRECHECK, sys, user, null, null);
            if (raw != null && !raw.isBlank()) {
                return raw.trim();
            }
            // LLM 不可用：把机器检测结果原样返回（至少保留可读的检出结论）
            log.warn("[AiSkill-{}] LLM 裁定不可用，降级返回机器检测结果", id());
            return machineResult;
        } catch (Exception e) {
            log.warn("[AiSkill-{}] 执行异常，fail-open", id(), e);
            return "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}";
        }
    }

    /** Prompt 注册表解析（带 null 兜底，与 Worker 口径一致） */
    private AiPromptRegistry.ResolvedPrompt prompt(String key, String fallback) {
        if (promptRegistry == null || key == null || fallback == null) {
            return new AiPromptRegistry.ResolvedPrompt(key == null ? id() : key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, null);
        } catch (Exception e) {
            return new AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}