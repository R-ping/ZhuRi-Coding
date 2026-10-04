package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.common.bailian.PromptSanitizer;
import com.zhuri.coding.content.mapper.coding.ApCodingInterviewMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.ai.AiFeatures;
import com.zhuri.coding.content.service.ai.AiLlmGateway;
import com.zhuri.coding.content.service.ai.AiQuotaService;
import com.zhuri.coding.content.service.coding.CodingInterviewService;
import com.zhuri.coding.model.coding.dtos.CodingInterviewFinishDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewStartDTO;
import com.zhuri.coding.model.coding.dtos.CodingInterviewTurnDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingInterview;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingInterviewFinishVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewHistoryVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewReportVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewSessionVO;
import com.zhuri.coding.model.coding.vos.CodingInterviewTurnVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingInterviewServiceImpl 单元测试（Coding 延展第三层 · Stage A 模拟面试）
 *
 * 覆盖：开面（续答/每日上限/额度拒绝/提纲失败拒绝/成功落库）、轮次（参数与 turnSeq 校验/
 * 追问上限服务端强制/FOLLOWUP 与 NEXT 控制行解析/主题耗尽 completed/额度异常透传/LLM 降级不落库）、
 * 结束（幂等回放/过期与超时拒绝/报告结构化成功/解析失败降级存原文）、报告与历史。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模拟面试服务单元测试")
class CodingInterviewServiceImplTest {

    private static final Integer USER_ID = 1001;

    /** 三主题提纲（≥ PLAN_MIN_TOPICS，可开面） */
    private static final String PLAN_JSON = "["
        + "{\"topic\":\"Java 基础\",\"mainQuestion\":\"谈谈 HashMap 的扩容机制\","
        + "\"keyPoints\":[\"扩容条件\",\"rehash\",\"红黑树\"],\"tag\":\"Java\"},"
        + "{\"topic\":\"MySQL\",\"mainQuestion\":\"说说 InnoDB 索引结构\","
        + "\"keyPoints\":[\"B+树\",\"聚簇索引\"],\"tag\":\"MySQL\"},"
        + "{\"topic\":\"Redis\",\"mainQuestion\":\"谈谈缓存穿透与雪崩\","
        + "\"keyPoints\":[\"穿透\",\"雪崩\",\"布隆过滤器\"],\"tag\":\"Redis\"}]";

    /** 结构化报告：covered=2 / missing=1 → coverageScore=4；structure=4、accuracy=4 → overall=4 */
    private static final String REPORT_JSON = "{\"items\":[{\"topic\":\"Java 基础\",\"structure\":4,"
        + "\"coverage\":{\"covered\":[\"扩容条件\",\"rehash\"],\"missing\":[\"红黑树\"]},\"accuracy\":4,"
        + "\"comment\":\"讲到了扩容触发条件，红黑树未展开\"}],"
        + "\"overall\":\"基础扎实\",\"suggestions\":[\"补充红黑树细节\"]}";

    /** 预置对话流水：首题（面试官）+ 一轮作答（用户，topicIndex=0） */
    private static final String TURNS_JSON = "["
        + "{\"role\":\"interviewer\",\"type\":\"question\",\"content\":\"谈谈 HashMap 的扩容机制\",\"topicIndex\":0,\"ts\":1700000000000},"
        + "{\"role\":\"user\",\"type\":\"answer\",\"content\":\"扩容是达到阈值翻倍\",\"topicIndex\":0,\"ts\":1700000000001}]";

    @Mock
    private ApCodingInterviewMapper interviewMapper;
    @Mock
    private ApCodingQuestionMapper questionMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private AiLlmGateway aiLlmGateway;
    @Mock
    private AiQuotaService quotaService;
    /** 净化器为纯工具类，走真实实现（mock 返回 null 会偏离生产行为） */
    @Spy
    private PromptSanitizer promptSanitizer = new PromptSanitizer();

    @InjectMocks
    private CodingInterviewServiceImpl service;

    // ---------- 录制型 Sink 与辅助 ----------

    private final List<String> deltas = new ArrayList<>();
    private CodingInterviewTurnVO doneVo;
    private String errorMessage;

    private CodingInterviewService.TurnSink sink() {
        return new CodingInterviewService.TurnSink() {
            @Override
            public void delta(String text) {
                deltas.add(text);
            }

            @Override
            public void done(CodingInterviewTurnVO vo) {
                doneVo = vo;
            }

            @Override
            public void error(String message) {
                errorMessage = message;
            }
        };
    }

    private ApCodingInterview ongoing(Long id) {
        ApCodingInterview record = new ApCodingInterview();
        record.setId(id);
        record.setUserId(USER_ID);
        record.setStatus(ApCodingInterview.STATUS_ONGOING);
        record.setDirection("Java 后端");
        record.setDifficulty(2);
        record.setPlanSnapshot(PLAN_JSON);
        record.setTurns(TURNS_JSON);
        record.setCurrentIndex(0);
        record.setFollowupCount(0);
        record.setTurnCount(1);
        record.setStartedTime(new Date(System.currentTimeMillis() - 60_000));
        record.setDeadlineTime(new Date(System.currentTimeMillis() + 600_000));
        return record;
    }

    private CodingInterviewStartDTO startDto(String direction, Integer difficulty) {
        CodingInterviewStartDTO dto = new CodingInterviewStartDTO();
        dto.setDirection(direction);
        dto.setDifficulty(difficulty);
        return dto;
    }

    private CodingInterviewTurnDTO turnDto(long id, String answer, int turnSeq) {
        CodingInterviewTurnDTO dto = new CodingInterviewTurnDTO();
        dto.setInterviewId(id);
        dto.setAnswer(answer);
        dto.setTurnSeq(turnSeq);
        return dto;
    }

    private CodingInterviewFinishDTO finishDto(long id) {
        CodingInterviewFinishDTO dto = new CodingInterviewFinishDTO();
        dto.setInterviewId(id);
        return dto;
    }

    /** 开面公共前置：无进行中 → 未触顶 → 额度通过 */
    private void stubStartGate() {
        when(interviewMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(interviewMapper.countToday(eq(USER_ID), any(Date.class))).thenReturn(0L);
        when(quotaService.tryConsume(USER_ID)).thenReturn(true);
    }

    // ==================== 开面 ====================

    @Test
    @DisplayName("开面 - 有进行中且未超时直接续答（不生成提纲、deadline 不变）")
    void testStartResumesOngoing() {
        ApCodingInterview ongoing = ongoing(9L);
        Date deadline = ongoing.getDeadlineTime();
        when(interviewMapper.selectOngoing(USER_ID)).thenReturn(ongoing);

        ResponseResult result = service.start(USER_ID, startDto("Java 后端", 2));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewSessionVO vo = (CodingInterviewSessionVO) result.getData();
        assertEquals(9L, vo.getInterviewId().longValue());
        assertTrue(vo.getRemainingSeconds() > 0 && vo.getRemainingSeconds() <= 600);
        assertEquals(3, vo.getTotalTopics().intValue());
        assertEquals("谈谈 HashMap 的扩容机制", vo.getCurrentTopic().getQuestion());
        assertEquals(2, vo.getTurns().size());
        assertEquals(deadline, ongoing.getDeadlineTime());
        verify(interviewMapper, never()).insert(any(ApCodingInterview.class));
        verifyNoInteractions(aiLlmGateway, quotaService, questionMapper, statMapper);
    }

    @Test
    @DisplayName("开面 - 每日场次达上限拒绝（不生成提纲、不落库）")
    void testStartDailyLimitRejected() {
        when(interviewMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(interviewMapper.countToday(eq(USER_ID), any(Date.class))).thenReturn(3L);

        ResponseResult result = service.start(USER_ID, startDto("Java 后端", 2));

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("上限"));
        verify(interviewMapper, never()).insert(any(ApCodingInterview.class));
        verifyNoInteractions(aiLlmGateway, quotaService, questionMapper, statMapper);
    }

    @Test
    @DisplayName("开面 - 额度不足返回 3301 并引导充值（不生成提纲）")
    void testStartQuotaRejected() {
        when(interviewMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(interviewMapper.countToday(eq(USER_ID), any(Date.class))).thenReturn(0L);
        when(quotaService.tryConsume(USER_ID)).thenReturn(false);

        ResponseResult result = service.start(USER_ID, startDto("Java 后端", 2));

        assertEquals(AppHttpCodeEnum.AI_QUOTA_EXHAUSTED.getCode(), result.getCode());
        verify(interviewMapper, never()).insert(any(ApCodingInterview.class));
        verifyNoInteractions(aiLlmGateway, questionMapper, statMapper);
    }

    @Test
    @DisplayName("开面 - 提纲生成不合格拒绝（不落库，宁缺勿假）")
    void testStartPlanInvalidRejected() {
        stubStartGate();
        when(questionMapper.selectRandomBatch(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(), anyString(), any(), any()))
            .thenReturn("抱歉，我无法生成提纲");

        ResponseResult result = service.start(USER_ID, startDto("Java 后端", 2));

        assertEquals(500, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("提纲生成失败"));
        verify(interviewMapper, never()).insert(any(ApCodingInterview.class));
    }

    @Test
    @DisplayName("开面 - 成功落库：方向 trim、难度默认进阶、首题预置、deadline=45 分钟")
    void testStartCreatesSession() {
        stubStartGate();
        ApCodingQuestion question = new ApCodingQuestion();
        question.setTags("Java,Redis");
        when(questionMapper.selectRandomBatch(any(), any(), anyInt())).thenReturn(List.of(question));
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setTagStats("{\"Java\":{\"total\":4,\"correct\":1},\"Redis\":{\"total\":2,\"correct\":2}}");
        when(statMapper.selectOne(any())).thenReturn(stat);
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(), anyString(), any(), any()))
            .thenReturn(PLAN_JSON);

        ResponseResult result = service.start(USER_ID, startDto("  Java 后端  ", null));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewSessionVO vo = (CodingInterviewSessionVO) result.getData();
        assertEquals(3, vo.getTotalTopics().intValue());
        assertEquals(1, vo.getTurns().size());
        assertEquals("谈谈 HashMap 的扩容机制", vo.getCurrentTopic().getQuestion());
        assertEquals(2_700, vo.getRemainingSeconds().intValue());

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).insert(captor.capture());
        ApCodingInterview saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals("Java 后端", saved.getDirection());
        assertEquals(2, saved.getDifficulty().intValue());
        assertEquals(ApCodingInterview.STATUS_ONGOING, saved.getStatus().intValue());
        assertEquals(0, saved.getCurrentIndex().intValue());
        assertEquals(0, saved.getFollowupCount().intValue());
        assertEquals(0, saved.getTurnCount().intValue());
        assertTrue(saved.getPlanSnapshot().contains("HashMap"));
        assertTrue(saved.getTurns().contains("\"role\":\"interviewer\""));
        assertEquals(45 * 60_000L, saved.getDeadlineTime().getTime() - saved.getStartedTime().getTime());
    }

    // ==================== 轮次（SSE） ====================

    @Test
    @DisplayName("轮次 - 参数缺失直接错误回调，无落库")
    void testTurnInvalidParam() {
        service.turnStream(USER_ID, null, sink());

        assertEquals("面试参数缺失，请刷新后重试", errorMessage);
        assertNull(doneVo);
        verifyNoInteractions(interviewMapper, aiLlmGateway, questionMapper, statMapper);
    }

    @Test
    @DisplayName("轮次 - turnSeq 与 DB 不一致拒绝（防双击/重放）")
    void testTurnSeqMismatchRejected() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));

        service.turnStream(USER_ID, turnDto(1L, "我的回答", 0), sink());

        assertEquals("面试状态已变化，请刷新后重试", errorMessage);
        assertNull(doneVo);
        verifyNoInteractions(aiLlmGateway);
        verify(interviewMapper, never()).update(any(ApCodingInterview.class), any());
    }

    @Test
    @DisplayName("轮次 - 追问上限服务端强制换题（不调用模型）")
    void testTurnFollowupLimitForcesNext() {
        ApCodingInterview record = ongoing(1L);
        record.setFollowupCount(1); // followup-max=1 已用尽
        when(interviewMapper.selectById(1L)).thenReturn(record);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        service.turnStream(USER_ID, turnDto(1L, "我的回答", 1), sink());

        verifyNoInteractions(aiLlmGateway);
        assertEquals("next", doneVo.getKind());
        assertTrue(doneVo.getText().startsWith("我们换个话题"));
        assertEquals("说说 InnoDB 索引结构", doneVo.getText().substring("我们换个话题：".length()));
        assertEquals(1, doneVo.getTopicIndex().intValue());
        assertEquals(2, doneVo.getTurnCount().intValue());
        assertFalse(doneVo.getCompleted());

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        ApCodingInterview saved = captor.getValue();
        assertEquals(2, saved.getTurnCount().intValue());
        assertEquals(1, saved.getCurrentIndex().intValue());
        assertEquals(0, saved.getFollowupCount().intValue());
        assertTrue(saved.getTurns().contains("\"role\":\"user\""));
    }

    @Test
    @DisplayName("轮次 - FOLLOWUP 控制行解析：不泄漏控制词，逐段转发追问")
    void testTurnFollowupStream() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);
        when(aiLlmGateway.generateStreamOrNull(eq(AiFeatures.INTERVIEW_TURN), anyString(), anyString(),
            any(), any(), any())).thenAnswer(inv -> {
                Consumer<String> onDelta = inv.getArgument(5);
                onDelta.accept("FOLLOWUP\n");
                onDelta.accept("你提到了扩容，");
                onDelta.accept("那红黑树转化条件是什么？");
                return "FOLLOWUP\n你提到了扩容，那红黑树转化条件是什么？";
            });

        service.turnStream(USER_ID, turnDto(1L, "扩容是达到阈值时翻倍", 1), sink());

        assertEquals("followup", doneVo.getKind());
        assertEquals("你提到了扩容，那红黑树转化条件是什么？", doneVo.getText());
        assertEquals("你提到了扩容，那红黑树转化条件是什么？", String.join("", deltas));
        assertFalse(String.join("", deltas).contains("FOLLOWUP"));
        assertEquals(0, doneVo.getTopicIndex().intValue());
        assertEquals(2, doneVo.getTurnCount().intValue());

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        ApCodingInterview saved = captor.getValue();
        assertEquals(1, saved.getFollowupCount().intValue());
        assertEquals(0, saved.getCurrentIndex().intValue());
        assertTrue(saved.getTurns().contains("\"type\":\"followup\""));
    }

    @Test
    @DisplayName("轮次 - NEXT 控制行中止模型流，服务端拼装下一题")
    void testTurnNextStreamAborts() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);
        when(aiLlmGateway.generateStreamOrNull(eq(AiFeatures.INTERVIEW_TURN), anyString(), anyString(),
            any(), any(), any())).thenAnswer(inv -> {
                Consumer<String> onDelta = inv.getArgument(5);
                try {
                    onDelta.accept("NEXT\n");
                    onDelta.accept("多余内容");
                } catch (CancellationException ignored) {
                    // 真实网关内部消化取消并返回 null（不计熔断失败）
                }
                return null;
            });

        service.turnStream(USER_ID, turnDto(1L, "回答完整", 1), sink());

        assertEquals("next", doneVo.getKind());
        assertTrue(doneVo.getText().startsWith("我们换个话题"));
        assertEquals(1, doneVo.getTopicIndex().intValue());
        assertEquals(2, doneVo.getTurnCount().intValue());
        // NEXT 分支仅服务端拼装文本一次性下发，无模型增量
        assertEquals(List.of(doneVo.getText()), deltas);

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        assertEquals(1, captor.getValue().getCurrentIndex().intValue());
        assertEquals(0, captor.getValue().getFollowupCount().intValue());
    }

    @Test
    @DisplayName("轮次 - 全部主题答完 completed=true（前端自动结束）")
    void testTurnCompletedOnLastTopic() {
        ApCodingInterview record = ongoing(1L);
        record.setCurrentIndex(2); // 最后一个主题
        record.setFollowupCount(1);
        when(interviewMapper.selectById(1L)).thenReturn(record);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        service.turnStream(USER_ID, turnDto(1L, "最后一题回答", 1), sink());

        verifyNoInteractions(aiLlmGateway);
        assertTrue(doneVo.getCompleted());
        assertTrue(doneVo.getText().contains("结束面试"));
        assertEquals(2, doneVo.getTopicIndex().intValue());
    }

    @Test
    @DisplayName("轮次 - 额度耗尽 QuotaExhaustedException 上抛（控制器转 [3301]），本轮不落库")
    void testTurnQuotaExhaustedPropagates() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(aiLlmGateway.generateStreamOrNull(eq(AiFeatures.INTERVIEW_TURN), anyString(), anyString(),
            any(), any(), any())).thenThrow(new AiLlmGateway.QuotaExhaustedException("额度已用尽"));

        assertThrows(AiLlmGateway.QuotaExhaustedException.class,
            () -> service.turnStream(USER_ID, turnDto(1L, "我的回答", 1), sink()));
        assertNull(doneVo);
        verify(interviewMapper, never()).update(any(ApCodingInterview.class), any());
    }

    @Test
    @DisplayName("轮次 - LLM 降级（null）错误回调且本轮不落库（可重试）")
    void testTurnLlmDegradeNoPersist() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(aiLlmGateway.generateStreamOrNull(eq(AiFeatures.INTERVIEW_TURN), anyString(), anyString(),
            any(), any(), any())).thenReturn(null);

        service.turnStream(USER_ID, turnDto(1L, "我的回答", 1), sink());

        assertEquals("面试官暂时离线，请重试", errorMessage);
        assertNull(doneVo);
        verify(interviewMapper, never()).update(any(ApCodingInterview.class), any());
    }

    @Test
    @DisplayName("轮次 - 原子落库失败（并发/过期）错误回调，无 done")
    void testTurnConcurrentUpdateFallback() {
        ApCodingInterview record = ongoing(1L);
        record.setFollowupCount(1); // 跳过 LLM 直接换题，聚焦原子占位失败分支
        when(interviewMapper.selectById(1L)).thenReturn(record);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(0);

        service.turnStream(USER_ID, turnDto(1L, "我的回答", 1), sink());

        assertEquals("面试状态已变化，请刷新后重试", errorMessage);
        assertNull(doneVo);
    }

    // ==================== 结束 / 报告 ====================

    @Test
    @DisplayName("结束 - 已结束且报告可用幂等回放（不重复生成）")
    void testFinishIdempotentReplay() {
        ApCodingInterview finished = ongoing(1L);
        finished.setStatus(ApCodingInterview.STATUS_FINISHED);
        finished.setReport(REPORT_JSON);
        finished.setOverallScore(4);
        finished.setFinishedTime(new Date());
        when(interviewMapper.selectById(1L)).thenReturn(finished);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewFinishVO vo = (CodingInterviewFinishVO) result.getData();
        assertTrue(vo.getReportReady());
        assertEquals(4, vo.getOverallScore().intValue());
        verify(interviewMapper, never()).update(any(ApCodingInterview.class), any());
        verifyNoInteractions(aiLlmGateway);
    }

    @Test
    @DisplayName("结束 - 已过期拒绝生成报告")
    void testFinishExpiredRejected() {
        ApCodingInterview expired = ongoing(1L);
        expired.setStatus(ApCodingInterview.STATUS_EXPIRED);
        when(interviewMapper.selectById(1L)).thenReturn(expired);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("已过期"));
        verifyNoInteractions(aiLlmGateway);
        verify(interviewMapper, never()).update(any(ApCodingInterview.class), any());
    }

    @Test
    @DisplayName("结束 - 进行中已超时置过期并拒绝（到点即废）")
    void testFinishTimeoutRejected() {
        ApCodingInterview record = ongoing(1L);
        record.setDeadlineTime(new Date(System.currentTimeMillis() - 1_000));
        when(interviewMapper.selectById(1L)).thenReturn(record);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("已超时"));
        verifyNoInteractions(aiLlmGateway);
    }

    @Test
    @DisplayName("结束 - 报告结构化成功：等级夹取 + 覆盖度服务端重算 + 综合均值")
    void testFinishReportReady() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn(REPORT_JSON);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewFinishVO vo = (CodingInterviewFinishVO) result.getData();
        assertTrue(vo.getReportReady());
        assertEquals(4, vo.getOverallScore().intValue());
        assertNotNull(vo.getFinishedTime());

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        ApCodingInterview saved = captor.getValue();
        assertEquals(ApCodingInterview.STATUS_FINISHED, saved.getStatus().intValue());
        assertEquals(4, saved.getOverallScore().intValue());
        assertTrue(saved.getReport().contains("\"items\""));
        assertNotNull(saved.getFinishedTime());
    }

    @Test
    @DisplayName("结束 - 报告解析失败降级：存模型原文 reportReady=false（可重试）")
    void testFinishReportDegraded() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoing(1L));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn("抱歉，报告生成遇到问题");
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewFinishVO vo = (CodingInterviewFinishVO) result.getData();
        assertFalse(vo.getReportReady());
        assertNull(vo.getOverallScore());

        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        assertEquals(ApCodingInterview.STATUS_FINISHED, captor.getValue().getStatus().intValue());
        assertEquals("抱歉，报告生成遇到问题", captor.getValue().getReport());
        assertNull(captor.getValue().getOverallScore());
    }

    @Test
    @DisplayName("报告 - 非本人/不存在拒绝")
    void testReportNotOwned() {
        ApCodingInterview other = ongoing(7L);
        other.setUserId(9999);
        when(interviewMapper.selectById(7L)).thenReturn(other);

        ResponseResult result = service.report(USER_ID, 7L);

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("面试不存在"));
    }

    @Test
    @DisplayName("报告 - 结构化可用：逐题等级/覆盖清单/已答主题数/对话回放")
    void testReportStructured() {
        ApCodingInterview record = ongoing(7L);
        record.setStatus(ApCodingInterview.STATUS_FINISHED);
        record.setReport(REPORT_JSON);
        record.setOverallScore(4);
        record.setFinishedTime(new Date());
        when(interviewMapper.selectById(7L)).thenReturn(record);

        ResponseResult result = service.report(USER_ID, 7L);

        assertEquals(200, result.getCode().intValue());
        CodingInterviewReportVO vo = (CodingInterviewReportVO) result.getData();
        assertTrue(vo.getReportReady());
        assertEquals(4, vo.getOverallScore().intValue());
        assertEquals(3, vo.getTotalTopics().intValue());
        assertEquals(1, vo.getItems().size());
        CodingInterviewReportVO.ReportItem item = vo.getItems().get(0);
        assertEquals(4, item.getStructure().intValue());
        assertEquals(4, item.getAccuracy().intValue());
        assertEquals(4, item.getCoverageScore().intValue());
        assertEquals(List.of("扩容条件", "rehash"), item.getCoverage().getCovered());
        assertEquals(List.of("红黑树"), item.getCoverage().getMissing());
        assertEquals(1, vo.getCompletedTopics().intValue());
        assertEquals(2, vo.getTurns().size());
        assertNull(vo.getRawText());
    }

    @Test
    @DisplayName("报告 - 结构化失败展示原文（报告不丢）")
    void testReportDegradedRawText() {
        ApCodingInterview record = ongoing(7L);
        record.setStatus(ApCodingInterview.STATUS_FINISHED);
        record.setReport("模型原始输出（非 JSON）");
        when(interviewMapper.selectById(7L)).thenReturn(record);

        ResponseResult result = service.report(USER_ID, 7L);

        assertEquals(200, result.getCode().intValue());
        CodingInterviewReportVO vo = (CodingInterviewReportVO) result.getData();
        assertFalse(vo.getReportReady());
        assertEquals("模型原始输出（非 JSON）", vo.getRawText());
        assertNull(vo.getItems());
    }

    @Test
    @DisplayName("历史 - 分页组装（主题数/等级/时间格式化）")
    @SuppressWarnings("unchecked")
    void testHistory() {
        Page<ApCodingInterview> page = new Page<>(1, 10);
        ApCodingInterview finished = ongoing(1L);
        finished.setStatus(ApCodingInterview.STATUS_FINISHED);
        finished.setOverallScore(4);
        finished.setFinishedTime(new Date());
        ApCodingInterview broken = ongoing(2L);
        broken.setPlanSnapshot(null);
        broken.setStartedTime(null);
        broken.setFinishedTime(null);
        page.setRecords(new ArrayList<>(List.of(finished, broken)));
        page.setTotal(2L);
        when(interviewMapper.selectPage(any(), any())).thenReturn(page);

        ResponseResult result = service.history(USER_ID, 1, 10);

        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<CodingInterviewHistoryVO> list = (List<CodingInterviewHistoryVO>) data.get("list");
        assertEquals(2, list.size());
        assertEquals(3, list.get(0).getTotalTopics().intValue());
        assertEquals(4, list.get(0).getOverallScore().intValue());
        assertFalse(list.get(0).getStartedTime().isEmpty());
        assertEquals(0, list.get(1).getTotalTopics().intValue());
        assertEquals("", list.get(1).getStartedTime());
        assertEquals(2L, ((Long) data.get("total")).longValue());
    }
}