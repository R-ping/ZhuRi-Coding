package com.zhuri.coding.content.service.ai.agent.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.ai.agent.tools.SimilaritySearchTool;
import com.zhuri.coding.content.service.ai.agent.workers.CriticExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.QualityExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SafetyExpertWorker;
import com.zhuri.coding.content.service.ai.agent.workers.SeoExpertWorker;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import com.zhuri.coding.model.article.pojos.ApArticle;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 发布预检「显式工作流」编排器单测。
 *
 * <p>覆盖：完整成功路径（三专家并行 + 终审 + Vo 字段齐全）、专家阶段独立降级（某专家
 * 失败不影响整体）、阻断性失败（终审为空/异常 → 返回 null 由调用方降级）、相似度命中填充、
 * ctx 阶段产物按类型归档。各 worker 与查重工具全程 mock，Executor 同步直跑以保证确定性。
 */
@ExtendWith(MockitoExtension.class)
class PrecheckWorkflowTest {

    @Mock
    private SafetyExpertWorker safetyWorker;
    @Mock
    private QualityExpertWorker qualityWorker;
    @Mock
    private SeoExpertWorker seoWorker;
    @Mock
    private CriticExpertWorker criticWorker;
    @Mock
    private SimilaritySearchTool similarityTool;

    /** 同步执行器：使 CompletableFuture 在主线程完成，便于确定性断言 */
    private final Executor sync = Runnable::run;

    private PrecheckWorkflow workflow() {
        return new PrecheckWorkflow(safetyWorker, qualityWorker, seoWorker,
            criticWorker, similarityTool, sync);
    }

    private void mockWorkersAllOk(String safetyJson, String qualityJson, String seoJson) {
        when(safetyWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(safetyJson);
        when(qualityWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(qualityJson);
        when(seoWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(seoJson);
    }

    @Test
    @DisplayName("完整成功：三专家并行 + 终审 + VO 字段齐全")
    void success_fullFlow() {
        mockWorkersAllOk(
            "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}",
            "{\"quality_score\":82,\"is_tech\":true,\"suggestions\":[\"优化结构\"]}",
            "{\"tags\":[\"MySQL\",\"性能优化\"],\"summary\":\"本文讲解索引优化。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\","
                + "\"quality_score\":82,\"is_tech\":true,\"suggestions\":[\"优化结构\"],"
                + "\"tags\":[\"MySQL\",\"性能优化\"],\"summary\":\"本文讲解索引优化。\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        AiPrecheckVo vo = workflow().run("标题", "正文内容", 1L);

        assertNotNull(vo);
        assertFalse(vo.getViolation());
        assertEquals(82, vo.getQualityScore());
        assertTrue(vo.getTech());
        assertEquals(2, vo.getTags().size());
        assertEquals("本文讲解索引优化。", vo.getSummary());
        assertNotNull(vo.getLatencyMs());
    }

    @Test
    @DisplayName("相似度命中填充：DUPLICATE 阶段高相似 → VO 相似字段写入")
    void success_withSimilarity() {
        mockWorkersAllOk(
            "{\"is_violation\":false}",
            "{\"quality_score\":60,\"is_tech\":true,\"suggestions\":[]}",
            "{\"tags\":[\"标题\"],\"summary\":\"摘要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":60,\"is_tech\":true,"
                + "\"suggestions\":[],\"tags\":[\"标题\"],\"summary\":\"摘要。\"}");
        ApArticle a = new ApArticle();
        a.setId(99L);
        a.setTitle("已有相似文章");
        SimilaritySearchTool.SimilarArticle hit = new SimilaritySearchTool.SimilarArticle(a, 0.93);
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(hit));

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        assertNotNull(vo);
        assertEquals(99L, vo.getSimilarArticleId());
        assertEquals("已有相似文章", vo.getSimilarTitle());
        assertEquals(0.93, vo.getSimilarity());
    }

    @Test
    @DisplayName("独立降级：SEO 专家失败 SKIPPED 填默认值，安全+质量照常产出 VO")
    void degrade_nonBlockingStageFailure() {
        mockWorkersAllOk(
            "{\"is_violation\":false}",
            "{\"quality_score\":70,\"is_tech\":true,\"suggestions\":[\"建议\"]}",
            "{\"tags\":[\"A\"],\"summary\":\"概要。\"}");
        // SEO 返回空 → SKIPPED，merge 以默认标签/摘要继续
        when(seoWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":70,\"is_tech\":true,"
                + "\"suggestions\":[\"建议\"],\"tags\":[],\"summary\":\"\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        // SEO 缺失不阻断整体：仍产出 VO（violation 与质量来自安全/质量阶段）
        assertNotNull(vo);
        assertFalse(vo.getViolation());
        assertEquals(70, vo.getQualityScore());
    }

    @Test
    @DisplayName("阻断：安全评审缺失 → 短路质量/SEO/终审 → 返回 null 由调用方降级")
    void block_safetyMissing() {
        when(safetyWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("");

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        // 安全缺失属阻断：即便 quality/seo 可达也不产出 VO（DAG 短路 + FORMAT 判阻断）
        assertNull(vo);
        verify(qualityWorker, never()).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(seoWorker, never()).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("阻断性失败：终审为空 → 返回 null 由调用方降级")
    void block_criticNull() {
        mockWorkersAllOk(
            "{\"is_violation\":false}",
            "{\"quality_score\":60,\"is_tech\":true,\"suggestions\":[]}",
            "{\"tags\":[\"A\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("critic down"));
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        assertNull(vo);
    }

    @Test
    @DisplayName("失败自愈：安全专家首次异常，重试后成功 → 产出 VO 且重试被调用")
    void heal_retryAfterTransientFailure() {
        when(safetyWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new RuntimeException("transient down"))
            .thenReturn("{\"is_violation\":false}");
        when(qualityWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"quality_score\":75,\"is_tech\":true,\"suggestions\":[]}");
        when(seoWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"tags\":[\"B\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":75,\"is_tech\":true,"
                + "\"suggestions\":[],\"tags\":[\"B\"],\"summary\":\"概要。\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        // 首次抛异常 → 重试（默认 stageMaxRetry=2）成功，未因瞬时故障而整体降级
        assertNotNull(vo);
        assertFalse(vo.getViolation());
        verify(safetyWorker, times(2)).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("失败自愈：质量专家连续抛异常（重试耗尽）→ SKIPPED，其他阶段照常产出 VO")
    void heal_retryExhaustedForNonBlockingStage() {
        when(safetyWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false}");
        when(qualityWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new RuntimeException("still down"));
        when(seoWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"tags\":[\"C\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":0,\"is_tech\":true,"
                + "\"suggestions\":[],\"tags\":[\"C\"],\"summary\":\"概要。\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        AiPrecheckVo vo = workflow().run("标题", "正文", null);

        // 质量（非阻断）重试耗尽仅 SKIPPED，质量字段走默认值，整体仍产出 VO
        assertNotNull(vo);
        assertFalse(vo.getViolation());
        verify(qualityWorker, times(2)).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("阶段事件：完整成功路径按 running→done 顺序派发 6 阶段，末事件为 FORMAT done")
    void stageEvents_successOrder() {
        mockWorkersAllOk(
            "{\"is_violation\":false,\"violation_type\":\"\",\"violation_reason\":\"\"}",
            "{\"quality_score\":82,\"is_tech\":true,\"suggestions\":[\"优化结构\"]}",
            "{\"tags\":[\"MySQL\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":82,\"is_tech\":true,"
                + "\"suggestions\":[\"优化结构\"],\"tags\":[\"MySQL\"],\"summary\":\"概要。\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        PrecheckWorkflow wf = workflow();
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> listener =
            (type, detail) -> events.add(type + ":" + detail.get());
        wf.addStageListener(listener);

        AiPrecheckVo vo = wf.run("标题", "正文", 1L);

        assertNotNull(vo);
        // 六个阶段都应出现 running 与 done
        for (StageType t : StageType.values()) {
            assertTrue(events.contains(t + ":running"), "缺少 " + t + " running：" + events);
            assertTrue(events.contains(t + ":done"), "缺少 " + t + " done：" + events);
        }
        // 每阶段 running 先于其各自 done（顺序合理）
        for (StageType t : StageType.values()) {
            assertTrue(events.indexOf(t + ":running") < events.indexOf(t + ":done"),
                t + " 的 running 应早于 done：" + events);
        }
        // FORMAT 作为最后一个阶段，以 done 收尾
        assertEquals("FORMAT:done", events.get(events.size() - 1));
    }

    @Test
    @DisplayName("阶段事件并发隔离：按对象引用移除监听器后不再触发（模拟 impl 流式路径 finally 清理）")
    void stageEvents_removeListenerIsolation() {
        mockWorkersAllOk(
            "{\"is_violation\":false}",
            "{\"quality_score\":70,\"is_tech\":true,\"suggestions\":[]}",
            "{\"tags\":[\"A\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":false,\"quality_score\":70,\"is_tech\":true,"
                + "\"suggestions\":[],\"tags\":[\"A\"],\"summary\":\"概要。\"}");
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        PrecheckWorkflow wf = workflow();
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> listener =
            (type, detail) -> events.add(type + ":" + detail.get());

        wf.addStageListener(listener);
        assertNotNull(wf.run("标题", "正文", 1L));
        // 模拟 impl.precheckStream 的 finally：仅按对象引用移除本次监听器
        wf.removeStageListener(listener);

        events.clear();
        wf.run("标题2", "正文2", 2L);
        // 移除后该监听器不再收到任何阶段事件（并发用户互不串扰）
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("阶段事件降级：终审崩溃 → 派发 CRITIC done 后附加 degraded，run 返回 null")
    void stageEvents_criticDegraded() {
        mockWorkersAllOk(
            "{\"is_violation\":false}",
            "{\"quality_score\":60,\"is_tech\":true,\"suggestions\":[]}",
            "{\"tags\":[\"A\"],\"summary\":\"概要。\"}");
        when(criticWorker.review(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("critic down"));
        when(similarityTool.searchSimilar(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        PrecheckWorkflow wf = workflow();
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.BiConsumer<StageType, java.util.function.Supplier<String>> listener =
            (type, detail) -> events.add(type + ":" + detail.get());
        wf.addStageListener(listener);

        AiPrecheckVo vo = wf.run("标题", "正文", null);

        assertNull(vo);
        assertTrue(events.contains("CRITIC:running"));
        assertTrue(events.contains("CRITIC:done"));
        assertTrue(events.contains("CRITIC:degraded"), "终审崩溃应派发 degraded：" + events);
        // degraded 紧跟 done 之后
        assertTrue(events.indexOf("CRITIC:done") < events.indexOf("CRITIC:degraded"));
    }

    @Test
    @DisplayName("DAG 短跑：安全判定违规 → 短路跳过 质量/SEO/查重/终审，仍产出 violation VO 且六阶段事件齐备")
    void dag_shortCircuitOnViolation() {
        when(safetyWorker.review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn("{\"is_violation\":true,\"violation_type\":\"违法违规\",\"violation_reason\":\"包含禁止内容\"}");
        // 不 stub 其余 worker/查重：短路发生时不应被调用

        Executor sync = Runnable::run;
        PrecheckWorkflow wf = new PrecheckWorkflow(safetyWorker, qualityWorker, seoWorker,
            criticWorker, similarityTool, sync);
        java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        wf.addStageListener((type, detail) -> events.add(type + ":" + detail.get()));

        AiPrecheckVo vo = wf.run("标题", "正文", null);

        assertNotNull(vo);
        assertTrue(vo.getViolation());
        assertEquals("违法违规", vo.getViolationType());
        assertTrue(vo.getTags().isEmpty());
        // 违规短路：质量/SEO/查重/终审均不被调用（避免无谓的高成本评估）
        verify(qualityWorker, never()).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(seoWorker, never()).review(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(criticWorker, never()).review(org.mockito.ArgumentMatchers.anyString());
        verify(similarityTool, never()).searchSimilar(org.mockito.ArgumentMatchers.anyString());
        // 即便被裁剪的阶段也必须派发 running→done（令前端各阶段进度条均收口、不悬挂），末端 FORMAT:done 收尾
        for (StageType t : StageType.values()) {
            assertTrue(events.contains(t + ":running"), "缺少 " + t + " running：" + events);
            assertTrue(events.contains(t + ":done"), "缺少 " + t + " done：" + events);
        }
        assertEquals("FORMAT:done", events.get(events.size() - 1));
    }
}