package com.heima.content.service.article.impl;

import com.heima.content.mapper.article.ApArticleAiAnalysisMapper;
import com.heima.content.model.ai.ArticleAuditResult;
import com.heima.content.model.ai.ViolationCheckResult;
import com.heima.common.bailian.PromptSanitizer;
import com.heima.common.bailian.StructuredOutputInvoker;
import com.heima.model.article.pojos.ApArticle;
import com.heima.model.article.pojos.ApArticleAiAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BailianAiService 接入结构化输出后的行为测试
 *
 * 验证：强类型 DTO 组装 resultMap、结果落库、fail-closed 异常不降级为通过。
 */
class BailianAiServiceImplTest {

    private StructuredOutputInvoker invoker;
    private ApArticleAiAnalysisMapper mapper;
    private PromptSanitizer sanitizer;
    private BailianAiServiceImpl service;

    @BeforeEach
    void setUp() {
        invoker = mock(StructuredOutputInvoker.class);
        mapper = mock(ApArticleAiAnalysisMapper.class);
        sanitizer = mock(PromptSanitizer.class);
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sanitizer.wrapWithDelimiters(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new BailianAiServiceImpl();
        setField("structuredOutputInvoker", invoker);
        setField("aiAnalysisMapper", mapper);
        setField("promptSanitizer", sanitizer);
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = BailianAiServiceImpl.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ApArticle article() {
        ApArticle a = new ApArticle();
        a.setId(1L);
        a.setTitle("标题");
        return a;
    }

    @Test
    @DisplayName("comprehensiveAudit - 成功: DTO 字段映射到 resultMap")
    void comprehensiveAuditSuccess() {
        ArticleAuditResult dto = new ArticleAuditResult();
        dto.setIsViolation(false);
        dto.setViolationType("");
        dto.setViolationReason("");
        dto.setTitleRelevanceScore(95);
        dto.setQualityScore(88);
        dto.setIsTech(true);
        when(invoker.invoke(anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
            .thenReturn(dto);

        Map<String, Object> r = service.comprehensiveAudit(article(), "content");

        assertTrue((Boolean) r.get("success"));
        assertEquals(95, r.get("titleRelevanceScore"));
        assertEquals(88, r.get("qualityScore"));
        assertEquals(Boolean.TRUE, r.get("isTechContent"));
        verify(mapper).insert(any(ApArticleAiAnalysis.class));
    }

    @Test
    @DisplayName("comprehensiveAudit - 违规: 透出违规类型与原因")
    void comprehensiveAuditViolation() {
        ArticleAuditResult dto = new ArticleAuditResult();
        dto.setIsViolation(true);
        dto.setViolationType("涉政");
        dto.setViolationReason("含敏感词");
        when(invoker.invoke(anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
            .thenReturn(dto);

        Map<String, Object> r = service.comprehensiveAudit(article(), "content");

        assertTrue((Boolean) r.get("success"));
        assertEquals(Boolean.TRUE, r.get("is_violation"));
        assertEquals("涉政", r.get("violation_type"));
        assertEquals("含敏感词", r.get("violation_reason"));
    }

    @Test
    @DisplayName("checkViolation - 成功: 强类型 DTO 映射 resultMap")
    void checkViolationSuccess() {
        ViolationCheckResult v = new ViolationCheckResult();
        v.setIsViolation(false);
        when(invoker.invoke(anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
            .thenReturn(v);

        Map<String, Object> r = service.checkViolation(1L, "标题", "内容");

        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals(Boolean.FALSE, r.get("is_violation"));
    }

    @Test
    @DisplayName("结构化输出失败抛业务异常: fail-closed success=false，不降级为通过")
    void failClosedOnStructuredFailure() {
        when(invoker.invoke(anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("LLM 服务异常"));

        Map<String, Object> r = service.checkViolation(1L, null, "内容");

        assertEquals(Boolean.FALSE, r.get("success"));
        verify(mapper, never()).insert(any(ApArticleAiAnalysis.class));
    }

    @Test
    @DisplayName("空内容: 直接返回 success=true，不调用 LLM")
    void emptyContentBypass() {
        Map<String, Object> r = service.checkViolation(1L, null, "");
        assertEquals(Boolean.TRUE, r.get("success"));
        verify(invoker, never()).invoke(any(), any(), any(), any(), anyString(), anyString(), any());
        assertNotNull(r);
        assertFalse((Boolean) r.get("is_violation"));
    }
}