package com.heima.common.bailian;

import com.heima.model.common.enums.AppHttpCodeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 结构化输出调用器单元测试
 *
 * 覆盖：首次成功、Markdown 清洗、未转义引号触发式修复、失败重试增强、最终失败抛业务异常。
 */
class StructuredOutputInvokerTest {

    private DashScopeClient dashScopeClient;
    private StructuredOutputProperties properties;
    private StructuredOutputInvoker invoker;
    private Logger log;

    @BeforeEach
    void setUp() {
        dashScopeClient = mock(DashScopeClient.class);
        properties = new StructuredOutputProperties();
        log = mock(Logger.class);
        when(log.isInfoEnabled()).thenReturn(true);
        when(log.isWarnEnabled()).thenReturn(true);
        when(log.isErrorEnabled()).thenReturn(true);
        when(log.isDebugEnabled()).thenReturn(true);
        invoker = new StructuredOutputInvoker(dashScopeClient, properties, null);
    }

    /** 强类型 DTO 测试替身：验证 snake_case -> 驼峰映射 */
    public static class AuditDto {
        private Boolean isViolation;
        private String violationType;
        private Integer qualityScore;
        public Boolean getIsViolation() { return isViolation; }
        public void setIsViolation(Boolean v) { isViolation = v; }
        public String getViolationType() { return violationType; }
        public void setViolationType(String v) { violationType = v; }
        public Integer getQualityScore() { return qualityScore; }
        public void setQualityScore(Integer v) { qualityScore = v; }
    }

    private AuditDto invoke(String response) {
        return invoker.invoke("sys", "user", AuditDto.class,
            AppHttpCodeEnum.SERVER_ERROR, "失败：", "test", log);
    }

    @Test
    @DisplayName("直接输出合法JSON: 首次调用即解析成功")
    void successFirstAttempt() {
        when(dashScopeClient.callGeneration(anyString(), anyString()))
            .thenReturn("{\"is_violation\":true,\"violation_type\":\"涉政\",\"quality_score\":80}");
        AuditDto dto = invoke(null);
        assertEquals(Boolean.TRUE, dto.getIsViolation());
        assertEquals("涉政", dto.getViolationType());
        assertEquals(80, dto.getQualityScore());
        verify(dashScopeClient, times(1)).callGeneration(anyString(), anyString());
    }

    @Test
    @DisplayName("带Markdown代码块: 清洗后解析")
    void markdownFencesStripped() {
        when(dashScopeClient.callGeneration(anyString(), anyString()))
            .thenReturn("```json\n{\"is_violation\":false,\"quality_score\":90}\n```");
        AuditDto dto = invoke(null);
        assertEquals(Boolean.FALSE, dto.getIsViolation());
        assertEquals(90, dto.getQualityScore());
    }

    @Test
    @DisplayName("字符串内未转义引号: 触发式修复后解析")
    void repairUnescapedQuote() {
        when(dashScopeClient.callGeneration(anyString(), anyString()))
            .thenReturn("{\"violation_type\":\"包含\"敏感\"词\",\"is_violation\":true}");
        AuditDto dto = invoke(null);
        assertEquals("包含\"敏感\"词", dto.getViolationType());
        assertEquals(Boolean.TRUE, dto.getIsViolation());
    }

    @Test
    @DisplayName("未转义引号去掉后无法修复: 不吞错并重试")
    void repairFailureLeadsToRetry() {
        properties.setStructuredMaxAttempts(3);
        // 每次返回相同的坏 JSON（修复后仍非法）
        when(dashScopeClient.callGeneration(anyString(), anyString()))
            .thenReturn("{\"is_violation\":true,,,,}");
        StructuredOutputException ex = assertThrows(StructuredOutputException.class, () -> invoke(null));
        assertEquals(AppHttpCodeEnum.SERVER_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().startsWith("失败："));
        // 重试次数 = 配置的 maxAttempts
        verify(dashScopeClient, times(3)).callGeneration(anyString(), anyString());
    }

    @Test
    @DisplayName("空响应: 重试后仍失败，抛业务异常且带错误码")
    void emptyResponseThrows() {
        properties.setStructuredMaxAttempts(2);
        when(dashScopeClient.callGeneration(anyString(), anyString())).thenReturn(null);
        StructuredOutputException ex = assertThrows(StructuredOutputException.class, () -> invoke(null));
        assertEquals(AppHttpCodeEnum.SERVER_ERROR, ex.getErrorCode());
        verify(dashScopeClient, times(2)).callGeneration(anyString(), anyString());
    }

    @Test
    @DisplayName("首次失败第二次成功: 重试 prompt 追加了严格JSON指令与失败原因")
    void retryRecovers() {
        properties.setStructuredMaxAttempts(2);
        when(dashScopeClient.callGeneration(anyString(), anyString()))
            .thenReturn("坏响应")
            .thenReturn("{\"is_violation\":false,\"quality_score\":75}");
        AuditDto dto = invoke(null);
        assertEquals(75, dto.getQualityScore());
        verify(dashScopeClient, times(2)).callGeneration(anyString(), anyString());

        // 捕获第二次调用的 prompt，校验已携带重试增强信息
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(dashScopeClient, times(2)).callGeneration(systemCaptor.capture(), anyString());
        String retryPrompt = systemCaptor.getAllValues().get(1);
        assertTrue(retryPrompt.contains("JSON 解析器直接解析"));
        assertTrue(retryPrompt.contains("上次失败原因"));
        assertTrue(retryPrompt.contains("安全约束")); // 防注入指令始终追加
    }
}