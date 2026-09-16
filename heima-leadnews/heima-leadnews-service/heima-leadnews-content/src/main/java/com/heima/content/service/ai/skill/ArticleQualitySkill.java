package com.heima.content.service.ai.skill;

import com.heima.content.service.ai.AiFeatures;
import com.heima.content.service.ai.AiLlmGateway;
import com.heima.content.service.ai.AiPromptRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文章质量评审 Skill（id=article_quality）。
 *
 * <p>能力：LLM 从原创性/逻辑/表达/信息密度综合评分（0-100）并给出可执行建议——原逻辑沉淀自
 * {@code QualityExpertWorker}，现可供 Agent 工具与后台批量评审复用（Prompt 注册表 {@code expert_quality}，
 * 代码常量 Version=0 兜底）。
 *
 * <p>输出：模型原文（严格 JSON：quality_score/is_tech/suggestions）。fail-open：异常返回 null。
 */
@Slf4j
@Component
public class ArticleQualitySkill implements AiSkill {

    private final AiLlmGateway llmGateway;
    private final AiPromptRegistry promptRegistry;

    public ArticleQualitySkill(AiLlmGateway llmGateway, AiPromptRegistry promptRegistry) {
        this.llmGateway = llmGateway;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public String id() {
        return "article_quality";
    }

    @Override
    public String name() {
        return "文章质量评审";
    }

    @Override
    public String description() {
        return "LLM 质量评审：综合评分（0-100）+ 2~4 条针对性改进建议";
    }

    @Override
    public Object execute(SkillContext ctx) {
        String title = ctx == null ? "" : (ctx.title() == null ? "" : ctx.title());
        String content = ctx == null ? "" : (ctx.content() == null ? "" : ctx.content());
        try {
            String user = "标题：" + title + "\n\n正文：\n" + content + "\n\n请完成质量评审，仅输出 JSON。";
            String sys = prompt(ctx == null ? null : ctx.promptKey(),
                    ctx == null ? null : ctx.fallbackPrompt()).content;
            String raw = llmGateway.generateOrNull(AiFeatures.PRECHECK, sys, user, null, null);
            return raw == null ? null : raw.trim();
        } catch (Exception e) {
            log.warn("[AiSkill-{}] 执行异常，fail-open", id(), e);
            return null;
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
}