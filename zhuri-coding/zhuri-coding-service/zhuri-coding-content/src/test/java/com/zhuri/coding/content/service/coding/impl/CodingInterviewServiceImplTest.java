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
import com.zhuri.coding.content.service.coding.CodingReportService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.Mockito.times;
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

    /**
     * 报告链路已抽成 {@link CodingReportService}：这里装配一个<b>真实实例</b>而不是 mock，
     * 好让「结束 / 报告」这批用例仍然真实覆盖分批评估、逐位对齐、三层降级与解析规范化 ——
     * 它们本来就是在验这套逻辑，mock 掉等于把测试掏空。
     *
     * <p>注意 {@code @InjectMocks} 只注入声明过的 mock/spy，这个非 mock 依赖需要手动接上；
     * 字段默认值（超时 30s、每批 3 个主题）由类里的字段初始化提供，与生产默认一致。</p>
     */
    private CodingReportService reportService;

    @BeforeEach
    void wireReportService() {
        reportService = new CodingReportService();
        ReflectionTestUtils.setField(reportService, "aiLlmGateway", aiLlmGateway);
        ReflectionTestUtils.setField(reportService, "promptSanitizer", promptSanitizer);
        ReflectionTestUtils.setField(service, "reportService", reportService);
    }

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

    // ---------- 题数（Stage A 补充） ----------

    /** 生成 n 个主题的提纲 JSON；source 按 resume/direction 交替，便于断言来源透出 */
    private static String planJson(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"topic\":\"主题").append(i + 1)
                .append("\",\"mainQuestion\":\"问题").append(i + 1)
                .append("\",\"keyPoints\":[\"考点\"],\"tag\":\"Java\",\"source\":\"")
                .append(i % 2 == 0 ? "resume" : "direction").append("\"}");
        }
        return sb.append(']').toString();
    }

    private CodingInterviewStartDTO startDtoWithCount(Integer count) {
        CodingInterviewStartDTO dto = startDto("Java 后端", 2);
        dto.setQuestionCount(count);
        return dto;
    }

    private void stubPlan(int planTopicCount) {
        stubStartGate();
        when(questionMapper.selectRandomBatch(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(), anyString(), any(), any()))
            .thenReturn(planJson(planTopicCount));
    }

    @Test
    @DisplayName("开面 - 题数缺省取配置 5；越界夹取到 3~10（入参越界不报错，按边界执行）")
    void testStartQuestionCountClamped() {
        stubPlan(12);

        CodingInterviewSessionVO byDefault =
            (CodingInterviewSessionVO) service.start(USER_ID, startDtoWithCount(null)).getData();
        assertEquals(5, byDefault.getTotalTopics().intValue(), "缺省应为配置默认 5");

        CodingInterviewSessionVO atMax =
            (CodingInterviewSessionVO) service.start(USER_ID, startDtoWithCount(20)).getData();
        assertEquals(10, atMax.getTotalTopics().intValue(), "上限夹取到 10");

        CodingInterviewSessionVO atMin =
            (CodingInterviewSessionVO) service.start(USER_ID, startDtoWithCount(1)).getData();
        assertEquals(3, atMin.getTotalTopics().intValue(), "下限夹取到 3");
    }

    @Test
    @DisplayName("开面 - 无简历：prompt 无简历段、配额全 direction，当前主题 source=direction")
    void testStartNoResumeAllDirection() {
        stubPlan(3);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        ResponseResult result = service.start(USER_ID, startDtoWithCount(3));

        verify(aiLlmGateway).generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(),
            userPrompt.capture(), any(), any());
        String prompt = userPrompt.getValue();
        assertTrue(prompt.contains("【简历题配额】无简历，全部主题取 source=direction"));
        assertFalse(prompt.contains("【候选人简历】"));

        CodingInterviewSessionVO vo = (CodingInterviewSessionVO) result.getData();
        // 无简历时服务端强制归一：模型即便标了 resume 也不下发，避免前端显示"简历深挖"却压根没传简历
        assertEquals("direction", vo.getCurrentTopic().getSource());
    }

    @Test
    @DisplayName("开面 - 有简历：prompt 注入简历段与配额，首题 source 透出")
    void testStartResumeInjectsPromptAndSource() {
        stubPlan(3);
        CodingInterviewStartDTO dto = startDtoWithCount(5);
        dto.setResumeText("三年 Java 后端，主导过订单中台重构，用到 RocketMQ 与分库分表");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        ResponseResult result = service.start(USER_ID, dto);

        verify(aiLlmGateway).generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(),
            userPrompt.capture(), any(), any());
        String prompt = userPrompt.getValue();
        // 5 个主题 × 0.6 = 3 个简历题，其余 2 个方向题
        assertTrue(prompt.contains("【简历题配额】3 个主题取 source=resume"), prompt);
        assertTrue(prompt.contains("其余 2 个取 source=direction"), prompt);
        assertTrue(prompt.contains("【候选人简历】"), "应注入简历段");
        assertTrue(prompt.contains("订单中台重构"), "简历原文应进入 prompt");

        CodingInterviewSessionVO vo = (CodingInterviewSessionVO) result.getData();
        assertEquals("resume", vo.getCurrentTopic().getSource(), "首题 source 应为 resume");
    }

    @Test
    @DisplayName("开面 - 超长简历按上限截断并带显式标记（尾部内容不进入 prompt）")
    void testStartResumeTruncated() {
        stubPlan(3);
        CodingInterviewStartDTO dto = startDtoWithCount(5);
        dto.setResumeText(repeat('A', 8000) + "TAIL_MARKER_SHOULD_BE_CUT");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        service.start(USER_ID, dto);

        verify(aiLlmGateway).generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(),
            userPrompt.capture(), any(), any());
        String prompt = userPrompt.getValue();
        assertTrue(prompt.contains("...(简历内容过长，已截断)"), "截断处应有显式标记");
        assertFalse(prompt.contains("TAIL_MARKER_SHOULD_BE_CUT"), "超限尾部不应进入 prompt");
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
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

    // ==================== 报告分批 + 二次汇总 + 三层降级 ====================

    /** 一批评估结果（模型返回的 JSON 数组；覆盖 1/2 → 覆盖度等级 3） */
    private static String batchJson(String topic) {
        return "[{\"topic\":\"" + topic + "\",\"structure\":4,"
            + "\"coverage\":{\"covered\":[\"考点\"],\"missing\":[\"缺口\"]},\"accuracy\":4,"
            + "\"comment\":\"讲到了主要部分\"}]";
    }

    /** 汇总结果（二次汇总的返回） */
    private static final String SUMMARY_JSON = "{\"overall\":\"整体还行\",\"suggestions\":[\"补充缺口\"]}";

    /** 指定主题数与对话流水的面试记录（报告分批用） */
    private ApCodingInterview ongoingWith(int topics, String turnsJson) {
        ApCodingInterview record = ongoing(1L);
        record.setPlanSnapshot(planJson(topics));
        record.setTurns(turnsJson);
        return record;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** 从落库的 report JSON 里取回结构化报告（走 report 接口同款解析） */
    private ApCodingInterview captureSaved(int topics, String turnsJson, String... llmReturns) {
        when(interviewMapper.selectById(1L)).thenReturn(ongoingWith(topics, turnsJson));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn(llmReturns[0], java.util.Arrays.copyOfRange(llmReturns, 1, llmReturns.length));
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);
        service.finish(USER_ID, finishDto(1L));
        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("报告 - 10 主题按每批 3 个评估：4 次分批 + 1 次二次汇总")
    void testFinishReportBatchedCalls() {
        when(interviewMapper.selectById(1L)).thenReturn(ongoingWith(10, TURNS_JSON));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn(batchJson("主题1"), batchJson("主题4"), batchJson("主题7"), batchJson("主题10"),
                SUMMARY_JSON);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewFinishVO vo = (CodingInterviewFinishVO) result.getData();
        assertTrue(vo.getReportReady());
        verify(aiLlmGateway, times(5))
            .generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("报告 - 单批失败只影响该批：其余主题照常出分，失败主题补「未评估」占位")
    void testFinishPartialBatchFailureIsolated() {
        // 4 主题 / 每批 3 个 → 两批；第 1 批成功、第 2 批返回非 JSON
        ApCodingInterview saved = captureSaved(4, TURNS_JSON,
            batchJson("主题1"), "抱歉，这次没有返回 JSON", SUMMARY_JSON);

        assertTrue(saved.getOverallScore() >= 1, "部分成功仍应出结构化报告");
        String stored = saved.getReport();
        assertEquals(1, countOccurrences(stored, "\"pending\":false"), "只有主题1 评估成功");
        assertEquals(3, countOccurrences(stored, "\"pending\":true"), "其余 3 个主题应为未评估占位");
        assertTrue(stored.contains("\"overall\":\"整体还行\""), "汇总照常产出");
        // 未评估主题不参与综合等级：只算主题1（结构 4 + 覆盖 3 + 准确 4）/ 3 ≈ 4
        assertEquals(4, saved.getOverallScore().intValue());
    }

    @Test
    @DisplayName("报告 - 汇总失败：改用各主题结论确定性拼装总评与建议（报告仍可用）")
    void testFinishSummaryFallback() {
        ApCodingInterview saved = captureSaved(3, TURNS_JSON, batchJson("主题1"), "汇总阶段模型抽风了");

        String stored = saved.getReport();
        assertTrue(stored.contains("各主题综合等级"), "汇总失败应走确定性兜底：" + stored);
        assertTrue(stored.contains("补强「缺口」"), "建议由未覆盖考点确定性生成");
    }

    @Test
    @DisplayName("报告 - 批内逐位对齐：模型少返一条时后续主题不错位，按位置补占位")
    void testFinishBatchAlignByPosition() {
        // 2 主题一批，模型只返回第 1 条 → 第 2 条必须是占位（addAll 会把第 2 个主题顶到第 1 条）
        ApCodingInterview saved = captureSaved(2, TURNS_JSON, batchJson("主题1"), SUMMARY_JSON);

        String stored = saved.getReport();
        assertTrue(stored.contains("\"topic\":\"主题1\",\"structure\":4"), "第 1 条应为模型返回的评估结果");
        assertTrue(stored.indexOf("主题1") < stored.indexOf("主题2"), "顺序须与提纲一致");
        assertEquals(1, countOccurrences(stored, "\"pending\":true"), "第 2 个主题应为未评估占位");
    }

    @Test
    @DisplayName("报告 - 长作答不再被按条截断（旧实现 1200 字处静默砍掉）")
    void testFinishLongAnswerNotTruncated() {
        String tail = "TAIL_MARKER_SHOULD_SURVIVE";
        String turns = "[{\"role\":\"interviewer\",\"type\":\"question\",\"content\":\"谈谈 HashMap\","
            + "\"topicIndex\":0,\"ts\":1},"
            + "{\"role\":\"user\",\"type\":\"answer\",\"content\":\""
            + repeat('答', 1_500) + tail + "\",\"topicIndex\":0,\"ts\":2}]";
        when(interviewMapper.selectById(1L)).thenReturn(ongoingWith(1, turns));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn(batchJson("主题1"), SUMMARY_JSON);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);

        service.finish(USER_ID, finishDto(1L));

        verify(aiLlmGateway, times(2)).generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(),
            prompts.capture(), any(), any());
        String batchPrompt = prompts.getAllValues().get(0);
        assertTrue(batchPrompt.contains(tail), "1500 字作答应完整进入分批 prompt");
        assertFalse(batchPrompt.contains("（内容过长，已截断）"), "未超主题上限不应触发截断标记");
    }

    @Test
    @DisplayName("报告 - 超过主题级上限才截断，且带显式语言标记（不是静默省略号）")
    void testFinishOverlongDialogueMarked() {
        String turns = "[{\"role\":\"interviewer\",\"type\":\"question\",\"content\":\"谈谈 HashMap\","
            + "\"topicIndex\":0,\"ts\":1},"
            + "{\"role\":\"user\",\"type\":\"answer\",\"content\":\""
            + repeat('答', 4_500) + "\",\"topicIndex\":0,\"ts\":2}]";
        when(interviewMapper.selectById(1L)).thenReturn(ongoingWith(1, turns));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenReturn(batchJson("主题1"), SUMMARY_JSON);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);

        service.finish(USER_ID, finishDto(1L));

        verify(aiLlmGateway, times(2)).generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(),
            prompts.capture(), any(), any());
        assertTrue(prompts.getAllValues().get(0).contains("...（内容过长，已截断）"),
            "超限应带显式标记，让模型知道后文没了而不是续写");
    }

    /** n 个主题的逐一评估结果（主题名与 planJson 对齐） */
    private static String batchJsonN(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"topic\":\"主题").append(i).append("\",\"structure\":4,")
                .append("\"coverage\":{\"covered\":[\"考点\"],\"missing\":[]},\"accuracy\":4,")
                .append("\"comment\":\"还行\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("报告 - 软预算耗尽：剩余主题标未评估，已评出的部分照常出报告（不整场丢）")
    void testFinishBudgetExhaustedPartialReport() {
        // 总预算 5s、汇总预留 8s → 软预算被夹到 1s；让第一批耗时超过它
        ReflectionTestUtils.setField(reportService, "reportTimeoutSeconds", 5);
        when(interviewMapper.selectById(1L)).thenReturn(ongoingWith(4, TURNS_JSON));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any()))
            .thenAnswer(invocation -> {
                Thread.sleep(1_300L);
                return batchJsonN(3);
            })
            .thenReturn(SUMMARY_JSON);
        when(interviewMapper.update(any(ApCodingInterview.class), any())).thenReturn(1);

        ResponseResult result = service.finish(USER_ID, finishDto(1L));

        assertEquals(200, result.getCode().intValue());
        CodingInterviewFinishVO vo = (CodingInterviewFinishVO) result.getData();
        assertTrue(vo.getReportReady(), "预算耗尽应出部分报告而不是整场失败");
        ArgumentCaptor<ApCodingInterview> captor = ArgumentCaptor.forClass(ApCodingInterview.class);
        verify(interviewMapper).update(captor.capture(), any());
        String stored = captor.getValue().getReport();
        assertEquals(3, countOccurrences(stored, "\"pending\":false"), "第一批 3 个主题已评估");
        assertEquals(1, countOccurrences(stored, "\"pending\":true"), "预算耗尽后剩余 1 个主题标未评估");
        verify(aiLlmGateway, times(2))
            .generateOrNull(eq(AiFeatures.INTERVIEW_REPORT), anyString(), anyString(), any(), any());
    }

    // ==================== 跨场历史去重 ====================

    @Test
    @DisplayName("开面 - 注入近几场已考主题（跨场去重）")
    void testStartInjectsHistoryTopics() {
        stubStartGate();
        when(questionMapper.selectRandomBatch(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(interviewMapper.selectRecentPlanSnapshots(eq(USER_ID), anyInt())).thenReturn(Collections.singletonList(
            "[{\"topic\":\"Redis 持久化\",\"mainQuestion\":\"RDB 与 AOF 的区别\","
                + "\"keyPoints\":[\"RDB\",\"AOF\"],\"tag\":\"Redis\",\"source\":\"direction\"}]"));
        when(aiLlmGateway.generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(), anyString(), any(), any()))
            .thenReturn(planJson(3));
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        service.start(USER_ID, startDtoWithCount(3));

        verify(aiLlmGateway).generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(),
            userPrompt.capture(), any(), any());
        String prompt = userPrompt.getValue();
        assertTrue(prompt.contains("【已考过的主题"), prompt);
        assertTrue(prompt.contains("Redis 持久化"), "历史主题应注入 prompt");
    }

    @Test
    @DisplayName("开面 - 无历史场次：prompt 明确标注「无」，不影响开面")
    void testStartNoHistoryTopics() {
        stubPlan(3);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);

        service.start(USER_ID, startDtoWithCount(3));

        verify(aiLlmGateway).generateOrNull(eq(AiFeatures.INTERVIEW_PLAN), anyString(),
            userPrompt.capture(), any(), any());
        assertTrue(userPrompt.getValue().contains("换角度、换深度或换考点）】无"), userPrompt.getValue());
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