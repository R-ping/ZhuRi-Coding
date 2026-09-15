package com.heima.content.service.ai;

import com.heima.content.service.ai.impl.AiEvalServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测门禁单元测试（P1-3）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>阈值判定方向：avgRecall@5 / avgCitedPrecision 为下限（>=），avgUnsupportedRate / refusalRate
 *       分别为上限（<=）与下限（>=）；value == threshold 视为通过（边界含等号）；</li>
 *   <li><b>fail-closed</b>：报告缺失 / 带 error / 召回有效题数低于 min-cases → 按 0 分判定，评测跑不出来 ≠ 通过；</li>
 *   <li>评测集无 expectNoAnswer 条目 → refusalRate 项 skipped 视为通过（门禁只约束存在的样本）；</li>
 *   <li>minRefusalRate 为负 → 该项检查整体禁用。</li>
 * </ol>
 */
class AiEvalServiceImplGateTest {

    private static AiEvalServiceImpl.GateThresholds thresholds(double minRecall, double minPrecision,
                                                               double maxUnsupported, double minRefusal,
                                                               int minCases) {
        return new AiEvalServiceImpl.GateThresholds(minRecall, minPrecision, maxUnsupported, minRefusal, minCases);
    }

    private static Map<String, Object> recallReport(int cases, double avgRecall5) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cases", cases);
        m.put("avgRecall@5", avgRecall5);
        m.put("avgRecall@10", avgRecall5);
        return m;
    }

    private static Map<String, Object> answerReport(double precision, double unsupported,
                                                    int refusalCases, double refusalRate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cases", refusalCases + 10);
        m.put("scored", 10);
        m.put("refusalCases", refusalCases);
        m.put("refusalHit", refusalCases);
        m.put("refusalRate", refusalRate);
        m.put("avgCitedPrecision", precision);
        m.put("avgCitedRecall", 60.0);
        m.put("avgUnsupportedRate", unsupported);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkOf(Map<String, Object> gate, String metric) {
        List<Map<String, Object>> checks = (List<Map<String, Object>>) gate.get("checks");
        assertNotNull(checks);
        for (Map<String, Object> c : checks) {
            if (metric.equals(c.get("metric"))) {
                return c;
            }
        }
        return null;
    }

    @Test
    @DisplayName("指标全达标 → pass=true 且 4 项 check 全过")
    void allPass() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 72.3), answerReport(75.0, 12.5, 2, 100.0));
        assertEquals(Boolean.TRUE, gate.get("pass"));
        for (String metric : new String[]{"avgRecall@5", "avgCitedPrecision", "avgUnsupportedRate", "refusalRate"}) {
            assertEquals(Boolean.TRUE, checkOf(gate, metric).get("pass"), metric);
        }
    }

    @Test
    @DisplayName("avgRecall@5 低于下限 → 整体 fail，其余项不受牵连")
    void recallBelowThreshold() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 45.0), answerReport(75.0, 12.5, 2, 100.0));
        assertEquals(Boolean.FALSE, gate.get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "avgRecall@5").get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "avgCitedPrecision").get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "avgUnsupportedRate").get("pass"));
    }

    @Test
    @DisplayName("未溯源率超上限（40 > 30）→ fail")
    void unsupportedRateAboveLimit() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 72.3), answerReport(75.0, 40.0, 2, 100.0));
        assertEquals(Boolean.FALSE, gate.get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "avgUnsupportedRate").get("pass"));
    }

    @Test
    @DisplayName("value == threshold 视为通过（边界含等号）")
    void thresholdBoundary() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(10, 60.0), answerReport(50.0, 30.0, 2, 50.0));
        assertEquals(Boolean.TRUE, gate.get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "avgRecall@5").get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "avgCitedPrecision").get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "avgUnsupportedRate").get("pass"));
        assertEquals(Boolean.TRUE, checkOf(gate, "refusalRate").get("pass"));
    }

    @Test
    @DisplayName("fail-closed：recall 报告带 error → 按 0 分判定 fail，detail 说明原因")
    void recallReportErrorFails() {
        Map<String, Object> bad = recallReport(0, 0);
        bad.put("error", "评测集为空");
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            bad, answerReport(75.0, 12.5, 2, 100.0));
        assertEquals(Boolean.FALSE, gate.get("pass"));
        Map<String, Object> c = checkOf(gate, "avgRecall@5");
        assertEquals(Boolean.FALSE, c.get("pass"));
        assertTrue(String.valueOf(c.get("detail")).contains("error"));
    }

    @Test
    @DisplayName("fail-closed：召回有效题数 < min-cases → fail（防评测集被清空后静默通过）")
    void recallTooFewCasesFails() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(3, 100.0), answerReport(75.0, 12.5, 2, 100.0));
        assertEquals(Boolean.FALSE, gate.get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "avgRecall@5").get("pass"));
    }

    @Test
    @DisplayName("answer 报告缺失 → 三个 answer 指标按 0 分判定 fail")
    void answerReportMissingFails() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 72.3), null);
        assertEquals(Boolean.FALSE, gate.get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "avgCitedPrecision").get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "avgUnsupportedRate").get("pass"));
    }

    @Test
    @DisplayName("评测集无 expectNoAnswer 条目 → refusalRate 项 skipped 视为通过")
    void refusalSkippedWhenNoCases() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 72.3), answerReport(75.0, 12.5, 0, 0.0));
        assertEquals(Boolean.TRUE, gate.get("pass"));
        Map<String, Object> c = checkOf(gate, "refusalRate");
        assertEquals(Boolean.TRUE, c.get("pass"));
        assertEquals(Boolean.TRUE, c.get("skipped"));
    }

    @Test
    @DisplayName("refusalRate 低于下限 → fail（有 expectNoAnswer 条目且拒绝不达标）")
    void refusalBelowThresholdFails() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, 50, 10),
            recallReport(22, 72.3), answerReport(75.0, 12.5, 2, 0.0));
        assertEquals(Boolean.FALSE, gate.get("pass"));
        assertEquals(Boolean.FALSE, checkOf(gate, "refusalRate").get("pass"));
    }

    @Test
    @DisplayName("minRefusalRate 为负 → 该项检查整体禁用")
    void refusalCheckDisabled() {
        Map<String, Object> gate = AiEvalServiceImpl.evaluateGate(
            thresholds(60, 50, 30, -1, 10),
            recallReport(22, 72.3), answerReport(75.0, 12.5, 0, 0.0));
        assertEquals(Boolean.TRUE, gate.get("pass"));
        assertEquals(null, checkOf(gate, "refusalRate"));
    }

    @Test
    @DisplayName("runGate 编排：gate.pass 与子报告透传，结构完整")
    void runGateOrchestration() {
        AiEvalServiceImpl service = new AiEvalServiceImpl();
        ReflectionTestUtils.setField(service, "gateMinAvgRecall", 60d);
        ReflectionTestUtils.setField(service, "gateMinCitedPrecision", 50d);
        ReflectionTestUtils.setField(service, "gateMaxUnsupportedRate", 30d);
        ReflectionTestUtils.setField(service, "gateMinRefusalRate", 50d);
        ReflectionTestUtils.setField(service, "gateMinCases", 10);

        AiEvalServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(recallReport(22, 72.3)).when(spy).runEval();
        org.mockito.Mockito.doReturn(answerReport(75.0, 12.5, 2, 100.0)).when(spy).runAnswerEval(12);

        Map<String, Object> report = spy.runGate();
        Map<String, Object> gate = (Map<String, Object>) report.get("gate");
        assertEquals(Boolean.TRUE, gate.get("pass"));
        assertNotNull(report.get("recall"));
        assertNotNull(report.get("answer"));
        assertNotNull(report.get("costMs"));
        assertEquals(4, ((List<?>) gate.get("checks")).size());
    }
}
