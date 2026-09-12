package com.heima.content.service.ai.agent;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * Agent 编排器（Spring AI 版）。
 *
 * <p>工具调用由 Spring AI 原生机制完成（@Tool → MethodToolCallbackProvider → 模型自主多轮调用）；
 * 最终输出要求以 FINAL: {json} 形式给出，由调用方解析。实现为空闲即返回的轻量封装。
 */
@Slf4j
@Component
public class AgentRunner {

    private final ChatClient chatClient;

    public AgentRunner(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 运行 Agent：注入工具并由 Spring AI 完成自主调用循环，模型最终输出 FINAL 文本。
     *
     * @param systemPrompt system（应说明可用工具与"最终仅输出 FINAL: {json}"约束）
     * @param userInput    任务输入
     * @param toolBeans    @Tool 标注的工具对象集合
     * @param maxSteps     保留参数（Spring AI 框架内部处理循环；此处仅约束不再使用）
     * @return 结果；异常时 completed=false（调用方降级直答）
     */
    public AgentResult run(String systemPrompt, String userInput, List<Object> toolBeans, int maxSteps) {
        try {
            MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build();
            String answer = chatClient.prompt(systemPrompt + "\n" + userInput)
                .toolCallbacks(provider)
                .call()
                .content();
            if (answer == null || answer.isBlank()) {
                return new AgentResult(null, 0, false);
            }
            return new AgentResult(answer.trim(), 0, true);
        } catch (Exception e) {
            log.error("[AgentRunner] Spring AI 工具调用失败", e);
            return new AgentResult(null, 0, false);
        }
    }
}
