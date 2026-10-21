package com.zhuri.coding.content.service.coding.impl;

import com.zhuri.coding.apis.reward.IRewardClient;
import com.zhuri.coding.content.constants.LevelScoreActionCode;
import com.zhuri.coding.content.mapper.coding.ApCodingAnswerRecordMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingDailyPoolMapper;
import com.zhuri.coding.content.mapper.coding.ApCodingUserStatMapper;
import com.zhuri.coding.content.service.coding.CodingAnswerTxService;
import com.zhuri.coding.content.service.coding.CodingDailyEvaluator;
import com.zhuri.coding.content.service.level.LevelService;
import com.zhuri.coding.model.coding.dtos.CodingDailyAnswerDTO;
import com.zhuri.coding.model.coding.pojos.ApCodingAnswerRecord;
import com.zhuri.coding.model.coding.pojos.ApCodingDailyPool;
import com.zhuri.coding.model.coding.pojos.ApCodingUserStat;
import com.zhuri.coding.model.coding.vos.CodingDailyAnswerVO;
import com.zhuri.coding.model.coding.vos.CodingDailyQuestionVO;
import com.zhuri.coding.model.coding.vos.CodingStatVO;
import com.zhuri.coding.model.common.dtos.ResponseResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CodingQuestionServiceImpl 单元测试（每日一题 · 简答）
 *
 * 覆盖：抽题（纯读不落库 / 换题 / 方向兜底 / 池子为空）、开始作答（落未作答流水 /
 * 换题改指向 / 幂等 / 已答拒绝）、作答（题目归属校验、评估通过落库并记分、评估降级照常落库、
 * 重复提交）、统计回填与签到降级。
 *
 * <p>「他正在做的是哪道」由作答流水的 status 表达（未作答 / 已作答），所以本类不再有 Redis 相关桩。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CodingQuestionServiceImpl 每日一题（简答）")
class CodingQuestionServiceImplTest {

    private static final Integer USER_ID = 1001;
    private static final Date TODAY = Date.valueOf(LocalDate.now());

    @Mock
    private ApCodingDailyPoolMapper poolMapper;
    @Mock
    private ApCodingAnswerRecordMapper recordMapper;
    @Mock
    private ApCodingUserStatMapper statMapper;
    @Mock
    private CodingAnswerTxService txService;
    @Mock
    private CodingDailyEvaluator evaluator;
    @Mock
    private LevelService levelService;
    @Mock
    private IRewardClient rewardClient;

    @InjectMocks
    private CodingQuestionServiceImpl service;

    // ---------- 辅助 ----------

    private ApCodingDailyPool pool(Long id, String direction) {
        ApCodingDailyPool p = new ApCodingDailyPool();
        p.setId(id);
        p.setDirection(direction);
        p.setStem("讲讲你对 Java 内存模型的理解。");
        p.setKeyPoints("[\"可见性\",\"有序性\",\"不保证原子性\"]");
        p.setDifficulty(2);
        p.setTags("Java,并发");
        p.setStatus(ApCodingDailyPool.STATUS_ENABLED);
        p.setUseCount(0);
        return p;
    }

    private CodingDailyEvaluator.Result evaluated() {
        CodingDailyEvaluator.Result r = new CodingDailyEvaluator.Result();
        r.pending = false;
        r.structure = 4;
        r.coverageScore = 3;
        r.accuracy = 4;
        r.level = 4;
        r.covered = List.of("可见性");
        r.missing = List.of("有序性", "不保证原子性");
        r.comment = "讲到了可见性，但没展开禁止重排。";
        return r;
    }

    /** 已作答的流水 */
    private ApCodingAnswerRecord record(Long poolId, Integer level) {
        ApCodingAnswerRecord r = issued(poolId);
        r.setUserAnswer("我的作答");
        r.setLevel(level);
        r.setFeedback("ok");
        r.setCovered("[\"可见性\"]");
        r.setMissing("[\"有序性\",\"不保证原子性\"]");
        r.setElapsedSeconds(120);
        r.setStatus(ApCodingAnswerRecord.STATUS_ANSWERED);
        return r;
    }

    /** 点了「开始作答」后落下的流水：指向某道题，状态还是未作答 */
    private ApCodingAnswerRecord issued(Long poolId) {
        ApCodingAnswerRecord r = new ApCodingAnswerRecord();
        r.setId(900L);
        r.setUserId(USER_ID);
        r.setPoolId(poolId);
        r.setAnswerDate(TODAY);
        r.setUserAnswer("");
        r.setScoreAwarded(0);
        r.setStatus(ApCodingAnswerRecord.STATUS_ISSUED);
        return r;
    }

    private void givenNoRecordToday() {
        lenient().when(txService.findDailyRecord(any(), any())).thenReturn(null);
    }

    private void givenStatExists(String direction) {
        ApCodingUserStat stat = new ApCodingUserStat();
        stat.setUserId(USER_ID);
        stat.setDirection(direction);
        stat.setTotalCount(3);
        stat.setTagStats("{\"Java\":{\"total\":3,\"levelSum\":10}}");
        lenient().when(statMapper.selectOne(any())).thenReturn(stat);
    }

    // ==================== 今日题 ====================

    @Nested
    @DisplayName("today 抽题")
    class Today {

        @Test
        @DisplayName("还没开始作答：按方向抽一道返回，什么都不落库")
        void testPickOnly() {
            givenNoRecordToday();
            givenStatExists("Java 后端");
            when(poolMapper.selectOneByDirection("Java 后端")).thenReturn(pool(5L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, null, null);

            assertEquals(200, result.getCode().intValue());
            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) result.getData();
            assertEquals(5L, vo.getId());
            assertFalse(vo.getAnswered());
            assertFalse(vo.getStarted());
            assertNull(vo.getLevel());
            verify(txService, never()).issueQuestion(any(), any(), any());
            verify(txService, never()).reissueQuestion(any(), any());
        }

        @Test
        @DisplayName("换一题：refresh=true 就重新抽一道，仍不落库")
        void testRefreshRepicks() {
            givenStatExists("Java 后端");
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));
            when(poolMapper.selectOneByDirection("Java 后端")).thenReturn(pool(6L, "Java 后端"));

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) service.today(USER_ID, null, true).getData();

            assertEquals(6L, vo.getId());
            // 换出来的是一道全新的题，界面要退回「开始作答 / 换一题」那一屏
            assertFalse(vo.getStarted());
            verify(txService, never()).issueQuestion(any(), any(), any());
            verify(txService, never()).reissueQuestion(any(), any());
        }

        @Test
        @DisplayName("已答：回放完整结果（含等级与考点清单），不再抽题")
        void testReplayAnswered() {
            when(txService.findDailyRecord(any(), any())).thenReturn(record(5L, 4));
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, null, null);

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) result.getData();
            assertTrue(vo.getAnswered());
            assertEquals(4, vo.getLevel());
            assertEquals(List.of("可见性"), vo.getCovered());
            assertEquals(List.of("有序性", "不保证原子性"), vo.getMissing());
            assertEquals("我的作答", vo.getUserAnswer());
            verify(poolMapper, never()).selectOneByDirection(any());
            verify(txService, never()).issueQuestion(any(), any(), any());
        }

        @Test
        @DisplayName("开始作答后刷新：看到的是同一道，不重抽")
        void testDoingQuestionStable() {
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) service.today(USER_ID, null, null).getData();

            assertEquals(5L, vo.getId());
            assertFalse(vo.getAnswered());
            // 这次带 started=true：前端据此直接亮出答题区，而不是再要一次「开始作答」
            assertTrue(vo.getStarted());
            verify(poolMapper, never()).selectOneByDirection(any());
            verify(txService, never()).issueQuestion(any(), any(), any());
        }

        @Test
        @DisplayName("方向无题：降级到不限方向兜底，仍不断供")
        void testDirectionFallback() {
            givenNoRecordToday();
            when(poolMapper.selectOneByDirection("小众方向")).thenReturn(null);
            when(poolMapper.selectOneAny()).thenReturn(pool(7L, "Java 后端"));

            ResponseResult result = service.today(USER_ID, "小众方向", null);

            assertEquals(200, result.getCode().intValue());
            assertEquals(7L, ((CodingDailyQuestionVO) result.getData()).getId());
        }

        @Test
        @DisplayName("池子为空：返回题库准备中，不落库")
        void testPoolEmpty() {
            givenNoRecordToday();
            when(poolMapper.selectOneByDirection(anyString())).thenReturn(null);
            when(poolMapper.selectOneAny()).thenReturn(null);

            ResponseResult result = service.today(USER_ID, null, null);

            assertEquals(400, result.getCode().intValue());
            assertTrue(String.valueOf(result.getMessage()).contains("题库准备中"));
            verify(txService, never()).issueQuestion(any(), any(), any());
        }

        @Test
        @DisplayName("正在做的那道事后被下架：重新抽一道，不落库")
        void testDoingQuestionDisabled() {
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));
            ApCodingDailyPool disabled = pool(5L, "Java 后端");
            disabled.setStatus(ApCodingDailyPool.STATUS_DISABLED);
            when(poolMapper.selectById(5L)).thenReturn(disabled);
            when(poolMapper.selectOneByDirection(anyString())).thenReturn(pool(8L, "Java 后端"));

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) service.today(USER_ID, null, null).getData();

            assertEquals(8L, vo.getId());
            verify(txService, never()).issueQuestion(any(), any(), any());
            verify(txService, never()).reissueQuestion(any(), any());
        }

        @Test
        @DisplayName("已作答的题事后被下架：结果照常回放")
        void testAnsweredReplaySurvivesDisable() {
            when(txService.findDailyRecord(any(), any())).thenReturn(record(5L, 4));
            ApCodingDailyPool disabled = pool(5L, "Java 后端");
            disabled.setStatus(ApCodingDailyPool.STATUS_DISABLED);
            when(poolMapper.selectById(5L)).thenReturn(disabled);

            CodingDailyQuestionVO vo = (CodingDailyQuestionVO) service.today(USER_ID, null, null).getData();

            assertTrue(vo.getAnswered());
            assertEquals(4, vo.getLevel());
            verify(poolMapper, never()).selectOneByDirection(any());
        }
    }

    // ==================== 开始作答 ====================

    @Nested
    @DisplayName("startAnswer 开始作答")
    class Start {

        @Test
        @DisplayName("第一次开始作答：落一条「未作答」流水")
        void testInserts() {
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            when(txService.findDailyRecord(any(), any())).thenReturn(null);

            assertEquals(200, service.startAnswer(USER_ID, 5L).getCode().intValue());
            verify(txService).issueQuestion(USER_ID, TODAY, 5L);
        }

        @Test
        @DisplayName("换了另一道重新开始：改这条流水的指向，不新增")
        void testReissueOnSwitch() {
            when(poolMapper.selectById(6L)).thenReturn(pool(6L, "Java 后端"));
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));

            assertEquals(200, service.startAnswer(USER_ID, 6L).getCode().intValue());
            verify(txService).reissueQuestion(900L, 6L);
            verify(txService, never()).issueQuestion(any(), any(), any());
        }

        @Test
        @DisplayName("同一道题重复点开始：幂等，不重复落库")
        void testIdempotent() {
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));

            assertEquals(200, service.startAnswer(USER_ID, 5L).getCode().intValue());
            verify(txService, never()).issueQuestion(any(), any(), any());
            verify(txService, never()).reissueQuestion(any(), any());
        }

        @Test
        @DisplayName("今日已答：不允许再开始作答")
        void testRejectedWhenAnswered() {
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            when(txService.findDailyRecord(any(), any())).thenReturn(record(5L, 4));

            assertEquals(400, service.startAnswer(USER_ID, 5L).getCode().intValue());
            verify(txService, never()).issueQuestion(any(), any(), any());
        }

        @Test
        @DisplayName("题目不存在或已下架：拒绝开始作答")
        void testRejectedWhenDisabled() {
            ApCodingDailyPool disabled = pool(5L, "Java 后端");
            disabled.setStatus(ApCodingDailyPool.STATUS_DISABLED);
            when(poolMapper.selectById(5L)).thenReturn(disabled);

            assertEquals(400, service.startAnswer(USER_ID, 5L).getCode().intValue());
            verifyNoInteractions(txService);
        }

        @Test
        @DisplayName("题目ID缺失：拒绝")
        void testRejectedWithoutPoolId() {
            assertEquals(400, service.startAnswer(USER_ID, null).getCode().intValue());
            verifyNoInteractions(txService);
        }
    }

    // ==================== 作答 ====================

    @Nested
    @DisplayName("answer 作答")
    class Answer {

        private CodingDailyAnswerDTO dto() {
            CodingDailyAnswerDTO dto = new CodingDailyAnswerDTO();
            dto.setPoolId(5L);
            dto.setAnswerText("JMM 定义了主内存与工作内存的抽象，volatile 保证可见性与有序性。");
            dto.setElapsedSeconds(120);
            return dto;
        }

        private void givenDoingToday() {
            lenient().when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            // 归属校验通过的前提：今天开始作答的那道就是 5
            lenient().when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));
        }

        @Test
        @DisplayName("入参校验：题目ID缺失 / 文本为空 / 超长")
        void testValidation() {
            givenDoingToday();
            CodingDailyAnswerDTO empty = dto();
            empty.setAnswerText("   ");

            assertEquals(400, service.answer(USER_ID, new CodingDailyAnswerDTO()).getCode());
            assertEquals(400, service.answer(USER_ID, empty).getCode());

            CodingDailyAnswerDTO tooLong = dto();
            tooLong.setAnswerText("长".repeat(CodingDailyEvaluator.ANSWER_MAX_LENGTH + 1));
            assertEquals(400, service.answer(USER_ID, tooLong).getCode());
        }

        @Test
        @DisplayName("今日已答：拒绝且不评估不落库")
        void testAlreadyAnswered() {
            givenDoingToday();
            when(txService.findDailyRecord(any(), any())).thenReturn(record(5L, 4));

            assertEquals(400, service.answer(USER_ID, dto()).getCode());
            verifyNoInteractions(evaluator, levelService);
            verify(txService, never()).claimAnswer(any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("评估通过：先 CAS 落答案，再补写评估，回填得分并记逐日分")
        void testEvaluateAndSave() {
            givenDoingToday();
            when(txService.claimAnswer(900L, 5L, dto().getAnswerText(), 120)).thenReturn(true);
            when(evaluator.evaluate(any(ApCodingDailyPool.class), anyString())).thenReturn(evaluated());
            when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
                eq(LevelScoreActionCode.ANSWER_QUESTION), anyString()))
                .thenReturn(Map.of("success", true, "score", new BigDecimal("3")));

            CodingDailyAnswerVO vo = (CodingDailyAnswerVO) service.answer(USER_ID, dto()).getData();

            assertEquals(4, vo.getLevel());
            assertEquals(3, vo.getScoreAwarded());
            assertEquals(3, vo.getCoverageScore());
            verify(txService).saveEvaluation(eq(USER_ID), eq(900L), any(ApCodingDailyPool.class),
                eq(TODAY), any(CodingDailyEvaluator.Result.class));
            verify(recordMapper).updateById(ArgumentMatchers.<ApCodingAnswerRecord>argThat(
                r -> r.getId() == 900L && r.getScoreAwarded() == 3));
        }

        @Test
        @DisplayName("评估降级：照常落库，等级为 null，但逐日分仍给")
        void testEvaluatePending() {
            givenDoingToday();
            CodingDailyEvaluator.Result pending = new CodingDailyEvaluator.Result();
            pending.pending = true;
            pending.comment = CodingDailyEvaluator.PENDING_COMMENT;
            when(txService.claimAnswer(any(), any(), anyString(), any())).thenReturn(true);
            when(evaluator.evaluate(any(ApCodingDailyPool.class), anyString())).thenReturn(pending);
            when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
                eq(LevelScoreActionCode.ANSWER_QUESTION), anyString()))
                .thenReturn(Map.of("success", true, "score", new BigDecimal("3")));

            CodingDailyAnswerVO vo = (CodingDailyAnswerVO) service.answer(USER_ID, dto()).getData();

            assertTrue(vo.getPending());
            assertNull(vo.getLevel());
            assertEquals(3, vo.getScoreAwarded());
        }

        @Test
        @DisplayName("评估回写失败：答案已经落库，仍把评估结果返回给用户")
        void testEvaluationWriteFailureDegrades() {
            givenDoingToday();
            when(txService.claimAnswer(any(), any(), anyString(), any())).thenReturn(true);
            when(evaluator.evaluate(any(ApCodingDailyPool.class), anyString())).thenReturn(evaluated());
            doThrow(new RuntimeException("db down")).when(txService)
                .saveEvaluation(any(), any(), any(), any(), any());
            when(levelService.recordActionWithLimit(eq(USER_ID.longValue()),
                eq(LevelScoreActionCode.ANSWER_QUESTION), anyString()))
                .thenReturn(Map.of("success", true, "score", new BigDecimal("3")));

            ResponseResult result = service.answer(USER_ID, dto());

            assertEquals(200, result.getCode().intValue());
            assertEquals(4, ((CodingDailyAnswerVO) result.getData()).getLevel());
        }

        @Test
        @DisplayName("没点过开始作答就提交：拦下，不评估不落库")
        void testRejectWithoutStart() {
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            // 今天还没有任何流水 —— 他没点「开始作答」
            when(txService.findDailyRecord(any(), any())).thenReturn(null);

            ResponseResult result = service.answer(USER_ID, dto());

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("先开始作答"));
            verifyNoInteractions(evaluator, levelService);
        }

        @Test
        @DisplayName("提交的不是他开始作答的那道（题目已更换）被拦下")
        void testRejectSwitchedPoolId() {
            when(poolMapper.selectById(5L)).thenReturn(pool(5L, "Java 后端"));
            // 他实际开始作答的是 7 号题，提交的却是 5 号
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(7L));

            ResponseResult result = service.answer(USER_ID, dto());

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("重新开始作答"));
            verifyNoInteractions(evaluator, levelService);
        }

        @Test
        @DisplayName("并发重复提交：CAS 没抢到就地返回，不再白跑一次模型评估")
        void testClaimLosesSkipsEvaluation() {
            givenDoingToday();
            when(txService.claimAnswer(any(), any(), anyString(), any())).thenReturn(false);

            assertEquals(400, service.answer(USER_ID, dto()).getCode());
            verifyNoInteractions(evaluator, levelService);
            verify(txService, never()).saveEvaluation(any(), any(), any(), any(), any());
        }
    }

    // ==================== 统计 ====================

    @Nested
    @DisplayName("myStat 统计")
    class MyStat {

        @Test
        @DisplayName("领域分布 / 平均等级 / 今日等级 回填")
        void testStat() {
            givenStatExists("Java 后端");
            when(recordMapper.avgLevel(USER_ID)).thenReturn(3.5);
            when(txService.findDailyRecord(any(), any())).thenReturn(record(5L, 4));
            when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
                .thenReturn(ResponseResult.okResult(Map.of("continuousDays", 6)));

            CodingStatVO vo = (CodingStatVO) service.myStat(USER_ID).getData();

            assertEquals(6, vo.getContinuousDays());
            assertEquals(3, vo.getTotalCount());
            assertEquals("Java 后端", vo.getDirection());
            assertEquals(3.5, vo.getAvgLevel());
            assertEquals(4, vo.getTodayLevel());
            assertTrue(vo.getTodayAnswered());
            assertTrue(((Map<?, ?>) vo.getTagStats()).containsKey("Java"));
        }

        @Test
        @DisplayName("开始作答但没提交：不算今日已答，也不带今日等级")
        void testIssuedNotAnswered() {
            givenStatExists("Java 后端");
            when(txService.findDailyRecord(any(), any())).thenReturn(issued(5L));
            when(rewardClient.getContinuousCheckinDays(anyLong()))
                .thenReturn(ResponseResult.okResult(Map.of("continuousDays", 1)));

            CodingStatVO vo = (CodingStatVO) service.myStat(USER_ID).getData();

            assertFalse(vo.getTodayAnswered());
            assertNull(vo.getTodayLevel());
        }

        @Test
        @DisplayName("签到服务不可用：连续天数降级 0，不影响其余字段")
        void testStatRewardDown() {
            when(statMapper.selectOne(any())).thenReturn(null);
            when(recordMapper.avgLevel(USER_ID)).thenReturn(null);
            when(rewardClient.getContinuousCheckinDays(USER_ID.longValue()))
                .thenThrow(new RuntimeException("reward down"));

            CodingStatVO vo = (CodingStatVO) service.myStat(USER_ID).getData();

            assertEquals(0, vo.getContinuousDays());
            assertEquals(0, vo.getTotalCount());
            assertNull(vo.getAvgLevel());
            assertFalse(vo.getTodayAnswered());
        }
    }
}
