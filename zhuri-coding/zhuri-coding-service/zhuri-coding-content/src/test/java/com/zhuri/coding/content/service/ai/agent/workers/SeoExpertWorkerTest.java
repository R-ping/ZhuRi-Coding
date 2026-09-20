package com.zhuri.coding.content.service.ai.agent.workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SEO 专家 Worker 单测（P2-1 补齐：专家 SYSTEM_PROMPT 接入注册表）。
 *
 * <p>覆盖：注册表命中（resolve 出的 content 传入 system）；未装配(null) 回落代码常量；
 * 注册表抛异常 fail-open 回落代码常量——行为零变化，且覆盖 {@link ExpertWorkerBase#prompt} 三态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SEO 专家 Worker（提示词版本化）")
class SeoExpertWorkerTest {

    private static final String EXPERT_JSON = "{\"tags\":[\"Redis\"],\"summary\":\"Redis 锁实践\"}";

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;

    private SeoExpertWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SeoExpertWorker(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
    }

    private String captureSystemPrompt() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("注册表命中：resolve 出的 content 传入 system（专家 prompt 版本化生效）")
    void reviewUsesResolvedPrompt() {
        com.zhuri.coding.content.service.ai.AiPromptRegistry registry =
            Mockito.mock(com.zhuri.coding.content.service.ai.AiPromptRegistry.class);
        when(registry.resolve(eq("expert_seo"), anyString(), isNull()))
            .thenReturn(new com.zhuri.coding.content.service.ai.AiPromptRegistry.ResolvedPrompt(
                "expert_seo", "已解析SEO专家prompt", 4));
        ReflectionTestUtils.setField(worker, "promptRegistry", registry);
        when(callSpec.content()).thenReturn(EXPERT_JSON);

        String raw = worker.review("Redis 锁", "加锁与续期。");

        assertEquals(EXPERT_JSON, raw);
        assertEquals("已解析SEO专家prompt", captureSystemPrompt());
        verify(registry).resolve(eq("expert_seo"), anyString(), isNull());
    }

    @Test
    @DisplayName("注册表未装配(null)：回落代码常量（行为零变化）")
    void reviewRegistryNullUsesConstant() {
        when(callSpec.content()).thenReturn(EXPERT_JSON);

        String raw = worker.review("Redis 锁", "加锁与续期。");

        assertEquals(EXPERT_JSON, raw);
        assertTrue(captureSystemPrompt().startsWith("你是内容社区《逐日 Coding》的内容运营专家"));
    }

    @Test
    @DisplayName("注册表抛异常 fail-open：回落代码常量")
    void reviewRegistryThrowsUsesConstant() {
        com.zhuri.coding.content.service.ai.AiPromptRegistry registry =
            Mockito.mock(com.zhuri.coding.content.service.ai.AiPromptRegistry.class);
        when(registry.resolve(anyString(), anyString(), isNull())).thenThrow(new RuntimeException("db down"));
        ReflectionTestUtils.setField(worker, "promptRegistry", registry);
        when(callSpec.content()).thenReturn(EXPERT_JSON);

        String raw = worker.review("Redis 锁", "加锁与续期。");

        assertEquals(EXPERT_JSON, raw);
        assertTrue(captureSystemPrompt().startsWith("你是内容社区《逐日 Coding》的内容运营专家"));
    }
}