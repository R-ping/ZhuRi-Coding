package com.zhuri.coding.content.service.ai.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiPromptRegistry;
import com.zhuri.coding.content.service.ai.agent.tools.ContentSafetyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ArticleSafetySkill 单元测试（P2 Skills：机械检测 + LLM 终审裁定）。
 *
 * <p>覆盖：注册表命中的裁定 prompt 传入 gateway（user 含机器检测结果）；LLM 不可用降级返回机器结果；
 * LLM 抛异常 fail-open 返回合规默认 JSON；注册表未装配回退代码常量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleSafetySkill（安全审查可复用能力）")
class ArticleSafetySkillTest {

    private static final String SAFETY_JSON =
        "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}";

    @Mock private ContentSafetyTool contentSafetyTool;
    @Mock private AiLlmGateway llmGateway;
    @Mock private AiPromptRegistry promptRegistry;

    private ArticleSafetySkill skill;

    @BeforeEach
    void setUp() {
        skill = new ArticleSafetySkill(contentSafetyTool, llmGateway, promptRegistry);
    }

    @Test
    @DisplayName("命中注册表：expert_safety 的 resolved content 作为 system，user 含机械检测结果")
    void executesWithResolvedPromptAndMachineResult() {
        when(contentSafetyTool.execute(anyString())).thenReturn("{\"is_violation\":true,\"type\":\"violence\"}");
        when(promptRegistry.resolve(eq("expert_safety"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_safety", "已解析安全裁定prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), anyString(), anyString(), any(), any()))
            .thenReturn(SAFETY_JSON);

        Object r = skill.execute(AiSkill.SkillContext.of("标题", "正文", "expert_safety", "fallback"));

        assertEquals(SAFETY_JSON, r);
        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).generateOrNull(eq(AiFeatures.PRECHECK), sysCaptor.capture(), userCaptor.capture(), any(), any());
        assertEquals("已解析安全裁定prompt", sysCaptor.getValue());
        assertNotNull(userCaptor.getValue());
        // user 中包含机械检测结果与全文
        org.junit.jupiter.api.Assertions.assertTrue(userCaptor.getValue().contains("机器检测结果"));
        org.junit.jupiter.api.Assertions.assertTrue(userCaptor.getValue().contains("标题"));
    }

    @Test
    @DisplayName("LLM 不可用（返回 null）：降级返回机器检测结果（仍可读）")
    void llmUnavailableDegradesToMachineResult() {
        when(contentSafetyTool.execute(anyString())).thenReturn("{\"is_violation\":true,\"type\":\"x\"}");
        when(promptRegistry.resolve(eq("expert_safety"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_safety", "已解析安全裁定prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), any(), anyString(), any(), any()))
            .thenReturn(null);

        Object r = skill.execute(AiSkill.SkillContext.of("t", "c", "expert_safety", "f"));

        assertEquals("{\"is_violation\":true,\"type\":\"x\"}", r);
    }

    @Test
    @DisplayName("LLM 抛异常 fail-open：返回合规默认 JSON，不向上抛")
    void llmThrowsFailsOpen() {
        when(contentSafetyTool.execute(anyString())).thenReturn(SAFETY_JSON);
        when(promptRegistry.resolve(eq("expert_safety"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_safety", "已解析安全裁定prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), any(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("model down"));

        Object r = skill.execute(AiSkill.SkillContext.of("t", "c", "expert_safety", "f"));

        assertEquals(SAFETY_JSON, r);
    }

    @Test
    @DisplayName("注册表未装配(null)：回落代码常量（fallbackPrompt）作为 system")
    void registryNullFallsBackToConstant() {
        ArticleSafetySkill noRegistry = new ArticleSafetySkill(contentSafetyTool, llmGateway, null);
        when(contentSafetyTool.execute(anyString())).thenReturn(SAFETY_JSON);
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), anyString(), anyString(), any(), any()))
            .thenReturn(SAFETY_JSON);

        Object r = noRegistry.execute(AiSkill.SkillContext.of("t", "c", "expert_safety", "代码兜底SAFETY"));

        assertEquals(SAFETY_JSON, r);
        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).generateOrNull(eq(AiFeatures.PRECHECK), sysCaptor.capture(), anyString(), any(), any());
        assertEquals("代码兜底SAFETY", sysCaptor.getValue());
    }

    @Test
    @DisplayName("空上下文：不抛错并返回兜底 JSON")
    void nullContextFailsOpen() {
        when(contentSafetyTool.execute(anyString())).thenReturn(SAFETY_JSON);
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), anyString(), anyString(), any(), any()))
            .thenReturn("{\"is_violation\":false}");

        assertNotNull(skill.execute(null));
    }
}