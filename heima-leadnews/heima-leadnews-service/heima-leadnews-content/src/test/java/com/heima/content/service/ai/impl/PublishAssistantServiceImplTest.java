package com.heima.content.service.ai.impl;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.common.bailian.DashScopeClient;
import com.heima.content.service.ai.agent.AgentResult;
import com.heima.content.service.ai.agent.AgentRunner;
import com.heima.content.service.ai.agent.tools.SimilaritySearchTool;
import com.heima.content.service.ai.agent.tools.SimilaritySearchTool.SimilarArticle;
import com.heima.content.service.ai.agent.workers.CriticExpertWorker;
import com.heima.content.service.ai.agent.workers.QualityExpertWorker;
import com.heima.content.service.ai.agent.workers.SafetyExpertWorker;
import com.heima.content.service.ai.agent.workers.SeoExpertWorker;
import com.heima.content.service.ai.spring.AiSimilarityTools;
import com.heima.content.service.ai.spring.PromptSafetyAdvisor;
import com.heima.content.service.ai.spring.SafetyGuardException;
import com.heima.model.article.dtos.AiPrecheckVo;
import com.heima.model.article.pojos.ApArticle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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
        + "\"summary\":\"讲解 Redis 分布式锁原理与落地要点\","
        + "\"similar_article_id\":null,\"similar_title\":\"\",\"similarity\":null}";

    @Mock private DashScopeClient dashScopeClient;
    @Mock private ChatModel chatModel;
    @Mock private AgentRunner agentRunner;
    @Mock private SafetyExpertWorker safetyExpertWorker;
    @Mock private QualityExpertWorker qualityExpertWorker;
    @Mock private SeoExpertWorker seoExpertWorker;
    @Mock private CriticExpertWorker criticExpertWorker;
    @Mock private AiSimilarityTools aiSimilarityTools;
    @Mock private SimilaritySearchTool similaritySearchTool;

    @InjectMocks
    private PublishAssistantServiceImpl service;

    @BeforeEach
    void installNoopAdvisor() {
        // 兜底路径走 ChatClient.defaultAdvisors(promptSafetyAdvisor)：用真实空组件（sanitizer/guard 均为 null）
        // 保证链式调用不 NPE 且不篡改 prompt/response，便于对 chatModel.call 精确打桩。
        ReflectionTestUtils.setField(service, "promptSafetyAdvisor", new PromptSafetyAdvisor(null, null));
    }

    // ==================== 入参短路 ====================

    @Test
    @DisplayName("标题或正文为空直接返回 null")
    void blankInputReturnsNull() {
        assertNull(service.precheck("  ", CONTENT, 1L, null));
        assertNull(service.precheck(TITLE, " ", 1L, null));
        verify(agentRunner, never()).run(anyString(), anyString(), anyList(), anyInt());
    }

    // ==================== 主编 Agent 主路径 ====================

    @Test
    @DisplayName("主编 Agent 收敛：FINAL JSON 映射为 VO")
    void agentPathMapsFinalJson() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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
    @DisplayName("Agent 未收敛时降级为一次性直答")
    void agentNotCompletedFallsBackToDirect() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
            .thenReturn(new AgentResult(null, 6, false));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
            new Generation(new AssistantMessage(FINAL_JSON)))));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    @Test
    @DisplayName("Agent 返回 JSON 无法解析时降级直答")
    void agentJsonUnparsableFallsBack() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
            .thenReturn(new AgentResult("FINAL: 这不是 JSON", 2, true));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
            new Generation(new AssistantMessage(FINAL_JSON)))));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    @Test
    @DisplayName("Agent 循环异常时降级直答")
    void agentThrowsFallsBack() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
            .thenThrow(new RuntimeException("loop exploded"));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
            new Generation(new AssistantMessage(FINAL_JSON)))));

        AiPrecheckVo vo = service.precheck(TITLE, CONTENT, 1L, null);

        assertNotNull(vo);
        assertEquals(88, vo.getQualityScore());
    }

    // ==================== 兜底直答失败路径 ====================

    @Test
    @DisplayName("兜底输出护栏命中（安全约束）→ 丢弃结果返回 null")
    void fallbackSafetyGuardHitReturnsNull() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
            .thenReturn(new AgentResult(null, 3, false));
        when(chatModel.call(any(Prompt.class))).thenThrow(new SafetyGuardException("LLM 输出疑似受注入影响，已阻断"));

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    @Test
    @DisplayName("兜底调用异常返回 null（接口不抛错）")
    void fallbackExceptionReturnsNull() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
            .thenReturn(new AgentResult(null, 3, false));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model down"));

        assertNull(service.precheck(TITLE, CONTENT, 1L, null));
    }

    // ==================== 相似度兜底（双保险） ====================

    @Test
    @DisplayName("相似兜底：命中高相似文章并四舍五入到 4 位小数")
    void fillSimilarityHit() {
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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
        when(agentRunner.run(anyString(), anyString(), anyList(), anyInt()))
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