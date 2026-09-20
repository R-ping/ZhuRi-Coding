package com.zhuri.coding.content.service.ai.spring;

import com.zhuri.coding.common.bailian.ComplianceGuard;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.common.bailian.PromptSecurityConstants;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 提示词安全横切层（Spring AI Advisor 版）。
 *
 * <p>把此前散落在 AiAsk / PublishAssistant / AgentRunner 里逐方法手写的三层防御，
 * 收敛为声明式的 BaseAdvisor，注册到 ChatClient.defaultAdvisors 后对所有模型调用统一生效：
 *
 * <ul>
 *   <li>Layer 1（输入净化）：user 消息经 {@link PromptSanitizer#sanitizeAndWrap} 清洗注入短语并
 *       用 UUID 动态边界标签包裹；</li>
 *   <li>Layer 2（提示词加固）：system 消息末尾追加 {@link PromptSecurityConstants#ANTI_INJECTION_INSTRUCTION}；</li>
 *   <li>Layer 3（输出护栏）：非流式响应经 {@link ComplianceGuard} 检测顺从短语，命中视为注入成功，
 *       抛 {@link SafetyGuardException} 由调用方降级（流式路径不做逐 chunk 判定，
 *       由调用方在汇聚完整文本后调用 {@link #guardStreamed} 兜底）。</li>
 * </ul>
 *
 * <p>通过 {@link #setOrder(int)} 显式靠前，确保在 ToolCall / 其他 Advisor 之前处理。
 */
@Slf4j
@Component
public class PromptSafetyAdvisor implements BaseAdvisor {

    /** 与其他 Advisor 的相对顺序：靠前净化输入，靠后校验输出 */
    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final PromptSanitizer promptSanitizer;
    private final ComplianceGuard complianceGuard;
    private int order = DEFAULT_ORDER;

    public PromptSafetyAdvisor(@Autowired(required = false) PromptSanitizer promptSanitizer,
                               @Autowired(required = false) ComplianceGuard complianceGuard) {
        this.promptSanitizer = promptSanitizer;
        this.complianceGuard = complianceGuard;
    }

    @Override
    public String getName() {
        return "prompt-safety-advisor";
    }

    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (promptSanitizer == null) {
            return request;
        }
        Prompt prompt = request.prompt();
        List<Message> instructions = prompt.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return request;
        }
        List<Message> secured = new ArrayList<>(instructions.size());
        for (Message m : instructions) {
            MessageType type = m.getMessageType();
            // Layer 1：净化用户输入（user/工具结果等外部内容；避免重复净化已包裹内容）
            if (type == MessageType.USER) {
                UserMessage userMsg = (UserMessage) m;
                String text = userMsg.getText();
                if (text != null && !text.isBlank() && !text.contains(PromptSecurityConstants.DATA_BOUNDARY_PREFIX)) {
                    Message safe = new UserMessage(promptSanitizer.sanitizeAndWrap("context", text));
                    copyMetadata(m, safe);
                    secured.add(safe);
                    continue;
                }
            }
            // Layer 2：system 追加防注入指令（幂等：已含【安全约束】则跳过）
            if (type == MessageType.SYSTEM) {
                SystemMessage sysMsg = (SystemMessage) m;
                String text = sysMsg.getText();
                if (text != null && !text.contains(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION.trim().substring(0, 12))) {
                    Message safe = new SystemMessage(text.trim() + "\n\n" + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
                    copyMetadata(m, safe);
                    secured.add(safe);
                    continue;
                }
            }
            secured.add(m);
        }
        // 仅当有变化时替换 prompt，保持上下文（conversationId 等）不变
        return request.mutate().prompt(new Prompt(secured, prompt.getOptions())).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (complianceGuard == null || response == null || response.chatResponse() == null) {
            return response;
        }
        ChatResponse chatResponse = response.chatResponse();
        String answer = chatResponse.getResult() != null
            && chatResponse.getResult().getOutput() != null
            ? chatResponse.getResult().getOutput().getText() : null;
        if (answer != null && complianceGuard.isComplianceResponse(answer)) {
            log.warn("[PromptSafetyAdvisor] 输出护栏命中（顺从短语），抛出异常交由调用方降级");
            throw new SafetyGuardException("LLM 输出疑似受注入影响，已阻断");
        }
        return response;
    }

    /** 流式路径：仅做前置净化（Layer 1/2）。不做逐 chunk 输出护栏 */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest secured = before(request, chain);
        return chain.nextStream(secured);
    }

    /** 流式响应经后将完整文本做最终护栏检查（在链最外层 doOnComplete/doOnError 兜底） */
    public String guardStreamed(String fullText) {
        if (complianceGuard == null || fullText == null) {
            return fullText;
        }
        if (complianceGuard.isComplianceResponse(fullText)) {
            log.warn("[PromptSafetyAdvisor] 流式输出护栏命中（顺从短语），标记异常");
            throw new SafetyGuardException("LLM 流式输出疑似受注入影响，已阻断");
        }
        return fullText;
    }

    private void copyMetadata(Message from, Message to) {
        // 保留原始 metadata（会话 id / 其他跟踪信息），避免丢失链路上下文
        if (from.getMetadata() != null) {
            from.getMetadata().forEach(to.getMetadata()::putIfAbsent);
        }
    }
}