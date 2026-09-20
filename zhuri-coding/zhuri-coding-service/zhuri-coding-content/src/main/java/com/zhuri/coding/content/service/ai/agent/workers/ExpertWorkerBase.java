package com.zhuri.coding.content.service.ai.agent.workers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 多智能体专家 Worker 基类（Orchestrator-Workers 模式中的 Worker 侧）。
 *
 * <p>每个专家 = 一个持有角色化 system prompt 的 {@link ChatClient} 调用：
 * 主编（Supervisor）通过 {@link org.springframework.ai.tool.annotation.Tool} 调用各专家，
 * 专家只输出本领域结构化 JSON，由主编汇总为终稿。
 *
 * <p>安全横切：所有 Worker 复用 {@code aiExpertChatClient} Bean（已内置 PromptSafetyAdvisor），
 * 输入净化 / 输出护栏对每个专家统一生效。
 *
 * <p>提示词版本化（P2-1 补齐）：专家 SYSTEM_PROMPT 接入 {@link com.zhuri.coding.content.service.ai.AiPromptRegistry}，
 * DB 有行用注册表版（正式版），无/异常回落代码常量（version=0）。Worker 无用户上下文，灰度分流传 null（走正式版）。
 */
public abstract class ExpertWorkerBase {

    protected final ChatClient chatClient;

    @Autowired(required = false)
    protected com.zhuri.coding.content.service.ai.AiPromptRegistry promptRegistry;

    protected ExpertWorkerBase(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 注册表解析（带 null 兜底）：DB 不可用/未装配时返回代码常量（version=0） */
    protected com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt prompt(
        String key, String fallback) {
        if (promptRegistry == null) {
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
        try {
            return promptRegistry.resolve(key, fallback, null);
        } catch (Exception e) {
            return new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(key, fallback, 0);
        }
    }

    /**
     * 以角色化 system prompt + 用户输入执行一次专家判断。
     *
     * @param systemPrompt 专家角色/职责/输出 JSON 结构约定
     * @param userInput    本次要评审的素材与上下文
     * @return 模型输出原文（由各专家自行保证为严格 JSON；解析失败时主编兜底降级）
     */
    protected String askExpert(String systemPrompt, String userInput) {
        String raw = chatClient.prompt()
            .system(systemPrompt)
            .user(userInput)
            .call()
            .content();
        return raw == null ? "" : raw.trim();
    }

    /** 工具参数 JSON 转义（双引号/反斜杠），避免破坏模型工具参数结构 */
    protected static String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}