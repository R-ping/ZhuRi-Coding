package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.content.mapper.coding.ApCodingAssessmentMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingJudge;
import com.zhuri.coding.model.coding.dtos.CodingAssessmentSubmitDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAssessment;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAssessmentHistoryVO;
import com.zhuri.coding.model.coding.vos.CodingAssessmentPaperVO;
import com.zhuri.coding.model.coding.vos.CodingAssessmentResultVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import com.zhuri.coding.model.common.enums.AppHttpCodeEnum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingAssessmentServiceImpl 单元测试（Coding 延展第二层 · Stage B 能力测评）
 *
 * 覆盖：开卷（组卷落库/续答/懒过期重开/冷却拒绝与关闭/题库不足拒绝/弱项标签优先级）、
 * 进行中（正常/懒过期/空）、交卷（判分归一化/领域分布/幂等/超时拒绝/非本人拒绝/
 * 并发占位回退/百分位样本不足与计算）、最近成绩与历史列表。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("能力测评服务单元测试")
class CodingAssessmentServiceImplTest {

    private static final Integer USER_ID = 1001;
    private static final String OPTIONS = "[\"A\",\"B\",\"C\",\"D\"]";

    @Mock
    private ApCodingQuestionMapper questionMapper;
    @Mock
    private ApCodingAssessmentMapper assessmentMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;

    @InjectMocks
    private CodingAssessmentServiceImpl service;

    // ---------- 辅助 ----------

    private ApCodingQuestion question(long id, int difficulty, String answer) {
        ApCodingQuestion q = new ApCodingQuestion();
        q.setId(id);
        q.setStem("题目" + id);
        q.setQuestionType(ApCodingQuestion.TYPE_SINGLE);
        q.setOptions(OPTIONS);
        q.setAnswer(answer);
        q.setExplanation("解析" + id);
        q.setDifficulty(difficulty);
        q.setTags("Redis,缓存");
        q.setStatus(ApCodingQuestion.STATUS_PUBLISHED);
        return q;
    }

    private CodingAssessmentServiceImpl.SnapshotQuestion snap(long id, String answer, String tags) {
        CodingAssessmentServiceImpl.SnapshotQuestion s = new CodingAssessmentServiceImpl.SnapshotQuestion();
        s.id = id;
        s.stem = "题目" + id;
        s.questionType = ApCodingQuestion.TYPE_SINGLE;
        s.options = OPTIONS;
        s.answer = answer;
        s.explanation = "解析" + id;
        s.difficulty = ApCodingQuestion.DIFFICULTY_EASY;
        s.tags = tags;
        return s;
    }

    private CodingAssessmentServiceImpl.AnswerItem answerItem(long questionId, List<Integer> userAnswer, boolean correct) {
        CodingAssessmentServiceImpl.AnswerItem item = new CodingAssessmentServiceImpl.AnswerItem();
        item.questionId = questionId;
        item.userAnswer = userAnswer;
        item.correct = correct;
        return item;
    }

    private ApCodingAssessment ongoing(Long id, List<CodingAssessmentServiceImpl.SnapshotQuestion> paper, long deadlineOffsetMs) {
        ApCodingAssessment record = new ApCodingAssessment();
        record.setId(id);
        record.setUserId(USER_ID);
        record.setStatus(ApCodingAssessment.STATUS_ONGOING);
        record.setPaperSnapshot(CodingJudge.writeJson(paper));
        record.setTotalCount(paper.size());
        record.setStartedTime(new Date(System.currentTimeMillis() - 60_000));
        record.setDeadlineTime(new Date(System.currentTimeMillis() + deadlineOffsetMs));
        return record;
    }

    /** 各难度返回足量题目（4/4/2 → 满 10 题） */
    private void stubFullPaper() {
        when(questionMapper.selectRandomBatch(eq(1), anyList(), anyInt())).thenReturn(List.of(
            question(1, 1, "[0]"), question(2, 1, "[0]"), question(3, 1, "[0]"), question(4, 1, "[0]")));
        when(questionMapper.selectRandomBatch(eq(2), anyList(), anyInt())).thenReturn(List.of(
            question(5, 2, "[0]"), question(6, 2, "[0]"), question(7, 2, "[0]"), question(8, 2, "[0]")));
        when(questionMapper.selectRandomBatch(eq(3), anyList(), anyInt())).thenReturn(List.of(
            question(9, 3, "[0]"), question(10, 3, "[0]")));
    }

    private CodingAssessmentSubmitDTO dto(long assessmentId, CodingAssessmentSubmitDTO.Item... items) {
        CodingAssessmentSubmitDTO dto = new CodingAssessmentSubmitDTO();
        dto.setAssessmentId(assessmentId);
        dto.setAnswers(List.of(items));
        return dto;
    }

    private CodingAssessmentSubmitDTO.Item item(long questionId, Integer... answers) {
        CodingAssessmentSubmitDTO.Item item = new CodingAssessmentSubmitDTO.Item();
        item.setQuestionId(questionId);
        item.setUserAnswer(List.of(answers));
        return item;
    }

    // ==================== 开卷 ====================

    @Test
    @DisplayName("开卷 - 组卷 10 题入库，快照含答案、下发卷面不含答案")
    void testStartCreatesPaper() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);
        stubFullPaper();

        ResponseResult result = service.start(USER_ID);

        assertEquals(200, result.getCode().intValue());
        CodingAssessmentPaperVO vo = (CodingAssessmentPaperVO) result.getData();
        assertEquals(10, vo.getQuestions().size());
        assertFalse(vo.getResumed());
        assertEquals(900, vo.getRemainingSeconds().intValue());
        assertEquals(900, vo.getDurationSeconds().intValue());
        assertEquals(1L, vo.getQuestions().get(0).getQuestionId().longValue());
        assertEquals(List.of("A", "B", "C", "D"), vo.getQuestions().get(0).getOptions());

        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).insert(captor.capture());
        ApCodingAssessment saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(ApCodingAssessment.STATUS_ONGOING, saved.getStatus().intValue());
        assertEquals(10, saved.getTotalCount().intValue());
        assertTrue(saved.getPaperSnapshot().contains("\"answer\":\"[0]\""));
    }

    @Test
    @DisplayName("开卷 - 有进行中且未超时直接续答（不重新组卷）")
    void testStartResumesOngoing() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(
            ongoing(100L, List.of(snap(1, "[0]", "Redis"), snap(2, "[0]", "MySQL")), 600_000));

        ResponseResult result = service.start(USER_ID);

        assertEquals(200, result.getCode().intValue());
        CodingAssessmentPaperVO vo = (CodingAssessmentPaperVO) result.getData();
        assertTrue(vo.getResumed());
        assertEquals(2, vo.getQuestions().size());
        assertTrue(vo.getRemainingSeconds() > 0 && vo.getRemainingSeconds() <= 600);
        verify(assessmentMapper, never()).insert(any(ApCodingAssessment.class));
        verify(assessmentMapper, never()).selectLatestSubmitted(any());
        verify(questionMapper, never()).selectRandomBatch(any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("开卷 - 进行中已超时先置过期再重新组卷")
    void testStartExpiresOngoingAndReopens() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(
            ongoing(100L, List.of(snap(1, "[0]", "Redis")), -600_000));
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);
        stubFullPaper();

        ResponseResult result = service.start(USER_ID);

        assertEquals(200, result.getCode().intValue());
        assertFalse(((CodingAssessmentPaperVO) result.getData()).getResumed());
        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).update(captor.capture(), any());
        assertEquals(ApCodingAssessment.STATUS_EXPIRED, captor.getValue().getStatus().intValue());
        verify(assessmentMapper).insert(any(ApCodingAssessment.class));
    }

    @Test
    @DisplayName("开卷 - 冷却期内拒绝并提示下次可考时间（不组卷）")
    void testStartCooldownRejected() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);
        ApCodingAssessment latest = new ApCodingAssessment();
        latest.setSubmittedTime(new Date(System.currentTimeMillis() - 86_400_000L));
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(latest);

        ResponseResult result = service.start(USER_ID);

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("下次可考时间"));
        verify(assessmentMapper, never()).insert(any(ApCodingAssessment.class));
        verify(questionMapper, never()).selectRandomBatch(any(), anyList(), anyInt());
        verifyNoInteractions(statMapper);
    }

    @Test
    @DisplayName("开卷 - 冷却配置为 0 时可立即重考（不查最近提交）")
    void testStartCooldownDisabled() {
        ReflectionTestUtils.setField(service, "retakeCooldownDays", 0);
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);
        stubFullPaper();

        ResponseResult result = service.start(USER_ID);

        assertEquals(200, result.getCode().intValue());
        verify(assessmentMapper, never()).selectLatestSubmitted(any());
        verify(assessmentMapper).insert(any(ApCodingAssessment.class));
    }

    @Test
    @DisplayName("开卷 - 题库不足最低题量拒绝")
    void testStartInsufficientBank() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);
        // 各难度都只回同一道题：同卷去重后仅 1 题 < 最低 5 题
        when(questionMapper.selectRandomBatch(any(), any(), anyInt()))
            .thenReturn(List.of(question(1, 1, "[0]")));

        ResponseResult result = service.start(USER_ID);

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("题库准备中"));
        verify(assessmentMapper, never()).insert(any(ApCodingAssessment.class));
    }

    @Test
    @DisplayName("开卷 - 弱项标签按正确率升序优先传给组卷（低正确率在前）")
    void testStartWeakTagsOrder() {
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setTagStats("{\"MySQL\":{\"total\":4,\"correct\":4},\"Redis\":{\"total\":2,\"correct\":0}}");
        when(statMapper.selectOne(any())).thenReturn(stat);
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);
        when(questionMapper.selectRandomBatch(any(), any(), anyInt())).thenReturn(Collections.emptyList());

        service.start(USER_ID); // 题量不足返回 400，本用例只验证弱项标签传递

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionMapper, atLeast(1)).selectRandomBatch(eq(1), captor.capture(), anyInt());
        assertEquals(List.of("Redis", "MySQL"), captor.getAllValues().get(0));
    }

    // ==================== 进行中 ====================

    @Test
    @DisplayName("进行中 - 未超时返回试卷（含服务端剩余秒数）")
    void testCurrentReturnsPaper() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(
            ongoing(100L, List.of(snap(1, "[0]", "Redis"), snap(2, "[0]", "MySQL")), 500_000));

        ResponseResult result = service.current(USER_ID);

        assertEquals(200, result.getCode().intValue());
        CodingAssessmentPaperVO vo = (CodingAssessmentPaperVO) result.getData();
        assertTrue(vo.getResumed());
        assertEquals(2, vo.getQuestions().size());
    }

    @Test
    @DisplayName("进行中 - 已超时懒置过期并返回空")
    void testCurrentLazyExpire() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(
            ongoing(100L, List.of(snap(1, "[0]", "Redis")), -1_000));

        ResponseResult result = service.current(USER_ID);

        assertEquals(200, result.getCode().intValue());
        assertNull(result.getData());
        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).update(captor.capture(), any());
        assertEquals(ApCodingAssessment.STATUS_EXPIRED, captor.getValue().getStatus().intValue());
    }

    @Test
    @DisplayName("进行中 - 无进行中返回空")
    void testCurrentEmpty() {
        when(assessmentMapper.selectOngoing(USER_ID)).thenReturn(null);

        ResponseResult result = service.current(USER_ID);

        assertEquals(200, result.getCode().intValue());
        assertNull(result.getData());
    }

    // ==================== 交卷 ====================

    @Test
    @DisplayName("交卷 - 判分归一化到百分制，领域分布与逐题解析正确")
    void testSubmitJudgeAndScore() {
        ApCodingAssessment record = ongoing(1L, List.of(
            snap(1, "[0]", "Redis,缓存"), snap(2, "[0,2]", "MySQL"), snap(3, "[1]", "Java")), 600_000);
        when(assessmentMapper.selectById(1L)).thenReturn(record);
        when(assessmentMapper.countSubmitted()).thenReturn(5L);
        when(assessmentMapper.update(any(ApCodingAssessment.class), any())).thenReturn(1);

        // 1 对、2 多选对、3 错答（正确答案 [1]，提交 [0]）
        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0), item(2, 0, 2), item(3, 0)));

        assertEquals(200, result.getCode().intValue());
        CodingAssessmentResultVO vo = (CodingAssessmentResultVO) result.getData();
        assertEquals(67, vo.getScore().intValue()); // 2/3 → 四舍五入
        assertEquals(2, vo.getCorrectCount().intValue());
        assertEquals(3, vo.getTotalCount().intValue());
        assertNull(vo.getPercentile()); // 样本 6 < 20 不展示
        assertEquals(3, vo.getItems().size());
        assertTrue(vo.getItems().get(0).getCorrect());
        assertTrue(vo.getItems().get(1).getCorrect());
        assertFalse(vo.getItems().get(2).getCorrect());
        assertEquals(List.of(0), vo.getItems().get(2).getUserAnswer());
        assertEquals(List.of(1), vo.getItems().get(2).getCorrectAnswer());
        assertEquals(3, vo.getDomainStats().size());
        assertTrue(vo.getDomainStats().containsKey("Redis"));

        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).update(captor.capture(), any());
        ApCodingAssessment saved = captor.getValue();
        assertEquals(ApCodingAssessment.STATUS_SUBMITTED, saved.getStatus().intValue());
        assertEquals(67, saved.getScore().intValue());
        assertEquals(2, saved.getCorrectCount().intValue());
        assertTrue(saved.getAnswers().contains("\"correct\":false"));
        assertTrue(saved.getDomainStats().contains("\"Redis\""));
        assertTrue(saved.getDurationSeconds() >= 59);
        assertTrue(saved.getSubmittedTime() != null);
    }

    @Test
    @DisplayName("交卷 - 已提交幂等回放成绩单，不再判分与写入")
    void testSubmitIdempotent() {
        ApCodingAssessment submitted = new ApCodingAssessment();
        submitted.setId(1L);
        submitted.setUserId(USER_ID);
        submitted.setStatus(ApCodingAssessment.STATUS_SUBMITTED);
        submitted.setPaperSnapshot(CodingJudge.writeJson(List.of(snap(1, "[0]", "Redis"))));
        submitted.setAnswers(CodingJudge.writeJson(List.of(answerItem(1L, List.of(0), true))));
        submitted.setScore(100);
        submitted.setCorrectCount(1);
        submitted.setTotalCount(1);
        submitted.setDomainStats("{\"Redis\":{\"total\":1,\"correct\":1}}");
        submitted.setSubmittedTime(new Date());
        when(assessmentMapper.selectById(1L)).thenReturn(submitted);

        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0)));

        assertEquals(200, result.getCode().intValue());
        CodingAssessmentResultVO vo = (CodingAssessmentResultVO) result.getData();
        assertEquals(100, vo.getScore().intValue());
        assertTrue(vo.getItems().get(0).getCorrect());
        verify(assessmentMapper, never()).update(any(ApCodingAssessment.class), any());
        verify(assessmentMapper, never()).countSubmitted();
    }

    @Test
    @DisplayName("交卷 - 超时拒绝并置过期")
    void testSubmitExpiredRejected() {
        when(assessmentMapper.selectById(1L)).thenReturn(
            ongoing(1L, List.of(snap(1, "[0]", "Redis")), -1_000));

        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0)));

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("已过期"));
        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).update(captor.capture(), any());
        assertEquals(ApCodingAssessment.STATUS_EXPIRED, captor.getValue().getStatus().intValue());
        verify(assessmentMapper, never()).countSubmitted();
    }

    @Test
    @DisplayName("交卷 - 非本人/不存在拒绝")
    void testSubmitNotOwned() {
        ApCodingAssessment other = ongoing(1L, List.of(snap(1, "[0]", "Redis")), 600_000);
        other.setUserId(9999);
        when(assessmentMapper.selectById(1L)).thenReturn(other);

        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0)));

        assertEquals(400, result.getCode().intValue());
        assertTrue(String.valueOf(result.getMessage()).contains("测评不存在"));
        verify(assessmentMapper, never()).update(any(ApCodingAssessment.class), any());
    }

    @Test
    @DisplayName("交卷 - 参数缺失拒绝")
    void testSubmitInvalidParam() {
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            service.submit(USER_ID, null).getCode());
        CodingAssessmentSubmitDTO noId = new CodingAssessmentSubmitDTO();
        assertEquals(AppHttpCodeEnum.PARAM_INVALID.getCode(),
            service.submit(USER_ID, noId).getCode());
        verifyNoInteractions(assessmentMapper);
    }

    @Test
    @DisplayName("交卷 - 并发占位失败后回读已提交记录幂等回放")
    void testSubmitConcurrentFallback() {
        ApCodingAssessment record = ongoing(1L, List.of(snap(1, "[0]", "Redis")), 600_000);
        ApCodingAssessment fresh = new ApCodingAssessment();
        fresh.setId(1L);
        fresh.setUserId(USER_ID);
        fresh.setStatus(ApCodingAssessment.STATUS_SUBMITTED);
        fresh.setPaperSnapshot(record.getPaperSnapshot());
        fresh.setAnswers(CodingJudge.writeJson(List.of(answerItem(1L, List.of(0), true))));
        fresh.setScore(100);
        fresh.setCorrectCount(1);
        fresh.setTotalCount(1);
        fresh.setSubmittedTime(new Date());
        when(assessmentMapper.selectById(1L)).thenReturn(record, fresh);
        when(assessmentMapper.countSubmitted()).thenReturn(0L);
        when(assessmentMapper.update(any(ApCodingAssessment.class), any())).thenReturn(0);

        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0)));

        assertEquals(200, result.getCode().intValue());
        assertEquals(100, ((CodingAssessmentResultVO) result.getData()).getScore().intValue());
    }

    @Test
    @DisplayName("交卷 - 样本达标计算百分位（最低 1%）")
    void testSubmitPercentileComputed() {
        ApCodingAssessment record = ongoing(1L, List.of(snap(1, "[0]", "Redis")), 600_000);
        when(assessmentMapper.selectById(1L)).thenReturn(record);
        when(assessmentMapper.countSubmitted()).thenReturn(25L);
        when(assessmentMapper.countSubmittedBelow(100)).thenReturn(20L);
        when(assessmentMapper.update(any(ApCodingAssessment.class), any())).thenReturn(1);

        ResponseResult result = service.submit(USER_ID, dto(1L, item(1, 0)));

        // 20 / (25+1) ≈ 76.92% → 77
        assertEquals(77, ((CodingAssessmentResultVO) result.getData()).getPercentile().intValue());
        ArgumentCaptor<ApCodingAssessment> captor = ArgumentCaptor.forClass(ApCodingAssessment.class);
        verify(assessmentMapper).update(captor.capture(), any());
        assertEquals(77, captor.getValue().getPercentile().intValue());
    }

    @Test
    @DisplayName("交卷 - 未作答与越界下标按错题处理")
    void testSubmitSanitizeAnswers() {
        ApCodingAssessment record = ongoing(1L, List.of(
            snap(1, "[0]", "Redis"), snap(2, "[1]", "MySQL")), 600_000);
        when(assessmentMapper.selectById(1L)).thenReturn(record);
        when(assessmentMapper.countSubmitted()).thenReturn(0L);
        when(assessmentMapper.update(any(ApCodingAssessment.class), any())).thenReturn(1);

        // 第一题未提交；第二题提交越界下标 9（清洗后为空 → 错）
        ResponseResult result = service.submit(USER_ID, dto(1L, item(2, 9)));

        CodingAssessmentResultVO vo = (CodingAssessmentResultVO) result.getData();
        assertEquals(0, vo.getScore().intValue());
        assertTrue(vo.getItems().get(0).getUserAnswer().isEmpty());
        assertTrue(vo.getItems().get(1).getUserAnswer().isEmpty());
    }

    // ==================== 成绩单 / 历史 ====================

    @Test
    @DisplayName("最近成绩 - 无记录返回空，有记录回放成绩单")
    void testLatest() {
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(null);
        assertNull(service.latest(USER_ID).getData());

        ApCodingAssessment submitted = new ApCodingAssessment();
        submitted.setId(7L);
        submitted.setStatus(ApCodingAssessment.STATUS_SUBMITTED);
        submitted.setPaperSnapshot(CodingJudge.writeJson(List.of(snap(1, "[0]", "Redis"))));
        submitted.setAnswers(CodingJudge.writeJson(List.of(answerItem(1L, List.of(0), true))));
        submitted.setScore(100);
        submitted.setCorrectCount(1);
        submitted.setTotalCount(1);
        submitted.setSubmittedTime(new Date());
        when(assessmentMapper.selectLatestSubmitted(USER_ID)).thenReturn(submitted);

        CodingAssessmentResultVO vo = (CodingAssessmentResultVO) service.latest(USER_ID).getData();
        assertEquals(100, vo.getScore().intValue());
        assertEquals(1, vo.getItems().size());
        assertEquals(1L, vo.getItems().get(0).getQuestionId().longValue());
    }

    @Test
    @DisplayName("历史列表 - 分页组装（含状态与成绩）")
    @SuppressWarnings("unchecked")
    void testHistory() {
        Page<ApCodingAssessment> page = new Page<>(1, 10);
        ApCodingAssessment submitted = new ApCodingAssessment();
        submitted.setId(1L);
        submitted.setStatus(ApCodingAssessment.STATUS_SUBMITTED);
        submitted.setScore(80);
        submitted.setCorrectCount(8);
        submitted.setTotalCount(10);
        submitted.setSubmittedTime(new Date());
        ApCodingAssessment expired = new ApCodingAssessment();
        expired.setId(2L);
        expired.setStatus(ApCodingAssessment.STATUS_EXPIRED);
        page.setRecords(new ArrayList<>(List.of(submitted, expired)));
        page.setTotal(2L);
        when(assessmentMapper.selectPage(any(), any())).thenReturn(page);

        ResponseResult result = service.history(USER_ID, 1, 10);

        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<CodingAssessmentHistoryVO> list = (List<CodingAssessmentHistoryVO>) data.get("list");
        assertEquals(2, list.size());
        assertEquals(80, list.get(0).getScore().intValue());
        assertEquals(ApCodingAssessment.STATUS_EXPIRED, list.get(1).getStatus().intValue());
        assertEquals("", list.get(1).getSubmittedTime());
        assertEquals(2L, ((Long) data.get("total")).longValue());
    }
}