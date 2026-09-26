package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.bailian.DashScopeClient;
import com.zhuri.coding.content.service.ai.agent.AgentResult;
import com.zhuri.coding.content.service.ai.agent.AgentRunner;
import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool;
import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool.SimilarArticle;
import com.zhuri.coding.content.service.ai.agent.workers.CriticExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.QualityExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SafetyExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SeoExpertWorker;
import com.zhuri.coding.content.service.ai.spring.PromptSafetyAdvisor;
import com.zhuri.coding.content.service.ai.spring.SafetyGuardException;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AI 发布助手（多智能体主编 + 兜底直答）单测。
 *
 * <p>覆盖：空标题/正文短路；主编 Agent 成功路径（FINAL JSON → VO 映射）；
 * Agent 未收敛/解析失败/异常时降级为一次性直答；兜底输出护栏命中与调用失败返回 null；
 * 相似度兜底（双保险：命中/阈值以下/排除自身）；封面图多模态审核。
 * AgentRunner/ChatModel 全程 mock；兜底路径使用真实空组件 PromptSafetyAdvisor 保证链式调用不 NPE。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 发布助手（PublishAssistantService：主编 + 降级链）")
class PublishAssistantServiceImplTest {

    private static final String TITLE = "Redis 分布式锁的正确姿势";
    private static final String CONTENT = "本文讲解 Redis 分布式锁的实现原理、常见坑位与最佳实践。";

    /** 主编最终输出的 FINAL JSON */
    private static final String FINAL_JSON = "{"
        + "\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\","
        + "\"quality_score\":88,\"is_tech\":true,"
        + "\"suggestions\":[\"补充锁超时与续期策略\",\"给出可运行示例代码\"],"
        + "\"tags\":[\"Redis\",\"分布式锁\"],"
        + "\"summary\":\"讲解 Redis 分布式锁原理与落地要点\"}";

    @Mock private DashScopeClient dashScopeClient;
    @Mock private ChatModel chatModel;
    /** 统一 LLM 出口（P0-2）：兜底直答已改为委托 gateway */
    @Mock private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;
    @Mock private AgentRunner agentRunner;
    @Mock private SafetyExpertWorker safetyExpertWorker;
    @Mock private QualityExpertWorker qualityExpertWorker;
    @Mock private SeoExpertWorker seoExpertWorker;
    @Mock private CriticExpertWorker criticExpertWorker;
    @Mock private SimilaritySearchTool similaritySearchTool;
/** Prompt 注册表（P2-1 补齐）：默认回显 fallback（version=0），特定用例按 key 重打桩 */
    @Mock private com.zhuri.coding.content.service.ai.AiPromptRegistry promptRegistry;

    /** 结构化输出 Skill（P2）：兜底直答 Bean 化优先（parsePrecheckBeanOrNull） */
    @Mock private com.zhuri.coding.content.service.ai.skill.JsonOutputSkill jsonOutputSkill;

    /** MCP 工具目录（P2-8）：主编 Agent 透传其 provider；默认 mock 返回 null（fail-open 路径） */
    @Mock private com.zhuri.coding.content.service.ai.mcp.McpToolCatalog mcpToolCatalog;

    @InjectMocks
    private PublishAssistantServiceImpl service;

    @BeforeEach
    void installNoopAdvisor() {
        // 兜底路径走 ChatClient.defaultAdvisors(promptSafetyAdvisor)：用真实空组件（sanitizer/guard 均为 null）
        // 保证链式调用不 NPE 且不篡改 prompt/response，便于对 chatModel.call 精确打桩。
        ReflectionTestUtils.setField(service, "promptSafetyAdvisor", new PromptSafetyAdvisor(null, null));
        // 注册表默认「回显 fallback」：既有用例不感知注册表存在（行为与改造前一致）
        lenient().when(promptRegistry.resolve(anyString(), anyString(), any()))
            .thenAnswer(inv -> new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                inv.getArgument(0), inv.getArgument(1), 0));
    }

    // ==================== 入参短路 ====================

    @Test
    @DisplayName("标题或正文为空直接返回 null")
    void blankInputReturnsNull() {
        assertNull(service.precheck("  ", CONTENT, 1L, null));
        assertNull(service.precheck(TITLE, " ", 1L, null));
        verify(agentRunner, never()).run(anyString(), anyString(), anyList(), any(), anyInt());
    }

    // ==================== 主编 Agent 主路径 ====================

    @Test
    @DisplayName("主编 Agent 收敛：FINAL JSON 映射为 VO")
    void agentPathMapsFinalJson() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        // 相似度兜底无命中
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertFalse(vo.getViolation());
        assertEquals(88, vo.getQualityScore());
        assertTrue(vo.getTech());
        assertEquals(List.of("补充锁超时与续期策略", "给出可运行示例代码"), vo.getSuggestions());
        assertEquals(List.of("Redis", "分布式锁"), vo.getTags());
        assertEquals("讲解 Redis 分布式锁原理与落地要点", vo.getSummary());
        assertNull(vo.getSimilarArticleId());
        assertTrue(vo.getLatencyMs() >= 0);
        verify(similaritySearchTool).searchSimilar(anyString());
    }

    @Test
    @DisplayName("模型在 FINAL JSON 里自填相似预警 → 一律不采信（字段只由确定性检索产出）")
    void modelFilledAlertIsIgnored() {
        // 即使模型"好心"多填了 similar_*，也不该进入 VO：这三个字段已从 prompt 的 schema 移除，
        // 解析路径也刻意不读它们 —— 权威来源只有 applySimilarity 那一处。
        String jsonWithFakeAlert = "{\"is_violation\":false,\"quality_score\":80,\"is_tech\":true,"
            + "\"suggestions\":[],\"tags\":[\"Redis\"],\"summary\":\"摘要。\","
            + "\"similar_article_id\":8888,\"similar_title\":\"编造的相似文章\",\"similarity\":0.99}";
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(jsonWithFakeAlert, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertNull(vo.getSimilarArticleId(), "模型自填的相似文章 ID 不得采信");
        assertNull(vo.getSimilarTitle(), "模型自填的标题不得采信");
        assertNull(vo.getSimilarity(), "模型自填的相似度不得采信（否则就是查无实据的误报）");
    }

    @Test
    @DisplayName("确定性检索异常 → 按未命中处理，字段清空（不采信模型填值）")
    void modelFilledAlertIsIgnoredOnSearchFailure() {
        String jsonWithFakeAlert = "{\"is_violation\":false,\"quality_score\":80,\"is_tech\":true,"
            + "\"suggestions\":[],\"tags\":[\"Redis\"],\"summary\":\"摘要。\","
            + "\"similar_article_id\":8888,\"similar_title\":\"编造的相似文章\",\"similarity\":0.99}";
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(jsonWithFakeAlert, 1, true));
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenThrow(new RuntimeException("pgvector down"));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertNull(vo.getSimilarArticleId(), "检索失败属'无确定性结论'，字段必须为空");
        assertNull(vo.getSimilarity());
    }

    @Test
    @DisplayName("Agent 未收敛时降级为一次性直答")
    void agentNotCompletedFallsBackToDirect() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(null, 6, false));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    @Test
    @DisplayName("Agent 返回 JSON 无法解析时降级直答")
    void agentJsonUnparsableFallsBack() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult("FINAL: 这不是 JSON", 2, true));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    @Test
    @DisplayName("Agent 循环异常时降级直答")
    void agentThrowsFallsBack() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenThrow(new RuntimeException("loop exploded"));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    // ==================== 主编/直答 prompt 版本化（P2-1 补齐） ====================

    @Test
    @DisplayName("主编主 prompt 走注册表：resolve 出的 content 传给 AgentRunner")
    void agentPathUsesResolvedAgentPrompt() {
        when(promptRegistry.resolve(eq("publish_precheck_agent"), anyString(), any()))
            .thenReturn(new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                "publish_precheck_agent", "已解析主编prompt", 2));
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
        verify(agentRunner).run(eq("已解析主编prompt"), anyString(), anyList(), any(), anyInt());
        verify(promptRegistry).resolve(eq("publish_precheck_agent"), anyString(), any());
    }

    @Test
    @DisplayName("MCP 工具 provider 透传主编 Agent：第 4 参即 McpToolCatalog 产出的 provider")
    void mcpProviderIsPassedToAgentRunner() {
        ToolCallbackProvider fakeProvider = () -> new ToolCallback[0];
        when(mcpToolCatalog.providerOrNull()).thenReturn(fakeProvider);
        when(agentRunner.run(anyString(), anyString(), anyList(), eq(fakeProvider), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        verify(mcpToolCatalog).providerOrNull();
        verify(agentRunner).run(anyString(), anyString(), anyList(), eq(fakeProvider), anyInt());
    }

    @Test
    @DisplayName("MCP 未装配(null)：透传必然为 null，主编 Agent 退化为仅方法型工具")
    void mcpNullFallsBackToNoExtraProvider() {
        ReflectionTestUtils.setField(service, "mcpToolCatalog", null);
        when(agentRunner.run(anyString(), anyString(), anyList(), isNull(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        verify(agentRunner).run(anyString(), anyString(), anyList(), isNull(), anyInt());
    }

    @Test
    @DisplayName("兜底直答 prompt 走注册表：resolve 出的 content 传给 LlmGateway")
    void directPathUsesResolvedDirectPrompt() {
        when(promptRegistry.resolve(eq("publish_precheck_direct"), anyString(), any()))
            .thenReturn(new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                "publish_precheck_direct", "已解析直答prompt", 3));
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(null, 6, false)); // Agent 未收敛 → 降级直答
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
        verify(llmGateway).generateOrNull(
            eq(com.zhuri.coding.content.service.ai.AiFeatures.PRECHECK), eq("已解析直答prompt"), anyString(), any(), any());
        verify(promptRegistry).resolve(eq("publish_precheck_direct"), anyString(), any());
    }

    @Test
    @DisplayName("注册表未装配(null)：回落代码常量，行为不变")
    void registryNullFallsBackToConstants() {
        ReflectionTestUtils.setField(service, "promptRegistry", null);
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
        verify(promptRegistry, never()).resolve(anyString(), anyString(), any());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(agentRunner).run(captor.capture(), anyString(), anyList(), any(), anyInt());
        assertTrue(captor.getValue().startsWith("你是内容社区《逐日 Coding》的主编 Agent"));
    }

    // ==================== 兜底直答失败路径 ====================

    @Test
    @DisplayName("兜底 Bean 化优先：JsonOutputSkill 解析成功即采用（不回落旧 JSON 映射）")
    void fallbackPrefersBeanParsing() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(null, 3, false));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);
        AiPrecheckVo beanVo = new AiPrecheckVo();
        beanVo.setViolation(false);
        beanVo.setQualityScore(99);
        when(jsonOutputSkill.parsePrecheckBeanOrNull(FINAL_JSON)).thenReturn(beanVo);
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(99, vo.getQualityScore(), "Spring AI Bean 化结果应直接采用");
        verify(jsonOutputSkill).parsePrecheckBeanOrNull(FINAL_JSON);
    }

    @Test
    @DisplayName("兜底输出护栏命中 → gateway 降级返回 null → 结果丢弃返回 null")
    void fallbackSafetyGuardHitReturnsNull() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(null, 3, false));
        // 护栏命中由 AiLlmGateway 内部捕获 SafetyGuardException 并返回 null（P0-2 统一出口语义）
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any())).thenReturn(null);

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    @Test
    @DisplayName("兜底调用异常返回 null（接口不抛错）")
    void fallbackExceptionReturnsNull() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(null, 3, false));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("model down"));

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    // ==================== 相似度兜底（双保险） ====================

    @Test
    @DisplayName("相似兜底：命中高相似文章并四舍五入到 4 位小数")
    void fillSimilarityHit() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        ApArticle old = article(100L, "旧文：Redis 分布式锁");
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenReturn(List.of(new SimilarArticle(old, 0.912345)));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 10L, null);

        assertNotNull(vo);
        assertEquals(100L, vo.getSimilarArticleId());
        assertEquals("旧文：Redis 分布式锁", vo.getSimilarTitle());
        assertEquals(0.9123, vo.getSimilarity());
    }

    @Test
    @DisplayName("相似兜底：低于预警阈值不提示")
    void fillSimilarityBelowThreshold() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenReturn(List.of(new SimilarArticle(article(100L, "旧文"), 0.5)));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 10L, null);

        assertNull(vo.getSimilarArticleId());
        assertNull(vo.getSimilarity());
    }

    @Test
    @DisplayName("相似兜底：最相似文章为自身时排除")
    void fillSimilaritySelfExcluded() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenReturn(List.of(new SimilarArticle(article(10L, "本文"), 0.95)));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 10L, null);

        assertNull(vo.getSimilarArticleId(), "排除自身后不应设置相似预警");
    }

    // ==================== 封面图多模态审核 ====================

    @Test
    @DisplayName("封面图审核：图片被判违规时回填 imageViolation")
    void coverImageFlagged() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());
        when(dashScopeClient.callVision(anyString(), eq("https://img/x.png"), anyString()))
            .thenReturn("{\"is_violation\":true,\"violation_type\":\"危险驾驶\",\"reason\":\"图中包含违规内容\"}");

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, "https://img/x.png");

        assertNotNull(vo);
        assertEquals("https://img/x.png", vo.getImageUrl());
        assertTrue(vo.getImageViolation());
        assertEquals("图中包含违规内容", vo.getImageReason());
    }

    @Test
    @DisplayName("封面图审核异常 fail-open：不影响预检主结果")
    void coverImageFailureFailsOpen() {
        when(agentRunner.run(anyString(), anyString(), anyList(), any(), anyInt()))
            .thenReturn(new AgentResult(FINAL_JSON, 1, true));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());
        when(dashScopeClient.callVision(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("vision down"));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, "https://img/x.png");

        assertNotNull(vo, "封面审核失败不应阻断预检结果产出");
        assertEquals(88, vo.getQualityScore());
        assertNull(vo.getImageViolation());
    }

    private ApArticle article(Long id, String title) {
        ApArticle a = new ApArticle();
        a.setId(id);
        a.setTitle(title);
        return a;
    }
}