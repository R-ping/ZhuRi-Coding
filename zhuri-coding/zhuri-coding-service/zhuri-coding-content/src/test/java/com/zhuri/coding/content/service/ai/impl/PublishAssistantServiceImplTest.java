package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zhuri.coding.common.bailian.DashScopeClient;
import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool;
import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool.SimilarArticle;
import com.zhuri.coding.content.service.ai.agent.workflow.PrecheckWorkflow;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AI 发布预检单测：工作流主路径 + 一次性直答兜底 + null 收尾。
 *
 * <p>重点验证：工作流成功时不触发直答；工作流失败时带出失败原因并标记 degradeReason；
 * 直答缺少明确的安全结论时绝不默认成"不违规"；相似度与封面审核的确定性补充。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 发布预检（工作流 → 直答 → null）")
class PublishAssistantServiceImplTest {

    private static final String TITLE = "Redis 分布式锁的正确姿势";
    private static final String CONTENT = "本文讲解 Redis 分布式锁的实现原理、常见坑位与最佳实践。";

    /** 直答输出的 FINAL JSON */
    private static final String FINAL_JSON = "{"
        + "\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\","
        + "\"quality_score\":88,\"is_tech\":true,"
        + "\"suggestions\":[\"补充锁超时与续期策略\",\"给出可运行示例代码\"],"
        + "\"tags\":[\"Redis\",\"分布式锁\"],"
        + "\"summary\":\"讲解 Redis 分布式锁原理与落地要点\"}";

    @Mock private DashScopeClient dashScopeClient;
    /** 统一 LLM 出口：兜底直答走这里 */
    @Mock private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;
    @Mock private PrecheckWorkflow precheckWorkflow;
    @Mock private SimilaritySearchTool similaritySearchTool;
    @Mock private com.zhuri.coding.content.service.ai.AiPromptRegistry promptRegistry;
    @Mock private com.zhuri.coding.content.service.ai.skill.JsonOutputSkill jsonOutputSkill;

    @InjectMocks
    private PublishAssistantServiceImpl service;

    @BeforeEach
    void installRegistryEcho() {
        // 注册表默认「回显 fallback」：既有用例不感知注册表存在
        lenient().when(promptRegistry.resolve(anyString(), anyString(), any()))
            .thenAnswer(inv -> new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                inv.getArgument(0), inv.getArgument(1), 0));
    }

    private static AiPrecheckVo workflowVo() {
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(false);
        vo.setQualityScore(88);
        vo.setTech(true);
        vo.setSuggestions(List.of("补充锁超时与续期策略", "给出可运行示例代码"));
        vo.setTags(List.of("Redis", "分布式锁"));
        vo.setSummary("讲解 Redis 分布式锁原理与落地要点");
        return vo;
    }

    // ==================== 入参短路 ====================

    @Test
    @DisplayName("标题或正文为空直接返回 null（不触发工作流/直答）")
    void blankInputReturnsNull() {
        assertNull(service.precheck("  ", CONTENT, 1L, null));
        assertNull(service.precheck(TITLE, " ", 1L, null));
        verifyNoInteractions(precheckWorkflow, llmGateway);
    }

    // ==================== 主路径：确定性工作流 ====================

    @Test
    @DisplayName("工作流成功：直接采用 VO，标记 source=workflow，不触发直答")
    void workflowSuccessSkipsDirect() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
        when(similaritySearchTool.searchSimilar(anyString())).thenReturn(List.of());

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertFalse(vo.getViolation());
        assertEquals(88, vo.getQualityScore());
        assertEquals("workflow", vo.getSource());
        assertNull(vo.getDegradeReason());
        assertTrue(vo.getLatencyMs() >= 0);
        verify(llmGateway, never()).generateOrNull(anyString(), anyString(), anyString(), any(), any());
    }

    // ==================== 兜底：一次性直答 ====================

    @Test
    @DisplayName("工作流失败（安全结论缺失）→ 直答兜底，并标记降级原因")
    void workflowFailureFallsBackToDirectWithReason() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.SAFETY_UNAVAILABLE));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals("direct", vo.getSource());
        assertEquals("SAFETY_UNAVAILABLE", vo.getDegradeReason());
        assertEquals(88, vo.getQualityScore());
    }

    @Test
    @DisplayName("工作流抛异常 → 按编排失败降级直答，失败原因标记为 ORCHESTRATION_FAILED")
    void workflowThrowsFallsBackAsOrchestrationFailure() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("dag exploded"));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals("direct", vo.getSource());
        assertEquals("ORCHESTRATION_FAILED", vo.getDegradeReason());
    }

    @Test
    @DisplayName("直答输出缺少 is_violation → 绝不默认成'不违规'，整体返回 null")
    void directOutputMissingViolationReturnsNull() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.SAFETY_UNAVAILABLE));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn("{\"quality_score\":80,\"tags\":[\"Redis\"]}");

        assertNull(service.precheck(TITLE, CONTENT, 1L, null),
            "安全结论缺失时必须返回 null，不能产出 violation=false 的报告");
    }

    @Test
    @DisplayName("直答被护栏拦截（gateway 返回 null）→ 返回 null")
    void directGuardHitReturnsNull() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.CRITIC_FAILED));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any())).thenReturn(null);

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    @Test
    @DisplayName("直答调用异常 → 返回 null（接口不抛错）")
    void directExceptionReturnsNull() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.FORMAT_FAILED));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("model down"));

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    @Test
    @DisplayName("直答 prompt 走注册表：resolve 出的 content 传给 LlmGateway")
    void directPathUsesResolvedPrompt() {
        when(promptRegistry.resolve(eq("publish_precheck_direct"), anyString(), any()))
            .thenReturn(new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                "publish_precheck_direct", "已解析直答prompt", 3));
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.SAFETY_UNAVAILABLE));
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(FINAL_JSON);

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        verify(llmGateway).generateOrNull(
            eq(com.zhuri.coding.content.service.ai.AiFeatures.PRECHECK),
            eq("已解析直答prompt"), anyString(), any(), any());
    }

    @Test
    @DisplayName("直答 Bean 化优先：JsonOutputSkill 解析成功即采用")
    void fallbackPrefersBeanParsing() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.failed(
                PrecheckWorkflow.Outcome.Failure.SAFETY_UNAVAILABLE));
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

    // ==================== 相似度补充（两条链路共用） ====================

    @Test
    @DisplayName("相似兜底：命中高相似文章并四舍五入到 4 位小数")
    void fillSimilarityHit() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
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
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenReturn(List.of(new SimilarArticle(article(100L, "旧文"), 0.5)));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 10L, null);

        assertNull(vo.getSimilarArticleId());
        assertNull(vo.getSimilarity());
    }

    @Test
    @DisplayName("相似兜底：最相似文章为自身时排除")
    void fillSimilaritySelfExcluded() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
        when(similaritySearchTool.searchSimilar(anyString()))
            .thenReturn(List.of(new SimilarArticle(article(10L, "本文"), 0.95)));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 10L, null);

        assertNull(vo.getSimilarArticleId(), "排除自身后不应设置相似预警");
    }

    // ==================== 封面图多模态审核 ====================

    @Test
    @DisplayName("封面图审核：图片被判违规时回填 imageViolation")
    void coverImageFlagged() {
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
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
        when(precheckWorkflow.run(anyString(), anyString(), any()))
            .thenReturn(PrecheckWorkflow.Outcome.success(workflowVo()));
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
