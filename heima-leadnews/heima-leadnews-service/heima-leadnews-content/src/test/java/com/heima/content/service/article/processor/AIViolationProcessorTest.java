package com.heima.content.service.article.processor;

import com.heima.content.service.article.BailianAiService;
import com.heima.model.article.pojos.ApArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章 AI 综合审核处理器测试（B4 安全收紧 + 2026-09 异常分类调整）
 *
 * 目标：
 * - 真实违规 → 返回 false（正常驳回，不重试）
 * - AI 服务不可用/异常（网络中断、超时、连接满等瞬时故障）→ 抛 AuditRetryableException，
 *   由编排方有界重试，而非把"服务抖动"误判成"内容违规"拒绝用户文章
 */
class AIViolationProcessorTest {

    private BailianAiService bailianAiService;
    private AIViolationProcessor processor;

    @BeforeEach
    void setUp() {
        bailianAiService = mock(BailianAiService.class);
        processor = new AIViolationProcessor(bailianAiService);
    }

    private ApArticle article() {
        ApArticle article = new ApArticle();
        article.setId(1L);
        return article;
    }

    @Test
    @DisplayName("空内容: 跳过AI审核直接通过")
    void testEmptyContent() {
        AuditProcessorContext context = new AuditProcessorContext();
        boolean passed = processor.process(article(), "", context);

        assertTrue(passed);
        verify(bailianAiService, never()).comprehensiveAudit(any(), anyString());
    }

    @Test
    @DisplayName("AI服务不可用(返回success=false): 抛可重试异常，而非判违规")
    void testRetryableOnUnavailable() {
        Map<String, Object> auditResult = new HashMap<>();
        auditResult.put("success", false);
        auditResult.put("is_violation", false);
        when(bailianAiService.comprehensiveAudit(any(), anyString())).thenReturn(auditResult);

        AuditProcessorContext context = new AuditProcessorContext();
        org.junit.jupiter.api.Assertions.assertThrows(AuditRetryableException.class,
            () -> processor.process(article(), "content", context));
        verify(bailianAiService).comprehensiveAudit(any(), anyString());
    }

    @Test
    @DisplayName("违规: 返回false并写入违规原因")
    void testViolation() {
        Map<String, Object> auditResult = new HashMap<>();
        auditResult.put("success", true);
        auditResult.put("is_violation", true);
        auditResult.put("violation_type", "涉政");
        auditResult.put("violation_reason", "包含敏感词");
        when(bailianAiService.comprehensiveAudit(any(), anyString())).thenReturn(auditResult);

        AuditProcessorContext context = new AuditProcessorContext();
        boolean passed = processor.process(article(), "content", context);

        assertFalse(passed);
        assertTrue(context.getExtra("failReason").toString().contains("涉政"));
    }

    @Test
    @DisplayName("通过: 返回true并保存AI分析结果")
    void testPassed() {
        Map<String, Object> auditResult = new HashMap<>();
        auditResult.put("success", true);
        auditResult.put("is_violation", false);
        when(bailianAiService.comprehensiveAudit(any(), anyString())).thenReturn(auditResult);

        AuditProcessorContext context = new AuditProcessorContext();
        boolean passed = processor.process(article(), "content", context);

        assertTrue(passed);
        assertEquals(auditResult, context.getAiAnalysisResult());
    }

    @Test
    @DisplayName("AI异常抛出: 包装为可重试异常，而非判违规")
    void testExceptionRetryable() {
        when(bailianAiService.comprehensiveAudit(any(), anyString()))
                .thenThrow(new RuntimeException("AI服务调用失败"));

        AuditProcessorContext context = new AuditProcessorContext();
        org.junit.jupiter.api.Assertions.assertThrows(AuditRetryableException.class,
            () -> processor.process(article(), "content", context));
    }
}