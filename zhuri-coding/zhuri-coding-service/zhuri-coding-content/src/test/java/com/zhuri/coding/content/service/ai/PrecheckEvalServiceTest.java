package com.zhuri.coding.content.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.zhuri.coding.content.service.ai.PrecheckEvalService.EvalCase;
import com.zhuri.coding.content.service.ai.PrecheckEvalService.Expected;
import com.zhuri.coding.content.service.ai.PrecheckEvalService.PerCase;
import com.zhuri.coding.content.service.ai.PrecheckEvalService.PrecheckEvalReport;
import com.zhuri.coding.content.service.ai.agent.workflow.PrecheckWorkflow;
import com.zhuri.coding.content.service.ai.impl.PrecheckEvalServiceImpl;
import com.zhuri.coding.model.article.dtos.AiPrecheckVo;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 发布预检「阶段级评测」纯单测（不依赖 LLM，本地可跑、断言准确）。
 *
 * <p>核心覆盖两处：
 * <ol>
 *   <li>维度命中判定函数 {@code PrecheckEvalServiceImpl.assess}（纯静态，Deterministic）：
 *       安全/标签/质量/摘要各自的命中语义、边界（quality == floor、期望未约束默认通过）、</li>
 *   <li>降级语义：VO 为 null → 各维度一律未命中 + degraded=true + pass=false；</li>
 *   <li>报告聚合 {@code evaluatePrecheck}（mock 工作流返回给定 VO）：各维度命中率 / 整体准确率 / perCase / baseline 结构。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PrecheckEvalServiceTest {

    @Mock
    private PrecheckWorkflow workflow;

    // ===== 维度命中判定（纯逻辑，无 LLM）=====

    @Test
    @DisplayName("四维全中：安全/标签/质量/摘要均命中 → pass=true")
    void assess_allHit() {
        AiPrecheckVo vo = vo(false, List.of("MySQL", "索引"), 82, "本文讲解索引优化的最佳实践。");
        PerCase pc = PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL", "索引"), 60, "索引"));
        assertFalse(pc.isDegraded());
        assertTrue(pc.isSafety());
        assertTrue(pc.isTag());
        assertTrue(pc.isQuality());
        assertTrue(pc.isSummary());
        assertTrue(pc.isPass());
    }

    @Test
    @DisplayName("安全维度：VO 违规状态与期望不一致 → safety 未命中，整体 pass=false")
    void assess_safetyMismatch() {
        // 期望不违规(false)，VO 却判违规(true) → safety 未命中
        AiPrecheckVo vo = vo(true, List.of("MySQL"), 82, "索引优化");
        PerCase pc = PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL"), 60, "索引"));
        assertFalse(pc.isSafety());
        assertFalse(pc.isPass());
        // 其余维度各自仍命中
        assertTrue(pc.isTag());
        assertTrue(pc.isQuality());
    }

    @Test
    @DisplayName("标签维度：tags_each_in 任一命中即过；完全不重叠则未命中")
    void assess_tagOverlap() {
        AiPrecheckVo vo = vo(false, List.of("JVM", "GC"), 70, "GC 调优");
        // 期望 {MySQL, GC} 至少一个命中（GC）→ tag 命中
        assertTrue(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL", "GC"), 60, "GC")).isTag());
        // 期望 {Redis, 缓存} 与 VO 标签无交集 → tag 未命中
        assertFalse(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("Redis", "缓存"), 60, "GC")).isTag());
        // 期望 tags 为空（未约束）→ 默认通过
        assertTrue(PrecheckEvalServiceImpl.assess(vo, expected(false, null, 60, "GC")).isTag());
    }

    @Test
    @DisplayName("质量维度：VO.qualityScore >= floor 即命中（含边界相等）")
    void assess_qualityFloor() {
        AiPrecheckVo vo = vo(false, List.of("MySQL"), 60, "索引");
        // quality 60 >= floor 60 → 命中（边界含等号）
        PerCase atFloor = PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL"), 60, "索引"));
        assertTrue(atFloor.isQuality());
        // VO 60 < floor 70 → 未命中
        PerCase below = PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL"), 70, "索引"));
        assertFalse(below.isQuality());
        // floor 为 null（未约束）→ 默认通过
        PerCase noFloor = PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("MySQL"), null, "索引"));
        assertTrue(noFloor.isQuality());
    }

    @Test
    @DisplayName("摘要维度：summary 包含关键词即命中；不含或未约束关键词语义正确")
    void assess_summaryKeyword() {
        AiPrecheckVo vo = vo(false, List.of("Redis"), 70, "本文讲解缓存穿透与雪崩的应对");
        // 含关键词"缓存" → 命中
        assertTrue(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("Redis"), 60, "缓存")).isSummary());
        // 不含关键词"雪崩"→ 命中（包含），含"布隆"下面再验证不含场景
        // 不含关键词"布隆" → 未命中
        assertFalse(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("Redis"), 60, "布隆")).isSummary());
        // 关键词为空白（未约束）→ 默认通过
        assertTrue(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("Redis"), 60, "")).isSummary());
        assertTrue(PrecheckEvalServiceImpl.assess(vo, expected(false, List.of("Redis"), 60, null)).isSummary());
    }

    @Test
    @DisplayName("降级：VO 为 null → 各维度一律未命中 + degraded=true + pass=false")
    void assess_degraded() {
        PerCase pc = PrecheckEvalServiceImpl.assess(null, expected(false, List.of("MySQL"), 60, "索引"));
        assertTrue(pc.isDegraded());
        assertFalse(pc.isSafety());
        assertFalse(pc.isTag());
        assertFalse(pc.isQuality());
        assertFalse(pc.isSummary());
        assertFalse(pc.isPass());
        // expected 为 null 同样按降级处理
        PerCase pc2 = PrecheckEvalServiceImpl.assess(vo(false, List.of("MySQL"), 82, "索引"), null);
        assertTrue(pc2.isDegraded());
        assertFalse(pc2.isPass());
    }

    // ===== 报告聚合（mock 工作流，无 LLM）=====

    @Test
    @DisplayName("报告聚合：多 case 各维度命中率 / 整体准确率 / perCase / baseline 结构正确")
    void evaluatePrecheck_aggregation() {
        // case1 全中（通过），case2 安全维度未命中（不通过）
        when(workflow.run(anyString(), anyString(), any()))
            .thenReturn(vo(false, List.of("MySQL", "索引"), 82, "本文讲解索引优化。"))   // 第 1 次调用：全中
            .thenReturn(vo(true, List.of("Redis"), 70, "缓存穿透"))                      // 第 2 次调用：safety 未命中
            .thenReturn(null);                                                          // 第 3 次调用：降级
        PrecheckEvalServiceImpl service = new PrecheckEvalServiceImpl(workflow);

        EvalCase c1 = new EvalCase("用例A", "正文A", expected(false, List.of("MySQL"), 60, "索引"));
        EvalCase c2 = new EvalCase("用例B", "正文B", expected(false, List.of("Redis"), 60, "缓存"));
        EvalCase c3 = new EvalCase("用例C", "正文C", expected(false, List.of("Redis"), 60, "缓存"));
        PrecheckEvalReport report = service.evaluatePrecheck(List.of(c1, c2, c3));

        // 总数与降级
        org.junit.jupiter.api.Assertions.assertEquals(3, report.getTotalCases());
        org.junit.jupiter.api.Assertions.assertEquals(1, report.getDegradedCases());
        // 整体准确率：只有 case1 四维全中 → 1/3 ≈ 0.333
        org.junit.jupiter.api.Assertions.assertEquals(1, report.getPassCases());
        org.junit.jupiter.api.Assertions.assertEquals(0.333, report.getOverallAccuracy(), 0.001);
        // 安全命中率：case1 命中，case2 未命中，case3 降级未命中 → 1/3
        org.junit.jupiter.api.Assertions.assertEquals(0.333, report.getSafetyHitRate(), 0.001);
        // 标签命中率：case1、case2 命中，case3 降级未命中 → 2/3
        org.junit.jupiter.api.Assertions.assertEquals(0.667, report.getTagHitRate(), 0.001);
        // perCase 粒度
        org.junit.jupiter.api.Assertions.assertEquals(3, report.getPerCase().size());
        PerCase p0 = report.getPerCase().get(0);
        assertFalse(p0.isDegraded());
        assertTrue(p0.isPass());                       // case1 全中 → 通过
        PerCase p1 = report.getPerCase().get(1);
        assertFalse(p1.isDegraded());
        assertFalse(p1.isSafety());                  // case2 安全未命中
        assertTrue(p1.isTag());
        assertTrue(p1.isQuality());
        assertFalse(p1.isPass());                      // case2 不通过
        PerCase p2 = report.getPerCase().get(2);
        assertTrue(p2.isDegraded());                   // case3 降级
        assertFalse(p2.isPass());
        // baseline 占位
        assertFalse(report.getBaseline().isAvailable());
        assertTrue(report.getBaseline().getNote().contains("待接入"));
    }

    private static AiPrecheckVo vo(boolean violation, List<String> tags, int quality, String summary) {
        AiPrecheckVo vo = new AiPrecheckVo();
        vo.setViolation(violation);
        vo.setTags(tags);
        vo.setQualityScore(quality);
        vo.setSummary(summary);
        return vo;
    }

    private static Expected expected(boolean violation, List<String> tags, Integer floor, String keyword) {
        return new Expected(violation, tags, floor, keyword);
    }
}