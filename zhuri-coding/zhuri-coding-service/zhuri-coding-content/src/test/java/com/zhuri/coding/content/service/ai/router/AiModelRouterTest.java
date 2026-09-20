package com.zhuri.coding.content.service.ai.router;

import com.zhuri.coding.content.service.ai.AiMetricsCollector;
import com.zhuri.coding.content.service.ai.AiTokenMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * AiModelRouter 单元测试（质量维度路由 + 成本维度折算）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>{@code resolve} 按 feature 映射选模型，未注册 key 兜底且不抛（fail-open）；</li>
 *   <li>{@code costReport} 金额 = Σ tokens × 模型单价，并按 feature/model 两级拆分；</li>
 *   <li><b>未配置定价的模型列入 unpricedModels</b>（金额按 0 计但不静默 —— 否则"没配价"会被误读成"免费"）；</li>
 *   <li>{@code costPer1kTokens} 供横向比较"哪个功能的单位成本高"。</li>
 * </ol>
 */
class AiModelRouterTest {

    @Mock
    private ChatModel primaryModel;
    @Mock
    private ChatModel commentModel;
    @Mock
    private ChatModel qwenPlusModel;
    @Mock
    private ChatModel flashModel;
    @Mock
    private AiMetricsCollector metrics;
    @Mock
    private AiTokenMeter tokenMeter;

    private AiModelRouter router;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("primaryChatModel", primaryModel);
        models.put("commentChatModel", commentModel);
        models.put("qwenFlashChatModel", flashModel);
        router = new AiModelRouter(models, metrics, tokenMeter);
        ReflectionTestUtils.setField(router, "defaultModelKey", "primaryChatModel");
        ReflectionTestUtils.setField(router, "featureMapping", Map.of("comment_audit", "commentChatModel"));
        ReflectionTestUtils.setField(router, "promptPrice",
                Map.of("qwen-plus", 0.0008, "qwen-turbo", 0.0003));
        ReflectionTestUtils.setField(router, "completionPrice",
                Map.of("qwen-plus", 0.002, "qwen-turbo", 0.0006));
    }

    @Test
    @DisplayName("resolve 按 feature 映射选模型")
    void testResolveByFeatureMapping() {
        assertSame(commentModel, router.resolve("comment_audit"));
        assertSame(primaryModel, router.resolve("ask"));
    }

    @Test
    @DisplayName("resolve 配置了未注册的 key → 兜底首个可用模型（fail-open，不阻断主链路）")
    void testResolveUnknownKeyFallsBack() {
        ReflectionTestUtils.setField(router, "featureMapping", Map.of("ask", "notRegistered"));
        assertNotNull(router.resolve("ask"));
    }

    @Test
    @DisplayName("costReport 金额 = Σ tokens × 单价（元/千 token），并按 feature/model 拆分")
    void testCostReportComputesAmount() {
        // ask 用了 qwen-plus：1000 prompt + 500 completion
        // rerank 用了 qwen-turbo：2000 prompt
        Map<String, Map<String, Map<String, Object>>> raw = new LinkedHashMap<>();
        raw.put("ask", Map.of("qwen-plus", Map.of("prompt", 1000L, "completion", 500L, "total", 1500L)));
        raw.put("rerank", Map.of("qwen-turbo", Map.of("prompt", 2000L, "completion", 0L, "total", 2000L)));
        when(tokenMeter.summaryByFeatureModel(7)).thenReturn(raw);

        Map<String, Object> report = router.costReport(7);

        // ask = 1000/1000*0.0008 + 500/1000*0.002 = 0.0008 + 0.001 = 0.0018
        @SuppressWarnings("unchecked")
        Map<String, Object> byFeature = (Map<String, Object>) report.get("byFeature");
        @SuppressWarnings("unchecked")
        Map<String, Object> ask = (Map<String, Object>) byFeature.get("ask");
        assertEquals(0.0018, (Double) ask.get("cost"), 1e-9);
        assertEquals(1500L, ask.get("tokens"));

        // rerank = 2000/1000*0.0003 = 0.0006
        @SuppressWarnings("unchecked")
        Map<String, Object> rerank = (Map<String, Object>) byFeature.get("rerank");
        assertEquals(0.0006, (Double) rerank.get("cost"), 1e-9);

        // 总计 = 0.0024
        assertEquals(0.0024, (Double) report.get("totalCost"), 1e-9);
        assertEquals(3500L, report.get("totalTokens"));
        assertEquals(7, report.get("days"));
        assertEquals("CNY", report.get("currency"));
    }

    @Test
    @DisplayName("未配置定价的模型：金额按 0 计，但列入 unpricedModels（不静默当免费）")
    void testUnpricedModelFlagged() {
        Map<String, Map<String, Map<String, Object>>> raw = new LinkedHashMap<>();
        raw.put("ask", Map.of("mystery-model", Map.of("prompt", 5000L, "completion", 1000L, "total", 6000L)));
        when(tokenMeter.summaryByFeatureModel(7)).thenReturn(raw);

        Map<String, Object> report = router.costReport(7);

        @SuppressWarnings("unchecked")
        List<String> unpriced = (List<String>) report.get("unpricedModels");
        assertTrue(unpriced.contains("mystery-model"));
        assertEquals(0.0, (Double) report.get("totalCost"), 1e-9);
        assertEquals(6000L, report.get("totalTokens"));
    }

    @Test
    @DisplayName("costPer1kTokens 供横向比较单位成本（混用模型时按加权平均）")
    void testCostPer1kTokens() {
        Map<String, Map<String, Map<String, Object>>> raw = new LinkedHashMap<>();
        // 1000 prompt qwen-plus = 0.0008 元 → 每千 token 0.0008
        raw.put("ask", Map.of("qwen-plus", Map.of("prompt", 1000L, "completion", 0L, "total", 1000L)));
        when(tokenMeter.summaryByFeatureModel(7)).thenReturn(raw);

        Map<String, Object> report = router.costReport(7);

        assertEquals(0.0008, (Double) report.get("costPer1kTokens"), 1e-9);
    }

    @Test
    @DisplayName("天数越界收敛到 1~30，空数据返回零值不抛")
    void testDaysClampedAndEmptySafe() {
        when(tokenMeter.summaryByFeatureModel(1)).thenReturn(new LinkedHashMap<>());
        when(tokenMeter.summaryByFeatureModel(30)).thenReturn(new LinkedHashMap<>());

        assertEquals(1, router.costReport(0).get("days"));
        assertEquals(30, router.costReport(999).get("days"));
        assertEquals(0.0, (Double) router.costReport(7).get("totalCost"), 1e-9);
    }

    @Test
    @DisplayName("configSnapshot 同时给出路由映射与定价（运维一屏看全）")
    void testConfigSnapshotContainsPricing() {
        Map<String, Object> snap = router.configSnapshot();

        assertEquals("primaryChatModel", snap.get("default"));
        assertNotNull(snap.get("features"));
        assertNotNull(snap.get("pricing"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) snap.get("pricing");
        @SuppressWarnings("unchecked")
        Map<String, Object> plus = (Map<String, Object>) pricing.get("qwen-plus");
        assertEquals(0.0008, (Double) plus.get("promptPer1k"), 1e-9);
        assertEquals(0.002, (Double) plus.get("completionPer1k"), 1e-9);
    }

    @Test
    @DisplayName("多 feature 共用一个低成本模型（flash）：全部解析到同一 Bean")
    void testResolveMultipleFeaturesToFlash() {
        ReflectionTestUtils.setField(router, "featureMapping", Map.of(
                "rewrite", "qwenFlashChatModel",
                "rerank", "qwenFlashChatModel",
                "comment_audit", "qwenFlashChatModel",
                "aigc_detect", "qwenFlashChatModel"));

        assertSame(flashModel, router.resolve("rewrite"));
        assertSame(flashModel, router.resolve("rerank"));
        assertSame(flashModel, router.resolve("comment_audit"));
        assertSame(flashModel, router.resolve("aigc_detect"));
    }

    @Test
    @DisplayName("未映射的高价值 feature 走默认模型（不强模型决不落到低成本模型）")
    void testResolveUnmappedFeatureUsesDefaultStrong() {
        ReflectionTestUtils.setField(router, "featureMapping", Map.of(
                "rewrite", "qwenFlashChatModel"));

        assertSame(primaryModel, router.resolve("ask"));
        assertSame(primaryModel, router.resolve("ask_stream"));
        assertSame(primaryModel, router.resolve("creator_report"));
    }

    @Test
    @DisplayName("default 指向自动配置主模型 key（openAiChatModel）时正确命中")
    void testResolveWithAutoConfigDefaultKey() {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put("openAiChatModel", commentModel);
        models.put("qwenFlashChatModel", flashModel);
        router = new AiModelRouter(models, metrics, tokenMeter);
        ReflectionTestUtils.setField(router, "defaultModelKey", "openAiChatModel");
        ReflectionTestUtils.setField(router, "featureMapping", Map.of("rewrite", "qwenFlashChatModel"));

        assertSame(commentModel, router.resolve("ask"));
        assertSame(flashModel, router.resolve("rewrite"));
    }
}
