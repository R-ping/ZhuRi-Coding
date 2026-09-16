package com.heima.content.service.ai.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.heima.content.service.ai.AiFeatures;
import com.heima.content.service.ai.AiLlmGateway;
import com.heima.content.service.ai.AiPromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ArticleQualitySkill 单元测试（P2 Skills：质量评审复用能力）。
 *
 * <p>覆盖：注册表命中的评审 prompt 传入 gateway（user 含标题与正文）；LLM 不可用返回 null（fail-open）；
 * LLM 抛异常返回 null；注册表未装配回退代码常量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleQualitySkill（质量评审可复用能力）")
class ArticleQualitySkillTest {

    private static final String QUALITY_JSON =
        "{\"quality_score\":82,\"is_tech\":true,\"suggestions\":[\"补充示例代码\"]}";

    @Mock private AiLlmGateway llmGateway;
    @Mock private AiPromptRegistry promptRegistry;

    private ArticleQualitySkill skill;

    @BeforeEach
    void setUp() {
        skill = new ArticleQualitySkill(llmGateway, promptRegistry);
    }

    @Test
    @DisplayName("命中注册表：expert_quality 的 resolved content 作为 system，user 含标题与正文")
    void executesWithResolvedPrompt() {
        when(promptRegistry.resolve(eq("expert_quality"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_quality", "已解析质量评审prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), anyString(), anyString(), any(), any()))
            .thenReturn(QUALITY_JSON);

        Object r = skill.execute(AiSkill.SkillContext.of("标题A", "正文B", "expert_quality", "fallback"));

        assertEquals(QUALITY_JSON, r);
        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).generateOrNull(eq(AiFeatures.PRECHECK), sysCaptor.capture(), userCaptor.capture(), any(), any());
        assertEquals("已解析质量评审prompt", sysCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(userCaptor.getValue().contains("标题A"));
        org.junit.jupiter.api.Assertions.assertTrue(userCaptor.getValue().contains("正文B"));
    }

    @Test
    @DisplayName("LLM 不可用（返回 null）：Skill 返回 null（调用方自己降级）")
    void llmUnavailableReturnsNull() {
        when(promptRegistry.resolve(eq("expert_quality"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_quality", "已解析质量评审prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), any(), anyString(), any(), any()))
            .thenReturn(null);

        assertNull(skill.execute(AiSkill.SkillContext.of("t", "c", "expert_quality", "f")));
    }

    @Test
    @DisplayName("LLM 抛异常 fail-open：返回 null，不向上抛")
    void llmThrowsFailsOpen() {
        when(promptRegistry.resolve(eq("expert_quality"), anyString(), any()))
            .thenReturn(new AiPromptRegistry.ResolvedPrompt("expert_quality", "已解析质量评审prompt", 2));
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), any(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("model down"));

        assertNull(skill.execute(AiSkill.SkillContext.of("t", "c", "expert_quality", "f")));
    }

    @Test
    @DisplayName("注册表未装配(null)：回落代码常量（fallbackPrompt）作为 system")
    void registryNullFallsBackToConstant() {
        ArticleQualitySkill noRegistry = new ArticleQualitySkill(llmGateway, null);
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), anyString(), anyString(), any(), any()))
            .thenReturn(QUALITY_JSON);

        Object r = noRegistry.execute(AiSkill.SkillContext.of("t", "c", "expert_quality", "代码兜底QUALITY"));

        assertEquals(QUALITY_JSON, r);
        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmGateway).generateOrNull(eq(AiFeatures.PRECHECK), sysCaptor.capture(), anyString(), any(), any());
        assertEquals("代码兜底QUALITY", sysCaptor.getValue());
    }

    @Test
    @DisplayName("空上下文：不抛错（标题/正文按空处理，system 为代码兜底）")
    void nullContextFailsOpen() {
        // ctx 为 null → promptKey 为 null → system 也是 null（any() 需匹配 null）
        when(llmGateway.generateOrNull(eq(AiFeatures.PRECHECK), any(), anyString(), any(), any()))
            .thenReturn(QUALITY_JSON);

        assertNotNull(skill.execute(null));
    }
}