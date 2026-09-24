package com.zhuri.coding.content.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhuri.coding.content.service.ai.PrecheckEvalService.PrecheckEvalReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 发布预检「阶段级评测」Gate 回归测试（CI 可选，无 LLM key 自动跳过）。
 *
 * <p>与 {@link AiEvalServiceImplGateTest} 同属 Gate 命名与"离线 / 无 key 可跑"约定：
 * 由 {@code @EnabledIfEnvironmentVariable(DASH_SCOPE_API_KEY)} 门控——存在 LLM key（本服务 spring.ai 配置
 * 读取 ${DASH_SCOPE_API_KEY}）时才加载真实 Spring 上下文（含真实 {@link PrecheckWorkflow} + 真实 worker，不 mock LLM）
 * 跑 5 条 golden；无 key 时类级跳过、不启动上下文，普通 CI（无 key）无缝离线。
 *
 * <p>成本：5 条 golden × 1 次真实预检（SAFETY/QUALITY/SEO/CRITIC + 查重）≈ 数秒，限 CI 手动/定时跑。
 * 断言策略：只断言「评测确实跑了」（拿到报告、totalCases==golden 数、整体准确率 &gt;= 0 仅记录不断言硬指标），
 * 避免 LLM 非确定性导致 flaky。真实质量对比看 {@link PrecheckEvalReport#getOverallAccuracy()} 与 perCase 明细的日志。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASH_SCOPE_API_KEY", matches = ".+")
class PrecheckEvalGateTest {

    private static final Logger log = LoggerFactory.getLogger(PrecheckEvalGateTest.class);

    @Autowired
    private PrecheckEvalService precheckEvalService;

    @Test
    @DisplayName("显式编排版在 golden 集上的阶段级回测（仅记录，不断言硬指标）")
    void gate_realWorkflowGoldenRegression() {
        List<PrecheckEvalService.EvalCase> cases = precheckEvalService.loadDefaultCases();
        // golden 集至少 5 条（含 1 条违规用例），评测集加载应完整
        assertTrue(cases.size() >= 5, "golden 集应至少 5 条，实际=" + cases.size());

        PrecheckEvalReport report = precheckEvalService.evaluatePrecheck(cases);

        // 评测真实执行：报告结构与统计字段齐全
        assertEquals(cases.size(), report.getTotalCases());
        // 整体准确率恒 >= 0（占位断言：仅标记回测确实跑通，不做硬指标回归，避免 LLM flaky）
        assertTrue(report.getOverallAccuracy() >= 0);
        assertTrue(report.getPassCases() + report.getDegradedCases() <= report.getTotalCases());

        // 打印报告供人工在 CI 日志中对齐"显式编排版 = 本次跑分"
        log.info("[PrecheckEval][Gate] totalCases={}, passCases={}, degradedCases={}, overallAccuracy={}",
            report.getTotalCases(), report.getPassCases(), report.getDegradedCases(), report.getOverallAccuracy());
        log.info("[PrecheckEval][Gate] safety={}, tag={}, quality={}, summary={}",
            report.getSafetyHitRate(), report.getTagHitRate(), report.getQualityHitRate(), report.getSummaryHitRate());
        for (PrecheckEvalService.PerCase pc : report.getPerCase()) {
            log.info("[PrecheckEval][Gate] case[{}] pass={} degraded={} safety={} tag={} quality={} summary={}",
                pc.getTitle(), pc.isPass(), pc.isDegraded(), pc.isSafety(), pc.isTag(), pc.isQuality(), pc.isSummary());
        }
        // baseline 仍为占位（增量4 未接入直答版真值）
        assertEquals(false, report.getBaseline().isAvailable());
    }
}