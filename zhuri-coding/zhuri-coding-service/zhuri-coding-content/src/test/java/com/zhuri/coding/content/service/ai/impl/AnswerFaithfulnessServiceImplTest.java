package com.zhuri.coding.content.service.ai.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.ai.AiMetricsCollector;
import com.zhuri.coding.content.service.ai.AnswerFaithfulnessService.Report;
import com.zhuri.coding.content.service.article.impl.ArticleEmbeddingServiceImpl;
import com.zhuri.coding.model.article.dtos.AiSourceVo;
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
 * 答案忠实度校验（三级递进：确定性 → 向量预筛 → LLM 复核）单测。
 *
 * <p>覆盖：空答案短路、引用序号越界、无引用实质句登记、向量高相似放行、低相似进复核、
 * LLM 复核命中/不可用/关闭时的结论兜底。embedding/chatModel 全程 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("答案忠实度校验（AnswerFaithfulnessService）")
class AnswerFaithfulnessServiceImplTest {

    /** 文档上下文：来源[1] 的正文片段 */
    private static final String DOCS_TEXT = "[1] 分布式锁实战\n\n正文内容支撑该结论的详细资料。\n----\n[2] 缓存穿透\n\n正文内容说明缓存失效场景的详细资料。";

    private static final String SUPPORTED_SENT = "该结论在[1]中有充分说明。";
    private static final String SUPPORTED_STRIPPED = "该结论在中有充分说明。";
    private static final String SUPPORTED_SRC = "[1] 分布式锁实战\n\n正文内容支撑该结论的详细资料。";

    @Mock
    private ArticleEmbeddingServiceImpl embeddingService;

    @Mock
    private com.zhuri.coding.content.service.ai.AiLlmGateway llmGateway;

    @Mock
    private AiMetricsCollector metrics;

    @InjectMocks
    private AnswerFaithfulnessServiceImpl service;

    @BeforeEach
    void configureThresholds() {
        ReflectionTestUtils.setField(service, "vecThreshold", 0.45d);
        ReflectionTestUtils.setField(service, "llmReviewEnabled", true);
        ReflectionTestUtils.setField(service, "maxSuspects", 5);
    }

    private AiSourceVo source(Long id) {
        AiSourceVo s = new AiSourceVo();
        s.setArticleId(id);
        return s;
    }

    /** 预筛路径：句子与来源[1]的正文向量夹角 */
    private void stubVec(double[] srcEmb) {
        when(embeddingService.generateEmbedding(SUPPORTED_STRIPPED)).thenReturn(new double[]{1, 0, 0});
        when(embeddingService.generateEmbedding(SUPPORTED_SRC)).thenReturn(srcEmb);
    }

    private void stubLlm(String json) {
        // P0-2：忠实度复核已改为走统一 LLM 出口（feature=faithfulness）
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(json);
    }

    // ==================== 确定性检查 ====================

    @Test
    @DisplayName("空答案返回空报告")
    void emptyAnswer() {
        Report report = service.check(null, List.of(source(1L)), DOCS_TEXT);
        assertEquals(0, report.getCheckedSentences());
        assertFalse(report.hasIssue());

        assertEquals(0, service.check("   ", null, null).getCheckedSentences());
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    @Test
    @DisplayName("引用序号越界（[9] 大于来源数 2）记为无效引用")
    void invalidCitationDetected() {
        Report report = service.check("该方案来自[9]的实践记录。", List.of(source(1L), source(2L)), null);

        assertEquals(1, report.getInvalidCitations().size());
        assertEquals(List.of(9), report.getInvalidCitations());
        assertTrue(report.hasIssue());
        assertEquals(1, report.getUnsupported().size(), "越界引用的句子应进入 unsupported");
        verify(embeddingService, never()).generateEmbedding(anyString());
        assertFalse(report.isLlmReviewed());
    }

    @Test
    @DisplayName("有实质内容但无引用的句子仅登记不重判")
    void noCitationRegistered() {
        Report report = service.check("这段内容信息量非常充足值得反复研读。", List.of(source(1L)), DOCS_TEXT);

        assertEquals(1, report.getNoCitation().size());
        assertFalse(report.hasIssue(), "无引用句子只记录，不进入 unsupported");
        verify(embeddingService, never()).generateEmbedding(anyString());
    }

    // ==================== 向量预筛 + LLM 复核 ====================

    @Test
    @DisplayName("句子与来源高相似（cos=1）→ 放行，不触发 LLM")
    void supportedSentencePasses() {
        stubVec(new double[]{1, 0, 0}); // 来源正文与句子同向 → cos=1

        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), DOCS_TEXT);

        assertTrue(report.getUnsupported().isEmpty());
        assertFalse(report.isLlmReviewed());
        assertFalse(report.hasIssue());
        verify(llmGateway, never()).generateOrNull(anyString(), anyString(), anyString(), any(), any());
        verify(metrics).incr("ai_faithfulness_checked");
    }

    @Test
    @DisplayName("句子与来源低相似（cos=0）→ 进可疑，LLM 复核确认不支撑")
    void lowSimilarityThenLlmFlags() {
        stubVec(new double[]{0, 1, 0}); // 正交 → cos=0 < 0.45 → 可疑
        stubLlm("{\"unsupported\":[{\"idx\":1,\"reason\":\"资料中找不到该结论\"}]}");

        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), DOCS_TEXT);

        assertTrue(report.isLlmReviewed());
        assertEquals(1, report.getUnsupported().size(), "LLM 确认不支撑 → 进入 unsupported");
        assertTrue(report.hasIssue());
        verify(metrics).incr("ai_faithfulness_suspect");
    }

    @Test
    @DisplayName("LLM 复核判全部支撑 → unsupported 为空（纠偏放行）")
    void llmVerdictAllSupported() {
        stubVec(new double[]{0, 1, 0});
        stubLlm("{\"unsupported\":[]}");

        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), DOCS_TEXT);

        assertTrue(report.isLlmReviewed());
        assertTrue(report.getUnsupported().isEmpty());
        assertFalse(report.hasIssue());
    }

    @Test
    @DisplayName("LLM 不可用时按向量预筛结论兜底（宁可多提示不漏报）")
    void llmUnavailableFallsBack() {
        stubVec(new double[]{0, 1, 0});
        // 模型不可用：gateway 返回 null（网关异常/未装配时的统一降级语义）
        when(llmGateway.generateOrNull(anyString(), anyString(), anyString(), any(), any())).thenReturn(null);

        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), DOCS_TEXT);

        // llmUsed 语义为「已发起 LLM 复核尝试」，模型不可用时同样标记为 true
        assertTrue(report.isLlmReviewed());
        assertEquals(1, report.getUnsupported().size(), "模型不可用按向量预筛结论兜底");
        assertEquals("该结论在[1]中有充分说明。", report.getUnsupported().get(0));
    }

    @Test
    @DisplayName("LLM 复核关闭时直接用向量预筛结论")
    void llmDisabledUsesVectorOnly() {
        ReflectionTestUtils.setField(service, "llmReviewEnabled", false);
        stubVec(new double[]{0, 1, 0});

        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), DOCS_TEXT);

        assertFalse(report.isLlmReviewed());
        assertEquals(1, report.getUnsupported().size());
        verify(llmGateway, never()).generateOrNull(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("来源正文缺失时跳过向量预筛（fail-open 不误报）")
    void missingBlocksSkipsPrescreen() {
        // docsText 为空 → blocks 为空 → 有引用句直接跳过余弦计算
        Report report = service.check(SUPPORTED_SENT, List.of(source(1L)), null);

        assertTrue(report.getUnsupported().isEmpty());
        assertFalse(report.isLlmReviewed());
        verify(embeddingService, never()).generateEmbedding(anyString());
    }
}