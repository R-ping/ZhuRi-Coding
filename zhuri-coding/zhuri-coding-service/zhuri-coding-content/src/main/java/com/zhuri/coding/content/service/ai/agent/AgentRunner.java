package com.zhuri.coding.content.service.ai.agent;

import com.zhuri.coding.content.service.ai.spring.PromptSafetyAdvisor;
import com.zhuri.coding.content.service.ai.spring.SafetyGuardException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Agent 编排器（Spring AI 版，自持有界 ReAct 循环）。
 *
 * <p>Spring AI 1.1.8 的模型内部工具执行循环不暴露轮次上限（内部硬编码兜底），
 * 直接依赖它会导致 maxSteps 形同虚设。因此这里关闭内部执行，
 * 由本类持有一个以 maxSteps 为硬上限的显式循环：
 *
 * <ul>
 *   <li>每轮仍走 ChatClient（{@link PromptSafetyAdvisor} 全程横切：净化/加固/护栏）；</li>
 *   <li>模型返回工具调用 → 本类直接调用 {@link ToolCallback} 执行并把结果以
 *       {@link ToolResponseMessage} 回填对话，进入下一轮；
 *       <b>同一轮内的多个工具调用经 aiAgentToolExecutor 并发执行</b>（Workflow 的
 *       Parallelization 模式），逐个等待以保持回填顺序稳定；</li>
 *   <li>模型返回非工具内容 → 收敛，得到 FINAL 文本；达到 maxSteps 仍不收敛 → 返回未完成交给调用方降级。</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentRunner {

    private final ChatClient chatClient;

    /** 单轮多工具并行执行专用池（见 AiAsyncConfig#aiAgentToolExecutor） */
    private final Executor toolExecutor;

    /** token 计量（Agent 有界循环每轮一次 LLM 调用，多轮成本必须逐轮计入） */
    private final com.zhuri.coding.content.service.ai.AiTokenMeter tokenMeter;

    public AgentRunner(@Qualifier("openAiChatModel") ChatModel chatModel,
                       PromptSafetyAdvisor promptSafetyAdvisor,
                       @Qualifier("aiAgentToolExecutor") Executor toolExecutor,
                       com.zhuri.coding.content.service.ai.AiTokenMeter tokenMeter) {
        this.chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(promptSafetyAdvisor)
            .build();
        this.toolExecutor = toolExecutor;
        this.tokenMeter = tokenMeter;
    }

    /**
     * 运行 Agent：有界 ReAct 循环（maxSteps 硬上限），模型最终输出 FINAL 文本。
     *
     * @param systemPrompt system（应说明可用工具与"最终仅输出 FINAL: {json}"约束）
     * @param userInput    任务输入（由 PromptSafetyAdvisor 净化后包裹）
     * @param toolBeans    @Tool 标注的工具对象集合
     * @param maxSteps     工具调用轮次硬上限（&gt;0），超限返回 completed=false
     * @return 结果；异常/护栏命中/超步时 completed=false（调用方降级直答），steps 为实际轮次
     */
    public AgentResult run(String systemPrompt, String userInput, List<Object> toolBeans, int maxSteps) {
        // 未显式携带额外工具源（如 MCP provider），保持与旧调用兼容
        return run(systemPrompt, userInput, toolBeans, null, maxSteps);
    }

    /**
     * 运行 Agent：有界 ReAct 循环，并在方法型工具之外并入外部 {@link ToolCallbackProvider} 提供的工具
     * （P2-8 延伸：主编 Agent 直接使用 MCP 工具生态，如 docs-fs 文档读写、clock 时间查询等社区 server）。
     *
     * <p>合并语义：{@code toolBeans} 与 {@code extraProvider} 各产出一组 {@link ToolCallback}，
     * 两组以「方法型工具在前、外部工具在后」的顺序拼接为同一回调数组（重名/数组序不影响
     * {@code findCallback} 的名称查找，模型按名称精确匹配工具）。
     *
     * @param systemPrompt  system（应说明可用工具与"最终仅输出 FINAL: {json}"约束）
     * @param userInput     任务输入（由 PromptSafetyAdvisor 净化后包裹）
     * @param toolBeans     @Tool 标注的工具对象集合
     * @param extraProvider 外部工具源（MCP 等）；null 或产出为空时退化为仅方法型工具
     * @param maxSteps      工具调用轮次硬上限（&gt;0），超限返回 completed=false
     * @return 结果；异常/护栏命中/超步时 completed=false（调用方降级直答），steps 为实际轮次
     */
    public AgentResult run(String systemPrompt, String userInput, List<Object> toolBeans,
                           ToolCallbackProvider extraProvider, int maxSteps) {
        int limit = Math.max(1, maxSteps);
        // 注意：Spring AI 1.1.8 的 ToolCallbacks.from(Object...) 会把传入对象当作工具 Bean 重新扫描，
        // 把已构建的 ToolCallbackProvider 作为参数传入会因扫描不到 @Tool 方法而抛异常（主编路径将静默降级直答）。
        // 正确用法：由 Provider 直接产出 ToolCallback[]（本方法内各源自行产出后再拼接）。
        ToolCallback[] callbacks = buildCallbacks(toolBeans, extraProvider);
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt));
        history.add(new UserMessage(userInput == null ? "" : userInput));
        try {
            for (int step = 1; step <= limit; step++) {
                ChatResponse response = chatClient.prompt()
                    .messages(history)
                    .options(ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build())
                    .toolCallbacks(callbacks)
                    .call()
                    .chatResponse();
                // 逐轮计量：ReAct 每轮都是一次真实 LLM 调用（多轮 Agent 的成本主要在这里）
                try {
                    tokenMeter.record(com.zhuri.coding.content.service.ai.AiFeatures.AGENT_EXPERT, response);
                } catch (Exception ignore) {
                    // 计量失败不影响 Agent 主流程
                }
                AssistantMessage out = response.getResult() != null
                    && response.getResult().getOutput() instanceof AssistantMessage
                    ? (AssistantMessage) response.getResult().getOutput() : null;
                if (out == null) {
                    return new AgentResult(null, step, false);
                }
                // 无工具调用 = 收敛，视为 FINAL 输出
                if (!out.hasToolCalls()) {
                    String answer = out.getText();
                    if (answer == null || answer.isBlank()) {
                        return new AgentResult(null, step, false);
                    }
                    return new AgentResult(answer.trim(), step, true);
                }
                // 执行本轮工具调用：同一轮多个调用并行（Parallelization），按原顺序回填结果
                List<AssistantMessage.ToolCall> calls = out.getToolCalls();
                List<CompletableFuture<ToolExecution>> futures = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : calls) {
                    futures.add(CompletableFuture.supplyAsync(() -> executeTool(callbacks, tc), toolExecutor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                for (CompletableFuture<ToolExecution> f : futures) {
                    ToolExecution ex = f.join();
                    responses.add(new ToolResponseMessage.ToolResponse(ex.toolCallId(), ex.toolName(), ex.result()));
                }
                history = new ArrayList<>(history);
                history.add(out);
                history.add(ToolResponseMessage.builder().responses(responses).build());
            }
            log.warn("[AgentRunner] 工具循环达到 maxSteps={} 上限仍未收敛", limit);
            return new AgentResult(null, limit, false);
        } catch (SafetyGuardException e) {
            log.warn("[AgentRunner] 输出护栏命中（顺从短语），标记未完成交由调用方降级: {}", e.getMessage());
            return new AgentResult(null, -1, false);
        } catch (Exception e) {
            log.error("[AgentRunner] ReAct 循环异常", e);
            return new AgentResult(null, -1, false);
        }
    }

    /**
     * 组装工具回调集：方法型工具 {@code toolBeans} 在前，外部 {@code extraProvider}（MCP 等）工具在后。
     * 任何来源缺失或产出为空都自动退化，不抛异常（MCP 故障绝不阻断主编 Agent 主链路）。
     */
    private static ToolCallback[] buildCallbacks(List<Object> toolBeans, ToolCallbackProvider extraProvider) {
        ToolCallback[] base = MethodToolCallbackProvider.builder()
            .toolObjects(toolBeans.toArray())
            .build()
            .getToolCallbacks();
        if (extraProvider == null) {
            return base;
        }
        ToolCallback[] extra;
        try {
            extra = extraProvider.getToolCallbacks();
        } catch (Exception e) {
            log.warn("[AgentRunner] 外部工具源（MCP）读取失败，仅使用方法型工具: {}", e.getMessage());
            return base;
        }
        if (extra == null || extra.length == 0) {
            return base;
        }
        ToolCallback[] merged = new ToolCallback[base.length + extra.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(extra, 0, merged, base.length, extra.length);
        return merged;
    }

    private static ToolCallback findCallback(ToolCallback[] callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (name.equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        return null;
    }

    /** 并行执行单个工具调用（未注册工具返回错误 JSON，不抛异常打断循环） */
    private ToolExecution executeTool(ToolCallback[] callbacks, AssistantMessage.ToolCall tc) {
        ToolCallback callback = findCallback(callbacks, tc.name());
        if (callback == null) {
            log.warn("[AgentRunner] 模型调用了未注册工具: {}", tc.name());
            return new ToolExecution(tc.id(), tc.name(), String.format("{\"error\":\"tool not found: %s\"}", tc.name()));
        }
        return new ToolExecution(tc.id(), tc.name(), safelyCall(callback, tc.arguments()));
    }

    /** 单次工具执行结果载体（含原始调用 id/name，用于稳定回填顺序） */
    private record ToolExecution(String toolCallId, String toolName, String result) {
    }

    /** 工具执行异常不中断循环：以错误 JSON 回填，让模型自行纠偏或收敛 */
    private static String safelyCall(ToolCallback callback, String arguments) {
        try {
            return callback.call(arguments);
        } catch (Exception e) {
            log.warn("[AgentRunner] 工具 {} 执行失败: {}", callback.getToolDefinition().name(), e.getMessage());
            return "{\"error\":\"" + (e.getMessage() == null ? "tool execution failed"
                : e.getMessage().replace("\"", "'")) + "\"}";
        }
    }
}