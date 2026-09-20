package com.heima.content.config;

import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多智能体专家（Worker）共享 ChatClient 配置。
 *
 * <p>每个专家 Worker 内部都是一次独立 LLM 调用，统一复用同一个 ChatClient Bean：
 * 安全三层防御（输入净化 / system 加固 / 输出护栏）由 {@link PromptSafetyAdvisor} 声明式横切，
 * 避免各专家各自构建、各自裸调。所有 Worker 必须注入该 Bean（{@code @Qualifier("aiExpertChatClient")}）。
 */
@Configuration
public class AiExpertConfig {

    /** 专家 Worker 共享 ChatClient（含 PromptSafetyAdvisor 安全横切）；固定用默认主模型（agent 为高价值路径） */
    @Bean("aiExpertChatClient")
    public ChatClient aiExpertChatClient(@Qualifier("openAiChatModel") ChatModel chatModel,
                                         PromptSafetyAdvisor promptSafetyAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultAdvisors(promptSafetyAdvisor)
            .build();
    }
}