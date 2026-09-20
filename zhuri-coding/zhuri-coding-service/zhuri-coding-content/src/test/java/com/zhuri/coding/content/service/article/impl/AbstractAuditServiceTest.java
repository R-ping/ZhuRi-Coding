package com.heima.content.service.article.impl;

import com.heima.content.service.article.BailianAiService;
import com.heima.model.audit.AuditContext;
import com.heima.model.audit.AuditEntityType;
import com.heima.model.audit.AuditResult;
import com.heima.model.audit.AuditServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AbstractAuditService 回归测试（重点关注 B4 AI 审核故障关闭 fail-closed）
 *
 * 核心安全诉求：AI 违规检测服务不可用时，绝不可将"检测异常"降级为"通过"（否则违规内容绕过审核上架）。
 * - 正常：AI 判定违规 -> 返回失败并触发 handleFailed；
 * - 正常：AI 判定不违规 -> 通过并触发 handlePassed；
 * - 故障：AI 抛异常 -> 必须向上抛 AuditServiceUnavailableException，且不触发 handlePassed / handleFailed。
 */
class AbstractAuditServiceTest {

    /**
     * 最小可实例化的审核服务子类，用于记录模板方法回调并暴露父类受保护行为。
     */
    private static class TestAuditService extends AbstractAuditService {
        boolean passedCalled;
        boolean failedCalled;
        String failedReason;

        @Override
        protected void handlePassed(AuditContext context) {
            passedCalled = true;
        }

        @Override
        protected void handleFailed(AuditContext context, String reason) {
            failedCalled = true;
            failedReason = reason;
        }
    }

    @Mock
    private BailianAiService bailianAiService;

    private TestAuditService auditService;

    private AuditContext contextWithContent() {
        return new AuditContext(AuditEntityType.PINS, 100L, 20001L)
            .withTitle("测试标题")
            .withContent("测试正文内容");
    }

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        auditService = new TestAuditService();
        // 私有 @Autowired 字段依赖注入（避免依赖 Spring 容器）
        Field field = AbstractAuditService.class.getDeclaredField("bailianAiService");
        field.setAccessible(true);
        field.set(auditService, bailianAiService);
    }

    @Test
    @DisplayName("B4 - AI检测异常: 抛 AuditServiceUnavailableException(fail-closed)，不降级通过")
    void testAuditAiErrorFailClosed() {
        when(bailianAiService.checkViolation(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("AI服务调用失败"));

        // 关键断言：不能返回 failed 之外的"通过"，应直接抛出审核服务不可用异常
        assertThrows(AuditServiceUnavailableException.class,
                () -> auditService.audit(contextWithContent()));

        // 异常路径不得误触发"审核通过/审核失败"回调
        assertFalse(auditService.passedCalled);
        assertFalse(auditService.failedCalled);
    }

    @Test
    @DisplayName("B4 - 判违规: AI判定违规返回失败并触发 handleFailed")
    void testAuditAiViolation() {
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("success", true);
        aiResult.put("is_violation", true);
        aiResult.put("violation_type", "涉政");
        aiResult.put("violation_reason", "包含敏感词");
        when(bailianAiService.checkViolation(anyLong(), anyString(), anyString())).thenReturn(aiResult);

        AuditResult result = auditService.audit(contextWithContent());

        assertFalse(result.isPassed());
        assertTrue(auditService.failedCalled);
        assertFalse(auditService.passedCalled);
        assertTrue(auditService.failedReason.contains("涉政"));
        assertTrue(auditService.failedReason.contains("敏感词"));
    }

    @Test
    @DisplayName("B4 - 判不违规: AI判定不违规审核通过并触发 handlePassed")
    void testAuditAiNotViolation() {
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("success", true);
        aiResult.put("is_violation", false);
        when(bailianAiService.checkViolation(anyLong(), anyString(), anyString()))
                .thenReturn(aiResult)
                .thenReturn(null); // 兼容多个内容实体/后续调用

        AuditResult result = auditService.audit(contextWithContent());

        assertTrue(result.isPassed());
        assertTrue(auditService.passedCalled);
        assertFalse(auditService.failedCalled);
    }

    @Test
    @DisplayName("B4 - AI服务不可用(返回success=false): fail-closed 抛出 AuditServiceUnavailableException，不降级通过")
    void testAuditAiServiceUnavailableFailClosed() {
        Map<String, Object> aiResult = new HashMap<>();
        aiResult.put("success", false); // AI 服务不可用的结果
        aiResult.put("is_violation", false);
        when(bailianAiService.checkViolation(anyLong(), anyString(), anyString())).thenReturn(aiResult);

        assertThrows(AuditServiceUnavailableException.class,
                () -> auditService.audit(contextWithContent()));

        // 异常降级路径不得误触发"审核通过/审核失败"回调
        assertFalse(auditService.passedCalled);
        assertFalse(auditService.failedCalled);
    }

    @Test
    @DisplayName("审核参数不完整(null context) - 直接返回失败，不调用AI")
    void testAuditNullContext() {
        AuditResult result = auditService.audit(null);

        assertFalse(result.isPassed());
        assertEquals("审核参数不完整", result.getReason());
    }
}