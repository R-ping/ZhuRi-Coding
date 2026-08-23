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
 * 文章 AI 综合审核处理器测试（B4 安全收紧）
 *
 * 目标：AI 审核服务不可用 / 违规 / 正常通过三类分支不"降级通过"，
 * 违规内容或故障时都不允许流入后续"上架"流程。
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
    @DisplayName("B4 - AI服务不可用(返回success=false): fail-closed 拒绝通过，不降级")
    void testFailClosedOnUnavailable() {
        Map<String, Object> auditResult = new HashMap<>();
        auditResult.put("success", false);
        auditResult.put("is_violation", false);
        when(bailianAiService.comprehensiveAudit(any(), anyString())).thenReturn(auditResult);

        AuditProcessorContext context = new AuditProcessorContext();
        boolean passed = processor.process(article(), "content", context);

        assertFalse(passed);
        assertTrue(context.getExtra("failReason").toString().contains("暂不可用"));
        verify(bailianAiService).comprehensiveAudit(any(), anyString());
    }

    @Test
    @DisplayName("B4 - 违规: 返回false并写入违规原因")
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
    @DisplayName("B4 - 通过: 返回true并保存AI分析结果")
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
    @DisplayName("B4 - AI异常抛出: fail-closed 拒绝通过，不降级")
    void testExceptionFailClosed() {
        when(bailianAiService.comprehensiveAudit(any(), anyString()))
                .thenThrow(new RuntimeException("AI服务调用失败"));

        AuditProcessorContext context = new AuditProcessorContext();
        boolean passed = processor.process(article(), "content", context);

        assertFalse(passed);
        assertTrue(context.getExtra("failReason").toString().contains("暂不可用"));
    }
}