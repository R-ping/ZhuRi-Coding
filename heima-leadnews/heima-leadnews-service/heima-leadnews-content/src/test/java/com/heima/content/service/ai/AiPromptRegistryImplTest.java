package com.heima.content.service.ai;

import com.heima.content.service.ai.impl.AiPromptRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prompt 注册表单测（P2-1）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>正式版 = enabled 且 rollout=0 中 version 最大；灰度 = rollout∈[1,99] 按 version 降序逐个试，
 *       {@code floorMod(userId,100) < rollout} 命中；userId=null 不参与灰度；</li>
 *   <li><b>fail-open</b>：DB 无行/异常/开关关闭 → 代码兜底（version=0）；DB 挂 → 沿用旧快照；</li>
 *   <li>快照刷新周期内不重查库（懒刷新）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Prompt 版本注册表（AiPromptRegistry）")
class AiPromptRegistryImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AiPromptRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new AiPromptRegistryImpl();
        ReflectionTestUtils.setField(registry, "enabled", true);
        ReflectionTestUtils.setField(registry, "refreshSeconds", 60);
        ReflectionTestUtils.setField(registry, "jdbcTemplate", jdbcTemplate);
    }

    private static Map<String, Object> row(String key, int version, String content, int rollout) {
        Map<String, Object> m = new HashMap<>();
        m.put("prompt_key", key);
        m.put("version", version);
        m.put("content", content);
        m.put("rollout_percent", rollout);
        return m;
    }

    @Test
    @DisplayName("DB 无该 key → 代码兜底 version=0")
    void fallbackWhenKeyMissing() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 7);
        assertEquals("FALLBACK", r.content);
        assertEquals(0, r.version);
    }

    @Test
    @DisplayName("开关关闭 → 直接代码兜底，不查库")
    void disabledSkipsDb() {
        ReflectionTestUtils.setField(registry, "enabled", false);
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 7);
        assertEquals("FALLBACK", r.content);
        assertEquals(0, r.version);
        verify(jdbcTemplate, times(0)).queryForList(anyString());
    }

    @Test
    @DisplayName("正式版命中：同 key 取 rollout=0 中 version 最大")
    void basePicksHighestVersion() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 1, "v1", 0),
            row("ai_ask_system", 3, "v3", 0),
            row("ai_ask_system", 2, "v2", 0)));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", null);
        assertEquals("v3", r.content);
        assertEquals(3, r.version);
    }

    @Test
    @DisplayName("灰度命中：floorMod(userId,100) < rollout → 灰度版")
    void grayRolloutHits() {
        // bucket = floorMod(7,100) = 7；rollout=30 → 命中
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 2, "gray-v2", 30),
            row("ai_ask_system", 1, "v1", 0)));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 7);
        assertEquals("gray-v2", r.content);
        assertEquals(2, r.version);
    }

    @Test
    @DisplayName("灰度未命中（bucket >= rollout）→ 回落正式版")
    void grayRolloutMissFallsBackToBase() {
        // bucket = floorMod(99,100) = 99；rollout=30 → 未命中
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 2, "gray-v2", 30),
            row("ai_ask_system", 1, "v1", 0)));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 99);
        assertEquals("v1", r.content);
        assertEquals(1, r.version);
    }

    @Test
    @DisplayName("多灰度版并存：version 降序优先，高版本先试")
    void grayVersionsTriedInDescendingOrder() {
        // bucket=7：< 50 命中 v3（先试），不再看 v2
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 3, "gray-v3", 50),
            row("ai_ask_system", 2, "gray-v2", 20),
            row("ai_ask_system", 1, "v1", 0)));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 7);
        assertEquals("gray-v3", r.content);
        assertEquals(3, r.version);
    }

    @Test
    @DisplayName("userId=null（系统内部调用）→ 不参与灰度，直接正式版")
    void nullUserSkipsGray() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 2, "gray-v2", 99),
            row("ai_ask_system", 1, "v1", 0)));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", null);
        assertEquals("v1", r.content);
        assertEquals(1, r.version);
    }

    @Test
    @DisplayName("DB 异常 → fail-open 走代码兜底")
    void dbErrorFallsBack() {
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new RuntimeException("db down"));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", 7);
        assertEquals("FALLBACK", r.content);
        assertEquals(0, r.version);
    }

    @Test
    @DisplayName("DB 挂后沿用旧快照：先成功加载，再刷新失败，仍返回旧内容")
    void staleSnapshotSurvivesDbOutage() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 1, "v1", 0)));
        assertEquals("v1", registry.resolve("ai_ask_system", "FALLBACK", null).content);
        // 刷新周期未到：不查库
        assertEquals("v1", registry.resolve("ai_ask_system", "FALLBACK", null).content);
        verify(jdbcTemplate, times(1)).queryForList(anyString());

        // 快进刷新周期，DB 开始报错：沿用旧快照
        ReflectionTestUtils.setField(registry, "refreshSeconds", 0);
        when(jdbcTemplate.queryForList(anyString())).thenThrow(new RuntimeException("db down"));
        AiPromptRegistry.ResolvedPrompt r = registry.resolve("ai_ask_system", "FALLBACK", null);
        assertEquals("v1", r.content);
        assertEquals(1, r.version);
    }

    @Test
    @DisplayName("刷新周期内重复 resolve 不重查库（懒刷新）")
    void lazyRefreshSkipsDbWithinWindow() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 1, "v1", 0)));
        registry.resolve("ai_ask_system", "FALLBACK", null);
        registry.resolve("ai_ask_system", "FALLBACK", null);
        registry.resolve("ai_ask_system", "FALLBACK", null);
        verify(jdbcTemplate, times(1)).queryForList(anyString());
    }

    @Test
    @DisplayName("content 空白的行跳过；null/空 key 防御不抛异常")
    void blankContentAndBlankKeyDefensive() {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
            row("ai_ask_system", 1, "  ", 0)));
        assertEquals("FALLBACK", registry.resolve("ai_ask_system", "FALLBACK", null).content);
        // null / 空 key 走兜底且不抛异常
        assertEquals("FB", registry.resolve(null, "FB", null).content);
        assertEquals("FB", registry.resolve("", "FB", null).content);
    }
}
