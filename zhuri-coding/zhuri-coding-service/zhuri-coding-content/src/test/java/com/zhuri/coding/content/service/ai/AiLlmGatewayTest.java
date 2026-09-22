package com.zhuri.coding.content.service.ai;

import com.zhuri.coding.content.service.ai.spring.PromptSafetyAdvisor;
import com.zhuri.coding.model.user.pojos.ApUser;
import com.zhuri.coding.utils.thread.AppThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiLlmGateway 单元测试（统一 LLM 出口）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>ChatModel 未装配 → 返回 null 且不发起调用（降级语义，不抛）；</li>
 *   <li>成功路径 → 返回文本且<b>自动计量</b>（这是本类存在的意义：调用点不可能漏统计）；</li>
 *   <li>模型异常 → 吞掉返回 null（与改造前 genText 行为一致，调用方无需感知）；</li>
 *   <li>流式 usage 缺失 → 按字符估算并标记 estimated（成本面板不出现空洞）；</li>
 *   <li>估算口径边界：空/负数 → 0，最小 1 token。</li>
 * </ol>
 */
class AiLlmGatewayTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private AiTokenMeter tokenMeter;
    @Mock
    private AiCircuitBreaker circuitBreaker;

    private AiLlmGateway gateway;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gateway = new AiLlmGateway();
        ReflectionTestUtils.setField(gateway, "chatModel", chatModel);
        // 用真实 advisor（sanitizer/guard 均为 null 时 before/after 原样透传）——
        // mock 它会让 before() 返回 null，advisor chain 直接 NPE，测不到真实链路
        ReflectionTestUtils.setField(gateway, "promptSafetyAdvisor",
                new PromptSafetyAdvisor(null, null));
        ReflectionTestUtils.setField(gateway, "tokenMeter", tokenMeter);
        ReflectionTestUtils.setField(gateway, "circuitBreaker", circuitBreaker);
        org.mockito.Mockito.lenient().when(circuitBreaker.allow(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
    }

    private ChatResponse textResponse(String text, Integer promptTokens, Integer completionTokens, String model) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder().model(model);
        if (promptTokens != null || completionTokens != null) {
            builder = builder.usage(new DefaultUsage(
                    promptTokens == null ? 0 : promptTokens,
                    completionTokens == null ? 0 : completionTokens));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), builder.build());
    }

    @Test
    @DisplayName("available(): ChatModel 未装配返回 false")
    void testAvailableFalseWhenNoChatModel() {
        ReflectionTestUtils.setField(gateway, "chatModel", null);
        assertFalse(gateway.available());
    }

    @Test
    @DisplayName("ChatModel 未装配 → 返回 null 且不发起调用（不抛异常）")
    void testNoChatModelDegrades() {
        ReflectionTestUtils.setField(gateway, "chatModel", null);

        String out = gateway.generateOrNull(AiFeatures.ASK, "sys", "user", null, null);

        assertNull(out);
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
    }

    @Test
    @DisplayName("成功路径 → 返回文本且自动计量（调用点不可能漏统计）")
    void testSuccessRecordsTokenUsage() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(textResponse("社区知识库中的答案", 150, 60, "qwen-plus"));

        String out = gateway.generateOrNull(AiFeatures.ASK, "system prompt", "user question", null, null);

        assertEquals("社区知识库中的答案", out);
        ArgumentCaptor<ChatResponse> captor = ArgumentCaptor.forClass(ChatResponse.class);
        verify(tokenMeter).record(org.mockito.ArgumentMatchers.eq(AiFeatures.ASK), captor.capture());
        assertEquals(150, captor.getValue().getMetadata().getUsage().getPromptTokens());
        assertEquals(60, captor.getValue().getMetadata().getUsage().getCompletionTokens());
    }

    @Test
    @DisplayName("模型异常 → 吞掉返回 null（与改造前行为一致），且不计量")
    void testModelFailureDegradesToNull() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm timeout"));

        String out = gateway.generateOrNull(AiFeatures.ASK, "sys", "user", null, null);

        assertNull(out);
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
    }

    @Test
    @DisplayName("流式：usage 回传 → 按真实 usage 计量（estimated=false）")
    void testStreamWithRealUsage() {
        ChatResponse last = textResponse("末块", 200, 90, "qwen-plus");
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("你", null, null, "qwen-plus"), last));

        StringBuilder deltas = new StringBuilder();
        String full = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "user",
                null, null, deltas::append);

        assertEquals("你末块", full);
        assertEquals("你末块", deltas.toString());
        verify(tokenMeter).record(AiFeatures.ASK_STREAM, "qwen-plus", 200, 90, false);
    }

    @Test
    @DisplayName("流式：usage 缺失 → 按字符估算并标记 estimated（成本面板不留空洞）")
    void testStreamWithoutUsageEstimates() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("这是一段没有 usage 的流式回答", null, null, "qwen")));

        String full = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "user",
                null, null, delta -> { });

        assertTrue(full.contains("没有 usage"));
        // 估算口径：1 token ≈ 2 字符。prompt = "sys"(3) + "user"(4) = 7 字符 → 3；
        // completion = "这是一段没有 usage 的流式回答"(18 字符) → 9
        verify(tokenMeter).record(AiFeatures.ASK_STREAM, "qwen", 3, 9, true);
    }

    @Test
    @DisplayName("流式：onDelta 为 null 时不 NPE（改造前存在该空指针风险）")
    void testStreamNullCallbackSafe() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("ok", 1, 1, "m")));

        String full = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "u", null, null, null);

        assertEquals("ok", full);
    }

    @Test
    @DisplayName("流式：模型异常 → 返回 null 不抛")
    void testStreamFailureDegrades() {
        when(chatModel.stream(any(Prompt.class))).thenThrow(new RuntimeException("stream broken"));

        assertNull(gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "u", null, null, null));
    }

    @Test
    @DisplayName("estimateTokens 边界：0/负数 → 0；字符数不足 2 时至少算 1 token")
    void testEstimateTokens() {
        assertEquals(0, AiLlmGateway.estimateTokens(0));
        assertEquals(0, AiLlmGateway.estimateTokens(-10));
        assertEquals(1, AiLlmGateway.estimateTokens(1));
        assertEquals(50, AiLlmGateway.estimateTokens(100));
    }

    // ==================== P1-1 熔断 ====================

    @Test
    @DisplayName("熔断打开 → 快速失败：不发起模型调用、返回 null")
    void testCircuitOpenFailsFast() {
        when(circuitBreaker.allow(AiCircuitBreaker.TARGET_LLM)).thenReturn(false);

        String out = gateway.generateOrNull(AiFeatures.ASK, "sys", "user", null, null);

        assertNull(out);
        verify(chatModel, never()).call(any(Prompt.class));
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
    }

    @Test
    @DisplayName("调用成功 → 上报熔断成功（半开试探命中即恢复）")
    void testCircuitSuccessReported() {
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("ok", 10, 5, "qwen"));

        String out = gateway.generateOrNull(AiFeatures.ASK, "sys", "user", null, null);

        assertEquals("ok", out);
        verify(circuitBreaker).onSuccess(AiCircuitBreaker.TARGET_LLM);
    }

    @Test
    @DisplayName("模型异常 → 上报熔断失败（累计达阈值后下次快速失败）")
    void testCircuitFailureReported() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm timeout"));

        String out = gateway.generateOrNull(AiFeatures.ASK, "sys", "user", null, null);

        assertNull(out);
        verify(circuitBreaker).onFailure(AiCircuitBreaker.TARGET_LLM);
    }

    @Test
    @DisplayName("流式路径同样受熔断保护：打开时不发起调用")
    void testStreamCircuitOpenFailsFast() {
        when(circuitBreaker.allow(AiCircuitBreaker.TARGET_LLM)).thenReturn(false);

        String out = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "user",
                null, null, delta -> { });

        assertNull(out);
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    // ==================== P2-3c 流式取消 ====================

    @Test
    @DisplayName("客户端取消（onDelta 抛 CancellationException）→ 返回 null、不计熔断失败、按字符估算计量")
    void testStreamCancelledDoesNotTripCircuit() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("第一段", null, null, "qwen"),
                        textResponse("第二段", null, null, "qwen")));
        AtomicInteger calls = new AtomicInteger();

        String out = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "user",
                null, null, delta -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new java.util.concurrent.CancellationException("client aborted");
                    }
                });

        assertNull(out);
        // 取消不是故障：不触发熔断失败（避免连环断连误开熔断）
        verify(circuitBreaker, never()).onFailure(any());
        verify(circuitBreaker, never()).onSuccess(any());
        // 已生成部分（"第一段"=3 字符）按字符估算计量：prompt="sys"+"user"=7 字符→3；completion=3 字符→1
        verify(tokenMeter).record(org.mockito.ArgumentMatchers.eq(AiFeatures.ASK_STREAM),
                org.mockito.ArgumentMatchers.eq("qwen"),
                org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    @DisplayName("包装的取消异常（cause 链）同样按取消处理，不计熔断失败")
    void testStreamWrappedCancellationRecognized() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("x", null, null, "m")));

        String out = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "user",
                null, null, delta -> {
                    throw new RuntimeException("wrapped",
                            new java.util.concurrent.CancellationException("client aborted"));
                });

        assertNull(out);
        verify(circuitBreaker, never()).onFailure(any());
    }

    // ==================== 探针端点收口（frame-ping / tools-ping） ====================

    @Test
    @DisplayName("probeOrNull：正常返回模型文本，但不计量 token（探针不计成本）")
    void testProbeReturnsTextWithoutMetering() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(textResponse("我是社区 AI 助手", 10, 5, "qwen"));

        String out = gateway.probeOrNull("请用一句话介绍你自己。");

        assertEquals("我是社区 AI 助手", out);
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
        verify(circuitBreaker).onSuccess(AiCircuitBreaker.TARGET_LLM);
    }

    @Test
    @DisplayName("probeOrNull：模型未装配 → 返回 null 不抛")
    void testProbeNoModelReturnsNull() {
        ReflectionTestUtils.setField(gateway, "chatModel", null);

        assertNull(gateway.probeOrNull("ping"));
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
    }

    @Test
    @DisplayName("probeWithToolsOrNull：挂工具回调走通并返回文本（tools-ping 语义）")
    void testProbeWithToolsReturnsText() {
        org.springframework.ai.tool.ToolCallbackProvider provider =
                org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(textResponse("is_violation: true", 20, 8, "qwen"));

        String out = gateway.probeWithToolsOrNull(null, "检查这段文字是否违规", provider);

        assertEquals("is_violation: true", out);
        verify(tokenMeter, never()).record(any(), any(ChatResponse.class));
    }

    @Test
    @DisplayName("流式：触达可用额度 → 中断并抛 QuotaExhaustedException，且已生成部分仍计量+结算")
    void testStreamQuotaExhaustedInterrupts() {
        AiQuotaService quota = mock(AiQuotaService.class);
        AiMetricsCollector metrics = mock(AiMetricsCollector.class);
        ReflectionTestUtils.setField(gateway, "quotaService", quota);
        ReflectionTestUtils.setField(gateway, "metrics", metrics);

        // 额度快照依赖 currentUserId()（ThreadLocal），故必须设置用户上下文
        ApUser user = new ApUser();
        user.setId(7);
        AppThreadLocalUtil.setUser(user);
        try {
            // 可用额度 8：prompt 侧("sys"+"u"=4 字符 → 2 token) 先计入，
            // 第 1 块 10 字符(→5) 累计 7 未触线；第 2 块后累计 9 ≥ 8 → 中断，第 3 块不应下发
            when(quota.availableTokens(7)).thenReturn(8L);
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                    textResponse("一二三四五六七八九十", null, null, "qwen"),
                    textResponse("甲乙丙丁", null, null, "qwen"),
                    textResponse("不应下发的第三块", null, null, "qwen")));

            StringBuilder deltas = new StringBuilder();
            assertThrows(AiLlmGateway.QuotaExhaustedException.class,
                    () -> gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "u", null, null,
                            deltas::append));

            // 已生成部分仍要计量与结算（成本面板不留空洞），且与「客户端取消」指标区分。
            // estimated=true 是真实预期：中断发生在流中途，通常尚未收到含 usage 的末块 → 按字符估算
            verify(tokenMeter).record(eq(AiFeatures.ASK_STREAM), any(), anyInt(), anyInt(), eq(true));
            verify(quota).settleTokens(eq(7), anyLong());
            verify(metrics).incr("aiask_stream_quota_exhausted");
            assertFalse(deltas.toString().contains("不应下发的第三块"), "触线后不应继续下发增量");
        } finally {
            AppThreadLocalUtil.clear();
        }
    }

    @Test
    @DisplayName("流式：额度充足 → 不受影响，正常返回并照常结算")
    void testStreamQuotaSufficientNoInterrupt() {
        AiQuotaService quota = mock(AiQuotaService.class);
        ReflectionTestUtils.setField(gateway, "quotaService", quota);

        ApUser user = new ApUser();
        user.setId(7);
        AppThreadLocalUtil.setUser(user);
        try {
            when(quota.availableTokens(7)).thenReturn(100_000L);
            when(chatModel.stream(any(Prompt.class)))
                    .thenReturn(Flux.just(textResponse("正常回答", 10, 4, "qwen")));

            String full = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "u", null, null, d -> {
            });

            assertEquals("正常回答", full);
            // 未触发中断：走正常收尾，仍按真实 usage 结算
            verify(quota).settleTokens(eq(7), anyLong());
        } finally {
            AppThreadLocalUtil.clear();
        }
    }

    @Test
    @DisplayName("流式：无用户上下文（ThreadLocal 为空）→ 不做额度中断，行为与改造前一致")
    void testStreamWithoutUserContextNoInterrupt() {
        AiQuotaService quota = mock(AiQuotaService.class);
        ReflectionTestUtils.setField(gateway, "quotaService", quota);
        // 未登录：currentUserId() 为 null → availableTokens(null) 由实现返回"不限"
        when(quota.availableTokens(null)).thenReturn(Long.MAX_VALUE);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("匿名回答", 10, 4, "qwen")));

        String full = gateway.generateStreamOrNull(AiFeatures.ASK_STREAM, "sys", "u", null, null, d -> {
        });

        assertEquals("匿名回答", full);
    }
}
