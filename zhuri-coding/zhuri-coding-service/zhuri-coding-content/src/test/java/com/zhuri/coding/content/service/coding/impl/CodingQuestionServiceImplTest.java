package com.zhuri.coding.content.service.coding.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.apis.user.IUserClient;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.article.ApArticleMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingQuestionMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAnswerTxService;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.article.pojos.ApArticle;
import com.zhuri.coding.model.coding.dtos.CodingAnswerDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingQuestion;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingAnswerVO;
import com.zhuri.coding.model.coding.vos.CodingQuestionVO;
import com.zhuri.coding.model.coding.vos.CodingRankingVO;
import com.zhuri.coding.model.coding.vos.CodingStatVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingQuestionServiceImpl 单元测试（Coding 延展第一层 · 每日一题与刷题）
 *
 * 覆盖：今日题（已答回放/缓存命中/难度自适应抽题/兜底链）、判分提交
 * （答对计分打卡、答错不加分、练习不计分、防换题、防重、参数校验）、
 * 榜单（名次/正确率/isSelf/用户服务降级）、题库列表、我的统计（含 Feign 降级）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("每日一题做题服务单元测试")
class CodingQuestionServiceImplTest {

    private static final Integer USER_ID = 1001;

    @Mock
    private ApCodingQuestionMapper questionMapper;
    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private CodingAnswerTxService txService;
    @Mock
    private ApArticleMapper articleMapper;
    @Mock
    private LevelService levelService;
    @Mock
    private IRewardClient rewardClient;
    @Mock
    private IUserClient userClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private CodingQuestionServiceImpl service;

    // ---------- 辅助 ----------

    private Date today() {
        return Date.valueOf(LocalDate.now());
    }

    private String dailyKey() {
        return "coding:daily:" + USER_ID + ":" + today();
    }

    private ApCodingQuestion question(Long id, int type, int difficulty, String answer) {
        ApCodingQuestion q = new ApCodingQuestion();
        q.setId(id);
        q.setStem("以下关于 Redis 缓存穿透的描述，哪一项是正确的？");
        q.setQuestionType(type);
        q.setOptions("[\"A选项\",\"B选项\",\"C选项\",\"D选项\"]");
        q.setAnswer(answer);
        q.setExplanation("缓存穿透指查询不存在的数据，可用布隆过滤器兜底。");
        q.setDifficulty(difficulty);
        q.setTags("Redis,缓存");
        q.setStatus(ApCodingQuestion.STATUS_PUBLISHED);
        q.setSourceType(ApCodingQuestion.SOURCE_AI);
        q.setSourceArticleId(5L);
        q.setAnswerCount(10);
        q.setCorrectCount(6);
        return q;
    }

    private ApCodingAnswerRecord dailyRecord(Long questionId, int isCorrect) {
        ApCodingAnswerRecord record = new ApCodingAnswerRecord();
        record.setId(100L);
        record.setUserId(USER_ID);
        record.setQuestionId(questionId);
        record.setAnswerDate(today());
        record.setUserAnswer("[0]");
        record.setIsCorrect(isCorrect);
        record.setIsDaily(1);
        record.setElapsedSeconds(12);
        record.setScoreAwarded(3);
        return record;
    }

    private ApCodingUserStat stat(int total, int correct, int practice, int practiceCorrect) {
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setTotalCount(total);
        stat.setCorrectCount(correct);
        stat.setPracticeCount(practice);
        stat.setPracticeCorrectCount(practiceCorrect);
        return stat;
    }

    // ==================== 今日题 ====================

    @Test
    @DisplayName("today - 当天已答：回放完整结果（含答案/解析/来源文章）")
    void testTodayReplayAnswered() {
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(dailyRecord(9L, 1));
        when(questionMapper.selectById(9L)).thenReturn(question(9L, 1, 2, "[0]"));
        ApArticle article = new ApArticle();
        article.setId(5L);
        article.setTitle("Redis 缓存实践");
        article.setStatus((byte) 9);
        when(articleMapper.selectById(5L)).thenReturn(article);

        ResponseResult result = service.today(USER_ID, null);

        assertEquals(200, result.getCode().intValue());
        CodingQuestionVO vo = (CodingQuestionVO) result.getData();
        assertTrue(vo.getAnswered());
        assertTrue(vo.getIsCorrect());
        assertEquals(List.of(0), vo.getCorrectAnswer());
        assertEquals("缓存穿透指查询不存在的数据，可用布隆过滤器兜底。", vo.getExplanation());
        assertEquals(3, vo.getScoreAwarded());
        assertEquals("Redis 缓存实践", vo.getSourceArticleTitle());
    }

    @Test
    @DisplayName("today - 缓存命中：直接返回缓存题且不查库抽题")
    void testTodayCachedQuestion() {
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn("7");
        when(questionMapper.selectById(7L)).thenReturn(question(7L, 1, 2, "[1]"));

        ResponseResult result = service.today(USER_ID, null);

        assertEquals(7L, ((CodingQuestionVO) result.getData()).getId());
        assertFalse(((CodingQuestionVO) result.getData()).getAnswered());
        verify(questionMapper, never()).selectRandomUnanswered(any(), any());
    }

    @Test
    @DisplayName("today - 缓存未命中：按历史正确率自适应抽题并写缓存")
    void testTodayAdaptivePicksHardAndCaches() {
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn(null);
        when(statMapper.selectOne(any())).thenReturn(stat(10, 9, 0, 0));
        when(questionMapper.selectRandomUnanswered(USER_ID, 3)).thenReturn(question(7L, 1, 3, "[0]"));

        ResponseResult result = service.today(USER_ID, null);

        CodingQuestionVO vo = (CodingQuestionVO) result.getData();
        assertEquals(3, vo.getDifficulty());
        verify(valueOps).set(dailyKey(), "7", 26, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("today - 难度题全答完：兜底链允许重复抽题不断供")
    void testTodayPickFallbackChain() {
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn(null);
        when(questionMapper.selectRandomUnanswered(USER_ID, 1)).thenReturn(null);
        when(questionMapper.selectRandomUnanswered(USER_ID, null)).thenReturn(null);
        when(questionMapper.selectRandomAny(1)).thenReturn(question(7L, 1, 1, "[0]"));

        ResponseResult result = service.today(USER_ID, 1);

        assertEquals(7L, ((CodingQuestionVO) result.getData()).getId());
    }

    // ==================== 作答提交 ====================

    @Test
    @DisplayName("answer - 当日题答对：计等级分 + 触发打卡 + 回填得分")
    void testAnswerDailyCorrect() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(0));
        dto.setIsDaily(true);
        dto.setElapsedSeconds(12);
        ApCodingQuestion q = question(9L, ApCodingQuestion.TYPE_SINGLE, 1, "[0]");
        when(questionMapper.selectById(9L)).thenReturn(q);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn("9");
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        ApCodingAnswerRecord rec = new ApCodingAnswerRecord();
        rec.setId(100L);
        when(txService.saveAnswer(eq(USER_ID), eq(q), anyString(), eq(true), eq(12), eq(true))).thenReturn(rec);
        Map<String, Object> levelResult = new HashMap<>();
        levelResult.put("success", true);
        levelResult.put("score", new BigDecimal("3"));
        when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
            eq(LevelScoreActionCode.ANSWER_QUESTION), anyString())).thenReturn(levelResult);
        when(rewardClient.completeCheckin(USER_ID.longValue()))
            .thenReturn(ResponseResult.okResult(Map.of("continuousDays", 5)));

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(200, result.getCode().intValue());
        CodingAnswerVO vo = (CodingAnswerVO) result.getData();
        assertTrue(vo.getIsCorrect());
        assertEquals(3, vo.getScoreAwarded());
        assertEquals(5, vo.getContinuousDays());
        assertEquals(11, vo.getAnswerCount());
        ArgumentCaptor<ApCodingAnswerRecord> captor = ArgumentCaptor.forClass(ApCodingAnswerRecord.class);
        verify(recordMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getScoreAwarded().intValue());
        verify(redisTemplate).delete(anyString());
    }

    @Test
    @DisplayName("answer - 当日题答错：锁定但不计分不打卡")
    void testAnswerDailyWrong() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(1));
        dto.setIsDaily(true);
        ApCodingQuestion q = question(9L, ApCodingQuestion.TYPE_SINGLE, 1, "[0]");
        when(questionMapper.selectById(9L)).thenReturn(q);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn("9");
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(txService.saveAnswer(eq(USER_ID), eq(q), anyString(), eq(false), isNull(), eq(true)))
            .thenReturn(new ApCodingAnswerRecord());

        ResponseResult result = service.answer(USER_ID, dto);

        CodingAnswerVO vo = (CodingAnswerVO) result.getData();
        assertFalse(vo.getIsCorrect());
        assertEquals(0, vo.getScoreAwarded());
        assertNull(vo.getContinuousDays());
        verifyNoInteractions(levelService);
        verifyNoInteractions(rewardClient);
        verify(recordMapper, never()).updateById(any(ApCodingAnswerRecord.class));
    }

    @Test
    @DisplayName("answer - 自由练习：只沉淀统计，不计分不打卡")
    void testAnswerPracticeNoReward() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(0));
        dto.setIsDaily(false);
        ApCodingQuestion q = question(9L, ApCodingQuestion.TYPE_SINGLE, 1, "[0]");
        when(questionMapper.selectById(9L)).thenReturn(q);
        when(txService.saveAnswer(eq(USER_ID), eq(q), anyString(), eq(true), isNull(), eq(false)))
            .thenReturn(new ApCodingAnswerRecord());

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(200, result.getCode().intValue());
        assertTrue(((CodingAnswerVO) result.getData()).getIsCorrect());
        verifyNoInteractions(levelService);
        verifyNoInteractions(rewardClient);
    }

    @Test
    @DisplayName("answer - 提交题目与缓存的今日题不一致：拒绝（防换题挑简单题）")
    void testAnswerRejectsMismatchedDailyQuestion() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(0));
        dto.setIsDaily(true);
        when(questionMapper.selectById(9L)).thenReturn(question(9L, 1, 1, "[0]"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn("5");

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("今日题目已更新"));
        verify(txService, never()).saveAnswer(any(), any(), anyString(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("answer - 今日已作答：直接拒绝")
    void testAnswerRejectsAlreadyAnswered() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(0));
        dto.setIsDaily(true);
        when(questionMapper.selectById(9L)).thenReturn(question(9L, 1, 1, "[0]"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(dailyKey())).thenReturn("9");
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(dailyRecord(9L, 1));

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("今日一题已作答"));
    }

    @Test
    @DisplayName("answer - 答案下标超出选项范围：拒绝")
    void testAnswerRejectsOutOfRange() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(9));
        when(questionMapper.selectById(9L)).thenReturn(question(9L, 1, 1, "[0]"));

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("超出选项范围"));
    }

    @Test
    @DisplayName("answer - 单选题只能选一个选项：拒绝")
    void testAnswerRejectsSingleChoiceMultiple() {
        CodingAnswerDTO dto = new CodingAnswerDTO();
        dto.setQuestionId(9L);
        dto.setAnswers(List.of(0, 1));
        when(questionMapper.selectById(9L)).thenReturn(question(9L, 1, 1, "[0]"));

        ResponseResult result = service.answer(USER_ID, dto);

        assertEquals(400, result.getCode().intValue());
        assertTrue(result.getMessage().contains("单选题只能选择一个选项"));
    }

    // ==================== 榜单 ====================

    @Test
    @DisplayName("ranking - 组装名次/正确率/isSelf 并批量补昵称头像")
    void testRankingAssemblesAndMarksSelf() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row1 = new HashMap<>();
        row1.put("userId", 200L);
        row1.put("totalCount", 10L);
        row1.put("correctCount", 8L);
        row1.put("avgSeconds", new BigDecimal("12.5"));
        rows.add(row1);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("userId", 300L);
        row2.put("totalCount", 5L);
        row2.put("correctCount", 2L);
        row2.put("avgSeconds", new BigDecimal("30.2"));
        rows.add(row2);
        when(recordMapper.selectRanking(any(), eq(20))).thenReturn(rows);
        Map<String, Object> info = new HashMap<>();
        info.put("nickname", "张三");
        info.put("avatar", "a.png");
        Map<String, Object> batch = new HashMap<>();
        batch.put("200", info);
        when(userClient.getBasicInfoBatch(anyList())).thenReturn(ResponseResult.okResult(batch));

        ResponseResult result = service.ranking("week", 200);

        assertEquals(200, result.getCode().intValue());
        @SuppressWarnings("unchecked")
        List<CodingRankingVO> list = (List<CodingRankingVO>) ((Map<String, Object>) result.getData()).get("list");
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).getRank());
        assertEquals("张三", list.get(0).getNickname());
        assertEquals(80, list.get(0).getAccuracy());
        assertEquals(13, list.get(0).getAvgSeconds());
        assertTrue(list.get(0).getIsSelf());
        assertEquals("", list.get(1).getNickname());
        assertEquals(40, list.get(1).getAccuracy());
        assertFalse(list.get(1).getIsSelf());
    }

    @Test
    @DisplayName("ranking - 用户服务不可用：昵称头像留空不拖垮榜单")
    void testRankingUserServiceDown() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("userId", 300L);
        row.put("totalCount", 5L);
        row.put("correctCount", 2L);
        row.put("avgSeconds", new BigDecimal("30.2"));
        rows.add(row);
        when(recordMapper.selectRanking(any(), eq(20))).thenReturn(rows);
        when(userClient.getBasicInfoBatch(anyList())).thenThrow(new RuntimeException("user down"));

        ResponseResult result = service.ranking("day", null);

        @SuppressWarnings("unchecked")
        List<CodingRankingVO> list = (List<CodingRankingVO>) ((Map<String, Object>) result.getData()).get("list");
        assertEquals(1, list.size());
        assertEquals("", list.get(0).getNickname());
        assertFalse(list.get(0).getIsSelf());
    }

    // ==================== 题库列表 ====================

    @Test
    @DisplayName("questions - 登录用户标记已答，匿名不查询作答记录")
    void testQuestionsMarksAnsweredForLoggedUser() {
        Page<ApCodingQuestion> page = new Page<>(1, 10);
        page.setRecords(List.of(question(9L, 1, 1, "[0]"), question(10L, 1, 2, "[0]")));
        page.setTotal(2);
        doReturn(page).when(questionMapper).selectPage(any(), any());
        ApCodingAnswerRecord answered = new ApCodingAnswerRecord();
        answered.setQuestionId(9L);
        when(recordMapper.selectList(any())).thenReturn(List.of(answered));

        ResponseResult result = service.questions(null, 1, 10, USER_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        @SuppressWarnings("unchecked")
        List<CodingQuestionVO> list = (List<CodingQuestionVO>) data.get("list");
        assertEquals(2, list.size());
        assertTrue(list.get(0).getAnswered());
        assertFalse(list.get(1).getAnswered());
        assertNull(list.get(0).getCorrectAnswer());
        verify(recordMapper).selectList(any());
    }

    // ==================== 我的统计 ====================

    @Test
    @DisplayName("myStat - 连续天数来自签到体系，今日作答态与领域分布正确回填")
    void testMyStat() {
        ApCodingUserStat stat = stat(10, 7, 4, 1);
        stat.setTagStats("{\"Redis\":{\"total\":3,\"correct\":2}}");
        stat.setFirstAnswerDate(today());
        stat.setLastAnswerDate(today());
        when(statMapper.selectOne(any())).thenReturn(stat);
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(dailyRecord(9L, 1));
        when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
            .thenReturn(ResponseResult.okResult(Map.of("continuousDays", 6)));

        ResponseResult result = service.myStat(USER_ID);

        CodingStatVO vo = (CodingStatVO) result.getData();
        assertEquals(6, vo.getContinuousDays());
        assertTrue(vo.getTodayAnswered());
        assertTrue(vo.getTodayCorrect());
        assertEquals(70, vo.getAccuracy());
        assertEquals(25, vo.getPracticeAccuracy());
        assertEquals(today().toString(), vo.getFirstAnswerDate());
        assertTrue(vo.getTagStats().containsKey("Redis"));
    }

    @Test
    @DisplayName("myStat - 奖励服务不可用：连续天数降级为 0")
    void testMyStatRewardServiceDown() {
        when(statMapper.selectOne(any())).thenReturn(null);
        when(txService.findDailyRecord(USER_ID, today())).thenReturn(null);
        when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
            .thenThrow(new RuntimeException("reward down"));

        ResponseResult result = service.myStat(USER_ID);

        CodingStatVO vo = (CodingStatVO) result.getData();
        assertEquals(0, vo.getContinuousDays());
        assertFalse(vo.getTodayAnswered());
        assertEquals(0, vo.getTotalCount());
    }
}