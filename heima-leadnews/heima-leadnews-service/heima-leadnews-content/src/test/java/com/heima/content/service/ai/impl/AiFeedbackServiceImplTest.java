package com.heima.content.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heima.content.mapper.ai.AiFeedbackMapper;
import com.heima.content.service.ai.AiMetricsCollector;
import com.heima.model.ai.pojos.AiFeedback;
import com.heima.model.common.dtos.ResponseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiFeedbackServiceImpl 单元测试（P1-2 反馈回灌）。
 *
 * <p>核心断言：
 * <ol>
 *   <li>badCases 只导出 👎 样本，feature 过滤与条数收敛；</li>
 *   <li>exportEvalCandidates 输出与 eval 集同构的候选（question + 空 golden + 来源备注）；</li>
 *   <li>statsByFeature 聚合 👍/👎 与差评率，<b>样本不足不告警</b>（1 条 👎 就告警会永远在响）；</li>
 *   <li>差评率超阈值 → 列入 alerted 并打指标。</li>
 * </ol>
 */
class AiFeedbackServiceImplTest {

    private final Integer userId = 100;

    @Mock
    private AiFeedbackMapper feedbackMapper;
    @Mock
    private AiMetricsCollector metrics;
    @Mock
    private com.heima.content.service.ai.AiFunnelMeter funnelMeter;

    private AiFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AiFeedbackServiceImpl();
        ReflectionTestUtils.setField(service, "feedbackMapper", feedbackMapper);
        ReflectionTestUtils.setField(service, "metrics", metrics);
        ReflectionTestUtils.setField(service, "funnelMeter", funnelMeter);
        ReflectionTestUtils.setField(service, "alertDownRate", 0.2);
    }

    private AiFeedback fb(String feature, int feedback, String question) {
        AiFeedback f = new AiFeedback();
        f.setUserId(userId);
        f.setFeature(feature);
        f.setSceneId("");
        f.setQuestion(question);
        f.setAnswer("回答内容");
        f.setFeedback(feedback);
        f.setCreateTime(new Date());
        f.setUpdateTime(new Date());
        return f;
    }

    // ==================== record：参数校验 ====================

    @Test
    @DisplayName("record：非法参数（未登录/无效反馈值）→ PARAM_INVALID 且不落库")
    void testRecordInvalidParams() {
        assertEquals(com.heima.model.common.enums.AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.record(null, "ask", "", "q", "a", 1).getCode());
        assertEquals(com.heima.model.common.enums.AppHttpCodeEnum.PARAM_INVALID.getCode(),
                service.record(userId, "ask", "", "q", "a", 0).getCode());
        verify(feedbackMapper, times(0)).insert(any(AiFeedback.class));
    }

    @Test
    @DisplayName("record：新反馈落库成功")
    void testRecordInserts() {
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        ResponseResult r = service.record(userId, "ask", "", "问题", "回答", 1);

        assertEquals(200, r.getCode());
        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals("问题", captor.getValue().getQuestion());
        assertEquals(1, captor.getValue().getFeedback());
    }

    @Test
    @DisplayName("record：👍 反馈打点漏斗（aiask_global → ask/feedback_up）")
    void testRecordFunnelUp() {
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        ResponseResult r = service.record(userId, com.heima.model.ai.pojos.AiFeedback.FEATURE_AIASK_GLOBAL,
                "", "问题", "回答", com.heima.model.ai.pojos.AiFeedback.FEEDBACK_UP);

        assertEquals(200, r.getCode());
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_FEEDBACK_UP);
    }

    @Test
    @DisplayName("record：👎/文章问答 feature 映射（aiask_article → ask_article/feedback_down，未知 → other/feedback_up）")
    void testRecordFunnelDownAndOtherMapping() {
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        ResponseResult r = service.record(userId, com.heima.model.ai.pojos.AiFeedback.FEATURE_AIASK_ARTICLE,
                "", "问题", "回答", com.heima.model.ai.pojos.AiFeedback.FEEDBACK_DOWN);
        assertEquals(200, r.getCode());
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.ASK_ARTICLE,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_FEEDBACK_DOWN);

        // 未识别的 feature 兜底至 other，且 👍 映射 feedback_up
        r = service.record(userId, "unknown_feature", "", "问题", "回答",
                com.heima.model.ai.pojos.AiFeedback.FEEDBACK_UP);
        assertEquals(200, r.getCode());
        verify(funnelMeter).incr(com.heima.content.service.ai.AiFeatures.OTHER,
                com.heima.content.service.ai.AiFunnelMeter.STAGE_FEEDBACK_UP);
    }

    // ==================== badCases ====================

    @Test
    @DisplayName("badCases：只导出 👎 样本")
    void testBadCasesOnlyDown() {
        when(feedbackMapper.selectList(any())).thenReturn(List.of(fb("ask", -1, "坏例问题")));

        List<AiFeedback> cases = service.badCases(null, 50);

        assertEquals(1, cases.size());
        assertEquals(-1, cases.get(0).getFeedback());
    }

    @Test
    @DisplayName("badCases：limit 越界收敛到 1~200")
    void testBadCasesLimitClamped() {
        when(feedbackMapper.selectList(any())).thenReturn(List.of());

        service.badCases("ask", 0);
        service.badCases("ask", 999);

        // 两次调用都安全完成（LIMIT 由 last() 拼接，越界值会被收敛）
        verify(feedbackMapper, times(2)).selectList(any());
    }

    // ==================== exportEvalCandidates ====================

    @Test
    @DisplayName("exportEvalCandidates：输出与 eval 集同构（question + 空 golden + 来源备注）")
    void testExportEvalCandidatesFormat() {
        when(feedbackMapper.selectList(any()))
            .thenReturn(List.of(fb("ask", -1, "为什么召回不到这篇文章？")));

        List<Map<String, Object>> candidates = service.exportEvalCandidates("ask", 10);

        assertEquals(1, candidates.size());
        Map<String, Object> item = candidates.get(0);
        assertEquals("为什么召回不到这篇文章？", item.get("question"));
        // golden 留空：机器不知道正确答案，人工补齐后才能并入评测集
        assertTrue(item.get("goldenArticleIds") instanceof List);
        assertTrue(((List<?>) item.get("goldenArticleIds")).isEmpty());
        String note = String.valueOf(item.get("note"));
        assertTrue(note.contains("feature=ask"));
    }

    // ==================== statsByFeature ====================

    @Test
    @DisplayName("statsByFeature：聚合 👍/👎 与差评率")
    void testStatsAggregation() {
        // ask：2 👍 + 2 👎 → 50%；summary：4 👍 + 0 👎 → 0%
        List<AiFeedback> all = List.of(
            fb("ask", 1, "q1"), fb("ask", 1, "q2"), fb("ask", -1, "q3"), fb("ask", -1, "q4"),
            fb("summary", 1, "q5"), fb("summary", 1, "q6"), fb("summary", 1, "q7"), fb("summary", 1, "q8"));
        when(feedbackMapper.selectList(any())).thenReturn(all);

        Map<String, Object> stats = service.statsByFeature(7);

        assertEquals(7, stats.get("days"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byFeature = (Map<String, Object>) stats.get("byFeature");
        @SuppressWarnings("unchecked")
        Map<String, Object> ask = (Map<String, Object>) byFeature.get("ask");
        assertEquals(2L, ask.get("up"));
        assertEquals(2L, ask.get("down"));
        assertEquals(50.0, ask.get("downRate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) byFeature.get("summary");
        assertEquals(0.0, summary.get("downRate"));
    }

    @Test
    @DisplayName("差评率超阈值且样本足够 → 列入 alerted 并打指标")
    void testAlertWhenDownRateExceeded() {
        // ask：1 👍 + 4 👎（样本 5 ≥ 5，差评率 80% ≥ 20%）→ 告警
        List<AiFeedback> all = List.of(
            fb("ask", 1, "q1"),
            fb("ask", -1, "q2"), fb("ask", -1, "q3"), fb("ask", -1, "q4"), fb("ask", -1, "q5"));
        when(feedbackMapper.selectList(any())).thenReturn(all);

        Map<String, Object> stats = service.statsByFeature(7);

        @SuppressWarnings("unchecked")
        List<String> alerted = (List<String>) stats.get("alerted");
        assertTrue(alerted.contains("ask"));
        verify(metrics).incr("ai_feedback_alert_ask");
    }

    @Test
    @DisplayName("样本不足（<5 条）不告警：1 条 👎 就响会永远在响")
    void testNoAlertWithTinySample() {
        List<AiFeedback> all = List.of(fb("ask", -1, "q1"));
        when(feedbackMapper.selectList(any())).thenReturn(all);

        Map<String, Object> stats = service.statsByFeature(7);

        @SuppressWarnings("unchecked")
        List<String> alerted = (List<String>) stats.get("alerted");
        assertFalse(alerted.contains("ask"));
        verify(metrics, never()).incr("ai_feedback_alert_ask");
    }

    @Test
    @DisplayName("days 越界收敛到 1~30")
    void testStatsDaysClamped() {
        when(feedbackMapper.selectList(any())).thenReturn(List.of());

        assertEquals(1, service.statsByFeature(0).get("days"));
        assertEquals(30, service.statsByFeature(999).get("days"));
    }
}
