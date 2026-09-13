package com.heima.content.service.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import com.heima.content.service.ai.spring.SafetyGuardException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Agent 编排器（有界 ReAct 循环）单测。
 *
 * <p>覆盖：无工具调用直接收敛（FINAL 文本）、单轮工具调用后收敛（Parallelization 同步执行并把结果回填）、
 * 未注册工具调用返回错误 JSON 不中断循环、工具抛异常 fail-open 收敛、maxSteps 达到上限仍未收敛返回未完成、
 * 模型输出为空/空白文本判定未完成、SafetyGuard/普通异常降级为未完成。ChatModel 全程 mock。
 *
 * <p>说明：工具执行使用 {@link Executor#execute} 同步直跑（Runnable::run），使 CompletableFuture 在主线程内完成，
 * 便于对工具调用次数与最终收敛结果做确定性断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 编排器（AgentRunner：有界 ReAct 循环）")
class AgentRunnerTest {

    @Mock
    private ChatModel chatModel;

    private final TestTools tools = new TestTools();

    private AgentRunner runner() {
        // 空组件 Advisor（sanitizer/guard 均 null）：ChatClient Advisor 链为 no-op，不篡改 prompt/response
        PromptSafetyAdvisor noop = new PromptSafetyAdvisor(null, null);
        Executor sync = Runnable::run;
        return new AgentRunner(chatModel, noop, sync);
    }

    private static AssistantMessage toolCallMsg(String callId, String name, String args) {
        return AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", name, args)))
            .build();
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ==================== 直接收敛 ====================

    @Test
    @DisplayName("模型无工具调用直接输出 FINAL → 立即收敛")
    void directConvergeWithoutToolCall() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(textResponse("FINAL: {\"ok\":true}"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertEquals(1, result.getSteps());
        assertTrue(result.isCompleted());
        assertEquals("FINAL: {\"ok\":true}", result.getFinalAnswer());
        assertEquals(0, tools.calls.get(), "无工具调用时不应触发工具");
    }

    @Test
    @DisplayName("模型输出为空白文本 → 判定未收敛")
    void blankConvergedTextIsNotCompleted() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("   "));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertFalse(result.isCompleted());
        assertNull(result.getFinalAnswer());
    }

    @Test
    @DisplayName("模型输出不是 AssistantMessage（output 为 null）→ 判定未完成")
    void nullOutputNotCompleted() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation((AssistantMessage) null))));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertFalse(result.isCompleted());
        assertEquals(1, result.getSteps());
    }

    // ==================== 工具调用后收敛 ====================

    @Test
    @DisplayName("首轮工具调用被同步执行，次轮输出 FINAL 收敛")
    void toolCallThenConverge() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(textResponseWithToolCall(), textResponse("FINAL: {\"ok\":true}"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getSteps());
        assertEquals("FINAL: {\"ok\":true}", result.getFinalAnswer());
        assertEquals(1, tools.calls.get(), "工具应被精确执行一次");
    }

    private ChatResponse textResponseWithToolCall() {
        return new ChatResponse(List.of(
            new Generation(toolCallMsg("call-1", "test_tool", "{\"input\":\"hello\"}"))));
    }

    @Test
    @DisplayName("工具执行异常以错误 JSON 回填，循环仍可收敛")
    void toolThrowsStillConverges() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(toolCallChatResponse("boom_tool"), textResponse("FINAL: {\"ok\":true}"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertTrue(result.isCompleted());
        assertEquals("FINAL: {\"ok\":true}", result.getFinalAnswer());
        assertEquals(1, tools.boomCalls.get());
    }

    @Test
    @DisplayName("模型调用未注册工具 → 返回错误 JSON 不中断，次轮收敛")
    void unknownToolReturnsErrorAndConverges() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(
                new ChatResponse(List.of(new Generation(toolCallMsg("call-9", "ghost_tool", "{}")))),
                textResponse("FINAL: {\"ok\":true}"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertTrue(result.isCompleted());
        assertEquals(2, result.getSteps());
        assertEquals(0, tools.calls.get(), "未注册工具不应调用真工具");
    }

    // ==================== 步数上限 / 异常降级 ====================

    @Test
    @DisplayName("每轮都返回工具调用 → 达到 maxSteps 仍未收敛，返回未完成")
    void maxStepsExceededReturnsNotCompleted() {
        // 每次调用都返回同一工具调用响应（永不收敛）
        when(chatModel.call(any(Prompt.class)))
            .thenAnswer(inv -> textResponseWithToolCall());

        AgentResult result = runner().run("system", "user input", List.of(tools), 2);

        assertFalse(result.isCompleted());
        assertEquals(2, result.getSteps(), "步数应等于 maxSteps=2");
        assertEquals(2, tools.calls.get(), "两个回合各执行一次工具");
    }

    @Test
    @DisplayName("输出护栏命中（SafetyGuardException）→ 降级为未完成")
    void safetyGuardExceptionDegrades() {
        when(chatModel.call(any(Prompt.class)))
            .thenThrow(new SafetyGuardException("LLM 输出疑似受注入影响，已阻断"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertFalse(result.isCompleted());
        assertEquals(-1, result.getSteps());
    }

    @Test
    @DisplayName("循环异常 → 降级为未完成（不向外抛）")
    void genericExceptionDegrades() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model down"));

        AgentResult result = runner().run("system", "user input", List.of(tools), 3);

        assertFalse(result.isCompleted());
        assertEquals(-1, result.getSteps());
        assertNull(result.getFinalAnswer());
    }

    // ==================== 测试工具 Bean ====================

    /** 带 @Tool 注解的工具对象（与生产 Worker 同一注册机制） */
    static class TestTools {

        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger boomCalls = new AtomicInteger();

        @Tool(description = "测试工具：回显输入")
        public String test_tool(String input) {
            calls.incrementAndGet();
            return "{\"echo\":\"" + input + "\"}";
        }

        @Tool(description = "必抛异常的工具：验证 fail-open")
        public String boom_tool(String input) {
            boomCalls.incrementAndGet();
            throw new RuntimeException("boom: " + input);
        }
    }

    private ChatResponse toolCallChatResponse(String toolName) {
        return new ChatResponse(List.of(
            new Generation(toolCallMsg("call-2", toolName, "{\"input\":\"x\"}"))));
    }
}